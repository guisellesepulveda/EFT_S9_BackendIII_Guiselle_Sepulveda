#!/usr/bin/env bash
# Matriz de seguridad POR CANAL: cada token (web, móvil, ATM, interno) contra cada BFF. Solo el token de SU canal es aceptado;
# los demás reciben 403 (o 401 en el BFF web, que no acepta Bearer: solo sesión con cookie). El ATM se prueba con certificado de
# cliente VÁLIDO, así que el rechazo es por el canal del token y no por mTLS.
# Salida en evidencias/local/cross_channel.log.   Requisitos: plataforma levantada, .env, certs/out.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
LOG="${CROSS_LOG:-evidencias/local/cross_channel.log}"; mkdir -p "$(dirname "$LOG")"
exec > >(tee "$LOG") 2>&1
CERTS="${CERTS_DIR:-certs/out}"; CA="$CERTS/ca.crt"
WEB="${BFF_WEB_URL:-https://localhost:8443}"; MOB="${BFF_MOBILE_URL:-https://localhost:8444}"; ATM="${BFF_ATM_URL:-https://localhost:8445}"
ok=0
code() { curl -s --cacert "$CA" "${@:2}" -o /dev/null -w '%{http_code}' "$1"; }
verdict() { [[ "$1" == 401 || "$1" == 403 ]] && echo "rechazado" || echo "autorizado"; }
check() { # descripción, código obtenido, "ok" si se espera aceptar o "no" si se espera rechazar
  local v; v="$(verdict "$2")"
  if { [[ "$3" == ok && "$v" == autorizado ]] || [[ "$3" == no && "$v" == rechazado ]]; }; then echo "  ✔ $1 → HTTP $2 ($v)"; else echo "  ✘ $1 → HTTP $2 ($v, se esperaba $([[ $3 == ok ]] && echo autorizado || echo rechazado))"; ok=1; fi
}

echo "== Matriz de seguridad por canal: token de cada canal contra cada BFF"
echo "   Sondas: BFF web GET /web/dashboard (solo sesión) · BFF móvil GET /mobile/home (scope mobile:read) · BFF ATM GET /atm/withdrawals/sonda (scope atm:operate, con certificado de cajero válido)"
echo
declare -a T_NAMES=(web mobile atm internal)
TOK_web="$(scripts/token.sh web customer1)"; TOK_mobile="$(scripts/token.sh mobile customer1)"; TOK_atm="$(scripts/token.sh atm)"; TOK_internal="$(scripts/token.sh internal)"
for t in "${T_NAMES[@]}"; do
  eval "tok=\$TOK_$t"; H=(-H "Authorization: Bearer $tok")
  w="$(code "$WEB/web/dashboard" "${H[@]}")"
  m="$(code "$MOB/mobile/home" "${H[@]}")"
  a="$(code "$ATM/atm/withdrawals/sonda" "${H[@]}" --cert "$CERTS/atm-0001.crt" --key "$CERTS/atm-0001.key")"
  echo "-- token del canal '$t'"
  check "$t → BFF web (Bearer)  " "$w" no
  [[ "$t" == mobile ]] && check "$t → BFF móvil          " "$m" ok || check "$t → BFF móvil          " "$m" no
  [[ "$t" == atm ]] && check "$t → BFF ATM (mTLS)    " "$a" ok || check "$t → BFF ATM (mTLS)    " "$a" no
done
echo
echo "-- sin token"
check "sin token → BFF móvil" "$(code "$MOB/mobile/home")" no
check "sin token → BFF ATM (con certificado)" "$(code "$ATM/atm/withdrawals/sonda" --cert "$CERTS/atm-0001.crt" --key "$CERTS/atm-0001.key")" no
echo
[[ $ok -eq 0 ]] && echo "MATRIZ POR CANAL: TODO CORRECTO" || echo "MATRIZ POR CANAL: HAY FALLOS"
exit $ok
