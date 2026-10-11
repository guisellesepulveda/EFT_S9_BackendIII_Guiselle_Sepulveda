# Benchmark de jobs batch: 1 hilo vs N hilos/particiones

Generado automáticamente por `scripts/benchmark-batch.sh` (`BatchBenchmarkIT`) el 2026-10-09. **No editar a mano.**

## Entorno y método

| Ítem | Valor |
|---|---|
| Máquina | Apple M3 Pro, 18 GB RAM |
| Núcleos visibles por la JVM | 11 |
| SO / arquitectura | Mac OS X 27.0.1 / aarch64 |
| JVM | Java HotSpot(TM) 64-Bit Server VM 24.0.1 |
| Base de datos | PostgreSQL 16 (alpine) en contenedor Docker, `shared_buffers=256MB`, `synchronous_commit=on` |
| Datos | CSV sintéticos de **500000 filas** por job, generados con `scripts/generate_synthetic_csv.py --seed 42` (mismos tipos de defectos que el legacy: fechas inválidas, montos vacíos/negativos/cero, tipos desconocidos, duplicados) |
| Repeticiones | 2 por configuración (se reporta el promedio); 1 corrida de calentamiento descartada |
| skipLimit | 100.000.000 (sin límite práctico: los defectos son parte de la carga) |

Métrica principal: duración del **step principal** (lectura → validación → escritura), que es lo que se paraleliza.
"Job completo" incluye validación de archivo, resumen/consolidación, exportación y reporte.
Todas las filas rechazadas se persisten en `batch_rejected_record` dentro del tiempo medido.

## Resultados

### dailyTransactionReportJob — partitioning local (TaskExecutorPartitionHandler)

Step `loadMovementsStep` (manager) con `gridSize` particiones por rango de líneas; chunk = 1000.

| Configuración | Step principal (s) | Job completo (s) | Filas/s (step) | Speedup vs 1.ª fila | Leídas | Escritas | Omitidas | Filtradas |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 partición (secuencial) | 58.5 | 58.6 | 8550 | 1.00× | 500000 | 336528 | 163472 | 0 |
| 2 particiones | 31.8 | 31.9 | 15733 | 1.84× | 500000 | 336528 | 163472 | 0 |
| 4 particiones | 20.1 | 20.2 | 24845 | 2.91× | 500000 | 336528 | 163472 | 0 |
| 8 particiones | 15.0 | 15.1 | 33382 | 3.90× | 500000 | 336528 | 163472 | 0 |


### dailyTransactionReportJob — sensibilidad al tamaño de chunk (4 particiones)

Una sola medición por tamaño; muestra el costo de commits frecuentes.

| Configuración | Step principal (s) | Job completo (s) | Filas/s (step) | Speedup vs 1.ª fila | Leídas | Escritas | Omitidas | Filtradas |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| chunk 50 | 19.9 | 20.1 | 25077 | 1.00× | 500000 | 336528 | 163472 | 0 |
| chunk 200 | 18.2 | 18.3 | 27496 | 1.10× | 500000 | 336528 | 163472 | 0 |
| chunk 1000 | 20.2 | 20.2 | 24810 | 0.99× | 500000 | 336528 | 163472 | 0 |
| chunk 5000 | 20.3 | 20.4 | 24637 | 0.98× | 500000 | 336528 | 163472 | 0 |


### annualStatementJob — step multi-hilo (SynchronizedItemStreamReader)

Step `loadTransactionsStep` con `TaskExecutor` de N hilos; lectura sincronizada, proceso y escritura en paralelo; chunk = 1000.

| Configuración | Step principal (s) | Job completo (s) | Filas/s (step) | Speedup vs 1.ª fila | Leídas | Escritas | Omitidas | Filtradas |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 hilo | 32.2 | 33.6 | 15526 | 1.00× | 500000 | 336632 | 160457 | 2911 |
| 2 hilos | 17.3 | 18.9 | 28935 | 1.86× | 500000 | 336632 | 160457 | 2911 |
| 4 hilos | 10.4 | 12.4 | 48086 | 3.10× | 500000 | 336632 | 160457 | 2911 |
| 8 hilos | 7.9 | 9.8 | 62980 | 4.06× | 500000 | 336632 | 160457 | 2911 |


### monthlyInterestJob — referencia secuencial

Este job es deliberadamente secuencial: deduplica en orden de archivo y es restartable chunk a chunk.

| Configuración | Step principal (s) | Job completo (s) | Filas/s (step) | Speedup vs 1.ª fila | Leídas | Escritas | Omitidas | Filtradas |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| secuencial (1 hilo) | 24.4 | 26.7 | 20504 | 1.00× | 500000 | 389500 | 106603 | 3897 |


## Lectura de los resultados

- La escalabilidad está limitada por **una sola instancia de PostgreSQL** (escrituras, índices únicos y WAL) y por la
  contención entre hilos; el speedup no es lineal. Con más nodos de aplicación o remote partitioning el cuello de botella
  pasa a ser la base de datos.
- Los números son de una laptop de desarrollo con Docker; sirven para comparar configuraciones entre sí, no como cifras de producción.
- El tiempo del sistema legacy (Shell/COBOL) **no se midió** en este proyecto: no se dispone de su ejecución; cualquier comparación
  con él es referencial.
