#!/usr/bin/env bash
# Verificación post-despliegue (se ejecuta DENTRO de la instancia, en ~/banco-xyz). Deja las evidencias en evidencias/aws/logs/:
#   compose-ps.log, docker-stats.log (memoria real vs. presupuesto), scale-demo.log (Eureka 3/2/2, reparto por X-Instance-Id,
#   caída de una réplica y recuperación), circuit-breakers.log, resumen.log
set -euo pipefail
cd "$(dirname "$0")/../../.."
OUT=evidencias/aws/logs
mkdir -p "$OUT"
set -a; source .env; set +a
GW_PORT_HOST=443

echo "== 1. docker compose ps (réplicas)" | tee "$OUT/resumen.log"
docker compose ps --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}\t{{.Ports}}' | tee "$OUT/compose-ps.log"

echo; echo "== 2. Recursos reales del host y memoria/CPU por contenedor frente a su límite (presupuesto en infra/aws/docker-compose.aws.yml)" | tee -a "$OUT/resumen.log"
{
  TOKEN="$(curl -s -m 3 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' || true)"
  echo "Tipo de instancia : $(curl -s -m 3 -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-type 2>/dev/null || true)"
  echo "IP pública        : $(curl -s -m 3 -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || true)"
  echo "vCPU (nproc)      : $(nproc 2>/dev/null || echo n/d)"
  echo "Memoria del host  :"; (free -h 2>/dev/null || free -m 2>/dev/null || echo "  (free no disponible)") | sed 's/^/  /'
  echo "Carga (uptime)    : $(uptime 2>/dev/null | sed 's/.*load average/load average/')"
  echo
  echo "\$ docker stats --no-stream"
  docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}'
  echo; echo "Suma de la memoria usada por los contenedores: $(docker stats --no-stream --format '{{.MemUsage}}' | awk '{u=$1; if (u ~ /GiB/) {sub(/GiB/,"",u); t+=u*1024} else if (u ~ /MiB/) {sub(/MiB/,"",u); t+=u} else if (u ~ /KiB/) {sub(/KiB/,"",u); t+=u/1024}} END {printf "%.0f MiB (%.2f GiB)", t, t/1024}')"
  command -v swapon >/dev/null && echo "Swap activo       : $(swapon --show --noheadings 2>/dev/null | wc -l | tr -d ' ') (el presupuesto de memoria no cuenta con swap)"
} | tee "$OUT/docker-stats.log"

echo; echo "== 3. Escalado, reparto de carga (X-Instance-Id) y tolerancia a la caída de una réplica" | tee -a "$OUT/resumen.log"
GATEWAY_URL="https://localhost:$GW_PORT_HOST" CACERT=certs/out/ca.crt SCALE_LOG="$OUT/scale-demo.log" scripts/scale-demo.sh

echo; echo "== 4. Estado de los circuit breakers (Actuator, red interna)" | tee -a "$OUT/resumen.log"
{
  for svc in api-gateway:18446 payments-service:8083 accounts-service:8082; do   # customers-service no llama a nadie: no tiene circuit breakers
    echo "--- ${svc%%:*}"
    docker compose exec -T "${svc%%:*}" wget -qO- "http://localhost:${svc##*:}/actuator/circuitbreakers" | python3 -c "import sys,json; [print(\"  %-22s %-10s fallos=%-6s llamadas=%s\" % (k, v[\"state\"], v[\"failureRate\"], v[\"bufferedCalls\"])) for k, v in json.load(sys.stdin)[\"circuitBreakers\"].items()]"
  done
} | tee "$OUT/circuit-breakers.log"

echo; echo "Verificación terminada. Evidencias en $OUT/ (cópielas al portátil: scp -r ec2-user@<host>:banco-xyz/$OUT evidencias/aws/)" | tee -a "$OUT/resumen.log"
