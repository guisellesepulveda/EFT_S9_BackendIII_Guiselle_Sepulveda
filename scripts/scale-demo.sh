#!/usr/bin/env bash
# Demo de escalado horizontal (C6) sobre docker compose. Requiere la plataforma levantada (docker compose up -d --build).
#   1) escala accounts×3, payments×2, customers×2   2) Eureka con todas las instancias
#   3) el gateway reparte las peticiones (X-Instance-Id y métricas por instancia en Prometheus)
#   4) mata una réplica SIN avisar (docker stop -t 0 = caída brusca): el tráfico continúa   5) la restaura
# Salida completa en evidencias/local/scale_demo.log (variable SCALE_LOG para otra ruta; infra/aws/ec2/verify.sh la usa en EC2).
# Requiere curl y python3. Variables: GATEWAY_URL, CACERT (por defecto local); Prometheus es opcional (perfil observability).
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
LOG="${SCALE_LOG:-evidencias/local/scale_demo.log}"
mkdir -p "$(dirname "$LOG")"
exec > >(tee "$LOG") 2>&1

GW="${GATEWAY_URL:-https://localhost:8446}"
CA="${CACERT:-certs/out/ca.crt}"
EUREKA="http://localhost:8761"
PROM="http://localhost:9090"
jget() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

healthy_count() { # servicio → nº de contenedores sanos
  docker compose ps "$1" --format '{{.Status}}' | grep -c "(healthy)" || true
}
wait_replicas() { # servicio n
  for _ in $(seq 1 90); do [[ "$(healthy_count "$1")" -ge "$2" ]] && return 0; sleep 3; done
  echo "  $1 no alcanzó $2 réplicas sanas"; return 1
}
eureka_summary() {
  curl -s -u "$EUREKA_USER:$EUREKA_PASSWORD" -H 'Accept: application/json' "$EUREKA/eureka/apps" | python3 -c '
import sys, json
apps = json.load(sys.stdin)["applications"]["application"]
for a in sorted(apps, key=lambda a: a["name"]):
    ins = a["instance"] if isinstance(a["instance"], list) else [a["instance"]]
    up = [i for i in ins if i["status"] == "UP"]
    print("  %-20s %d UP  %s" % (a["name"], len(up), ", ".join(sorted(i["instanceId"].split(":")[1][:12] for i in up))))'
}

echo "== 1. Escalado horizontal: accounts-service×3, payments-service×2, customers-service×2"
echo '$ docker compose up -d --scale accounts-service=3 --scale payments-service=2 --scale customers-service=2'
docker compose up -d --scale accounts-service=3 --scale payments-service=2 --scale customers-service=2 2>&1 | grep -E "Started|Running|Created" | sed 's/^/  /' | tail -12
wait_replicas accounts-service 3; wait_replicas payments-service 2; wait_replicas customers-service 2
echo
echo '$ docker compose ps accounts-service payments-service customers-service'
docker compose ps accounts-service payments-service customers-service --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}\t{{.Ports}}'
echo "  (ninguna réplica publica puertos al host ni fija container_name: solo el gateway y los BFF son accesibles desde fuera)"

echo; echo "== 2. Eureka: instancias registradas (dashboard: $EUREKA, usuario $EUREKA_USER)"
sleep 8; eureka_summary

echo; echo "== 3. Reparto de carga: 30 GET por servicio a través del gateway (Spring Cloud LoadBalancer, round-robin)"
INT="$(scripts/token.sh internal)"
H=(-H "Authorization: Bearer $INT" -H 'Content-Type: application/json')
c() { curl -s --cacert "$CA" "${H[@]}" "$@"; }
RUN="$(date +%s)"
CUST="$(c -d "{\"documentNumber\":\"$((10000000 + RUN % 9000000))-5\",\"firstName\":\"Escala\",\"lastName\":\"Horizontal\",\"email\":\"escala.$RUN@example.com\",\"phone\":\"+56911111111\"}" "$GW/api/customers" | jget 'd["id"]')"
ACC="$(c -d "{\"customerId\":\"$CUST\",\"type\":\"CHECKING\"}" "$GW/api/accounts" | jget 'd["id"]')"
PAY="$(c -H "Idempotency-Key: escala-$RUN" -d "{\"toAccountId\":\"$ACC\",\"amount\":1000}" "$GW/api/payments/deposit" | jget 'd["paymentId"]')"
sleep 2
tally() { # ruta n → histograma de X-Instance-Id y códigos HTTP
  local path="$1" n="$2" tmp; tmp="$(mktemp)"
  for _ in $(seq 1 "$n"); do
    curl -s --cacert "$CA" -H "Authorization: Bearer $INT" -o /dev/null -D - -w 'HTTP %{http_code}\n' "$GW$path" | tr -d '\r' \
      | awk 'tolower($1)=="x-instance-id:" {id=$2} /^HTTP [0-9]+$/ {code=$2} END {print code, (id==""?"-":id)}' >> "$tmp"
    sleep 0.06     # el gateway limita 20 req/s por usuario: se respeta para medir el balanceo, no el rate limiter
  done
  sort "$tmp" | uniq -c | sed 's/^ */    /;s/ / peticiones → HTTP /;s/ \([^ ]*\)$/ · instancia \1/'
  rm -f "$tmp"
}
for entry in "customers:/api/customers/$CUST" "accounts:/api/accounts/$ACC" "payments:/api/payments/$PAY"; do
  echo "  GET ${entry#*:}  (${entry%%:*}-service)"; tally "${entry#*:}" 30
done
if curl -s -o /dev/null --max-time 3 "$PROM/-/ready"; then
echo "  Peticiones atendidas por instancia según Prometheus (métrica http_server_requests, scrape por DNS de las réplicas):"
sleep 12
for svc in customers-service accounts-service payments-service; do
  curl -s "$PROM/api/v1/query" --data-urlencode "query=sum by (instance) (http_server_requests_seconds_count{application=\"$svc\",uri=~\"/(customers|accounts|payments)/\\\\{.*\"})" \
    | python3 -c "
import sys, json
r = json.load(sys.stdin)['data']['result']
print('    %-18s %s' % ('$svc', ', '.join('%s=%d' % (x['metric']['instance'], float(x['value'][1])) for x in sorted(r, key=lambda x: x['metric']['instance']))))"
done
else
  echo "  (Prometheus no está activo: perfil 'observability' apagado; el reparto queda demostrado por X-Instance-Id)"
fi

echo; echo "== 4. Tolerancia a fallos: se mata UNA réplica de accounts-service SIN aviso y el tráfico continúa"
VICTIM="$(docker compose ps -q accounts-service | sed -n 2p)"
VNAME="$(docker inspect -f '{{.Name}}' "$VICTIM" | tr -d '/')"
echo "\$ docker stop -t 0 $VNAME     # caída brusca: no se da de baja en Eureka"
docker stop -t 0 "$VICTIM" >/dev/null
echo "  40 peticiones inmediatas (30 GET + 10 POST /api/accounts), sin esperar a que Eureka expulse la instancia:"
echo "  GET /api/accounts/{id}"; tally "/api/accounts/$ACC" 30
OK=0; FAIL=0
for i in $(seq 1 10); do
  code="$(curl -s --cacert "$CA" "${H[@]}" -o /dev/null -w '%{http_code}' -d "{\"customerId\":\"$CUST\",\"type\":\"SAVINGS\"}" "$GW/api/accounts")"
  [[ "$code" == 201 ]] && OK=$((OK + 1)) || FAIL=$((FAIL + 1)); sleep 0.06
done
echo "  POST /api/accounts → $OK creadas (201), $FAIL fallidas"
echo "  (el gateway reintenta las escrituras SOLO ante conexión rechazada; si una conexión reutilizada se cerró a mitad, el cliente reintenta)"
echo "  Eureka (la instancia caída sale por vencimiento del lease, ~15 s):"; sleep 18; eureka_summary | grep -E "ACCOUNTS"
echo "  Tras la expulsión, 30 GET más:"; tally "/api/accounts/$ACC" 30

echo; echo "== 5. Restauro la réplica y el reparto vuelve a 3 instancias"
echo "\$ docker start $VNAME"
docker start "$VICTIM" >/dev/null
wait_replicas accounts-service 3; sleep 10
eureka_summary | grep -E "ACCOUNTS"
tally "/api/accounts/$ACC" 30
echo; echo "== 6. Kafka: las particiones de cada tópico se reparten entre las réplicas del grupo de consumidores"
for g in accounts-saga-requested payments-saga-debited; do
  echo "\$ kafka-consumer-groups --describe --group $g"
  docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group "$g" 2>/dev/null \
    | awk 'NR==1 || $1=="GROUP" {next} NF>=8 {printf "    %-22s partición %s → réplica %s\n", $2, $3, substr($8, 2)}' | sort | head -9
done

echo; echo '$ docker compose ps  (resumen final)'
docker compose ps --format 'table {{.Name}}\t{{.Status}}' | grep -E "accounts|payments|customers|NAME"
