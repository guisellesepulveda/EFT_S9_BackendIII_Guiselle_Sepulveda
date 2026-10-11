# Análisis de datos legacy

Base del "antes vs después" de la modernización. Todos los conteos se generan con
[`scripts/analizar_datos_legacy.py`](../scripts/analizar_datos_legacy.py) (solo biblioteca estándar de Python):

```bash
python3 -I scripts/analizar_datos_legacy.py            # tabla legible
python3 -I scripts/analizar_datos_legacy.py --json     # JSON
python3 -I scripts/analizar_datos_legacy.py data/legacy/semana_1   # otro snapshot
```

## 1. Origen y dataset elegido

Fuente: <https://github.com/KariVillagran/fin_legacy_data> (commit clonado con `--depth 1`).
El repositorio trae **tres snapshots** de los mismos tres archivos:

| Carpeta | Filas por archivo | Uso |
|---|---|---|
| `data/semana_1` | 8–10 | Referencia (casi sin errores) → `data/legacy/semana_1/` |
| `data/semana_2` | 8–10 | Referencia (pocos errores) → `data/legacy/semana_2/` |
| `data/semana_3` | **1000** | **Dataset principal** → `data/legacy/*.csv` |

Los tres archivos principales usan UTF-8, fin de línea `\n`, sin BOM, sin espacios sobrantes en los valores.

## 2. `movimientos_financieros_diarios.csv` (1000 filas)

Columnas: `id, fecha, monto, tipo`.

| Defecto | Filas | Notas |
|---|---:|---|
| `tipo` fuera de {`debito`,`credito`} | **383** | `invalido` 317, `desconocido` 66 |
| `monto` vacío | **168** | no mencionado en el README del origen |
| `monto` negativo | **125** | mínimo observado: -200 |
| `monto` = 0 | **20** | |
| Fecha en `yyyy/MM/dd` | 252 | formato "no estándar" que debe normalizarse |
| Fecha en `dd-MM-yyyy` | 222 | **no contemplado en el enunciado** |
| Fecha en `dd/MM/yyyy` | 230 | **no contemplado en el enunciado**; 138 sin ambigüedad (día > 12), 92 ambiguas con `MM/dd` |
| Fecha `yyyy-MM-dd` con mes 13 (`2024-13-01`) | **49** | calendario inválido → debe rechazarse siempre |
| Fecha `yyyy-MM-dd` válida | 247 | |
| `id` duplicado / fila duplicada | 0 | `id` es único (1–1000) |

Distribución de montos presentes (832): mín -200, mediana 1000, p90 = p99 = máx **3000**, sin decimales.
**Implicación:** no hay montos "atípicos" en el CSV real; la detección de anomalías por umbral solo se podrá
demostrar con el umbral configurable bajo y con el CSV sintético del benchmark.

Filas sin ningún defecto (reglas provisionales, ver §6): **209** con fechas estrictas · **392** con fechas extendidas.

## 3. `intereses_trimestrales.csv` (1000 filas)

Columnas: `cuenta_id, nombre, saldo, edad, tipo`.

| Defecto | Filas | Notas |
|---|---:|---|
| `saldo` vacío | **204** | |
| `edad` vacía | **171** | |
| `edad` = 150 (fuera de [18,100]) | **46** | no hay edades < 18 |
| `tipo` fuera de {`ahorro`,`prestamo`,`hipoteca`} | **325** | `-1` 281, `unknown` 44 |
| `tipo` = `hipoteca` | 203 | **no contemplado en el enunciado** (solo ahorro/préstamo) |
| `nombre` = `Unknown` | 48 | |
| Filas totalmente idénticas (duplicados exactos, filas extra) | **7** | |
| `cuenta_id` repetido (filas extra sobre la 1.ª aparición) | **950** | solo **50 cuentas distintas** (101–150) |

Hallazgo clave: `cuenta_id` **no es una clave única**. Una misma cuenta aparece hasta en 31 filas (cuenta 148; mínimo 12) y con **7 nombres
distintos en promedio**; el dataset es sintético. Deduplicar por `cuenta_id` eliminaría 950 de 1000 filas.
Saldos presentes: 5000–12000, sin decimales.

Filas sin ningún defecto (reglas provisionales): **509** (tras quitar duplicados exactos). De los 7 duplicados exactos,
5 son además inválidos (se rechazan) y **2 son válidos y se filtran** como duplicado (`FILTERED`).

## 4. `estados_financieros_anuales.csv` (1000 filas)

Columnas: `cuenta_id, fecha, transaccion, monto, descripcion`. Todas las fechas son de 2024; 20 cuentas distintas.

| Defecto | Filas | Notas |
|---|---:|---|
| `transaccion` fuera de {`deposito`,`retiro`,`compra`} | **102** | `depósito` (con tilde) 51 → normalizable; `pago` 51 |
| Tipos válidos | 898 | `compra` 327, `deposito` 293, `retiro` 278 |
| `monto` vacío | **48** | |
| `monto` negativo | **232** | |
| `monto` = 0 | **116** | |
| `descripcion` vacía | **184** | |
| `deposito` con monto negativo | 60 | inconsistencia de signo |
| `retiro` con monto positivo | **154** | en `semana_1` los retiros vienen negativos (`-500`) |
| Fecha `yyyy/MM/dd` / `dd-MM-yyyy` / `dd/MM/yyyy` / `yyyy-MM-dd` | 276 / 240 / 258 / 226 | |
| Filas duplicadas (exactas o por clave de negocio) | 0 | |

Filas sin ningún defecto (reglas provisionales, con `depósito`→`deposito` de D-4): **230** con fechas estrictas · **468** con fechas extendidas.

## 5. Resumen "antes vs después" (base para informe y video)

| Archivo | Filas | Válidas (fechas estrictas) | Válidas (fechas extendidas) | Rechazadas/omitidas por el batch |
|---|---:|---:|---:|---|
| movimientos | 1000 | 209 | 392 | 791 / 608 → tabla `batch_rejected_record` |
| intereses | 1000 | 509 | 509 | 491 |
| estados | 1000 | 230 | 468 | 770 / 532 |

"Antes": el legacy (Shell/COBOL) procesaba sin trazabilidad de rechazos. "Después": cada fila rechazada queda
persistida con job, línea, motivo y contenido crudo. Las cifras exactas del batch real se contrastarán con esta tabla
al implementar los jobs (`docs/equivalencia_legacy.md`).

## 6. Reglas provisionales usadas para contar "filas válidas"

- **movimientos:** fecha aceptada según política, `tipo` ∈ {debito, credito}, `monto` presente y > 0.
- **intereses:** `saldo` presente y ≥ 0, `tipo` ∈ {ahorro, prestamo, hipoteca}, `edad` vacía o en [18,100], sin duplicado exacto.
- **estados:** fecha aceptada según política, `transaccion` (normalizada a minúsculas y sin tildes) ∈ {deposito, retiro, compra}, `monto` presente y > 0, `descripcion` no vacía.
- Política de fechas **estricta** = `yyyy-MM-dd` y `yyyy/MM/dd` (lo que pide el enunciado). **Extendida** = estricta + `dd-MM-yyyy` + `dd/MM/yyyy`.

## 7. Decisiones D-1 a D-5 (aplicadas)

Los datos reales contradicen o van más allá de lo supuesto en el enunciado. Se aplicó la **propuesta por defecto** (adoptada
sin cambios); la alternativa queda indicada y es configurable donde se señala:

| # | Tema | Decisión aplicada | Alternativa |
|---|---|---|---|
| D-1 | Formatos de fecha (`dd-MM-yyyy`, `dd/MM/yyyy`) | Lista de formatos **configurable** (`batch.date-formats`); por defecto solo `yyyy-MM-dd` y `yyyy/MM/dd` (el enunciado), resto rechazado y registrado | Habilitar `dd-MM-yyyy`/`dd/MM/yyyy` (en este dataset `dd/MM` nunca es ambiguo con `MM/dd`: el 2.º campo jamás supera 12) |
| D-2 | Clave de deduplicación en intereses | **Fila completa** (`cuenta_id,nombre,saldo,edad,tipo`) → 7 duplicados | `cuenta_id` (descartaría 950 filas) |
| D-3 | Tipo `hipoteca`; `-1` / `unknown` | `hipoteca` se trata como cuenta de crédito con tasa propia configurable; `-1`/`unknown` rechazados | Rechazar `hipoteca` (el enunciado solo nombra ahorro/préstamo) |
| D-4 | Signo de `monto` y tipos en estados | `depósito` → `deposito` (normalización); `pago` rechazado; el signo se deriva del tipo y `monto ≤ 0` se rechaza | Aceptar monto negativo en `retiro` (convención de `semana_1`) |
| D-5 | `edad` vacía | Advertencia, no rechazo (la edad no entra en el cálculo) | Rechazar |

## 8. Resultado del batch sobre estos datos 

Los jobs reales reproducen exactamente los conteos anteriores (verificado por `EquivalenceIT` contra el cálculo independiente en
Python, ver `docs/equivalencia_legacy.md`). Para cada archivo: `filas = escritas + rechazadas + filtradas`.

| Job | Filas | Escritas | Rechazadas (`SKIPPED`) | Filtradas (`FILTERED`) |
|---|---:|---:|---:|---:|
| dailyTransactionReportJob | 1000 | 209 | 791 | 0 |
| monthlyInterestJob | 1000 | 509 | 489 | 2 |
| annualStatementJob | 1000 | 230 | 770 | 0 |

## 9. Reproducibilidad

Los CSV se versionan en el repo (≈ 100 KB) para que `docker compose up` funcione sin red. Para re-descargar:

```bash
git clone --depth 1 https://github.com/KariVillagran/fin_legacy_data /tmp/fin_legacy_data
cp /tmp/fin_legacy_data/data/semana_3/*.csv data/legacy/
```
