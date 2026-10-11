#!/usr/bin/env python3
"""Análisis de calidad de los CSV legacy (Etapa 0).

Uso:  python3 -I scripts/analizar_datos_legacy.py [directorio_csv] [--json]

Solo usa la biblioteca estándar. Imprime conteos por tipo de error para cada archivo.
Con --json emite el resultado como JSON (útil para tests y para el informe).
"""
import csv
import json
import re
import sys
import unicodedata
from collections import Counter
from datetime import datetime
from pathlib import Path

TIPOS_MOVIMIENTO = {"debito", "credito"}
TIPOS_CUENTA = {"ahorro", "prestamo", "hipoteca"}
TIPOS_TRANSACCION = {"deposito", "retiro", "compra"}
EDAD_MIN, EDAD_MAX = 18, 100

RE_ISO = re.compile(r"^\d{4}-\d{2}-\d{2}$")
RE_SLASH_YMD = re.compile(r"^\d{4}/\d{2}/\d{2}$")
RE_DMY_DASH = re.compile(r"^\d{2}-\d{2}-\d{4}$")
RE_DMY_SLASH = re.compile(r"^\d{2}/\d{2}/\d{4}$")


def clasificar_fecha(valor):
    """Devuelve (categoria, valida_calendario)."""
    v = (valor or "").strip()
    if not v:
        return "vacia", False
    for rx, fmt, nombre in (
        (RE_ISO, "%Y-%m-%d", "yyyy-MM-dd"),
        (RE_SLASH_YMD, "%Y/%m/%d", "yyyy/MM/dd"),
        (RE_DMY_DASH, "%d-%m-%Y", "dd-MM-yyyy"),
        (RE_DMY_SLASH, "%d/%m/%Y", "dd/MM/yyyy"),
    ):
        if rx.match(v):
            try:
                datetime.strptime(v, fmt)
                return nombre, True
            except ValueError:
                return nombre + " (calendario inválido)", False
    return "otro", False


def normalizar_tipo(valor):
    """D-4: minúsculas y sin tildes ('depósito' -> 'deposito')."""
    s = unicodedata.normalize("NFD", (valor or "").strip().lower())
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def a_numero(valor):
    v = (valor or "").strip()
    if v == "":
        return None, "vacio"
    try:
        return float(v), "ok"
    except ValueError:
        return None, "no_numerico"


def leer(path):
    with open(path, newline="", encoding="utf-8") as fh:
        lector = csv.DictReader(fh)
        filas = list(lector)
        return lector.fieldnames, filas


def espacios(filas):
    """Cuenta valores con espacios sobrantes (leading/trailing)."""
    return sum(1 for f in filas for v in f.values() if v is not None and v != v.strip())


def analizar_movimientos(path):
    cols, filas = leer(path)
    c = Counter()
    fechas = Counter()
    tipos = Counter()
    vistos = Counter()
    ids = Counter()
    montos = []
    for f in filas:
        ids[f["id"].strip()] += 1
        vistos[tuple(v.strip() for v in f.values())] += 1
        cat, _ = clasificar_fecha(f["fecha"])
        fechas[cat] += 1
        t = f["tipo"].strip()
        tipos[t or "(vacio)"] += 1
        if t not in TIPOS_MOVIMIENTO:
            c["tipo_invalido"] += 1
        m, est = a_numero(f["monto"])
        if est != "ok":
            c["monto_" + est] += 1
        else:
            montos.append(m)
            if m < 0:
                c["monto_negativo"] += 1
            elif m == 0:
                c["monto_cero"] += 1
    c["id_duplicado_(filas_extra)"] = sum(n - 1 for n in ids.values() if n > 1)
    c["fila_completa_duplicada_(filas_extra)"] = sum(n - 1 for n in vistos.values() if n > 1)
    c["valores_con_espacios"] = espacios(filas)
    return {
        "columnas": cols, "filas": len(filas), "errores": dict(c),
        "formatos_fecha": dict(fechas), "tipos": dict(tipos),
        "monto_max": max(montos) if montos else None, "monto_min": min(montos) if montos else None,
    }


def analizar_intereses(path):
    cols, filas = leer(path)
    c = Counter()
    tipos = Counter()
    claves = Counter()
    filas_exactas = Counter()
    edades_ok = []
    for f in filas:
        claves[f["cuenta_id"].strip()] += 1
        filas_exactas[tuple(v.strip() for v in f.values())] += 1
        t = f["tipo"].strip()
        tipos[t or "(vacio)"] += 1
        if t not in TIPOS_CUENTA:
            c["tipo_cuenta_invalido"] += 1
        s, est = a_numero(f["saldo"])
        if est != "ok":
            c["saldo_" + est] += 1
        elif s < 0:
            c["saldo_negativo"] += 1
        e, est = a_numero(f["edad"])
        if est != "ok":
            c["edad_" + est] += 1
        elif not (EDAD_MIN <= e <= EDAD_MAX):
            c["edad_fuera_de_rango_[%d-%d]" % (EDAD_MIN, EDAD_MAX)] += 1
        else:
            edades_ok.append(e)
        if not f["nombre"].strip():
            c["nombre_vacio"] += 1
    c["cuenta_id_duplicado_(filas_extra)"] = sum(n - 1 for n in claves.values() if n > 1)
    c["fila_completa_duplicada_(filas_extra)"] = sum(n - 1 for n in filas_exactas.values() if n > 1)
    c["valores_con_espacios"] = espacios(filas)
    return {
        "columnas": cols, "filas": len(filas), "errores": dict(c), "tipos": dict(tipos),
        "cuentas_distintas": len(claves),
    }


def analizar_estados(path):
    cols, filas = leer(path)
    c = Counter()
    fechas = Counter()
    tipos = Counter()
    exactas = Counter()
    clave_negocio = Counter()
    for f in filas:
        exactas[tuple(v.strip() for v in f.values())] += 1
        clave_negocio[(f["cuenta_id"].strip(), f["fecha"].strip(), f["transaccion"].strip(), f["monto"].strip())] += 1
        cat, _ = clasificar_fecha(f["fecha"])
        fechas[cat] += 1
        t = f["transaccion"].strip()
        tipos[t or "(vacio)"] += 1
        if t not in TIPOS_TRANSACCION:
            c["transaccion_invalida"] += 1
        m, est = a_numero(f["monto"])
        if est != "ok":
            c["monto_" + est] += 1
        else:
            if m < 0:
                c["monto_negativo"] += 1
            elif m == 0:
                c["monto_cero"] += 1
            # Inconsistencias de signo respecto al tipo (útil para consolidar saldo)
            if t == "deposito" and m < 0:
                c["deposito_con_monto_negativo"] += 1
            if t == "retiro" and m > 0:
                c["retiro_con_monto_positivo"] += 1
        if not f["descripcion"].strip():
            c["descripcion_vacia"] += 1
        if not f["cuenta_id"].strip():
            c["cuenta_id_vacio"] += 1
    c["fila_completa_duplicada_(filas_extra)"] = sum(n - 1 for n in exactas.values() if n > 1)
    c["clave_negocio_duplicada_(filas_extra)"] = sum(n - 1 for n in clave_negocio.values() if n > 1)
    c["valores_con_espacios"] = espacios(filas)
    return {
        "columnas": cols, "filas": len(filas), "errores": dict(c),
        "formatos_fecha": dict(fechas), "tipos": dict(tipos),
    }


def fecha_aceptada(valor, politica):
    """politica 'estricta' = yyyy-MM-dd y yyyy/MM/dd (el enunciado); 'extendida' añade dd-MM-yyyy y dd/MM/yyyy."""
    cat, ok = clasificar_fecha(valor)
    if not ok:
        return False
    if cat in ("yyyy-MM-dd", "yyyy/MM/dd"):
        return True
    return politica == "extendida"


def filas_validas(base):
    """Cuenta filas sin ningún defecto bajo las reglas PROVISIONALES de la Etapa 0.

    Reglas definitivas se fijan en la Etapa 1 (ver docs/analisis_datos_legacy.md, sección 'Decisiones abiertas').
    """
    out = {}
    _, mov = leer(base / "movimientos_financieros_diarios.csv")
    _, ints = leer(base / "intereses_trimestrales.csv")
    _, est = leer(base / "estados_financieros_anuales.csv")
    for pol in ("estricta", "extendida"):
        def m_ok(f):
            m, e = a_numero(f["monto"])
            return (fecha_aceptada(f["fecha"], pol) and f["tipo"].strip() in TIPOS_MOVIMIENTO
                    and e == "ok" and m > 0)

        def e_ok(f):
            m, e = a_numero(f["monto"])
            return (fecha_aceptada(f["fecha"], pol) and normalizar_tipo(f["transaccion"]) in TIPOS_TRANSACCION
                    and e == "ok" and m > 0 and f["descripcion"].strip() != "")

        vistos = set()
        i_ok = 0
        for f in ints:
            clave = tuple(v.strip() for v in f.values())
            s_, es = a_numero(f["saldo"])
            ed, ee = a_numero(f["edad"])
            valido = (es == "ok" and s_ >= 0 and f["tipo"].strip() in TIPOS_CUENTA
                      and (ee == "vacio" or (ee == "ok" and EDAD_MIN <= ed <= EDAD_MAX)))
            if valido and clave not in vistos:
                i_ok += 1
            vistos.add(clave)
        out[pol] = {
            "movimientos": [sum(1 for f in mov if m_ok(f)), len(mov)],
            "intereses": [i_ok, len(ints)],
            "estados": [sum(1 for f in est if e_ok(f)), len(est)],
        }
    return out


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    base = Path(args[0]) if args else Path(__file__).resolve().parent.parent / "data" / "legacy"
    resultado = {
        "movimientos_financieros_diarios.csv": analizar_movimientos(base / "movimientos_financieros_diarios.csv"),
        "intereses_trimestrales.csv": analizar_intereses(base / "intereses_trimestrales.csv"),
        "estados_financieros_anuales.csv": analizar_estados(base / "estados_financieros_anuales.csv"),
    }
    resultado["_filas_validas_provisional"] = filas_validas(base)
    if "--json" in argv:
        print(json.dumps(resultado, indent=2, ensure_ascii=False))
        return
    for nombre, r in resultado.items():
        if nombre.startswith("_"):
            print("=" * 70)
            print("Filas válidas [válidas, total] (reglas provisionales):")
            for pol, d in r.items():
                print("  política de fechas %-9s %s" % (pol, d))
            continue
        print("=" * 70)
        print(nombre, "| filas:", r["filas"], "| columnas:", r["columnas"])
        for k in ("formatos_fecha", "tipos"):
            if k in r:
                print("  %s:" % k, dict(sorted(r[k].items())))
        for k, v in sorted(r["errores"].items()):
            print("  %-45s %d" % (k, v))


if __name__ == "__main__":
    main(sys.argv)
