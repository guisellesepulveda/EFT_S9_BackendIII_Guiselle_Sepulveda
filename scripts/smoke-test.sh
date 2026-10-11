#!/usr/bin/env bash
# Smoke test de TODA la plataforma sobre docker compose (o procesos locales): tokens por canal → cliente → cuentas → depósito →
# transferencia (saga) → consulta en los 3 BFF → los 3 jobs batch. Sale con código ≠ 0 si algo falla.
# Salida completa en evidencias/local/smoke-test.log (o evidencias/aws/logs/smoke-test-remote.log con --host).
# Requisitos: .env, plataforma levantada, curl, python3.
#
# Contra un entorno remoto (EC2, ver despliegue.md), DESDE el portátil:
#   scripts/smoke-test.sh --host <IP_o_DNS> [--insecure] [--certs-dir certs/aws]
#   --host        IP o DNS público. Puertos del despliegue AWS: gateway 443, BFF web 8441, BFF móvil 8442, BFF ATM 8443 (mTLS), auth 9000
#   --insecure    no verifica el certificado del servidor (certificados de desarrollo autofirmados). Sin esta opción se valida
#                 con la CA de --certs-dir (la que generó infra/aws/ec2/push-secrets.sh con la IP en el SAN)
#   --certs-dir   carpeta con ca.crt y el certificado de cliente del cajero (por defecto certs/out, o certs/aws con --host)
set -euo pipefail
cd "$(dirname "$0")/.."

HOST=""; INSECURE=0; CERTS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="${2:?--host requiere un valor}"; shift 2 ;;
    --insecure) INSECURE=1; shift ;;
    --certs-dir) CERTS="${2:?--certs-dir requiere un valor}"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "opción desconocida: $1 (use --help)"; exit 2 ;;
  esac
done

set -a; source .env; set +a
if [[ -n "$HOST" ]]; then
  export GATEWAY_URL="https://$HOST:${GATEWAY_PORT:-443}" BFF_WEB_URL="https://$HOST:${BFF_WEB_PORT:-8441}" \
         BFF_MOBILE_URL="https://$HOST:${BFF_MOBILE_PORT:-8442}" BFF_ATM_URL="https://$HOST:${BFF_ATM_PORT:-8443}" AUTH_URL="http://$HOST:${AUTH_PORT:-9000}"
  export CERTS_DIR="${CERTS:-certs/aws}"
  LOG=evidencias/aws/logs/smoke-test-remote.log
else
  export CERTS_DIR="${CERTS:-certs/out}"
  LOG=evidencias/local/smoke-test.log
fi
if [[ $INSECURE -eq 1 ]]; then
  # curl "-k": los scripts hijos lo heredan por PATH (certificados autofirmados; el mTLS del cajero SIGUE exigiéndose)
  SHIM="$(mktemp -d)"; trap 'rm -rf "$SHIM"' EXIT
  printf '#!/bin/sh\nexec %s -k "$@"\n' "$(command -v curl)" > "$SHIM/curl"; chmod +x "$SHIM/curl"
  export PATH="$SHIM:$PATH"
fi
mkdir -p "$(dirname "$LOG")" logs
exec > >(tee "$LOG") 2>&1

GW="${GATEWAY_URL:-https://localhost:8446}"
CA="${CACERT:-$CERTS_DIR/ca.crt}"
fail=0
step() { echo; echo "################ $* ################"; }
jget() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

echo "SMOKE TEST Banco XYZ — $(date '+%Y-%m-%d %H:%M:%S') — destino: ${HOST:-local} (gateway $GW)$([[ $INSECURE -eq 1 ]] && echo ' [--insecure]')"
if [[ -z "$HOST" ]] && command -v docker >/dev/null && [[ -n "$(docker compose ps -q 2>/dev/null)" ]]; then
  step "0. Estado de los contenedores"
  docker compose ps --format 'table {{.Name}}\t{{.Status}}' | head -40
  bad="$(docker compose ps --format '{{.Status}}' | grep -ciE "unhealthy|starting|exited|restarting|dead" || true)"
  [[ "$bad" == 0 ]] || { echo "  ✘ hay contenedores no sanos"; fail=1; }
fi

step "1. Tokens por canal (auth-server: Authorization Code + PKCE para web/móvil, client_credentials para ATM e interno)"
for ch in internal atm; do t="$(scripts/token.sh $ch)"; echo "  ✔ token $ch → scopes: $(python3 -c "import sys,json,base64; p=sys.argv[1].split('.')[1]; p+='='*(-len(p)%4); print(' '.join(json.loads(base64.urlsafe_b64decode(p))['scope']))" "$t")"; done
for ch in web mobile; do t="$(scripts/token.sh $ch customer1)"; echo "  ✔ token $ch (customer1) → scopes: $(python3 -c "import sys,json,base64; p=sys.argv[1].split('.')[1]; p+='='*(-len(p)%4); print(' '.join(json.loads(base64.urlsafe_b64decode(p))['scope']))" "$t")"; done

step "2. Flujo de negocio por el gateway: cliente → cuentas → depósito → transferencia → reglas → seguridad → eventos"
scripts/e2e-flow.sh || fail=1

step "3. Los tres BFF: web (sesión+cookie), móvil (PKCE+ETag/gzip), ATM (mTLS+Idempotency-Key) y rechazo entre canales"
scripts/bff-flow.sh || fail=1

step "4. Los tres jobs batch lanzados por el gateway (rol BATCH_ADMIN) sobre los CSV legacy"
BATCH="$(scripts/token.sh web batch-admin)"
RUN="smoke-$(date +%s)"
for job in dailyTransactionReportJob monthlyInterestJob annualStatementJob; do
  resp="$(curl -s --cacert "$CA" -H "Authorization: Bearer $BATCH" -H 'Content-Type: application/json' -X POST \
          -d "{\"parameters\":{\"runId\":\"$RUN\"}}" "$GW/api/batch/jobs/$job")"
  id="$(jget 'd["executionId"]' <<<"$resp" 2>/dev/null || true)"
  if [[ -z "$id" ]]; then echo "  ✘ $job: no se pudo lanzar: $resp"; fail=1; continue; fi
  status=""
  for _ in $(seq 1 120); do
    view="$(curl -s --cacert "$CA" -H "Authorization: Bearer $BATCH" "$GW/api/batch/executions/$id")"
    status="$(jget 'd["status"]' <<<"$view")"
    [[ "$status" =~ ^(COMPLETED|FAILED|STOPPED)$ ]] && break; sleep 1
  done
  [[ "$status" == COMPLETED ]] && echo "  ✔ $job (ejecución $id): $status" || { echo "  ✘ $job (ejecución $id): $status"; fail=1; }
  python3 -c "
import sys, json
for s in json.load(sys.stdin)['steps']:
    if ':' not in s['name']: print('      %-26s %-9s leídos=%-5d escritos=%-5d omitidos=%-4d filtrados=%d' % (s['name'], s['status'], s['read'], s['written'], s['skipped'], s['filtered']))" <<<"$view"
  rej="$(curl -s --cacert "$CA" -H "Authorization: Bearer $BATCH" "$GW/api/batch/executions/$id/rejected?limit=1000" | jget 'len(d)')"
  echo "      registros rechazados/filtrados persistidos en batch_rejected_record: $rej"
done
echo "  sin token → HTTP $(curl -s --cacert "$CA" -o /dev/null -w '%{http_code}' -X POST "$GW/api/batch/jobs/dailyTransactionReportJob")"
echo "  token de cliente (sin BATCH_ADMIN) → HTTP $(curl -s --cacert "$CA" -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(scripts/token.sh web customer1)" -X POST "$GW/api/batch/jobs/dailyTransactionReportJob")"

echo
if [[ $fail -eq 0 ]]; then echo "SMOKE TEST: TODO CORRECTO"; else echo "SMOKE TEST: HAY FALLOS"; fi
exit $fail
