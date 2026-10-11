#!/usr/bin/env bash
# Levanta la plataforma como procesos locales (sin Docker para las apps), contra la infraestructura de
#   COMPOSE_PROFILES=infra docker compose up -d   (PostgreSQL, Kafka, Zipkin, Prometheus, Grafana; sin las apps).
# OJO: .env trae COMPOSE_PROFILES=apps (todo en contenedores); este modo (apps en el host) exige el perfil infra a solas.
# Uso: scripts/run-local.sh start|stop|status|logs <servicio>
# Requiere .env (scripts/init-env.sh), JDK 21+ y Maven. Logs en logs/<servicio>.log
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {split($2,a,"."); print a[1]}')"
if [[ "${JAVA_MAJOR:-0}" -lt 21 ]] && [[ -x /usr/libexec/java_home ]]; then JAVA_HOME="$(/usr/libexec/java_home -v 21+)"; export JAVA_HOME; fi

[[ -f .env ]] || { echo "Falta .env: ejecute scripts/init-env.sh"; exit 1; }
set -a; source .env; set +a
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
export CONFIG_REPO_PATH="file:$PWD/infra/config-repo"
JVM_OPTS="-Xmx400m -XX:+UseSerialGC"   # solo para los servicios (no para Maven)
mkdir -p logs

# Certificados de desarrollo (CA propia, servidor, cajero ATM-0001): HTTPS en gateway y BFF, mTLS en bff-atm
[[ -f certs/out/server.p12 ]] || bash certs/generate-dev-certs.sh

# nombre:módulo:puerto-de-salud  (en orden de arranque). Gateway y BFF sirven HTTPS (8446, 8443-8445); su salud va en HTTP plano
# por el puerto de gestión (18446, 18443-18445).
SERVICES=(
  "config-server:infra/config-server:8888"
  "discovery-server:infra/discovery-server:8761"
  "auth-server:infra/auth-server:9000"
  "customers-service:services/customers-service:8081"
  "accounts-service:services/accounts-service:8082"
  "payments-service:services/payments-service:8083"
  "batch-service:batch/batch-service:8085"
  "api-gateway:infra/api-gateway:18446"
  "bff-web:bff/bff-web:18443"
  "bff-mobile:bff/bff-mobile:18444"
  "bff-atm:bff/bff-atm:18445"
)

jar_of() { ls "$1"/target/*.jar | grep -v -E 'original|sources' | head -1; }

wait_health() { # nombre puerto
  for _ in $(seq 1 90); do
    if curl -fs "http://localhost:$2/actuator/health" >/dev/null 2>&1; then echo "  $1 listo (:$2)"; return 0; fi
    sleep 2
  done
  echo "  $1 NO arrancó; revise logs/$1.log"; return 1
}

start_all() {
  echo "Compilando..."
  mvn -q -DskipTests package -pl infra/config-server,infra/discovery-server,infra/auth-server,infra/api-gateway,services/customers-service,services/accounts-service,services/payments-service,bff/bff-web,bff/bff-mobile,bff/bff-atm,batch/batch-service -am
  for entry in "${SERVICES[@]}"; do
    IFS=: read -r name module port <<<"$entry"
    if [[ -f "logs/$name.pid" ]] && kill -0 "$(cat logs/$name.pid)" 2>/dev/null; then echo "  $name ya está en ejecución"; continue; fi
    extra=()
    [[ "$name" == "batch-service" ]] && extra=(--batch.output-dir="$PWD/outputs" --spring.profiles.active=local)
    [[ "$name" == "batch-service" ]] && export BATCH_DB_USER=batch
    [[ "$name" == "bff-atm" ]] && export ATM_DB_USER=atm
    nohup java $JVM_OPTS -jar "$(jar_of "$module")" ${extra[@]+"${extra[@]}"} > "logs/$name.log" 2>&1 &
    echo $! > "logs/$name.pid"
    echo "Arrancando $name (pid $(cat logs/$name.pid))"
    wait_health "$name" "$port"
  done
}

stop_all() {
  for entry in "${SERVICES[@]}"; do
    IFS=: read -r name _ _ <<<"$entry"
    if [[ -f "logs/$name.pid" ]]; then
      kill "$(cat logs/$name.pid)" 2>/dev/null && echo "Detenido $name" || true
      rm -f "logs/$name.pid"
    fi
  done
}

status_all() {
  for entry in "${SERVICES[@]}"; do
    IFS=: read -r name _ port <<<"$entry"
    if curl -fs "http://localhost:$port/actuator/health" >/dev/null 2>&1; then echo "UP    $name (:$port)"; else echo "DOWN  $name (:$port)"; fi
  done
}

case "${1:-}" in
  start) start_all ;;
  stop) stop_all ;;
  status) status_all ;;
  logs) tail -n 100 -f "logs/${2:?indique el servicio}.log" ;;
  *) echo "Uso: $0 start|stop|status|logs <servicio>"; exit 1 ;;
esac
