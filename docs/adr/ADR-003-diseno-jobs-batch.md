# ADR-003 — Diseño de los procesos batch (Spring Batch)

- **Estado:** Aceptada (2026-10-09)
- **Criterios de la pauta:** C3 (batch con manejo avanzado de errores, paralelismo y escalabilidad), C1 (proceso clave 1), C2

## Contexto

El legacy (Shell/COBOL) procesa tres archivos CSV sin trazabilidad de rechazos, sin reproceso parcial y de forma secuencial.
Los datos reales son muy sucios (ver `docs/analisis_datos_legacy.md`: entre 50 % y 80 % de las filas tienen algún defecto).
Se necesitan jobs que (a) no pierdan nada en silencio, (b) puedan reanudarse tras un fallo sin duplicar, (c) escalen.

## Decisiones

| # | Decisión | Justificación |
|---|---|---|
| 1 | Cada job = **validar archivo → leer/validar/escribir → agregar/consolidar → exportar → reporte**, con steps separados | Cumple la separación pedida y permite reiniciar solo lo pendiente (`allowStartIfComplete(false)`, `startLimit`) |
| 2 | **Tablas de staging por instancia** (`daily_transaction`, `interest_calculation`, `annual_transaction`) y los resúmenes se calculan con SQL (`GROUP BY`) en un step posterior | La agregación no depende del orden ni del paralelismo → resultado determinista con particiones o hilos; el restart de un step no corrompe totales |
| 3 | **skip** de `FlatFileParseException` e `InvalidRecordException` (hasta `skipLimit`) + `SkipListener` que persiste cada rechazo en `batch_rejected_record` (job, línea, motivo, contenido crudo) dentro de la transacción del chunk | Integridad: toda línea queda escrita, omitida o filtrada. Si se excede el límite el job FALLA (no oculta un archivo roto) |
| 4 | **retry** de `TransientDataAccessException`/`CannotGetJdbcConnectionException` con **backoff exponencial** configurable | Absorbe fallos transitorios de BD sin intervención |
| 5 | `noRollback(InvalidRecordException)` | Sin esto cada registro inválido provoca rollback y reproceso del chunk; con 80 % de filas inválidas el costo sería cuadrático |
| 6 | El `InvalidRecordException` acumula **todos** los defectos de la línea | Permite conteos por tipo de error comparables con el análisis de datos y alimenta `transaction_anomaly` |
| 7 | **Deduplicación idempotente**: el processor guarda *clave → línea de la primera aparición*; una línea es duplicado solo si otra línea anterior ya tiene esa clave | Un set simple "visto/no visto" pierde datos cuando el chunk hace rollback y se reprocesa (defecto real encontrado por `RetryAndValidationIT`). Al reiniciar se precarga desde la BD |
| 8 | **dailyTransactionReportJob: partitioning local** (`LineRangePartitioner` + `TaskExecutorPartitionHandler`, `gridSize` configurable) | Los rangos de líneas son independientes; los workers heredan restart por partición (reanudan las fallidas) |
| 9 | **annualStatementJob: step multi-hilo** con `SynchronizedItemStreamReader`, `saveState=false` | La lectura es el único punto serial. Sin estado confiable del reader, el restart relee todo y la **idempotencia la dan las claves únicas** (`ON CONFLICT DO NOTHING`) |
| 10 | **monthlyInterestJob secuencial** | Deduplica en orden de archivo y su restart es exacto por chunk; el paralelismo se demuestra en los otros dos jobs |
| 11 | Escritura con `JdbcBatchItemWriter` + `ON CONFLICT` y claves únicas con `job_instance_id` | Reejecución segura: reintentar o reiniciar nunca duplica filas |
| 12 | Actualización de saldo: si una cuenta aparece varias veces en el archivo, **prevalece la última línea** | El CSV legacy repite `cuenta_id` con datos distintos; la regla es determinista y documentada |
| 13 | `JobExecutionListener`: ante `FAILED` emite `batch.job.failed` a Kafka (tópico `batch.job.events`, contrato versionado) y programa un **reintento automático** (restart) hasta N ejecuciones con espera; no reintenta fallos de validación de archivo | Cumple el requisito de reintento automático y observabilidad por eventos; un fallo de Kafka nunca rompe el job |
| 14 | `POST /api/batch/jobs/{jobName}` con rol `BATCH_ADMIN` (resource server JWT, claim `roles`), 202 asíncrono; cron configurable (`batch.schedule.*`, apagado por defecto) | Operación manual protegida + ejecución programada |
| 15 | Metadatos de Spring Batch en **PostgreSQL** (`JobRepository`) | Persisten reinicios y comparten estado entre instancias |

## Remote partitioning con Kafka (siguiente paso, no implementado)

Para superar un solo nodo, el step manager del job diario pasaría a **remote partitioning** (Spring Batch Integration,
`RemotePartitioningManagerStepBuilder`):

1. El manager publica un mensaje por partición (rango de líneas + `jobExecutionId`/`stepExecutionId`) en `batch.partitions.requests`
   (≥ 8 particiones de Kafka; la clave es el nombre de la partición).
2. N instancias de `batch-service` (escaladas con `--scale` o Service Auto Scaling de ECS) ejecutan el worker
   (`RemotePartitioningWorkerStepBuilder`) y responden en `batch.partitions.replies`; el manager agrega el estado.
3. Requisitos: el archivo debe estar en almacenamiento compartido (Amazon S3/EFS) o dividirse previamente; el `JobRepository`
   compartido ya lo cumple (PostgreSQL); los workers ya son idempotentes por las claves únicas (decisión 11).
4. Trade-offs: más piezas móviles (tópicos, timeouts de respuesta, workers caídos a mitad de partición → el restart de partición
   ya soportado); el cuello de botella pasa a la base de datos (ver el benchmark que genera `scripts/benchmark-batch.sh`).

## Alternativas descartadas

- **Multi-hilo en todos los jobs:** la deduplicación y el restart por chunk dejan de ser deterministas.
- **Deduplicar solo con la BD (`ON CONFLICT`):** funciona, pero el enunciado pide filtrar en el `ItemProcessor` y registrar el filtrado.
- **Resumen calculado en memoria dentro del writer:** frágil ante restart y particiones concurrentes.
- **`skip` también en escritura:** ocultaría errores de infraestructura como si fueran errores de datos.

## Consecuencias

- Más tablas y un step adicional por job, a cambio de resultados deterministas y reinicio seguro.
- El step multi-hilo relee el archivo completo en un restart (costo aceptado, documentado).
- `EquivalenceIT` (3 configuraciones de paralelismo) garantiza que paralelismo y chunk size no cambian el resultado.
