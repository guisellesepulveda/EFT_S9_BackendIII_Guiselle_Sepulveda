# Instrucciones — cómo levantar, probar y evaluar la plataforma

Guía **reproducible por un tercero** del proyecto Banco XYZ (EFT PBY2203). Todo corre localmente con Docker; no se necesita cuenta AWS.
Cada prueba indica el **resultado esperado**. Los comandos se ejecutan **desde la raíz del repositorio**. Los scripts guardan sus logs en `evidencias/` (carpeta que no se versiona y que se crea sola al ejecutarlos).

Índice: [1 Prerrequisitos](#1-prerrequisitos) · [2 Puesta en marcha](#2-puesta-en-marcha) · [3 URLs](#3-urls-de-cada-componente) · [4 Tokens por canal](#4-tokens-por-canal) ·
[5 Pruebas por componente](#5-pruebas-por-componente) · [6 Colección Postman](#6-colección-postman) · [7 Pruebas automatizadas](#7-pruebas-automatizadas) ·
[8 Probar contra AWS](#8-probar-contra-el-entorno-aws) · [9 Problemas frecuentes](#9-problemas-frecuentes)

---

## 1. Prerrequisitos

| Herramienta | Versión | Para qué |
|---|---|---|
| **Docker** con **Compose v2.24+** | probado con Docker 29.8.2 / Compose v5.5.1 | Construir y ejecutar toda la plataforma (la compilación Maven ocurre dentro de Docker) |
| Memoria asignada a Docker | **≥ 10 GiB** (≥ 12 GiB con el escalado 3/2/2) | 17 contenedores; los servicios Spring usan 180–360 MiB cada uno |
| **JDK 21+** | 21 o superior (probado con 24) | Solo `keytool`, que usa `certs/generate-dev-certs.sh` para crear el truststore |
| `curl`, `python3`, `openssl`, `jq` | cualquier versión reciente | Scripts de prueba y generación de `.env` y certificados |
| Maven 3.9 | opcional | Solo para `mvn verify` (pruebas automatizadas); requiere además Docker para Testcontainers |

Puertos libres en el host: **8443, 8444, 8445, 8446, 9000, 8761, 8089, 9411, 9090, 3000, 5432, 9092** (ver [§3](#3-urls-de-cada-componente)).

## 2. Puesta en marcha

```bash
# 1) Secretos y certificados de desarrollo (idempotente: no sobreescribe .env ni certs/out si ya existen)
scripts/init-env.sh
#    → .env con secretos aleatorios (no se versiona) y certs/out/ (CA propia, certificado de servidor, certificado del cajero ATM-0001)

# 2) Construir las imágenes y levantar todo (la primera vez descarga dependencias Maven: 3-6 min; después, segundos por la caché)
docker compose up -d --build

# 3) Esperar a que estén sanos (≈ 1 min) y comprobarlo
docker compose ps
```

**Resultado esperado:** 17 contenedores *Up*; los 14 que tienen healthcheck (postgres, kafka, zipkin, config-server, discovery-server, auth-server, api-gateway,
customers/accounts/payments-service, bff-web/mobile/atm, batch-service) en estado **`(healthy)`**. `kafka-ui`, `prometheus` y `grafana` no tienen healthcheck.

```bash
# 4) Escalado horizontal (3 réplicas de accounts, 2 de payments, 2 de customers)
docker compose up -d --scale accounts-service=3 --scale payments-service=2 --scale customers-service=2
#    equivalente:  docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d
docker compose ps accounts-service payments-service customers-service      # 3 + 2 + 2 filas (healthy)

# 5) Verificación completa en un solo comando (≈ 3 min)
scripts/smoke-test.sh          # → evidencias/local/smoke-test.log · termina con "SMOKE TEST: TODO CORRECTO" (≈ 77 ✔, 0 ✘)

# Apagar: conserva los datos  |  borrar todo (datos y volúmenes)
docker compose down            # docker compose down -v
```

*Modo alternativo (apps como procesos del host, útil para depurar):* `COMPOSE_PROFILES=infra docker compose up -d && scripts/run-local.sh start`
(logs en `logs/`; `scripts/run-local.sh stop` los detiene). **No** mezcle los dos modos a la vez: usan los mismos puertos.

## 3. URLs de cada componente

> Certificado HTTPS de desarrollo: para `curl` use `--cacert certs/out/ca.crt`; en el navegador, importe `certs/out/ca.crt` como CA de confianza o acepte la advertencia.
> Las credenciales salen de `.env`: `EUREKA_USER/EUREKA_PASSWORD`, `GRAFANA_ADMIN_USER/GRAFANA_ADMIN_PASSWORD`, y los usuarios de demostración de [§4](#4-tokens-por-canal).

| Componente | URL | Notas |
|---|---|---|
| **API Gateway** | `https://localhost:8446` | Rutas `/api/customers`, `/api/accounts`, `/api/payments`, `/api/batch`, `/api/admin/*`. Exige JWT |
| **Eureka** (dashboard) | http://localhost:8761 | Usuario/contraseña de `.env`. Muestra una fila por réplica |
| **Auth server** (OIDC) | http://localhost:9000 | `/.well-known/openid-configuration`, `/oauth2/jwks`, login de demo |
| **BFF web** + Swagger | `https://localhost:8443` · `/swagger-ui.html` | Sesión con cookie; login en `/oauth2/authorization/web-client` |
| **BFF móvil** + Swagger | `https://localhost:8444` · `/swagger-ui.html` | Bearer `mobile:*` |
| **BFF ATM** + Swagger | `https://localhost:8445` · `/swagger-ui.html` | **mTLS obligatorio**: sin certificado de cliente la conexión se rechaza (también para el navegador) |
| **Kafka UI** | http://localhost:8089 | Tópicos, particiones, mensajes y `*.DLT` |
| **Zipkin** | http://localhost:9411 | Trazas distribuidas |
| **Prometheus** | http://localhost:9090 | *Status → Targets*: todas las réplicas (9 en `up`; 13 con el escalado 3/2/2) |
| **Grafana** | http://localhost:3000 | Carpeta *Banco XYZ* → dashboard **Banco XYZ — Plataforma** |
| **Swagger de los microservicios** | `http://localhost:8081\|8082\|8083\|8085/swagger-ui/index.html` | Los microservicios **no publican puertos** (se escalan). Para verlos: `docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d` con **una** réplica |
| Contratos OpenAPI de los BFF | `docs/openapi/bff-*.json` | Para desarrollo en paralelo: `python3 scripts/openapi_mock.py docs/openapi/bff-mobile.json 4010` levanta un mock |

## 4. Tokens por canal

Cada canal usa un **cliente OAuth2 distinto** con scopes distintos. El script `scripts/token.sh` hace todo el flujo e imprime solo el token:

```bash
scripts/token.sh internal              # client_credentials, scope internal            (comunicación entre servicios)
scripts/token.sh atm                   # client_credentials, scope atm:operate         (cajero)
scripts/token.sh web customer1         # Authorization Code + PKCE, scopes web:read web:write
scripts/token.sh mobile customer1      # Authorization Code + PKCE, scopes mobile:read mobile:transfer
scripts/token.sh web admin             # usuario con ROLE_ADMIN (endpoints /api/admin/*)
scripts/token.sh web batch-admin       # usuario con ROLE_BATCH_ADMIN (lanzar jobs batch)
```

Usuarios de demostración: `customer1` (CUSTOMER), `admin` (ADMIN), `batch-admin` (BATCH_ADMIN); contraseñas `DEMO_USER_PASSWORD`, `ADMIN_PASSWORD`, `BATCH_ADMIN_PASSWORD` en `.env`.

**Con `curl` explícito** (así funciona por dentro):

```bash
set -a; source .env; set +a

# a) client_credentials (internal / atm)
curl -s -u "internal-service:$INTERNAL_SERVICE_CLIENT_SECRET" -d grant_type=client_credentials -d scope=internal http://localhost:9000/oauth2/token
curl -s -u "atm-client:$ATM_CLIENT_SECRET"                    -d grant_type=client_credentials -d scope=atm:operate http://localhost:9000/oauth2/token

# b) Authorization Code + PKCE (web / móvil) — 5 pasos
CLIENT=mobile-client; SECRET=$MOBILE_CLIENT_SECRET; REDIRECT='bancoxyz://callback'; SCOPE='openid mobile:read mobile:transfer'
VERIFIER=$(openssl rand -base64 48 | tr -d '=+/\n' | cut -c1-64)
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
AUTHZ="http://localhost:9000/oauth2/authorize?response_type=code&client_id=$CLIENT&scope=openid%20mobile:read%20mobile:transfer&redirect_uri=bancoxyz%3A%2F%2Fcallback&state=x&code_challenge=$CHALLENGE&code_challenge_method=S256"
JAR=$(mktemp)
curl -s -c $JAR -b $JAR -o /dev/null "$AUTHZ"                                                   # 1. abre la sesión y redirige a /login
CSRF=$(curl -s -c $JAR -b $JAR http://localhost:9000/login | grep -o 'name="_csrf"[^>]*value="[^"]*"' | sed 's/.*value="//;s/"$//' | head -1)
curl -s -c $JAR -b $JAR -o /dev/null --data-urlencode username=customer1 --data-urlencode "password=$DEMO_USER_PASSWORD" \
     --data-urlencode "_csrf=$CSRF" http://localhost:9000/login                                  # 2. login del usuario
CODE=$(curl -s -c $JAR -b $JAR -o /dev/null -D - "$AUTHZ" | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}' | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')   # 3. código
curl -s -u "$CLIENT:$SECRET" -d grant_type=authorization_code -d "code=$CODE" --data-urlencode "redirect_uri=$REDIRECT" -d "code_verifier=$VERIFIER" http://localhost:9000/oauth2/token   # 4-5. canje con el verificador PKCE
```

**Resultado esperado:** un JSON con `access_token` (JWT), `scope` y `expires_in` (300 s para móvil y ATM). Un `code` reutilizado o un `code_verifier` incorrecto devuelve `invalid_grant`.

## 5. Pruebas por componente

Variables útiles para los ejemplos:

```bash
set -a; source .env; set +a
GW=https://localhost:8446; CA=certs/out/ca.crt
INT=$(scripts/token.sh internal); H=(-H "Authorization: Bearer $INT" -H 'Content-Type: application/json')
```

### 5.1 Procesos batch (C3)

Los tres jobs leen los CSV de `data/legacy/` (montados en el contenedor) y se lanzan por el gateway con un usuario `BATCH_ADMIN`.

```bash
BATCH=$(scripts/token.sh web batch-admin)
for job in dailyTransactionReportJob monthlyInterestJob annualStatementJob; do
  curl -s --cacert $CA -H "Authorization: Bearer $BATCH" -H 'Content-Type: application/json' -X POST \
       -d '{"parameters":{"runId":"demo-1"}}' $GW/api/batch/jobs/$job | jq -c '{executionId,jobName,status}'
done
curl -s --cacert $CA -H "Authorization: Bearer $BATCH" $GW/api/batch/executions/1 | jq '.status, (.steps[] | select(.name|contains(":")|not) | {name,status,read,written,skipped,filtered})'
```

**Resultado esperado** (por job, `COMPLETED`; son los conteos que imprime `scripts/smoke-test.sh`):

| Job | Step principal | Leídos | Escritos | Omitidos (rechazados) | Filtrados (duplicados) | Siguiente step |
|---|---|---:|---:|---:|---:|---|
| `dailyTransactionReportJob` | `loadMovementsStep` (4 particiones) | 1000 | 209 | 791 | 0 | `summarizeStep` 185 resúmenes |
| `monthlyInterestJob` | `calculateInterestStep` | 1000 | 509 | 489 | 2 | `updateBalancesStep` 50 saldos |
| `annualStatementJob` | `loadTransactionsStep` (multi-hilo) | 1000 | 230 | 770 | 0 | `consolidateStep` 20 cuentas |

- **Ver los rechazados** (nada se pierde en silencio): `curl -s --cacert $CA -H "Authorization: Bearer $BATCH" "$GW/api/batch/executions/1/rejected?limit=5" | jq` →
  `line_number`, `disposition`, `reason_code`, `reason`, `raw_content`. O en la base: `docker compose exec postgres psql -U batch -d batchdb -c "SELECT job_name, disposition, count(*) FROM batch_rejected_record GROUP BY 1,2 ORDER BY 1,2"`.
- **Resultados y archivos:** tablas `daily_transaction_summary`, `transaction_anomaly`, `interest_calculation`, `annual_statement` en `batchdb`; CSV/JSON de auditoría en el volumen: `docker compose exec batch-service ls /app/outputs/daily /app/outputs/annual`.
- **Forzar un fallo y reiniciar** (continúa desde el último chunk confirmado, sin duplicar filas):

```bash
P='{"parameters":{"runId":"fallo-1","periodo":"MENSUAL","simulateFailureAtChunk":"5"}}'
curl -s --cacert $CA -H "Authorization: Bearer $BATCH" -H 'Content-Type: application/json' -X POST -d "$P" $GW/api/batch/jobs/monthlyInterestJob | jq -c .   # → FAILED en el chunk 5 (100 filas confirmadas)
curl -s --cacert $CA -H "Authorization: Bearer $BATCH" -H 'Content-Type: application/json' -X POST -d "$P" $GW/api/batch/jobs/monthlyInterestJob | jq -c .   # mismos parámetros → misma instancia, retoma y termina
```

  **Esperado:** la 1.ª ejecución queda `FAILED` con `calculateInterestStep` habiendo confirmado **201 filas** (los chunks anteriores al fallo); la 2.ª (misma instancia) termina `COMPLETED` y escribe
  solo las **308 restantes**: **201 + 308 = 509 filas con 509 claves distintas**, sin duplicados (`docker compose exec postgres psql -U batch -d batchdb -c "SELECT job_instance_id, count(*), count(DISTINCT dedup_key) FROM interest_calculation GROUP BY 1 ORDER BY 1"`).
  Si espera más de 30 s, el **reintento automático** (`batch.auto-retry`: 3 intentos, 30 s) puede completarla solo y emite el evento `batch.job.failed` en `batch.job.events`.
  Demo completa y autocontenida: `scripts/batch-demo.sh` (→ `evidencias/local/batch_demo.log`). Benchmark 1 hilo vs N: `scripts/benchmark-batch.sh` (→ `evidencias/local/batch_benchmark.md`).
- **Seguridad:** sin token → `401`; token de cliente sin `BATCH_ADMIN` → `403`.

### 5.2 Microservicios (C5)

```bash
# cliente → cuenta → depósito → transferencia, por el gateway
CUST=$(curl -s --cacert $CA "${H[@]}" -d '{"documentNumber":"12345678-5","firstName":"Camila","lastName":"Rojas","email":"c@example.com","phone":"+56912345678"}' $GW/api/customers | jq -r .id)
A=$(curl -s --cacert $CA "${H[@]}" -d "{\"customerId\":\"$CUST\",\"type\":\"CHECKING\"}" $GW/api/accounts | jq -r .id)
B=$(curl -s --cacert $CA "${H[@]}" -d "{\"customerId\":\"$CUST\",\"type\":\"SAVINGS\"}" $GW/api/accounts | jq -r .id)
curl -s --cacert $CA "${H[@]}" -H 'Idempotency-Key: dep-0001' -d "{\"toAccountId\":\"$A\",\"amount\":10000}" $GW/api/payments/deposit | jq -c .      # 202 PENDING → COMPLETED en ~1 s
sleep 2     # la saga es asíncrona: espere a que el depósito se acredite (si no, la transferencia responde 422 por fondos insuficientes)
curl -s --cacert $CA "${H[@]}" -H 'Idempotency-Key: trf-0001' -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amount\":2500.50}" $GW/api/payments/transfer | jq -c .
curl -s --cacert $CA "${H[@]}" $GW/api/accounts/$A/balance | jq -c .    # balance 7499.50   ·   cuenta B: 2500.50
```

O todo el flujo (21 verificaciones): `scripts/e2e-flow.sh` → termina con **`FLUJO E2E: TODO CORRECTO`**. Comprueba, entre otras cosas:

| Verificación | Resultado esperado |
|---|---|
| Crear cliente / cuentas / cerrar cuenta con saldo 0 | `201` / `201` / `200` |
| Depósito y transferencia (saga asíncrona) | `202` y luego estado `COMPLETED`; historial `PENDING → DEBITED → COMPLETED` |
| Misma `Idempotency-Key` repetida | `200` con el **mismo** `paymentId` (no duplica) |
| Retiro sin fondos · transferencia a la misma cuenta · a cuenta cerrada | `422` las tres |
| Sin token · token de cliente en endpoint admin · canal intentando debitar directo | `401` · `403` · `403` |
| Perfil del cliente tras abrir 3 y cerrar 1 cuenta | `accountsOpen = 2` (consistencia eventual por el tópico `accounts.lifecycle`) |
| Cabecera `X-Trace-Id` | presente: úsela en Zipkin |

Cada microservicio expone Swagger UI, Actuator (`health`, `info`, `metrics`, `prometheus`; `circuitbreakers` en payments/accounts), errores `ProblemDetail` (RFC 7807) y migraciones Flyway.

### 5.3 Backend for Frontend por canal (C4)

```bash
PAYLOADS_OUT=evidencias/local/bff_payloads.md scripts/bff-flow.sh     # → termina con "FLUJO BFF: TODO CORRECTO"
```

Prueba **en vivo los tres canales** (49 verificaciones). Resumen de lo esperado:

| Canal | Qué se comprueba | Resultado esperado |
|---|---|---|
| **ATM** `https://localhost:8445` | Emisión simulada de tarjeta (scope `internal`) · saldo · 5 últimos movimientos | `201` · saldo con **cuenta enmascarada** (`****0016`) · ≤ 5 movimientos |
| | Retiro con `Idempotency-Key`; reintento con la misma clave | `200 APPROVED dispense=true`; el reintento devuelve **lo mismo** y el saldo baja **una sola vez** |
| | Sin `Idempotency-Key` · misma clave con otro monto · > 200.000 · > 500.000/día | `400` · `422` · `422` · `422` |
| | 2 PIN erróneos, 3.º, y PIN correcto con la tarjeta bloqueada | `401`, `401`, `423` (bloqueada), `423` |
| | **Token móvil / web / interno contra el ATM** (con certificado válido) | **`403`** (y alerta `CROSS_CHANNEL_TOKEN_REJECTED` en `security.alerts`) |
| | Sin certificado de cliente · certificado de otra CA | el **handshake TLS falla** (`curl` sale con 56) |
| **Móvil** `https://localhost:8444` | `/mobile/home` y `ETag` + `If-None-Match` | `200` y luego **`304`** sin cuerpo; `Cache-Control: private, max-age=10` |
| | `/mobile/accounts?fields=number,balance` · `fields=ssn` | respuesta reducida (91 B) · `400` |
| | Transferencia sin / con `Idempotency-Key` | `400` / `202` |
| | Token ATM, web o interno contra el BFF móvil · cuenta ajena | `403` · `404` |
| **Web** `https://localhost:8443` | Login Authorization Code + PKCE (`code_challenge_method=S256`) y sesión | cookie `BFFWEB_SESSION` con **`HttpOnly; Secure; SameSite=Lax`**; `/web/me` **no** contiene tokens |
| | Dashboard agregado (`/web/dashboard`), paginación y filtros | `200` con cliente + perfil + cuentas con movimientos |
| | `POST` sin / con `X-XSRF-TOKEN` · `Origin` no permitido | `403` / `202` · sin `Access-Control-Allow-Origin` |
| | Sin sesión · Bearer móvil o ATM contra el BFF web | `401` · `401` |

Tabla comparativa de tamaño de payload (mismo dato en cada canal; la genera `PAYLOADS_OUT=<archivo> scripts/bff-flow.sh`). Resultado esperado de una corrida local:

| Variante | Llamadas | Bytes | Reducción vs. sin BFF |
|---|---:|---:|---:|
| Sin BFF (el cliente arma el resumen llamando al gateway) | 5 | 3.268 | — |
| BFF web `GET /web/dashboard?movements=5` | 1 | 2.373 | 27 % |
| BFF móvil `GET /mobile/home` | 1 | 252 | 92 % |
| BFF móvil `GET /mobile/accounts?fields=number,balance` | 1 | 91 | 97 % |
| BFF móvil con `If-None-Match` vigente | 1 | 0 (HTTP 304) | 100 % |
| BFF ATM `POST /atm/balance` | 1 | 96 | 97 % |

Las cifras dependen de los datos de la corrida, pero la relación entre canales se mantiene.

**Matriz de seguridad por canal** (cada token contra cada BFF; solo el token de su canal es aceptado): `scripts/cross-channel-matrix.sh` → termina con `MATRIZ POR CANAL: TODO CORRECTO`.

**Rechazo cruzado entre canales a mano:**

```bash
MOBILE=$(scripts/token.sh mobile customer1)
curl -s --cacert $CA --cert certs/out/atm-0001.crt --key certs/out/atm-0001.key -H "Authorization: Bearer $MOBILE" \
     -H 'Content-Type: application/json' -d '{}' -o /dev/null -w '%{http_code}\n' https://localhost:8445/atm/balance     # → 403
```

### 5.4 Resiliencia: circuit breaker, retry y fallback (Resilience4j)

```bash
scripts/demo-resilience.sh        # detecta contenedores o procesos locales; → "Estado final: COMPLETED"
```

Detiene `accounts-service` y muestra, en orden (resultado esperado):

1. `POST /payments/deposit` con accounts caído → **`202` con `status=PENDING` y `degraded=true` (`ACCOUNTS_UNAVAILABLE`)**: la transacción **no se pierde**.
2. Tras seis depósitos fallidos, `/actuator/circuitbreakers` de payments muestra el circuito **`accounts` en `OPEN`**.
3. El gateway responde su **fallback**: `503` `ProblemDetail` `UPSTREAM_UNAVAILABLE` con `Retry-After: 5`.
4. El pago sigue `PENDING`; al reiniciar `accounts-service` el evento esperaba en Kafka y **el pago pasa solo a `COMPLETED`** (historial `PENDING → … → COMPLETED`); saldo final = 6 × 500.
5. En el **BFF ATM**, si payments o accounts no responden: `503` con *"Servicio temporalmente no disponible. No se dispensará dinero."* y `dispense=false` (nunca se dispensa sin confirmación).

A mano: `docker compose stop accounts-service` · consulte el circuito: `docker compose exec payments-service wget -qO- http://localhost:8083/actuator/circuitbreakers | jq` · `docker compose start accounts-service`.

### 5.5 Kafka: tópicos, mensajes y DLT

1. **Kafka UI** → http://localhost:8089 → *Topics*: 8 tópicos de **3 particiones** (`transactions.requested|debited|credited|completed|failed`, `security.alerts`, `accounts.lifecycle`, `batch.job.events`) y sus `*.DLT`. Tras `scripts/e2e-flow.sh`, abra `transactions.completed` → *Messages*: sobres con `eventId`, `eventType`, `occurredAt`, `version` y `payload`.
2. **Dead Letter Topic** — envíe un mensaje inválido a un tópico con consumidor:

```bash
echo 'mensaje-que-no-es-un-evento' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic security.alerts
sleep 12     # reintentos con backoff exponencial antes de rendirse
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic security.alerts.DLT \
     --from-beginning --max-messages 1 --timeout-ms 15000
```

   **Esperado:** el mensaje aparece en **`security.alerts.DLT`** (en Kafka UI, con las cabeceras `kafka_dlt-exception-*` que explican el fallo) y el consumidor sigue procesando el resto.
3. **Reparto de particiones entre réplicas:** `docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group accounts-saga-requested` →
   con 3 réplicas de accounts, **cada partición asignada a una réplica distinta**.
4. **Outbox:** `docker compose exec postgres psql -U payments -d paymentsdb -c "SELECT topic, count(*), count(published_at) FROM outbox_event GROUP BY 1"` → todo publicado.

### 5.6 Escalado horizontal y tolerancia a fallos (C6)

```bash
scripts/scale-demo.sh             # → evidencias/local/scale_demo.log (≈ 3 min)
```

**Esperado:** (1) `docker compose ps` con 3/2/2 réplicas *healthy*; (2) Eureka con `ACCOUNTS-SERVICE 3 UP`, `PAYMENTS-SERVICE 2 UP`, `CUSTOMERS-SERVICE 2 UP`; (3) 30 GET por servicio repartidos
(≈ 10/10/10 en accounts y 15/15 en los otros, según `X-Instance-Id`; Prometheus muestra los mismos conteos por instancia); (4) **se mata una réplica sin aviso** (`docker stop -t 0`) y 30 GET + 10 POST siguen
respondiendo `200`/`201`; unos 15 s después Eureka la expulsa (2 UP); (5) al restaurarla vuelve a 3 UP; (6) las particiones de Kafka se reparten entre réplicas.
Para verlo en el navegador: Eureka http://localhost:8761 y, durante la prueba, `curl -si ... | grep -i x-instance-id`.

### 5.7 Observabilidad (logs, métricas y trazas)

- **Logs JSON** con `traceId`/`spanId`: `docker compose logs --tail 5 accounts-service`.
- **Traza distribuida:** ejecute `scripts/e2e-flow.sh`; al final imprime `traceId=…`. Ábralo en `http://localhost:9411/zipkin/traces/<traceId>`: **una sola traza** con gateway → payments → accounts → customers y los saltos por Kafka (el outbox conserva el contexto).
- **Métricas:** `http://localhost:9090/targets` (todas las réplicas `up`) y el dashboard de Grafana (instancias, req/s, latencia media, 5xx, heap y estado de los circuit breakers).

## 6. Colección Postman

Importe `docs/postman/banco-xyz.postman_collection.json` y `docs/postman/banco-xyz.postman_environment.json` (Bruno puede importar la colección Postman).
Complete en el entorno `internal_secret` y `atm_secret` desde su `.env`, y pegue en `mobile_token` / `batch_admin_token` la salida de `scripts/token.sh mobile customer1` y
`scripts/token.sh web batch-admin`. Ejecute primero la carpeta **0 · Tokens**. Para el BFF ATM configure en *Settings → Certificates* la CA (`certs/out/ca.crt`) y el certificado de cliente
de `localhost:8445` (`certs/out/atm-0001.crt` / `.key`); sin certificado la conexión falla, que es lo esperado. La colección incluye la prueba de rechazo cruzado (token ATM contra el BFF móvil, token móvil contra el ATM → `403`).

## 7. Pruebas automatizadas

```bash
mvn verify        # ≈ 5 min; requiere Docker (Testcontainers levanta PostgreSQL y Kafka reales)
```

**Esperado:** `BUILD SUCCESS` con **283 pruebas** y 0 fallos (1 omitida: el benchmark de 500.000 filas, que se ejecuta aparte con `scripts/benchmark-batch.sh`).
Por módulo: batch-service 78 · bff-atm 33 · bff-web 28 · accounts-service 27 · payments-service 26 · bff-mobile 24 · customers-service 20 · api-gateway 16 · platform-common 12 · auth-server 12 · config-server 4 · discovery-server 3.
Incluyen la **equivalencia con el legacy** (`EquivalenceIT`), el rechazo de tokens de otro canal en los tres BFF, mTLS real y los contratos OpenAPI de los BFF.

## 8. Probar contra el entorno AWS

Con la plataforma desplegada en EC2 (pasos en `despliegue.md`), el **mismo** smoke test corre desde su portátil contra la IP pública:

```bash
scripts/smoke-test.sh --host <IP elástica de la instancia> --certs-dir certs/aws              # valida el certificado con la CA (SAN con la IP)
scripts/smoke-test.sh --host <IP elástica de la instancia> --certs-dir certs/aws --insecure   # sin validar el certificado autofirmado
# → evidencias/aws/logs/smoke-test-remote.log · "SMOKE TEST: TODO CORRECTO"
```

Puertos del despliegue AWS: gateway **443**, BFF web **8441**, BFF móvil **8442**, BFF ATM **8443** (mTLS), auth **9000**. Las herramientas internas (Eureka, Kafka UI, Zipkin) se ven por túnel SSH con `infra/aws/ec2/tunnel.sh`.

## 9. Problemas frecuentes

| Síntoma | Solución |
|---|---|
| `docker compose up` falla con `required variable ... is missing` | Falta `.env`: ejecute `scripts/init-env.sh`. |
| Un contenedor queda `unhealthy` o se reinicia | `docker compose logs <servicio>`; casi siempre es poca memoria en Docker Desktop (suba a ≥ 12 GiB) o un puerto ocupado. |
| `port is already allocated` | Hay otro modo en ejecución (`scripts/run-local.sh stop`) o un servicio local en 5432/9092/8443-8446/9000: deténgalo. |
| `curl: (60) SSL certificate problem` | Use `--cacert certs/out/ca.crt` (la CA es de desarrollo, no está en el almacén del sistema). |
| `.env` / contraseñas de PostgreSQL rechazadas tras regenerar `.env` | El volumen guarda las contraseñas anteriores: `docker compose down -v` y levante de nuevo. |
| Los tests no encuentran Docker | `mvn verify` necesita el daemon de Docker (Testcontainers). |
| Cambié `infra/config-repo/*.yml` y no se nota | El Config Server lee los archivos al arrancar cada cliente: `docker compose restart <servicio>`. |
