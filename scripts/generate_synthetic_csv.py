#!/usr/bin/env python3
"""Genera CSV sintéticos grandes con la MISMA estructura y los mismos tipos de defectos que los CSV legacy
(fechas inválidas, montos vacíos/negativos/cero, tipos desconocidos, saldos/edades faltantes, duplicados).

Uso:  python3 scripts/generate_synthetic_csv.py [--rows 500000] [--out data/synthetic] [--seed 42]
Es determinista (misma semilla → mismos archivos). data/synthetic/ está en .gitignore.
"""
import argparse
import random
from datetime import date, timedelta
from pathlib import Path

NAMES = ["John Doe", "Jane Smith", "Bob Johnson", "Alice Brown", "Charlie Green", "Diana Prince", "Steve Rogers", "Unknown"]
DESCRIPTIONS = ["Ingreso mensual", "Retiro parcial", "Ingreso extra", "Ingreso de fin de año", "Compra en tienda", "Ingreso navideño"]


def rand_date(rnd, p_valid=0.85):
    d = date(2024, 1, 1) + timedelta(days=rnd.randrange(366))
    r = rnd.random()
    if r < p_valid * 0.6:
        return d.strftime("%Y-%m-%d")
    if r < p_valid:
        return d.strftime("%Y/%m/%d")
    return rnd.choice([d.strftime("%d-%m-%Y"), d.strftime("%d/%m/%Y"), "2024-13-01"])


def movimientos(path, rows, rnd):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("id,fecha,monto,tipo\n")
        for i in range(1, rows + 1):
            monto = str(rnd.choice([500, 1000, 1200, 1500, 2000, 2500, 3000])) if rnd.random() < 0.88 else rnd.choice(["", "-200", "0"])
            tipo = rnd.choice(["debito", "credito"]) if rnd.random() < 0.9 else rnd.choice(["invalido", "desconocido"])
            f.write(f"{i},{rand_date(rnd)},{monto},{tipo}\n")


def intereses(path, rows, rnd):
    accounts = max(1000, rows // 3)
    prev = None
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("cuenta_id,nombre,saldo,edad,tipo\n")
        for _ in range(rows):
            if prev and rnd.random() < 0.01:          # duplicado exacto de la fila anterior
                f.write(prev)
                continue
            saldo = str(rnd.randrange(1000, 50001, 100)) if rnd.random() < 0.9 else ""
            edad = str(rnd.randrange(18, 96)) if rnd.random() < 0.9 else rnd.choice(["", "150"])
            tipo = rnd.choice(["ahorro", "prestamo", "hipoteca"]) if rnd.random() < 0.92 else rnd.choice(["-1", "unknown"])
            prev = f"{rnd.randrange(1, accounts + 1)},{rnd.choice(NAMES)},{saldo},{edad},{tipo}\n"
            f.write(prev)


def estados(path, rows, rnd):
    accounts = max(100, rows // 500)
    prev = None
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("cuenta_id,fecha,transaccion,monto,descripcion\n")
        for _ in range(rows):
            if prev and rnd.random() < 0.005:
                f.write(prev)
                continue
            tx = rnd.choice(["deposito", "retiro", "compra"]) if rnd.random() < 0.93 else rnd.choice(["pago", "depósito"])
            monto = str(rnd.choice([100, 500, 1000, 1500, 2000, 2500, 3000])) if rnd.random() < 0.9 else rnd.choice(["", "0", "-100"])
            desc = rnd.choice(DESCRIPTIONS) if rnd.random() < 0.92 else ""
            prev = f"{rnd.randrange(1, accounts + 1)},{rand_date(rnd)},{tx},{monto},{desc}\n"
            f.write(prev)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=500_000)
    ap.add_argument("--out", default="data/synthetic")
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    rnd = random.Random(a.seed)
    movimientos(out / "movimientos_financieros_diarios.csv", a.rows, rnd)
    intereses(out / "intereses_trimestrales.csv", a.rows, rnd)
    estados(out / "estados_financieros_anuales.csv", a.rows, rnd)
    for p in sorted(out.glob("*.csv")):
        print(f"{p}  {p.stat().st_size / 1_000_000:.1f} MB")


if __name__ == "__main__":
    main()
