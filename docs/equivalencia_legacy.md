# Equivalencia con el legacy 

Este documento lo **genera** `scripts/equivalencia_legacy.py`; no lo edites a mano.

## 1. Método

1. `scripts/equivalencia_legacy.py` recalcula, con Python (`decimal`, redondeo HALF_UP) y **sin compartir código con el
   batch Java**, el resultado esperado de los tres jobs sobre los CSV reales de `data/legacy/`.
2. El resultado se guarda en `batch/batch-service/src/test/resources/equivalence/` (CSV + `expected_counts.json`).
3. `EquivalenceIT` ejecuta los jobs contra PostgreSQL (Testcontainers) y compara **todas** las filas y conteos con ese
   esperado (montos con `compareTo`, sin tolerancia). Con el test en verde, esperado == resultado del job.

Reglas aplicadas (decisiones de `docs/analisis_datos_legacy.md` §7): fechas `yyyy-MM-dd` y `yyyy/MM/dd` (D-1),
deduplicación por fila completa (D-2), `hipoteca` aceptada con tasa propia y `-1`/`unknown` rechazados (D-3),
`depósito`→`deposito`, `pago` rechazado, monto > 0 (D-4), edad vacía = advertencia (D-5).
Umbral de anomalía: monto > 2500. Tasas anuales **de ejemplo**:
ahorro 0.0250, prestamo 0.1200, hipoteca 0.0600.

## 2. Conteos esperados por job (CSV real, 1000 filas cada uno)

| Job / archivo | Filas | Escritas (válidas) | Rechazadas (skip) | Filtradas (duplicados) |
|---|---|---|---|---|
| dailyTransactionReportJob / movimientos | 1000 | 209 | 791 | 0 |
| monthlyInterestJob / intereses | 1000 | 509 | 489 | 2 |
| annualStatementJob / estados | 1000 | 230 | 770 | 0 |

Invariante verificado por el test: `filas = escritas + rechazadas + filtradas`, y cada línea omitida o filtrada
queda en `batch_rejected_record` (nada se pierde en silencio).

Anomalías de movimientos (un registro puede tener varias):
| Tipo | Registros |
|---|---|
| INVALID_DATE | 501 |
| MISSING_AMOUNT | 168 |
| NON_POSITIVE_AMOUNT | 145 |
| OUTLIER_AMOUNT | 34 |
| UNKNOWN_TYPE | 383 |

## 3. Cálculo a mano de intereses (muestra de las primeras 6 filas válidas)

`interés = saldo × tasaAnual ÷ n` con n = 12 (MENSUAL) o 4 (TRIMESTRAL), redondeado a 2 decimales HALF_UP.

| Línea CSV | Cuenta | Tipo | Saldo | Tasa anual | Mensual | Trimestral |
|---|---|---|---|---|---|---|
| 2 | 131 | hipoteca | 8000 | 0.0600 | 8000 × 0.0600 ÷ 12 = 40.00 | 8000 × 0.0600 ÷ 4 = 120.00 |
| 4 | 109 | hipoteca | 7000 | 0.0600 | 7000 × 0.0600 ÷ 12 = 35.00 | 7000 × 0.0600 ÷ 4 = 105.00 |
| 6 | 150 | hipoteca | 7000 | 0.0600 | 7000 × 0.0600 ÷ 12 = 35.00 | 7000 × 0.0600 ÷ 4 = 105.00 |
| 9 | 119 | ahorro | 8000 | 0.0250 | 8000 × 0.0250 ÷ 12 = 16.67 | 8000 × 0.0250 ÷ 4 = 50.00 |
| 11 | 139 | prestamo | 12000 | 0.1200 | 12000 × 0.1200 ÷ 12 = 120.00 | 12000 × 0.1200 ÷ 4 = 360.00 |
| 15 | 121 | ahorro | 7000 | 0.0250 | 7000 × 0.0250 ÷ 12 = 14.58 | 7000 × 0.0250 ÷ 4 = 43.75 |

## 4. Muestra de estados anuales consolidados

Saldo final = depósitos − retiros − compras (saldo inicial 0).

| Cuenta | Año | Depósitos | Retiros | Compras | Saldo final | Movimientos |
|---|---|---|---|---|---|---|
| 101 | 2024 | 4500.00 | 6000.00 | 16500.00 | -18000.00 | 12 |
| 102 | 2024 | 3000.00 | 5500.00 | 3500.00 | -6000.00 | 7 |
| 103 | 2024 | 12500.00 | 6000.00 | 6500.00 | 0.00 | 13 |
| 104 | 2024 | 2500.00 | 9000.00 | 6000.00 | -12500.00 | 9 |
| 105 | 2024 | 10000.00 | 11000.00 | 8500.00 | -9500.00 | 16 |
| 106 | 2024 | 10000.00 | 7500.00 | 2500.00 | 0.00 | 9 |

## 5. Cómo reproducir

```bash
python3 scripts/equivalencia_legacy.py                       # regenera esperado + este documento
mvn -pl batch/batch-service verify -Dit.test=EquivalenceIT   # compara contra los jobs reales
```
