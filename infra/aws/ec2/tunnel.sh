#!/usr/bin/env bash
# Túnel SSH (DESDE el portátil) para ver las herramientas internas de la instancia SIN exponerlas a internet:
#   infra/aws/ec2/tunnel.sh [<llave.pem> <IP_o_DNS> [usuario]]
# Sin argumentos usa SSH_KEY y APPS_IP de infra/aws/.deploy-state (la IP elástica no cambia aunque el lab detenga la instancia).
# Luego, en el navegador local:
#   Eureka     http://localhost:8761   (usuario/contraseña: EUREKA_USER / EUREKA_PASSWORD del .env)
#   Kafka UI   http://localhost:8089
#   Zipkin     http://localhost:9411
#   Grafana    http://localhost:3000   (solo si se desplegó con --observability; GRAFANA_ADMIN_* del .env)
#   Prometheus http://localhost:9090   (idem)
# Ctrl+C cierra el túnel.
set -euo pipefail
cd "$(dirname "$0")/../../.."
STATE=infra/aws/.deploy-state
[[ -f "$STATE" ]] && { set -a; source "$STATE"; set +a; }
KEY="${1:-${SSH_KEY:-}}"; HOST="${2:-${APPS_IP:-}}"; USER_="${3:-ec2-user}"
[[ -n "$KEY" && -n "$HOST" ]] || { echo "uso: tunnel.sh [<llave.pem> <host> [usuario]]  (o complete infra/aws/.deploy-state)"; exit 2; }
echo "Túnel abierto hacia $HOST: Eureka :8761 · Kafka UI :8089 · Zipkin :9411 · Grafana :3000 · Prometheus :9090  (Ctrl+C para cerrar)"
exec ssh -i "$KEY" -o StrictHostKeyChecking=accept-new -N \
  -L 8761:localhost:8761 -L 8089:localhost:8089 -L 9411:localhost:9411 -L 3000:localhost:3000 -L 9090:localhost:9090 \
  "$USER_@$HOST"
