#!/usr/bin/env python3
"""Cálculo INDEPENDIENTE de los resultados esperados de los 3 jobs (equivalencia con el legacy, Etapa 1.5).

No comparte código con el batch en Java: reimplementa las reglas documentadas en
docs/analisis_datos_legacy.md §7 (D-1..D-5) y docs/adr/ADR-002 con `decimal.Decimal` (HALF_UP).

Uso (desde la raíz del repo):
    python3 scripts/equivalencia_legacy.py

Genera:
  batch/batch-service/src/test/resources/equivalence/*.csv|json   (los consume EquivalenceIT)
  docs/equivalencia_legacy.md
"""
import csv
import json
import re
import unicodedata
from collections import Counter, OrderedDict
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "legacy"
OUT = ROOT / "batch" / "batch-service" / "src" / "test" / "resources" / "equivalence"
DOC = ROOT / "docs" / "equivalencia_legacy.md"

# Parámetros: DEBEN coincidir con application.yml / infra/config-repo/batch-service.yml
RATES = OrderedDict([("ahorro", Decimal("0.0250")), ("prestamo", Decimal("0.1200")), ("hipoteca", Decimal("0.0600"))])
DIVISOR = {"MENSUAL": 12, "TRIMESTRAL": 4}
OUTLIER_THRESHOLD = Decimal("2500")
AGE_MIN, AGE_MAX = 18, 100
DATE_FORMATS = [("%Y-%m-%d", r"^\d{4}-\d{2}-\d{2}$"), ("%Y/%m/%d", r"^\d{4}/\d{2}/\d{2}$")]  # D-1 (estricta)


def parse_date(v):
    v = (v or "").strip()
    for fmt, rx in DATE_FORMATS:
        if re.match(rx, v):
            try:
                return datetime.strptime(v, fmt).date()
            except ValueError:
                return None
    return None


def dec(v):
    try:
        return Decimal(v)
    except Exception:
        return None


def q2(x):
    return x.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def strip_accents(s):
    s = unicodedata.normalize("NFD", s.strip().lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def read(name):
    with open(DATA / name, newline="", encoding="utf-8") as fh:
        r = csv.reader(fh)
        header = next(r)
        # número de línea real del archivo (encabezado = 1)
        return header, [(i + 2, [c.strip() for c in row], row) for i, row in enumerate(r)]


# ----------------------------------------------------------------------------- movimientos
def movimientos():
    _, rows = read("movimientos_financieros_diarios.csv")
    valid, rejected, anomalies, seen = [], 0, Counter(), set()
    for line, c, _raw in rows:
        defects = []
        try:
            tid = int(c[0])
        except ValueError:
            tid = None
            defects.append("INVALID_ID")
        d = parse_date(c[1])
        if d is None:
            defects.append("INVALID_DATE")
        m = None
        if c[2] == "":
            defects.append("MISSING_AMOUNT")
        else:
            m = dec(c[2])
            if m is None:
                defects.append("INVALID_AMOUNT")
            elif m <= 0:
                defects.append("NON_POSITIVE_AMOUNT")
        if c[3] not in ("debito", "credito"):
            defects.append("UNKNOWN_TYPE")
        if defects:
            rejected += 1
            anomalies.update(defects)
            continue
        if tid in seen:
            continue
        seen.add(tid)
        valid.append((line, tid, d, c[3], m))
        if m > OUTLIER_THRESHOLD:
            anomalies["OUTLIER_AMOUNT"] += 1
    summary = {}
    for _l, _id, d, t, m in valid:
        s = summary.setdefault((d, t), [0, Decimal(0), Decimal(0)])
        s[0] += 1
        s[1] += m
        s[2] = max(s[2], m)
    return len(rows), valid, rejected, anomalies, summary


# ----------------------------------------------------------------------------- intereses
def intereses():
    _, rows = read("intereses_trimestrales.csv")
    written, rejected, filtered, seen = [], 0, 0, set()
    defect_counts = Counter()
    for line, c, _raw in rows:
        defects = []
        try:
            cuenta = int(c[0])
        except ValueError:
            cuenta = None
            defects.append("INVALID_ACCOUNT_ID")
        if c[1] == "":
            defects.append("MISSING_NAME")
        saldo = None
        if c[2] == "":
            defects.append("MISSING_BALANCE")
        else:
            saldo = dec(c[2])
            if saldo is None:
                defects.append("INVALID_BALANCE")
            elif saldo < 0:
                defects.append("NEGATIVE_BALANCE")
        if c[3] != "":  # D-5: edad vacía = advertencia
            try:
                age = int(c[3])
                if not AGE_MIN <= age <= AGE_MAX:
                    defects.append("AGE_OUT_OF_RANGE")
            except ValueError:
                defects.append("INVALID_AGE")
        if c[4] not in RATES:
            defects.append("INVALID_ACCOUNT_TYPE")
        if defects:
            rejected += 1
            defect_counts.update(defects)
            continue
        key = "|".join(c[:5])
        if key in seen:
            filtered += 1
            continue
        seen.add(key)
        written.append((line, cuenta, c[1], c[4], saldo))
    return len(rows), written, rejected, filtered, defect_counts


def interest_rows(written, periodo):
    out = []
    for line, cuenta, nombre, tipo, saldo in written:
        rate = RATES[tipo]
        interes = q2(saldo * rate / DIVISOR[periodo])
        out.append((line, cuenta, nombre, tipo, saldo, rate, interes, saldo + interes))
    return out


def balances(rows):
    last = {}
    for r in rows:  # filas en orden de línea: la última de cada cuenta prevalece
        last[r[1]] = r
    return [(k, v[2], v[3], v[7]) for k, v in sorted(last.items())]


# ----------------------------------------------------------------------------- estados
def estados():
    _, rows = read("estados_financieros_anuales.csv")
    valid, rejected, filtered, seen = [], 0, 0, set()
    defect_counts = Counter()
    for line, c, _raw in rows:
        defects = []
        try:
            cuenta = int(c[0])
        except ValueError:
            cuenta = None
            defects.append("INVALID_ACCOUNT_ID")
        d = parse_date(c[1])
        if d is None:
            defects.append("INVALID_DATE")
        tipo = strip_accents(c[2])
        if tipo not in ("deposito", "retiro", "compra"):
            defects.append("INVALID_TRANSACTION_TYPE")
        m = None
        if c[3] == "":
            defects.append("MISSING_AMOUNT")
        else:
            m = dec(c[3])
            if m is None:
                defects.append("INVALID_AMOUNT")
            elif m <= 0:
                defects.append("NON_POSITIVE_AMOUNT")
        if c[4] == "":
            defects.append("MISSING_DESCRIPTION")
        if defects:
            rejected += 1
            defect_counts.update(defects)
            continue
        key = "|".join([str(cuenta), d.isoformat(), tipo, format(m, "f"), c[4]])
        if key in seen:
            filtered += 1
            continue
        seen.add(key)
        valid.append((line, cuenta, d, tipo, m, c[4]))
    stm = {}
    for _l, cuenta, d, tipo, m, _desc in valid:
        s = stm.setdefault((cuenta, d.year), {"deposito": Decimal(0), "retiro": Decimal(0), "compra": Decimal(0), "n": 0})
        s[tipo] += m
        s["n"] += 1
    return len(rows), valid, rejected, filtered, defect_counts, stm


def w(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as fh:
        cw = csv.writer(fh, lineterminator="\n")
        cw.writerow(header)
        cw.writerows(rows)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    n_m, m_valid, m_rej, m_anom, m_sum = movimientos()
    n_i, i_written, i_rej, i_fil, i_def = intereses()
    n_e, e_valid, e_rej, e_fil, e_def, e_stm = estados()

    w(OUT / "expected_daily_summary.csv", ["fecha", "tipo", "cantidad", "monto_total", "monto_maximo"],
      [(d.isoformat(), t, s[0], q2(s[1]), q2(s[2])) for (d, t), s in sorted(m_sum.items())])
    for p in DIVISOR:
        rows = interest_rows(i_written, p)
        w(OUT / f"expected_interest_{p}.csv",
          ["line_number", "cuenta_id", "nombre", "tipo", "saldo_anterior", "tasa_anual", "interes", "saldo_nuevo"],
          [(l, c, n, t, q2(s), r, i, q2(sn)) for l, c, n, t, s, r, i, sn in rows])
        w(OUT / f"expected_balances_{p}.csv", ["cuenta_id", "nombre", "tipo", "saldo"],
          [(c, n, t, q2(s)) for c, n, t, s in balances(rows)])
    w(OUT / "expected_annual_statements.csv",
      ["cuenta_id", "year", "depositos", "retiros", "compras", "saldo_final", "movimientos"],
      [(c, y, q2(s["deposito"]), q2(s["retiro"]), q2(s["compra"]),
        q2(s["deposito"] - s["retiro"] - s["compra"]), s["n"]) for (c, y), s in sorted(e_stm.items())])

    counts = {
        "movimientos": {"filas": n_m, "escritas": len(m_valid), "rechazadas": m_rej, "filtradas": 0,
                        "anomalias": dict(sorted(m_anom.items()))},
        "intereses": {"filas": n_i, "escritas": len(i_written), "rechazadas": i_rej, "filtradas": i_fil,
                      "defectos": dict(sorted(i_def.items()))},
        "estados": {"filas": n_e, "escritas": len(e_valid), "rechazadas": e_rej, "filtradas": e_fil,
                    "defectos": dict(sorted(e_def.items())), "cuentas_con_estado": len(e_stm)},
    }
    (OUT / "expected_counts.json").write_text(json.dumps(counts, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    write_doc(counts, i_written, e_stm)
    print(json.dumps(counts, indent=2, ensure_ascii=False))


def md_table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "|".join("---" for _ in header) + "|"]
    out += ["| " + " | ".join(str(x) for x in r) + " |" for r in rows]
    return "\n".join(out)


def write_doc(counts, i_written, e_stm):
    m, i, e = counts["movimientos"], counts["intereses"], counts["estados"]
    sample = i_written[:6]
    hand = []
    for line, cuenta, nombre, tipo, saldo in sample:
        rate = RATES[tipo]
        mensual = q2(saldo * rate / 12)
        trimestral = q2(saldo * rate / 4)
        hand.append((line, cuenta, tipo, saldo, rate,
                     f"{saldo} × {rate} ÷ 12 = {mensual}", f"{saldo} × {rate} ÷ 4 = {trimestral}"))
    stm_rows = [(c, y, q2(s["deposito"]), q2(s["retiro"]), q2(s["compra"]),
                 q2(s["deposito"] - s["retiro"] - s["compra"]), s["n"]) for (c, y), s in sorted(e_stm.items())][:6]
    doc = f"""# Equivalencia con el legacy

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
Umbral de anomalía: monto > {OUTLIER_THRESHOLD}. Tasas anuales **de ejemplo**:
{", ".join(f"{k} {v}" for k, v in RATES.items())}.

## 2. Conteos esperados por job (CSV real, 1000 filas cada uno)

{md_table(["Job / archivo", "Filas", "Escritas (válidas)", "Rechazadas (skip)", "Filtradas (duplicados)"],
          [("dailyTransactionReportJob / movimientos", m["filas"], m["escritas"], m["rechazadas"], m["filtradas"]),
           ("monthlyInterestJob / intereses", i["filas"], i["escritas"], i["rechazadas"], i["filtradas"]),
           ("annualStatementJob / estados", e["filas"], e["escritas"], e["rechazadas"], e["filtradas"])])}

Invariante verificado por el test: `filas = escritas + rechazadas + filtradas`, y cada línea omitida o filtrada
queda en `batch_rejected_record` (nada se pierde en silencio).

Anomalías de movimientos (un registro puede tener varias):
{md_table(["Tipo", "Registros"], sorted(m["anomalias"].items()))}

## 3. Cálculo a mano de intereses (muestra de las primeras 6 filas válidas)

`interés = saldo × tasaAnual ÷ n` con n = 12 (MENSUAL) o 4 (TRIMESTRAL), redondeado a 2 decimales HALF_UP.

{md_table(["Línea CSV", "Cuenta", "Tipo", "Saldo", "Tasa anual", "Mensual", "Trimestral"], hand)}

## 4. Muestra de estados anuales consolidados

Saldo final = depósitos − retiros − compras (saldo inicial 0).

{md_table(["Cuenta", "Año", "Depósitos", "Retiros", "Compras", "Saldo final", "Movimientos"], stm_rows)}

## 5. Cómo reproducir

```bash
python3 scripts/equivalencia_legacy.py                       # regenera esperado + este documento
mvn -pl batch/batch-service verify -Dit.test=EquivalenceIT   # compara contra los jobs reales
```
"""
    DOC.write_text(doc, encoding="utf-8")


if __name__ == "__main__":
    main()
