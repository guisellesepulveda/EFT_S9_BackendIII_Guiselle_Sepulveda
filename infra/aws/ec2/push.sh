#!/usr/bin/env bash
# Sube el código y los secretos a la instancia (DESDE el portátil). Útil mientras el repositorio no tenga remoto en GitHub, y
# necesario para que el smoke test remoto funcione: el portátil conserva la misma .env y la misma CA que la instancia.
#   infra/aws/ec2/push.sh [--host IP_o_DNS] [--key llave.pem] [--user ec2-user] [--role apps]
#   infra/aws/ec2/push.sh <llave.pem> <IP_o_DNS> [usuario]                 (forma posicional, equivalente)
# Sin argumentos toma SSH_KEY y APPS_IP de infra/aws/.deploy-state (lo escribe el paso a paso de despliegue.md, Parte A).
# Qué hace:
#   1. Crea .env local (scripts/init-env.sh) si no existe.
#   2. Genera certs/aws con la IP/DNS de la instancia (la IP ELÁSTICA) en el SAN del certificado de servidor (CA propia de desarrollo).
#   3. rsync del código a ~/banco-xyz (sin .git, target, .env, certs ni logs).
#   4. Copia .env y SOLO lo que necesita el servidor (server.p12, truststore.p12, ca.crt) a la instancia. El certificado de
#      cliente del cajero (atm-0001.*) y la clave de la CA se quedan en el portátil.
set -euo pipefail
cd "$(dirname "$0")/../../.."
STATE=infra/aws/.deploy-state
[[ -f "$STATE" ]] && { set -a; source "$STATE"; set +a; }
HOST="${APPS_IP:-}"; KEY="${SSH_KEY:-}"; USER_="ec2-user"; POS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="${2:?--host requiere un valor}"; shift 2 ;;
    --key) KEY="${2:?--key requiere un valor}"; shift 2 ;;
    --user) USER_="${2:?--user requiere un valor}"; shift 2 ;;
    --role) [[ "${2:-}" == apps ]] || { echo "--role ${2:-?}: este despliegue usa UN host (rol 'apps'); ver docs/adr/ADR-010"; exit 2; }; shift 2 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    -*) echo "opción desconocida: $1"; exit 2 ;;
    *) POS+=("$1"); shift ;;
  esac
done
if [[ ${#POS[@]} -ge 2 ]]; then KEY="${POS[0]}"; HOST="${POS[1]}"; [[ -n "${POS[2]:-}" ]] && USER_="${POS[2]}"; fi
[[ -n "$KEY" && -n "$HOST" ]] || { echo "Falta la llave o el host: use --key/--host o complete infra/aws/.deploy-state (ver despliegue.md, Parte A)"; exit 2; }
SSH="ssh -i $KEY -o StrictHostKeyChecking=accept-new"
REMOTE="$USER_@$HOST"

[[ -f .env ]] || scripts/init-env.sh
set -a; source .env; set +a
if [[ "$HOST" =~ ^[0-9.]+$ ]]; then SAN="IP:$HOST"; else SAN="DNS:$HOST"; fi

echo "== 1. Certificados con $SAN en el SAN → certs/aws"
CERT_EXTRA_SAN="$SAN" certs/generate-dev-certs.sh certs/aws >/dev/null
echo "   certs/aws listo (CA, servidor, cajero ATM-0001, certificado ajeno)"

echo "== 2. Código fuente → $REMOTE:banco-xyz"
$SSH "$REMOTE" 'mkdir -p ~/banco-xyz/certs/out'
rsync -az --delete -e "$SSH" \
  --exclude .git --exclude target --exclude node_modules --exclude .env --exclude certs/out --exclude certs/aws \
  --exclude logs --exclude outputs --exclude data/synthetic --exclude evidencias/aws --exclude infra/aws/.deploy-state ./ "$REMOTE:banco-xyz/"

echo "== 3. Secretos y certificados del servidor"
scp -q -i "$KEY" .env "$REMOTE:banco-xyz/.env"
scp -q -i "$KEY" certs/aws/server.p12 certs/aws/truststore.p12 certs/aws/ca.crt "$REMOTE:banco-xyz/certs/out/"
# .env solo para su dueño; los PKCS12 y la CA deben ser LEGIBLES por el usuario no-root de los contenedores (uid 100): con 600 el gateway y los BFF no arrancan
$SSH "$REMOTE" 'chmod 600 ~/banco-xyz/.env && chmod 755 ~/banco-xyz/certs/out && chmod 644 ~/banco-xyz/certs/out/server.p12 ~/banco-xyz/certs/out/truststore.p12 ~/banco-xyz/certs/out/ca.crt'
echo "Listo. En la instancia:  cd ~/banco-xyz && infra/aws/ec2/deploy.sh"
