# ADR-002 — Periodicidad del cálculo de intereses (mensual vs. trimestral)

- **Estado:** Aceptada (2026-10-09)
- **Criterios de la pauta:** C3 (procesos batch), C2 (decisión justificada)

## Contexto

El enunciado pide el proceso "Cálculo de Intereses **Mensuales**", pero el archivo de entrada del legacy se llama
`intereses_trimestrales.csv` (y su README habla de "intereses trimestrales"). Sus columnas son `cuenta_id, nombre, saldo,
edad, tipo`; **no contiene fechas ni periodo**, por lo que el archivo no impone una periodicidad.

## Decisión

Implementar `monthlyInterestJob` con un parámetro de job **`periodo=MENSUAL|TRIMESTRAL`** (por defecto `MENSUAL`):

```
interes = saldo * (tasaAnual(tipoCuenta) / n)      n = 12 (MENSUAL) | 4 (TRIMESTRAL)
```

- `tasaAnual` por tipo de cuenta vive en el Config Server (`infra/config-repo`), no en el código.
- `periodo` forma parte de los `JobParameters` (identificadores de la instancia), de modo que ejecutar el mismo archivo
  en modo mensual y trimestral produce dos instancias distintas y no choca con la deduplicación.
- El nombre del job se mantiene `monthlyInterestJob` (alineado con el enunciado); el archivo se conserva con su nombre
  legacy para trazabilidad.

## Justificación

- Cumple literalmente el enunciado (mensual por defecto) sin perder la compatibilidad con el nombre del archivo legacy.
- Permite demostrar la equivalencia con el legacy trimestral (`periodo=TRIMESTRAL`) en `docs/equivalencia_legacy.md`.
- Parametrizar evita bifurcar el código y es consistente con "configuración externalizada" (Spring Cloud Config).

## Consecuencias

- Hay que cubrir ambos modos con tests (mensual = anual/12, trimestral = anual/4).
- Pendiente de decisión (ver `docs/analisis_datos_legacy.md` §7, D-2 y D-3): clave de deduplicación y tratamiento de la
  cuenta `hipoteca`; las tasas concretas serán valores **de ejemplo** y se declararán como tales en el informe.

## Alternativas descartadas

- Renombrar el job a `quarterlyInterestJob`: contradice el enunciado.
- Fijar solo mensual: pierde la comparabilidad con el archivo trimestral del legacy.
