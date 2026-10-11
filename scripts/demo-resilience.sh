#!/usr/bin/env bash
# Demostración de resiliencia. Funciona en los DOS modos de ejecución: contenedores (docker compose up -d) o procesos locales
# (scripts/run-local.sh start); detecta el modo solo. Detiene accounts-service, crea un pago y muestra:
#   payments → PENDING degradado (no se pierde) · gateway → fallback 503 ProblemDetail · estado de circuitos (Actuator)
#   y, al reiniciar accounts-service, el pago se completa SOLO (el evento esperaba en Kafka).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
GW="${GATEWAY_URL:-https://localhost:8446}"
CACERT="${CACERT:-certs/out/ca.crt}"      # CA de desarrollo: el gateway sirve HTTPS
WEB="$(scripts/token.sh web customer1)"
DOCKER=0; [[ -n "$(docker compose ps -q accounts-service 2>/dev/null || true)" ]] && DOCKER=1
echo "Modo: $([[ $DOCKER -eq 1 ]] && echo contenedores || echo procesos locales)"
auth=(-H "Authorization: Bearer $WEB")
json=(-H 'Content-Type: application/json')

# Espera a que el gateway vea al servicio (Eureka + caché del balanceador tardan unos segundos tras un arranque)
wait_route() { # ruta que responde algo distinto de 503 cuando el servicio está visible
  for _ in $(seq 1 60); do
    code=$(curl -s --cacert "$CACERT" -o /dev/null -w '%{http_code}' "${auth[@]}" "$GW$1") ; [[ "$code" != 503 ]] && return 0; sleep 1
  done
  echo "El gateway no ve $1"; return 1
}
wait_route /api/accounts/00000000-0000-0000-0000-000000000000
wait_route /api/customers/00000000-0000-0000-0000-000000000000

# Datos de apoyo: cliente y cuenta
CUST=$(curl -fsS --cacert "$CACERT" "${auth[@]}" "${json[@]}" -d "{\"documentNumber\":\"$((10000000 + $(date +%s) % 9000000))-5\",\"firstName\":\"Resi\",\"lastName\":\"Liencia\",\"email\":\"resi.$(date +%s)@example.com\"}" "$GW/api/customers" | jq -r .id)
ACC=$(curl -fsS --cacert "$CACERT" "${auth[@]}" "${json[@]}" -d "{\"customerId\":\"$CUST\",\"type\":\"CHECKING\"}" "$GW/api/accounts" | jq -r .id)
echo "Cuenta de prueba: $ACC"

echo; echo "== 1. Detengo accounts-service"
if [[ $DOCKER -eq 1 ]]; then
  docker compose stop accounts-service 2>&1 | sed 's/^/  /' | tail -3
else
  kill "$(cat logs/accounts-service.pid)"; rm -f logs/accounts-service.pid
  # esperar al cierre REAL del puerto (el health cae antes de que Tomcat deje de atender)
  for _ in $(seq 1 30); do (exec 3<>/dev/tcp/localhost/8082) 2>/dev/null || break; sleep 1; done
fi
sleep 1

echo; echo "== 2. POST /payments/deposit con accounts caído → se acepta como PENDING degradado (HTTP 202); 6 depósitos para abrir el circuito"
RUN="$(date +%s)"
for i in 1 2 3 4 5 6; do
  RES=$(curl -sS --cacert "$CACERT" -w '\nHTTP %{http_code}\n' "${auth[@]}" "${json[@]}" -H "Idempotency-Key: resi-$RUN-$i" -d "{\"toAccountId\":\"$ACC\",\"amount\":500}" "$GW/api/payments/deposit")
  if [[ $i -eq 1 ]]; then
    echo "$RES" | sed '$d' | jq -c '{paymentId,status,degraded,degradedReason}'; echo "$RES" | tail -1
    PID=$(echo "$RES" | sed '$d' | jq -r .paymentId)
  fi
  sleep 0.3
done

echo; echo "== 3. Estado de los circuitos de payments (/actuator/circuitbreakers)"
if [[ $DOCKER -eq 1 ]]; then   # el puerto de los microservicios no se publica: se consulta dentro del contenedor
  docker compose exec -T payments-service wget -qO- http://localhost:8083/actuator/circuitbreakers
else
  curl -fsS localhost:8083/actuator/circuitbreakers
fi | jq -c '.circuitBreakers | map_values({state, failureRate})'

echo; echo "== 4. El gateway responde el fallback del circuit breaker (503 ProblemDetail + Retry-After)"
curl -sS --cacert "$CACERT" -D - -o /tmp/gw-fallback.json "${auth[@]}" "$GW/api/accounts/$ACC" | tr -d '\r' | grep -iE "^HTTP|retry-after"
jq -c '{status,code,service,detail}' /tmp/gw-fallback.json

echo; echo "== 5. El pago NO se perdió: sigue PENDING"
curl -fsS --cacert "$CACERT" "${auth[@]}" "$GW/api/payments/$PID" | jq -c '{status,degraded}'

echo; echo "== 6. Reinicio accounts-service: procesa el evento pendiente y el pago se completa solo"
if [[ $DOCKER -eq 1 ]]; then
  docker compose start accounts-service 2>&1 | sed 's/^/  /' | tail -1
  until [[ "$(docker compose ps accounts-service --format '{{.Status}}' | grep -c healthy)" -ge 1 ]]; do sleep 2; done
else
  scripts/run-local.sh start 2>&1 | grep accounts-service | tail -1
fi
wait_route /api/accounts/00000000-0000-0000-0000-000000000000
for i in $(seq 1 60); do S=$(curl -fsS --cacert "$CACERT" "${auth[@]}" "$GW/api/payments/$PID" | jq -r .status); [[ "$S" == COMPLETED ]] && break; sleep 1; done
echo "Estado final: $S (tras ~${i}s)"
curl -fsS --cacert "$CACERT" "${auth[@]}" "$GW/api/payments/$PID" | jq -c '[.history[] | {status, detail}]'
echo "Saldo final de la cuenta (6 × 500 = 3000 esperado): $(curl -fsS --cacert "$CACERT" "${auth[@]}" "$GW/api/accounts/$ACC/balance" | jq -r .balance)"
[[ "$S" == COMPLETED ]]
