#!/usr/bin/env bash
# Despliegue en la instancia EC2 (se ejecuta DENTRO de la instancia, en la raíz del repo: ~/banco-xyz):
#   infra/aws/ec2/deploy.sh [--public-host IP_o_DNS] [--observability] [--no-scale] [--skip-build] [--role apps]
# 1) certificados + .env (si no los subió push.sh)  2) construye las imágenes EN la instancia (sin ECR: menos permisos IAM)
# 3) levanta por OLEADAS (infra → config/eureka/auth → servicios → réplicas → gateway, BFF y batch), esperando a que cada una esté
#    sana y midiendo su duración (la instancia tiene 2 vCPU)  4) escala 3/2/2 (SCALE_ACCOUNTS/PAYMENTS/CUSTOMERS lo ajustan)  5) resumen y URLs.
# --role apps: aceptado por compatibilidad con el runbook (con un solo host, ese es el único rol).
set -euo pipefail
cd "$(dirname "$0")/../../.."

PUBLIC_HOST=""; OBS=0; SCALE=1; BUILD=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --public-host) PUBLIC_HOST="${2:?--public-host requiere un valor}"; shift 2 ;;
    --observability) OBS=1; shift ;;
    --no-scale) SCALE=0; shift ;;
    --skip-build) BUILD=0; shift ;;
    --role) [[ "${2:-}" == apps ]] || { echo "--role ${2:-?}: este despliegue usa UN host (rol 'apps'); ver docs/adr/ADR-010"; exit 2; }; shift 2 ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "opción desconocida: $1"; exit 2 ;;
  esac
done

say() { echo; echo "=== $* ==="; }
need() { command -v "$1" >/dev/null || { echo "Falta '$1' (¿corrió user-data.sh?)"; exit 1; }; }
need docker; need openssl; need python3
[[ -f certs/out/server.p12 ]] || need keytool          # keytool (JDK) solo hace falta si los certificados no se subieron con push.sh
docker compose version >/dev/null || { echo "Falta el plugin Docker Compose v2"; exit 1; }

# --- IP/DNS pública: argumento, o metadatos de la instancia (IMDSv2) ---
if [[ -z "$PUBLIC_HOST" ]]; then
  TOKEN="$(curl -s -m 3 -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' || true)"
  PUBLIC_HOST="$(curl -s -m 3 -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4 || true)"
fi
[[ -n "$PUBLIC_HOST" ]] || { echo "No se pudo determinar la IP pública: use --public-host <IP elástica de la instancia>"; exit 1; }
if [[ "$PUBLIC_HOST" =~ ^[0-9.]+$ ]]; then SAN="IP:$PUBLIC_HOST"; else SAN="DNS:$PUBLIC_HOST"; fi
echo "Destino público: $PUBLIC_HOST"

say "1. Secretos (.env) y certificados"
export CERT_EXTRA_SAN="$SAN"
scripts/init-env.sh                       # no sobreescribe .env ni certs/out si push.sh ya los subió
if ! openssl x509 -in certs/out/server.crt -noout -ext subjectAltName 2>/dev/null | grep -q "${PUBLIC_HOST}" \
   && [[ -f certs/out/server.crt ]]; then
  echo "AVISO: el certificado de servidor no incluye $PUBLIC_HOST en el SAN; los clientes deberán usar --insecure."
fi

# Los contenedores corren como usuario no-root (uid 100, "app") y montan certs/out en solo lectura: el keystore y el truststore PKCS12 (protegidos por contraseña)
# y la CA pública deben ser legibles por ese usuario. Con modo 600 y dueño ec2-user el gateway y los BFF no arrancan (Permission denied al abrir /certs/server.p12).
# Las claves privadas (*.key) conservan el modo 600.
chmod 755 certs/out
chmod 644 certs/out/server.p12 certs/out/truststore.p12 certs/out/ca.crt

set_env() { # clave valor → deja KEY=valor en .env (reemplaza si existe)
  if grep -q "^$1=" .env; then sed -i.bak "s|^$1=.*|$1=$2|" .env && rm -f .env.bak; else echo "$1=$2" >> .env; fi
}
PROFILES="apps"; [[ $OBS -eq 1 ]] && PROFILES="apps,observability"
set_env PUBLIC_HOST "$PUBLIC_HOST"
set_env AUTH_ISSUER_URI "http://$PUBLIC_HOST:9000"
set_env COMPOSE_PROFILES "$PROFILES"
set_env COMPOSE_FILE "docker-compose.yml:infra/aws/docker-compose.aws.yml"
chmod 600 .env

export DOCKER_BUILDKIT=1                  # los Dockerfile usan RUN --mount=type=cache (BuildKit embebido en el daemon)
if [[ $BUILD -eq 1 ]]; then
  say "2. Construcción de imágenes en la instancia (la primera vez descarga dependencias Maven: varios minutos)"
  T_BUILD="$(date +%s)"
  docker compose build
  echo "Construcción de las imágenes: $(( $(date +%s) - T_BUILD )) s (nproc=$(nproc 2>/dev/null || echo ?))"
fi

wait_healthy() { # servicios... → espera a que todos sus contenedores estén healthy (máx. 15 min: con 2 vCPU las JVM arrancan despacio)
  for _ in $(seq 1 180); do
    local pending=0
    for s in "$@"; do
      [[ -n "$(docker compose ps -q "$s")" ]] || { pending=1; continue; }
      docker compose ps "$s" --format '{{.Status}}' | grep -qv "(healthy)" && pending=1
    done
    [[ $pending -eq 0 ]] && return 0; sleep 5
  done
  echo "Timeout esperando: $*"; docker compose ps; return 1
}
# Oleadas: con pocos vCPU arrancar ~14 JVM a la vez retrasa a todas. Cada oleada espera a que la anterior esté sana y mide su duración.
T0="$(date +%s)"; TIEMPOS=""
oleada() { # nombre, servicios a esperar...  (el `docker compose up` de la oleada lo ejecuta quien llama justo antes)
  local nombre="$1"; shift; local t="$(date +%s)"
  wait_healthy "$@"
  TIEMPOS="$TIEMPOS$(printf '  %-52s %4d s\n' "$nombre" $(( $(date +%s) - t )))"$'\n'
}
SCALE_ACCOUNTS="${SCALE_ACCOUNTS:-3}"; SCALE_PAYMENTS="${SCALE_PAYMENTS:-2}"; SCALE_CUSTOMERS="${SCALE_CUSTOMERS:-2}"

say "3. Arranque por oleadas (infra → config/eureka/auth → servicios → réplicas → gateway, BFF y batch)"
echo "   oleada 1/5: PostgreSQL, Kafka, Zipkin y Kafka UI"
docker compose up -d postgres kafka zipkin kafka-ui
oleada "1. infraestructura (postgres, kafka, zipkin)" postgres kafka zipkin
echo "   oleada 2/5: config-server, discovery-server y auth-server"
docker compose up -d config-server discovery-server
oleada "2a. config-server y discovery-server" config-server discovery-server
docker compose up -d auth-server
oleada "2b. auth-server" auth-server
echo "   oleada 3/5: un contenedor de cada microservicio"
docker compose up -d customers-service accounts-service payments-service
oleada "3. customers, accounts y payments (1 réplica c/u)" customers-service accounts-service payments-service
echo "   oleada 4/5: réplicas ($SCALE_ACCOUNTS accounts, $SCALE_PAYMENTS payments, $SCALE_CUSTOMERS customers)"
if [[ $SCALE -eq 1 ]]; then
  docker compose up -d --scale accounts-service="$SCALE_ACCOUNTS" --scale payments-service="$SCALE_PAYMENTS" --scale customers-service="$SCALE_CUSTOMERS" \
    customers-service accounts-service payments-service
fi
oleada "4. réplicas de los microservicios" customers-service accounts-service payments-service
echo "   oleada 5/5: api-gateway, 3 BFF y batch"
if [[ $SCALE -eq 1 ]]; then
  docker compose up -d --scale accounts-service="$SCALE_ACCOUNTS" --scale payments-service="$SCALE_PAYMENTS" --scale customers-service="$SCALE_CUSTOMERS"
else
  docker compose up -d
fi
oleada "5. api-gateway, bff-web, bff-mobile, bff-atm y batch" api-gateway bff-web bff-mobile bff-atm batch-service

say "5. Resumen"
printf 'Duración de cada oleada (medida en esta instancia):\n%s  total del despliegue (sin la construcción): %d s\n' "$TIEMPOS" $(( $(date +%s) - T0 ))
docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
cat <<URLS

Plataforma desplegada. Desde fuera (según el Security Group):
  Gateway (HTTPS)      https://$PUBLIC_HOST:443
  BFF web / móvil      https://$PUBLIC_HOST:8441  /  https://$PUBLIC_HOST:8442
  BFF ATM (mTLS)       https://$PUBLIC_HOST:8443   (exige certificado de cliente)
  Auth server (OIDC)   http://$PUBLIC_HOST:9000
Solo por túnel SSH (infra/aws/ec2/tunnel.sh): Eureka :8761, Kafka UI :8089, Zipkin :9411$([[ $OBS -eq 1 ]] && echo ", Grafana :3000, Prometheus :9090")
Siguiente paso: infra/aws/ec2/verify.sh   (evidencias en evidencias/aws/logs/)
URLS
