# ADR-008 — Contenedores y escalado horizontal con Docker Compose

- **Estado:** Aceptada (2026-10-10)
- **Criterios de la pauta:** C2 (escalabilidad ante demanda variable; disponibilidad), C6

## Decisión

1. **Un Dockerfile multi-stage por módulo desplegable (11)**, generados desde una plantilla (`scripts/gen-dockerfiles.sh`):
   *build* con `maven:3.9-eclipse-temurin-21` y *runtime* con `eclipse-temurin:21-jre-alpine` (JRE slim, ~260-320 MB por imagen),
   **usuario no-root**, `HEALTHCHECK` contra Actuator (en el puerto de gestión para gateway y BFF) y JVM consciente del contenedor
   (`-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`: el heap se calcula sobre el límite de memoria del contenedor).
2. **La etapa *build* es idéntica en los 11 Dockerfiles** y el contexto (`.dockerignore` en lista blanca) solo contiene código fuente.
   BuildKit la cachea: el reactor Maven se compila **una vez** (~40 s) y las otras 10 imágenes se arman en segundos.
3. **`docker-compose.yml` con dos perfiles:** `infra` (PostgreSQL, Kafka KRaft, Kafka UI, Zipkin, Prometheus, Grafana) y `apps`
   (infra + config, discovery, auth, gateway, 3 microservicios, 3 BFF y batch). `COMPOSE_PROFILES=apps` viene en `.env.example`, así que
   **`docker compose up -d --build` levanta todo**; `COMPOSE_PROFILES=infra` deja solo la infraestructura para ejecutar las apps en el host.
   Orden de arranque con `depends_on: condition: service_healthy` (config → discovery → auth → apps), red dedicada `bancoxyz`.
4. **Escalabilidad horizontal sin cambios de código:** los microservicios no fijan `container_name` ni publican puertos (solo gateway,
   BFF, auth-server y el dashboard de Eureka); el `instance-id` de Eureka usa el `HOSTNAME` del contenedor, de modo que cada réplica es
   única. `docker compose up -d --scale accounts-service=3 --scale payments-service=2 --scale customers-service=2`
   (o `docker-compose.scale.yml`) las suma al registro y el gateway las balancea (Spring Cloud LoadBalancer, `lb://`).
5. **Tolerancia a la caída de una réplica** (probada con `docker stop -t 0`, caída brusca sin baja en Eureka):
   - `connect-timeout` del gateway de **0,5 s** y lista de instancias del balanceador refrescada cada **5 s** (`loadbalancer.cache.ttl`).
   - **`Retry` del gateway en dos filtros:** lecturas (GET) ante cualquier `IOException`, hasta 5 reintentos; escrituras (3 reintentos)
     solo ante conexión rechazada o agotada (no se envió nada, reintentar es seguro). Una escritura cuya conexión reutilizada se
     cierra a mitad la reintenta el cliente con su `Idempotency-Key`. Con 3 reintentos se observó 1 GET de cada ~30 fallando (el
     balanceador puede volver a elegir una réplica muerta; causa probable: solo excluye la instancia del intento anterior, hipótesis no verificada en el código de Spring); con 5, **0 fallos en
     4 rondas × 30 GET** matando una réplica de accounts-service, y `GatewayRetryDeadReplicasIT` cubre 2 de 3 réplicas muertas.
6. **Observabilidad que sigue el escalado:** Prometheus descubre **todas las réplicas por DNS** del servicio (`dns_sd_configs`) y
   Grafana trae un dashboard provisionado (instancias activas, req/s, latencia media, 5xx, heap, circuit breakers).

## Justificación

- **Escalabilidad ante demanda variable:** más réplicas = más capacidad sin tocar código ni configuración; los consumidores Kafka
  (tópicos de 3 particiones) reparten las particiones entre réplicas, el outbox usa `FOR UPDATE SKIP LOCKED` y las migraciones Flyway
  se serializan con un lock de PostgreSQL, por lo que ninguna réplica pisa a otra.
- **Disponibilidad:** una réplica muerta no corta el servicio; Eureka la expulsa en ~15 s y, mientras tanto, el gateway la salta.
- **Portabilidad:** las mismas imágenes y el mismo compose corren en el portátil y en una EC2 (Etapa 5); en ECS Fargate cada servicio
  de Compose pasa a ser un *service* con Auto Scaling (arquitectura objetivo, `despliegue.md`).

## Alternativas descartadas

- **Un Dockerfile genérico con `ARG MODULE`:** más corto, pero la pauta pide un Dockerfile por módulo y un cambio en un módulo
  invalidaría el cache de todos; con la etapa *build* idéntica obtenemos lo mejor de ambos.
- **Imagen con `spring-boot:build-image` (buildpacks):** más pesada y menos transparente para evidenciar usuario no-root, healthcheck y flags de JVM.
- **Kubernetes:** excede el alcance de la evaluación; Compose + `--scale` demuestra el mismo principio con mucho menos costo.

## Límites conocidos

- Escalado **a nivel de contenedores en un solo host**: si el host cae, cae todo (la arquitectura objetivo en AWS usa ECS multi-AZ).
- Rate limiting y sesión web en memoria: con varias réplicas de gateway/BFF se comparten con Redis (ver ADR-007).
- `payments-service` con varias réplicas depende de la idempotencia (clave de operación) para que el reintentador y los consumidores
  no dupliquen efectos; está verificado por el smoke test con 3/2/2 réplicas, no con pruebas de carga.
