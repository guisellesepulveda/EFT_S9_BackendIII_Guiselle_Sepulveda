#!/usr/bin/env bash
# Benchmark de los jobs batch: 1 hilo vs N hilos/particiones sobre CSV sintéticos (por defecto 500.000 filas).
# Requiere: Docker en marcha, JDK 21+, Maven y Python 3.
# Uso: scripts/benchmark-batch.sh [filas]          (resultado: evidencias/local/batch_benchmark.md)
set -euo pipefail
cd "$(dirname "$0")/.."
ROWS="${1:-500000}"

# El proyecto compila con --release 21: si el JDK por defecto es más antiguo, busca uno ≥ 21 (macOS)
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {split($2,a,"."); print a[1]}')"
if [[ "${JAVA_MAJOR:-0}" -lt 21 ]] && [[ -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21+)"; export JAVA_HOME
fi

python3 scripts/generate_synthetic_csv.py --rows "$ROWS" --out data/synthetic

MACHINE="$( (sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2 || echo 'desconocida') | sed 's/^ *//' )"
MEM_GB="$( (sysctl -n hw.memsize 2>/dev/null | awk '{printf "%d", $1/1073741824}') || (awk '/MemTotal/ {printf "%d", $2/1048576}' /proc/meminfo 2>/dev/null) || echo '?')"

mvn -q -pl batch/batch-service verify \
  -Dit.test=BatchBenchmarkIT -Dbenchmark=true -Dbenchmark.rows="$ROWS" \
  -Dbenchmark.machine="$MACHINE, ${MEM_GB} GB RAM" \
  -Dbenchmark.dir="$PWD/data/synthetic" -Dbenchmark.report="$PWD/evidencias/local/batch_benchmark.md" \
  2>&1 | grep -E "^\[BENCH\]|BUILD|ERROR" || true
echo "Informe: evidencias/local/batch_benchmark.md"
