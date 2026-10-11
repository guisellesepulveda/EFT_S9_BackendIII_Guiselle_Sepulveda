#!/usr/bin/env bash
# Verificación EN VIVO de los tres BFF (requiere scripts/run-local.sh start): autenticación y autorización por canal, rechazo de
# tokens de otros canales, mTLS del cajero, límites/idempotencia del retiro y comparación de tamaño de payload.
# Imprime cada verificación (✔/✘) y sale con código ≠ 0 si alguna falla. Con PAYLOADS_OUT=<archivo> escribe la tabla Markdown.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
GW="${GATEWAY_URL:-https://localhost:8446}"
WEBBFF="${BFF_WEB_URL:-https://localhost:8443}"
MOBBFF="${BFF_MOBILE_URL:-https://localhost:8444}"
ATMBFF="${BFF_ATM_URL:-https://localhost:8445}"
CERTS="${CERTS_DIR:-certs/out}"            # CA y certificados de cliente del cajero (mTLS)
CA="${CACERT:-$CERTS/ca.crt}"
ATM_CERT=(--cert "$CERTS/atm-0001.crt" --key "$CERTS/atm-0001.key")
AUTH_URL="${AUTH_URL:-http://localhost:9000}"
RUN="$(date +%s)"
ok=0
check() { if [[ "$2" == "$3" ]]; then echo "  ✔ $1: $2"; else echo "  ✘ $1: obtenido '$2', esperado '$3'"; ok=1; fi; }
code() { curl -s --cacert "$CA" -o /dev/null -w '%{http_code}' "$@"; }          # solo el código HTTP
bytes() { curl -s --cacert "$CA" -o /dev/null -w '%{size_download}' "$@"; }     # bytes del cuerpo recibido (tal como viajan)
jget() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

echo "== 0. Datos de apoyo (cliente 'customer1' con 2 cuentas y saldo) vía gateway HTTPS con token interno"
INT="$(scripts/token.sh internal)"
H=(-H "Authorization: Bearer $INT" -H 'Content-Type: application/json')
CUST_JSON="$(curl -s --cacert "$CA" "${H[@]}" "$GW/api/customers/by-username/customer1")"
if [[ "$(jget 'd.get("id","")' <<<"$CUST_JSON")" == "" ]]; then
  CUST_JSON="$(curl -fsS --cacert "$CA" "${H[@]}" -d '{"documentNumber":"11111111-1","firstName":"Camila","lastName":"Rojas","email":"customer1@example.com","phone":"+56912345678","username":"customer1"}' "$GW/api/customers")"
fi
CUST="$(jget 'd["id"]' <<<"$CUST_JSON")"
ACCS="$(curl -fsS --cacert "$CA" "${H[@]}" "$GW/api/accounts?customerId=$CUST")"
open_account() { # índice → id de la n-ésima cuenta ABIERTA existente, o vacío (se reutilizan: la corrida es repetible)
  jget '([a["id"] for a in d if a["status"]=="OPEN"]+["",""])['"$1"']' <<<"$ACCS"
}
A="$(open_account 0)"; B="$(open_account 1)"
[[ -n "$A" ]] || A="$(curl -fsS --cacert "$CA" "${H[@]}" -d "$(printf '{"customerId":"%s","type":"CHECKING"}' "$CUST")" "$GW/api/accounts" | jget 'd["id"]')"
[[ -n "$B" ]] || B="$(curl -fsS --cacert "$CA" "${H[@]}" -d "$(printf '{"customerId":"%s","type":"SAVINGS"}' "$CUST")" "$GW/api/accounts" | jget 'd["id"]')"
pay() { # tipo cuerpo clave → espera COMPLETED
  local id; id="$(curl -fsS --cacert "$CA" "${H[@]}" -H "Idempotency-Key: $3" -d "$2" "$GW/api/payments/$1" | jget 'd["paymentId"]')"
  for _ in $(seq 1 60); do [[ "$(curl -fsS --cacert "$CA" "${H[@]}" "$GW/api/payments/$id" | jget 'd["status"]')" == COMPLETED ]] && return 0; sleep 0.5; done
  echo "  el pago $id no se completó"; return 1
}
pay deposit "$(printf '{"toAccountId":"%s","amount":900000}' "$A")" "seed-dep-$RUN"
pay transfer "$(printf '{"fromAccountId":"%s","toAccountId":"%s","amount":2500.50}' "$A" "$B")" "seed-trf-$RUN"
echo "  cliente=$CUST cuentaA=$A cuentaB=$B"

# ------------------------------------------------------------------------------------------------------------------ ATM
echo; echo "== 1. BFF ATM (mTLS + client_credentials atm:operate): $ATMBFF"
ATM="$(scripts/token.sh atm)"
MOBILE="$(scripts/token.sh mobile customer1)"
WEBTOKEN="$(scripts/token.sh web customer1)"
CARD="4000$(printf '%010d' "$RUN")"; CARD2="4001$(printf '%010d' "$RUN")"
J() { printf '{"cardId":"%s","pin":"%s"%s}' "$1" "$2" "${3:+,\"amount\":$3}"; }           # cuerpo de consulta/retiro
JC() { printf '{"cardId":"%s","accountId":"%s","pin":"1234"}' "$1" "$2"; }                  # emisión de tarjeta
atm() { curl -s --cacert "$CA" "${ATM_CERT[@]}" -H 'Content-Type: application/json' "$@"; }
atmcode() { curl -s --cacert "$CA" "${ATM_CERT[@]}" -H 'Content-Type: application/json' -o /dev/null -w '%{http_code}' "$@"; }
for c in "$CARD" "$CARD2"; do
  check "emisión simulada de tarjeta ****${c: -4} (scope internal) → HTTP" \
    "$(atmcode -H "Authorization: Bearer $INT" -d "$(JC "$c" "$A")" "$ATMBFF/atm/internal/cards")" 201
done
AH=(-H "Authorization: Bearer $ATM")
echo "  -- consultas (el PIN se valida en el servidor)"
BAL="$(atm "${AH[@]}" -d "$(J "$CARD" 1234)" "$ATMBFF/atm/balance")"; echo "  saldo → $BAL"
ACCNUM="$(curl -s --cacert "$CA" "${H[@]}" "$GW/api/accounts/$A" | jget 'd["number"]')"
check "saldo (el número de cuenta sale enmascarado)" "$(jget 'd["account"]' <<<"$BAL")" "****${ACCNUM: -4}"
MOV="$(atm "${AH[@]}" -d "$(J "$CARD" 1234)" "$ATMBFF/atm/movements")"; echo "  movimientos → $MOV"
check "movimientos (máx. 5)" "$([[ "$(jget 'len(d)' <<<"$MOV")" -le 5 ]] && echo si || echo no)" si
echo "  -- retiro con Idempotency-Key"
KEY="atm-w-$RUN"
W1="$(atm "${AH[@]}" -H "Idempotency-Key: $KEY" -d "$(J "$CARD" 1234 20000)" "$ATMBFF/atm/withdrawals")"; echo "  retiro → $W1"
check "retiro aprobado y dispensa solo tras confirmar" "$(jget 'd["status"]+"/"+str(d["dispense"])' <<<"$W1")" "APPROVED/True"
W2="$(atm "${AH[@]}" -H "Idempotency-Key: $KEY" -d "$(J "$CARD" 1234 20000)" "$ATMBFF/atm/withdrawals")"
check "reintento con la MISMA clave → misma decisión (sin doble débito)" "$([[ "$W1" == "$W2" ]] && echo igual || echo distinto)" igual
BEFORE="$(curl -s --cacert "$CA" "${H[@]}" "$GW/api/accounts/$A/balance" | jget 'd["balance"]')"; echo "  saldo de la cuenta tras el retiro y su reintento: $BEFORE"
check "sin Idempotency-Key → HTTP" "$(atmcode "${AH[@]}" -d "$(J "$CARD" 1234 20000)" "$ATMBFF/atm/withdrawals")" 400
check "misma clave con otro monto → HTTP" "$(atmcode "${AH[@]}" -H "Idempotency-Key: $KEY" -d "$(J "$CARD" 1234 30000)" "$ATMBFF/atm/withdrawals")" 422
check "monto sobre el máximo por retiro (200.000) → HTTP" "$(atmcode "${AH[@]}" -H "Idempotency-Key: big-$RUN-1" -d "$(J "$CARD" 1234 210000)" "$ATMBFF/atm/withdrawals")" 422
for i in 1 2; do atmcode "${AH[@]}" -H "Idempotency-Key: day-$RUN-$i" -d "$(J "$CARD" 1234 200000)" "$ATMBFF/atm/withdrawals" >/dev/null; done
check "supera el límite diario (500.000) → HTTP" "$(atmcode "${AH[@]}" -H "Idempotency-Key: day-$RUN-3" -d "$(J "$CARD" 1234 200000)" "$ATMBFF/atm/withdrawals")" 422
echo "  -- PIN"
check "PIN incorrecto → HTTP" "$(atmcode "${AH[@]}" -d "$(J "$CARD2" 0000)" "$ATMBFF/atm/balance")" 401
check "2.º PIN incorrecto → HTTP" "$(atmcode "${AH[@]}" -d "$(J "$CARD2" 0001)" "$ATMBFF/atm/balance")" 401
check "3.er PIN incorrecto bloquea la tarjeta → HTTP" "$(atmcode "${AH[@]}" -d "$(J "$CARD2" 0002)" "$ATMBFF/atm/balance")" 423
check "tarjeta bloqueada incluso con el PIN correcto → HTTP" "$(atmcode "${AH[@]}" -d "$(J "$CARD2" 1234)" "$ATMBFF/atm/balance")" 423
echo "  -- SEGURIDAD POR CANAL (tokens de otros canales contra el ATM, con certificado de cajero válido)"
check "token MÓVIL contra BFF ATM → HTTP" "$(atmcode -H "Authorization: Bearer $MOBILE" -d "$(J "$CARD" 1234)" "$ATMBFF/atm/balance")" 403
check "token WEB contra BFF ATM → HTTP" "$(atmcode -H "Authorization: Bearer $WEBTOKEN" -d "$(J "$CARD" 1234)" "$ATMBFF/atm/balance")" 403
check "token INTERNO contra BFF ATM → HTTP" "$(atmcode -H "Authorization: Bearer $INT" -d "$(J "$CARD" 1234)" "$ATMBFF/atm/balance")" 403
check "sin token → HTTP" "$(atmcode -d "$(J "$CARD" 1234)" "$ATMBFF/atm/balance")" 401
echo "  -- mTLS"
set +e
curl -s --cacert "$CA" -o /dev/null -H "Authorization: Bearer $ATM" -H 'Content-Type: application/json' -d '{}' "$ATMBFF/atm/balance"; NOCERT=$?
curl -s --cacert "$CA" --cert "$CERTS/rogue.crt" --key "$CERTS/rogue.key" -o /dev/null -H "Authorization: Bearer $ATM" -H 'Content-Type: application/json' -d '{}' "$ATMBFF/atm/balance"; ROGUE=$?
set -e
check "sin certificado de cliente: el handshake TLS falla (curl sale ≠ 0)" "$([[ $NOCERT -ne 0 ]] && echo falla || echo pasa)" falla
check "certificado firmado por OTRA CA: el handshake TLS falla" "$([[ $ROGUE -ne 0 ]] && echo falla || echo pasa)" falla
echo "  (curl exit sin cert=$NOCERT, cert ajeno=$ROGUE)"

# --------------------------------------------------------------------------------------------------------------- MOBILE
echo; echo "== 2. BFF móvil (PKCE + access token corto, scopes mobile:*): $MOBBFF"
MH=(-H "Authorization: Bearer $MOBILE")
HOME_HDRS="$(curl -s --cacert "$CA" -D - -o /dev/null "${MH[@]}" "$MOBBFF/mobile/home" | tr -d '\r')"
ETAG="$(awk 'tolower($1)=="etag:" {print $2}' <<<"$HOME_HDRS")"
echo "  cabeceras: $(grep -iE '^(etag|cache-control)' <<<"$HOME_HDRS" | tr '\n' ' ')"
check "GET /mobile/home → HTTP" "$(code "${MH[@]}" "$MOBBFF/mobile/home")" 200
check "ETag presente" "$([[ -n "$ETAG" ]] && echo si || echo no)" si
check "If-None-Match → HTTP 304 sin cuerpo" "$(code "${MH[@]}" -H "If-None-Match: $ETAG" "$MOBBFF/mobile/home")" 304
PLAIN="$(bytes "${MH[@]}" "$MOBBFF/mobile/accounts")"; GZ="$(bytes --compressed "${MH[@]}" "$MOBBFF/mobile/accounts")"
FIELDS="$(bytes "${MH[@]}" "$MOBBFF/mobile/accounts?fields=number,balance")"
echo "  /mobile/accounts: completo=$PLAIN B, gzip=$GZ B, fields=number,balance → $FIELDS B"
check "fields= reduce la respuesta" "$([[ "$FIELDS" -lt "$PLAIN" ]] && echo si || echo no)" si
check "campo no permitido (fields=ssn) → HTTP" "$(code "${MH[@]}" "$MOBBFF/mobile/accounts?fields=ssn")" 400
TRF='{"from":"'"$A"'","to":"'"$B"'","amount":1000,"note":"demo móvil"}'
check "transferencia sin Idempotency-Key → HTTP" "$(code "${MH[@]}" -H 'Content-Type: application/json' -d "$TRF" "$MOBBFF/mobile/transfers")" 400
check "transferencia con Idempotency-Key → HTTP" "$(code "${MH[@]}" -H 'Content-Type: application/json' -H "Idempotency-Key: mob-$RUN-0001" -d "$TRF" "$MOBBFF/mobile/transfers")" 202
echo "  -- SEGURIDAD POR CANAL"
check "token ATM contra BFF móvil → HTTP" "$(code -H "Authorization: Bearer $ATM" "$MOBBFF/mobile/home")" 403
check "token WEB contra BFF móvil → HTTP" "$(code -H "Authorization: Bearer $WEBTOKEN" "$MOBBFF/mobile/home")" 403
check "token INTERNO contra BFF móvil → HTTP" "$(code -H "Authorization: Bearer $INT" "$MOBBFF/mobile/home")" 403
check "sin token → HTTP" "$(code "$MOBBFF/mobile/home")" 401
check "cuenta ajena (otro cliente) → HTTP" "$(code "${MH[@]}" "$MOBBFF/mobile/accounts/00000000-0000-0000-0000-000000000000/movements")" 404

# ------------------------------------------------------------------------------------------------------------------- WEB
echo; echo "== 3. BFF web (patrón BFF: sesión + cookie HttpOnly/Secure/SameSite, Authorization Code + PKCE): $WEBBFF"
JAR="$(mktemp)"; AJAR="$(mktemp)"; trap 'rm -f "$JAR" "$AJAR"' EXIT
LOC="$(curl -s --cacert "$CA" -c "$JAR" -b "$JAR" -o /dev/null -D - "$WEBBFF/oauth2/authorization/web-client" | tr -d '\r' | awk 'tolower($1)=="location:" {print $2}')"
echo "  1) el BFF redirige al auth-server con PKCE: $(sed -E 's/.*(code_challenge_method=[A-Za-z0-9]+).*/\1/' <<<"$LOC")"
curl -s -c "$AJAR" -b "$AJAR" -o /dev/null "$LOC"
LOGIN_PAGE="$(curl -s -c "$AJAR" -b "$AJAR" "$AUTH_URL/login")"
CSRFTOK="$(grep -o 'name="_csrf"[^>]*value="[^"]*"' <<<"$LOGIN_PAGE" | sed 's/.*value="//; s/"$//' | head -1)"
curl -s -c "$AJAR" -b "$AJAR" -o /dev/null --data-urlencode "username=customer1" --data-urlencode "password=$DEMO_USER_PASSWORD" --data-urlencode "_csrf=$CSRFTOK" "$AUTH_URL/login"
CALLBACK="$(curl -s -c "$AJAR" -b "$AJAR" -o /dev/null -D - "$LOC" | tr -d '\r' | awk 'tolower($1)=="location:" {print $2}')"
echo "  2) tras el login el auth-server devuelve el código al BFF: ${CALLBACK%%\?*}"
LOGIN_HDRS="$(curl -s --cacert "$CA" -c "$JAR" -b "$JAR" -o /dev/null -D - "$CALLBACK" | tr -d '\r')"
SETCOOKIE="$(grep -i '^set-cookie: BFFWEB_SESSION' <<<"$LOGIN_HDRS" | head -1)"
echo "  3) el BFF canjea el código (secreto del cliente solo en el servidor) y crea la sesión: $SETCOOKIE"
check "cookie de sesión HttpOnly" "$(grep -qi 'HttpOnly' <<<"$SETCOOKIE" && echo si || echo no)" si
check "cookie de sesión Secure" "$(grep -qi 'Secure' <<<"$SETCOOKIE" && echo si || echo no)" si
check "cookie de sesión SameSite" "$(grep -qi 'SameSite=Lax' <<<"$SETCOOKIE" && echo si || echo no)" si
WC=(--cacert "$CA" -b "$JAR" -c "$JAR")
ME="$(curl -s "${WC[@]}" "$WEBBFF/web/me")"; echo "  /web/me → $ME"
check "usuario de la sesión" "$(jget 'd["username"]' <<<"$ME")" customer1
check "el navegador NO recibe tokens (/web/me no los incluye)" "$(grep -qiE 'access_token|refresh_token|eyJ' <<<"$ME" && echo expuesto || echo oculto)" oculto
DASH="$(curl -s "${WC[@]}" "$WEBBFF/web/dashboard?movements=5")"
check "dashboard agregado: cliente + perfil + cuentas con movimientos" "$(jget 'all(k in d for k in ("customer","profile","accounts")) and all("recentMovements" in a for a in d["accounts"])' <<<"$DASH")" True
check "paginación y filtros /web/accounts?size=1 → HTTP" "$(code "${WC[@]:0}" "$WEBBFF/web/accounts?size=1&status=OPEN")" 200
XSRF="$(awk '$6=="XSRF-TOKEN" {print $7}' "$JAR" | tail -1)"
TRFW='{"fromAccountId":"'"$A"'","toAccountId":"'"$B"'","amount":500,"description":"demo web"}'
check "POST sin token CSRF → HTTP" "$(code "${WC[@]}" -H 'Content-Type: application/json' -H "Idempotency-Key: web-$RUN-0001" -d "$TRFW" "$WEBBFF/web/transfers")" 403
check "POST con token CSRF (X-XSRF-TOKEN) → HTTP" "$(code "${WC[@]}" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $XSRF" -H "Idempotency-Key: web-$RUN-0001" -d "$TRFW" "$WEBBFF/web/transfers")" 202
check "CORS: origen no permitido no recibe Access-Control-Allow-Origin" "$(curl -s --cacert "$CA" -o /dev/null -D - -H 'Origin: https://evil.example' "${WC[@]}" "$WEBBFF/web/me" | tr -d '\r' | grep -ci '^access-control-allow-origin' || true)" 0
echo "  -- SEGURIDAD POR CANAL"
check "sin sesión → HTTP" "$(code "$WEBBFF/web/dashboard")" 401
check "token MÓVIL como Bearer contra BFF web → HTTP" "$(code -H "Authorization: Bearer $MOBILE" "$WEBBFF/web/dashboard")" 401
check "token ATM como Bearer contra BFF web → HTTP" "$(code -H "Authorization: Bearer $ATM" "$WEBBFF/web/dashboard")" 401

# --------------------------------------------------------------------------------------------------------- ALERTAS (Kafka)
echo; echo "== 4. Alertas a security.alerts (outbox → Kafka → consumidor en customers-service)"
ADMIN="$(scripts/token.sh web admin)"
sleep 3
ALERTS="$(curl -s --cacert "$CA" -H "Authorization: Bearer $ADMIN" "$GW/api/admin/customers-service/security-alerts?limit=200")"
for t in PIN_FAILED CARD_LOCKED CROSS_CHANNEL_TOKEN_REJECTED DAILY_LIMIT_EXCEEDED; do
  n="$(jget 'sum(1 for a in d if "'"$t"'" in json.dumps(a))' <<<"$ALERTS" 2>/dev/null || echo 0)"
  check "alerta $t registrada (≥1)" "$([[ "${n:-0}" -ge 1 ]] && echo si || echo no)" si
done

# ------------------------------------------------------------------------------------------------------ PAYLOADS
echo; echo "== 5. Comparación de payload para el MISMO dato (resumen de cuentas del cliente)"
G_CUST="$(bytes "${H[@]}" "$GW/api/customers/$CUST")"
G_PROF="$(bytes "${H[@]}" "$GW/api/customers/$CUST/profile")"
G_ACCS="$(bytes "${H[@]}" "$GW/api/accounts?customerId=$CUST")"
OPEN_IDS="$(jget '" ".join(a["id"] for a in d if a["status"]=="OPEN")' <<<"$(curl -s --cacert "$CA" "${H[@]}" "$GW/api/accounts?customerId=$CUST")")"
NOPEN=0; G_MOV=0
for id in $OPEN_IDS; do NOPEN=$((NOPEN + 1)); G_MOV=$((G_MOV + $(bytes "${H[@]}" "$GW/api/accounts/$id/movements?limit=5"))); done
RAW=$((G_CUST + G_PROF + G_ACCS + G_MOV)); RAW_CALLS=$((3 + NOPEN))
ms() { python3 -c "print(round(1000*($*)))"; }
T_RAW=0; for u in "customers/$CUST" "customers/$CUST/profile" "accounts?customerId=$CUST"; do T_RAW="$T_RAW + $(curl -s --cacert "$CA" "${H[@]}" -o /dev/null -w '%{time_total}' "$GW/api/$u")"; done
for id in $OPEN_IDS; do T_RAW="$T_RAW + $(curl -s --cacert "$CA" "${H[@]}" -o /dev/null -w '%{time_total}' "$GW/api/accounts/$id/movements?limit=5")"; done
T_RAW_MS="$(ms "$T_RAW")"
curl -s "${WC[@]}" -o /dev/null "$WEBBFF/web/dashboard?movements=5"            # calienta la caché de cliente
T_WEB_MS="$(ms "$(curl -s "${WC[@]}" -o /dev/null -w '%{time_total}' "$WEBBFF/web/dashboard?movements=5")")"
WEB_B="$(bytes "${WC[@]}" "$WEBBFF/web/dashboard?movements=5")"
WEB_GZ="$(bytes "${WC[@]}" --compressed "$WEBBFF/web/dashboard?movements=5")"
MOB_B="$(bytes "${MH[@]}" "$MOBBFF/mobile/home")"
MOB_GZ="$(bytes --compressed "${MH[@]}" "$MOBBFF/mobile/home")"
MOB_F="$(bytes "${MH[@]}" "$MOBBFF/mobile/accounts?fields=number,balance")"
ATM_B="$(bytes "${AH[@]}" "${ATM_CERT[@]}" -H 'Content-Type: application/json' -d "$(J "$CARD" 1234)" "$ATMBFF/atm/balance")"
echo "  sin BFF ($RAW_CALLS llamadas secuenciales, $T_RAW_MS ms): $RAW B | web (1 llamada, $T_WEB_MS ms) | web: $WEB_B B | móvil: $MOB_B B (gzip $MOB_GZ B, fields $MOB_F B) | ATM: $ATM_B B"
if [[ -n "${PAYLOADS_OUT:-}" ]]; then
  pct() { python3 -c "print(f'{100*(1-$1/$2):.0f} %')"; }
  cat > "$PAYLOADS_OUT" <<MD
# Comparación de tamaño de payload por canal (C4)

> Generado por \`scripts/bff-flow.sh\` ($(date '+%Y-%m-%d %H:%M')). Mismo cliente, mismas 2 cuentas y los mismos últimos movimientos.
> Los bytes son los del **cuerpo tal como viaja por la red** (\`size_download\` de curl).

| Variante | Llamadas del cliente | Bytes | Reducción vs. sin BFF |
|---|---:|---:|---:|
| Sin BFF: el cliente arma el resumen llamando al gateway (cliente + perfil + cuentas + movimientos de cada cuenta abierta) | $RAW_CALLS | $RAW | — |
| **BFF web** \`GET /web/dashboard?movements=5\` (cliente + perfil + cuentas + movimientos agregados en paralelo) | 1 | $WEB_B | $(pct "$WEB_B" "$RAW") |
| BFF web con gzip | 1 | $WEB_GZ | $(pct "$WEB_GZ" "$RAW") |
| **BFF móvil** \`GET /mobile/home\` (solo saldo total y campos esenciales por cuenta) | 1 | $MOB_B | $(pct "$MOB_B" "$RAW") |
| BFF móvil con gzip (\`Accept-Encoding: gzip\`) | 1 | $MOB_GZ | $(pct "$MOB_GZ" "$RAW") |
| BFF móvil \`GET /mobile/accounts?fields=number,balance\` (respuesta parcial) | 1 | $MOB_F | $(pct "$MOB_F" "$RAW") |
| BFF móvil con \`If-None-Match\` (ETag vigente) | 1 | 0 (HTTP 304) | 100 % |
| **BFF ATM** \`POST /atm/balance\` (cuenta enmascarada, saldo y moneda) | 1 | $ATM_B | $(pct "$ATM_B" "$RAW") |

**Tiempo de obtener el resumen (misma máquina, gateway local):** sin BFF, $RAW_CALLS llamadas secuenciales ≈ **$T_RAW_MS ms**; con el BFF web, 1 llamada
(servicios consultados en paralelo) ≈ **$T_WEB_MS ms**. En el canal web la ventaja es menos viajes de red y latencia, no menos bytes: necesita los datos completos.

**Nota sobre gzip:** el servidor solo comprime respuestas de 256 B o más (\`server.compression.min-response-size\`), por eso con un cliente de pocas cuentas
las filas "con gzip" no cambian. Con más datos sí: en una corrida con 6 cuentas abiertas, \`/mobile/accounts\` pasó de 1492 B a 503 B comprimido (−66 %),
y \`MobileTransportIT\` verifica que la respuesta comprimida es menor e idéntica al descomprimirla.

**Lectura:** el canal web recibe los datos completos y agregados en una sola llamada; el móvil recibe lo mínimo y, además, comprimido,
con validación por ETag y campos a pedido; el cajero recibe solo lo imprescindible para operar. Las cifras dependen de los datos de la
corrida (nombres, cantidad de cuentas y movimientos), pero la relación entre canales se mantiene.
MD
  echo "  tabla escrita en $PAYLOADS_OUT"
fi

echo
if [[ $ok -eq 0 ]]; then echo "FLUJO BFF: TODO CORRECTO"; else echo "FLUJO BFF: HAY FALLOS"; fi
exit $ok
