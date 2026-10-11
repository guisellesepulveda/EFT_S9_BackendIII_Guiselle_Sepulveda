# ADR-004 — Descomposición en microservicios, base por servicio y API Gateway

- **Estado:** Aceptada (2026-10-10)
- **Criterios de la pauta:** C1 (procesos 2 y 4), C2 (escalabilidad, disponibilidad, seguridad), C5

## Contexto

El monolito legacy concentra clientes, cuentas y pagos en una sola base y un solo despliegue: cualquier cambio exige
desplegar todo, un pico de pagos degrada la consulta de saldos y una falla arrastra al resto.

## Decisión

| Elemento | Decisión |
|---|---|
| Límites de servicio | `customers-service` (datos personales y perfil), `accounts-service` (cuentas, saldos y libro mayor), `payments-service` (pagos, transferencias, depósitos, retiros y saga). Cada uno es dueño de su ciclo de vida y su contrato OpenAPI |
| Datos | **Database per service**: una base PostgreSQL y un usuario por servicio (`infra/postgres/init`); no hay consultas ni FKs entre bases, solo referencias lógicas por id y eventos |
| Descubrimiento y balanceo | Eureka + Spring Cloud LoadBalancer (`lb://`); cada instancia registra `instance-id` con el hostname del contenedor, por lo que `--scale` agrega réplicas sin configuración |
| Entrada | Spring Cloud Gateway: ruteo `lb://`, validación JWT, rate limiting por usuario, circuit breaker con fallback `ProblemDetail`, propagación de `traceId` (`X-Trace-Id` + `traceparent`) |
| Configuración | Spring Cloud Config Server (backend nativo `infra/config-repo`, perfiles `local`/`docker`/`aws`, protegido con usuario/contraseña) |
| Código común | Librería `libs/platform-common` (contrato de eventos, outbox, idempotencia, DLT, seguridad JWT, errores RFC 7807, `X-Instance-Id`, token interno): evita reimplementar lo transversal seis veces, pero **no** comparte entidades ni lógica de negocio |
| Observabilidad | Actuator (`health`, `info`, `metrics`, `prometheus`, `circuitbreakers`), logs JSON con `traceId`/`spanId` (perfil docker/aws) y trazas en Zipkin que cruzan HTTP y Kafka |

## Justificación (requerimientos de negocio)

1. **Escalabilidad ante demanda variable:** los pagos escalan sin escalar clientes; el gateway reparte entre réplicas y el
   estado vive en PostgreSQL/Kafka, no en la memoria de las instancias (salvo el rate limiter, ver abajo).
2. **Disponibilidad y tolerancia a fallos:** si cae `accounts-service`, el alta de pagos sigue (quedan `PENDING` y se procesan al
   volver); si cae `customers-service`, se valida con la caché local. Bulkhead, retry, circuit breaker y timeout acotan el daño.
3. **Time-to-market con equipos independientes:** contratos explícitos (REST + eventos versionados) y bases separadas permiten
   evolucionar cada servicio por separado.

## Consecuencias y riesgos

- Consistencia eventual entre servicios (perfil del cliente, saldos proyectados): se acepta y se documenta (ver ADR-005).
- Más operación (más procesos, trazas distribuidas): se mitiga con Docker Compose, Config Server y trazas de extremo a extremo.
- **Rate limiter en memoria por instancia del gateway:** con N réplicas el límite efectivo se multiplica por N. Aceptable para la demo;
  en producción se usa `RedisRateLimiter` (estado compartido, ElastiCache). Está aislado en `InMemoryRateLimiter`.
- Los endpoints `/actuator/*` de los servicios no se publican por el gateway; solo son alcanzables dentro de la red interna.
- No se implementa autorización por propiedad del recurso (que un cliente solo vea sus cuentas): se autoriza por canal/scope y rol.
  Es el principal paso de endurecimiento pendiente (claim `customer_id` en el token y verificación en `accounts`/`payments`).

## Alternativas descartadas

- **Base compartida con esquemas separados:** más simple, pero permite acoplamiento por SQL y rompe la independencia de despliegue.
- **Gateway propio con Zuul/Nginx:** menos integración con Eureka, JWT y observabilidad que Spring Cloud Gateway.
- **Descubrimiento por DNS/Service Connect desde el día 1:** en local no existe; se documenta como alternativa en AWS (despliegue.md).
- **Mono-repositorio sin librería común:** duplicaría outbox, idempotencia y seguridad en cada servicio.
