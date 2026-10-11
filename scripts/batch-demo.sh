#!/usr/bin/env bash
# Demo de batch-service SIN el resto de la plataforma: PostgreSQL en Docker + el jar real con perfil "basic"
# (usuario BATCH_ADMIN en memoria). Lanza los 3 jobs por REST, fuerza un fallo, reinicia y muestra rechazados.
# Salida completa en evidencias/local/batch_demo.log
# Requiere: Docker, JDK 21+, Maven, curl, python3.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {split($2,a,"."); print a[1]}')"
if [[ "${JAVA_MAJOR:-0}" -lt 21 ]] && [[ -x /usr/libexec/java_home ]]; then JAVA_HOME="$(/usr/libexec/java_home -v 21+)"; export JAVA_HOME; fi

PG=batch-demo-pg; PORT=8085; DBPORT=55432
export BATCH_ADMIN_PASSWORD="${BATCH_ADMIN_PASSWORD:-$(python3 -c 'import secrets;print(secrets.token_urlsafe(12))')}"
AUTH="batch-admin:${BATCH_ADMIN_PASSWORD}"
LOG=evidencias/local/batch_demo.log
mkdir -p evidencias/local /tmp/batch-demo-out
: > "$LOG"
say() { echo "$@" | tee -a "$LOG"; }
psql_() { docker exec "$PG" psql -U batch -d batchdb -P pager=off -c "$1" | tee -a "$LOG"; }
cleanup() { [[ -n "${APP_PID:-}" ]] && kill "$APP_PID" 2>/dev/null || true; docker rm -f "$PG" >/dev/null 2>&1 || true; }
trap cleanup EXIT

say "== 1. PostgreSQL"
docker rm -f "$PG" >/dev/null 2>&1 || true
docker run -d --name "$PG" -e POSTGRES_USER=batch -e POSTGRES_PASSWORD=demo -e POSTGRES_DB=batchdb -p "$DBPORT:5432" postgres:16-alpine >/dev/null
until docker exec "$PG" pg_isready -U batch -d batchdb >/dev/null 2>&1; do sleep 1; done

say "== 2. Compilar y arrancar batch-service (perfil basic)"
mvn -q -pl batch/batch-service -DskipTests package
JAR=$(ls batch/batch-service/target/batch-service-*.jar | grep -v original | head -1)
java -jar "$JAR" --spring.profiles.active=basic --server.port=$PORT --spring.cloud.config.enabled=false \
  --spring.datasource.url=jdbc:postgresql://localhost:$DBPORT/batchdb --spring.datasource.username=batch \
  --spring.datasource.password=demo --batch.events.enabled=false --batch.auto-retry.enabled=false \
  --batch.output-dir=/tmp/batch-demo-out --batch.chunk-size=50 > /tmp/batch-demo-app.log 2>&1 &
APP_PID=$!
until curl -s "localhost:$PORT/actuator/health" | grep -q UP; do sleep 1; done
say "App arriba (health UP)"

launch() { # $1 job, $2 JSON de parámetros
  curl -s -u "$AUTH" -H 'Content-Type: application/json' -X POST "localhost:$PORT/api/batch/jobs/$1" -d "{\"parameters\":$2}"
}
field() { python3 -c "import sys,json;print(json.load(sys.stdin)['$1'])"; }
wait_for() { # $1 executionId -> imprime estado final
  while :; do
    S=$(curl -s -u "$AUTH" "localhost:$PORT/api/batch/executions/$1" | field status)
    [[ "$S" =~ ^(COMPLETED|FAILED|STOPPED)$ ]] && { echo "$S"; return; }; sleep 1
  done
}
steps() { curl -s -u "$AUTH" "localhost:$PORT/api/batch/executions/$1" | python3 -c "
import sys,json
for s in json.load(sys.stdin)['steps']:
    if ':' not in s['name']: print('  %-24s %-9s leidos=%-5d escritos=%-5d omitidos=%-5d filtrados=%d' % (s['name'], s['status'], s['read'], s['written'], s['skipped'], s['filtered']))" | tee -a "$LOG"; }

say; say "== 3. Seguridad: sin token -> 401; con token pero sin rol se prueba en BatchApiSecurityIT"
say "POST sin credenciales: HTTP $(curl -s -o /dev/null -w '%{http_code}' -X POST localhost:$PORT/api/batch/jobs/dailyTransactionReportJob)"

for J in dailyTransactionReportJob annualStatementJob; do
  say; say "== 4. $J"
  ID=$(launch "$J" '{"inputFile":"'"$PWD"'/data/legacy/'"$([[ $J == daily* ]] && echo movimientos_financieros_diarios || echo estados_financieros_anuales)"'.csv"}' | field executionId)
  say "executionId=$ID estado=$(wait_for "$ID")"; steps "$ID"
done

say; say "== 5. monthlyInterestJob con fallo forzado en el chunk 5 (simulateFailureAtChunk=5)"
P='{"runId":"demo-restart","periodo":"MENSUAL","simulateFailureAtChunk":"5","inputFile":"'"$PWD"'/data/legacy/intereses_trimestrales.csv"}'
ID1=$(launch monthlyInterestJob "$P" | field executionId)
say "1.a ejecución $ID1: $(wait_for "$ID1")"; steps "$ID1"
psql_ "SELECT count(*) AS filas_confirmadas_antes_del_restart FROM interest_calculation"
say "== 6. Reinicio con los MISMOS parámetros (continúa desde el último chunk confirmado)"
ID2=$(launch monthlyInterestJob "$P" | field executionId)
say "2.a ejecución $ID2 (misma instancia): $(wait_for "$ID2")"; steps "$ID2"
psql_ "SELECT count(*) AS filas_finales, count(DISTINCT dedup_key) AS claves_distintas FROM interest_calculation"

say; say "== 7. Rechazados (primeros 8) y conteo por motivo"
psql_ "SELECT job_name, line_number, disposition, reason_code, raw_content FROM batch_rejected_record WHERE job_name='monthlyInterestJob' ORDER BY line_number LIMIT 8"
psql_ "SELECT job_name, disposition, count(*) FROM batch_rejected_record GROUP BY 1,2 ORDER BY 1,2"
say "== 8. Anomalías del job diario por tipo"
psql_ "SELECT anomaly_type, count(*) FROM transaction_anomaly GROUP BY 1 ORDER BY 1"
say "== 9. Archivos generados"; ls /tmp/batch-demo-out/* | head -8 | tee -a "$LOG"
say; say "Log completo en $LOG"
