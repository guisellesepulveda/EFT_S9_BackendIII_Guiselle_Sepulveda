#!/usr/bin/env bash
# Flujo de negocio completo a través del API Gateway (requiere la plataforma levantada: scripts/run-local.sh start).
# Cliente → 3 cuentas → depósito → transferencia (saga) → reglas de negocio → seguridad por canal → consistencia eventual.
# Imprime cada verificación (✔/✘) y sale con código ≠ 0 si alguna falla.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
GW="${GATEWAY_URL:-https://localhost:8446}"
CACERT="${CACERT:-${CERTS_DIR:-certs/out}/ca.crt}"      # CA de desarrollo (certs/generate-dev-certs.sh): el gateway sirve HTTPS
RUN="$(date +%s)"

ok=0
HTTP=""; BODY=""
check() { # descripción, obtenido, esperado
  if [[ "$2" == "$3" ]]; then echo "  ✔ $1: $2"; else echo "  ✘ $1: obtenido '$2', esperado '$3'"; ok=1; fi
}
# api MÉTODO RUTA [CUERPO] [TOKEN] [cabeceras extra…]  → deja el cuerpo en $BODY y el código HTTP en $HTTP
api() {
  local method="$1" path="$2" body="${3:-}" token="${4:-$WEB}"
  shift $(( $# < 4 ? $# : 4 ))
  local out; out="$(mktemp)"
  if [[ -n "$body" ]]; then
    HTTP=$(curl -sS --cacert "$CACERT" -o "$out" -w '%{http_code}' -X "$method" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' ${1+"$@"} -d "$body" "$GW$path")
  else
    HTTP=$(curl -sS --cacert "$CACERT" -o "$out" -w '%{http_code}' -X "$method" -H "Authorization: Bearer $token" ${1+"$@"} "$GW$path")
  fi
  BODY="$(cat "$out")"; rm -f "$out"
}
field() { jq -r "$1" <<<"$BODY"; }
wait_status() { # paymentId estado → imprime el estado alcanzado
  local s=""
  for _ in $(seq 1 60); do
    api GET "/api/payments/$1"; s="$(field .status)"
    [[ "$s" == "$2" ]] && break
    sleep 0.5
  done
  echo "$s"
}

echo "== Tokens (Authorization Code + PKCE contra el auth-server)"
WEB="$(scripts/token.sh web customer1)"
ADMIN="$(scripts/token.sh web admin)"
echo "  token web de customer1 y de admin obtenidos"

echo "== 1. Cliente"
api POST /api/customers "{\"documentNumber\":\"$((10000000 + RUN % 9000000))-5\",\"firstName\":\"Camila\",\"lastName\":\"Rojas\",\"email\":\"camila.$RUN@example.com\",\"phone\":\"+56912345678\",\"username\":\"e2e-$RUN\"}"
check "POST /customers → HTTP" "$HTTP" 201
CUST="$(field .id)"

echo "== 2. Cuentas (accounts-service verifica al cliente en customers-service: Eureka + Feign + Resilience4j)"
api POST /api/accounts "{\"customerId\":\"$CUST\",\"type\":\"CHECKING\"}"; check "cuenta A → HTTP" "$HTTP" 201; A="$(field .id)"
api POST /api/accounts "{\"customerId\":\"$CUST\",\"type\":\"SAVINGS\"}"; check "cuenta B → HTTP" "$HTTP" 201; B="$(field .id)"
api POST /api/accounts "{\"customerId\":\"$CUST\",\"type\":\"SAVINGS\"}"; check "cuenta C → HTTP" "$HTTP" 201; C="$(field .id)"
api PATCH "/api/accounts/$C/close"; check "cerrar C (saldo 0) → HTTP" "$HTTP" 200

echo "== 3. Depósito de 10000 en A (saga asíncrona: requested → credited → completed)"
api POST /api/payments/deposit "{\"toAccountId\":\"$A\",\"amount\":10000}" "$WEB" -H "Idempotency-Key: dep-$RUN"
check "HTTP" "$HTTP" 202; P1="$(field .paymentId)"
check "estado final del depósito" "$(wait_status "$P1" COMPLETED)" COMPLETED
api POST /api/payments/deposit "{\"toAccountId\":\"$A\",\"amount\":10000}" "$WEB" -H "Idempotency-Key: dep-$RUN"
check "idempotencia: misma Idempotency-Key → HTTP 200 y el mismo pago" "$HTTP/$([[ "$(field .paymentId)" == "$P1" ]] && echo mismo || echo distinto)" "200/mismo"

echo "== 4. Transferencia A → B de 2500.50"
api POST /api/payments/transfer "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":2500.50}"
check "HTTP" "$HTTP" 202; P2="$(field .paymentId)"
check "estado final de la transferencia" "$(wait_status "$P2" COMPLETED)" COMPLETED
api GET "/api/accounts/$A/balance"; check "saldo A" "$(field .balance)" 7499.50
api GET "/api/accounts/$B/balance"; check "saldo B" "$(field .balance)" 2500.50
api GET "/api/payments/$P2"; echo "  historial del pago: $(jq -c '[.history[].status]' <<<"$BODY")"

echo "== 5. Reglas de negocio"
api POST /api/payments/withdraw "{\"fromAccountId\":\"$B\",\"amount\":999999}"; check "retiro sin fondos → HTTP" "$HTTP" 422
api POST /api/payments/transfer "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$A\",\"amount\":10}"; check "transferencia a la misma cuenta → HTTP" "$HTTP" 422
api POST /api/payments/transfer "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$C\",\"amount\":10}"; check "transferencia a cuenta cerrada → HTTP" "$HTTP" 422

echo "== 6. Seguridad"
HTTP=$(curl -s --cacert "$CACERT" -o /dev/null -w '%{http_code}' "$GW/api/customers"); check "sin token → HTTP" "$HTTP" 401
api GET /api/admin/customers-service/security-alerts; check "token de cliente (sin ROLE_ADMIN) en endpoint admin → HTTP" "$HTTP" 403
api GET /api/admin/customers-service/security-alerts "" "$ADMIN"; check "token con ROLE_ADMIN en endpoint admin → HTTP" "$HTTP" 200
api POST "/api/accounts/$A/debit" "{\"operationId\":\"x-$RUN\",\"amount\":1}"; check "un canal NO puede debitar directamente (solo scope internal) → HTTP" "$HTTP" 403

echo "== 7. Consistencia eventual (accounts.lifecycle → perfil del cliente) y proyecciones"
OPEN=""
for _ in $(seq 1 40); do api GET "/api/customers/$CUST/profile"; OPEN="$(field .accountsOpen)"; [[ "$OPEN" == "2" ]] && break; sleep 0.5; done
check "cuentas abiertas en el perfil (3 abiertas − 1 cerrada)" "$OPEN" 2
api GET "/api/accounts/$A/movements?limit=5"; echo "  movimientos de A: $(jq -c '[.[] | {op_type, amount, movement_status}]' <<<"$BODY")"
for _ in $(seq 1 20); do api GET "/api/admin/customers-service/notifications?limit=2" "" "$ADMIN"; [[ "$(jq length <<<"$BODY")" -ge 2 ]] && break; sleep 0.5; done
echo "  notificaciones (transactions.completed → customers): $(jq -c '[.[].message]' <<<"$BODY")"

echo "== 8. Trazabilidad"
TRACE="$(curl -s --cacert "$CACERT" -D - -o /dev/null -H "Authorization: Bearer $WEB" "$GW/api/accounts/$A" | tr -d '\r' | awk 'tolower($1)=="x-trace-id:" {print $2}')"
check "X-Trace-Id presente" "$([[ -n "$TRACE" ]] && echo si || echo no)" si
echo "  traceId=$TRACE  (Zipkin, local o por túnel SSH: http://localhost:9411/zipkin/traces/$TRACE)"

echo
if [[ $ok -eq 0 ]]; then echo "FLUJO E2E: TODO CORRECTO"; else echo "FLUJO E2E: HAY FALLOS"; fi
echo "$A $B $C $CUST" > logs/e2e-last.ids
exit $ok
