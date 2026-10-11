#!/usr/bin/env bash
# Obtiene un access token del auth-server por canal e imprime SOLO el token (apto para $(...)).
#   scripts/token.sh internal                 → client_credentials, scope internal
#   scripts/token.sh atm                      → client_credentials, scope atm:operate
#   scripts/token.sh web    [usuario]         → Authorization Code + PKCE (confidencial), scopes web:read web:write
#   scripts/token.sh mobile [usuario]         → Authorization Code + PKCE, scopes mobile:read mobile:transfer
# Usuarios de demo: customer1 (CUSTOMER), admin (ADMIN), batch-admin (BATCH_ADMIN); contraseñas en .env.
# Variables: AUTH_URL (por defecto http://localhost:9000), BFF_WEB_URL (https://localhost:8443; para el redirect del cliente web).
# Requiere curl y openssl.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
AUTH_URL="${AUTH_URL:-http://localhost:9000}"
channel="${1:?canal: internal|atm|web|mobile}"
user="${2:-customer1}"

json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }

client_credentials() { # client secret scope
  curl -fsS -u "$1:$2" -d grant_type=client_credentials -d "scope=$3" "$AUTH_URL/oauth2/token" | json_field access_token
}

auth_code() { # client secret scope redirect usuario password
  local client="$1" secret="$2" scope="$3" redirect="$4" username="$5" password="$6"
  local jar verifier challenge location code csrf
  jar="$(mktemp)"; trap 'rm -f "$jar"' RETURN
  verifier="$(openssl rand -base64 48 | tr -d '=+/\n' | cut -c1-64)"
  challenge="$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
  local authorize="$AUTH_URL/oauth2/authorize?response_type=code&client_id=$client&scope=$(printf '%s' "$scope" | sed 's/ /%20/g')&redirect_uri=$(printf '%s' "$redirect" | sed 's/:/%3A/g; s#/#%2F#g')&state=cli&code_challenge=$challenge&code_challenge_method=S256"
  curl -fsS -c "$jar" -b "$jar" -o /dev/null "$authorize" || true          # crea la sesión y redirige a /login
  csrf="$(curl -fsS -c "$jar" -b "$jar" "$AUTH_URL/login" | sed -n 's/.*name="_csrf" type="hidden" value="\([^"]*\)".*/\1/p' | head -1)"
  [[ -n "$csrf" ]] || csrf="$(curl -fsS -c "$jar" -b "$jar" "$AUTH_URL/login" | grep -o 'name="_csrf"[^>]*value="[^"]*"' | sed 's/.*value="//; s/"$//' | head -1)"
  curl -sS -c "$jar" -b "$jar" -o /dev/null --data-urlencode "username=$username" --data-urlencode "password=$password" \
       --data-urlencode "_csrf=$csrf" "$AUTH_URL/login"
  location="$(curl -sS -c "$jar" -b "$jar" -o /dev/null -D - "$authorize" | tr -d '\r' | awk 'tolower($1)=="location:" {print $2}')"
  code="$(printf '%s' "$location" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')"
  [[ -n "$code" ]] || { echo "No se obtuvo el código de autorización (¿usuario/contraseña?). Location: $location" >&2; exit 1; }
  curl -fsS -u "$client:$secret" -d grant_type=authorization_code -d "code=$code" --data-urlencode "redirect_uri=$redirect" \
       -d "code_verifier=$verifier" "$AUTH_URL/oauth2/token" | json_field access_token
}

password_of() {
  case "$1" in
    customer1) echo "$DEMO_USER_PASSWORD" ;;
    admin) echo "$ADMIN_PASSWORD" ;;
    batch-admin) echo "$BATCH_ADMIN_PASSWORD" ;;
    *) echo "usuario desconocido: $1" >&2; exit 1 ;;
  esac
}

case "$channel" in
  internal) client_credentials internal-service "$INTERNAL_SERVICE_CLIENT_SECRET" internal ;;
  atm) client_credentials atm-client "$ATM_CLIENT_SECRET" atm:operate ;;
  web) auth_code web-client "$WEB_CLIENT_SECRET" "openid web:read web:write" "${BFF_WEB_URL:-https://localhost:8443}/login/oauth2/code/web-client" "$user" "$(password_of "$user")" ;;
  mobile) auth_code mobile-client "$MOBILE_CLIENT_SECRET" "openid mobile:read mobile:transfer" "bancoxyz://callback" "$user" "$(password_of "$user")" ;;
  *) echo "canal desconocido: $channel" >&2; exit 1 ;;
esac
