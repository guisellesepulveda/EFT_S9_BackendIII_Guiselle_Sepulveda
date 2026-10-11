# ADR-007 — Patrón Backend for Frontend (BFF) con seguridad y rendimiento por canal

- **Estado:** Aceptada (2026-10-10)
- **Criterios de la pauta:** C1 (proceso 3: patrón BFF), C2 (escalabilidad, seguridad y cumplimiento por canal, time-to-market), C4

## Contexto

Banco XYZ atiende tres canales con necesidades opuestas: el **navegador** (datos completos, riesgo XSS/CSRF), la **app móvil**
(red lenta y cara, tokens en un dispositivo que puede perderse) y el **cajero** (hardware del banco, operaciones críticas que
mueven dinero físico). Una sola API genérica obligaría a que los tres consuman lo mismo y a que la seguridad sea el mínimo común.

## Decisión

Un **BFF por canal**, como módulo y despliegue independiente (`bff-web`, `bff-mobile`, `bff-atm`), con su propio contrato OpenAPI
versionado en `docs/openapi/` y un mock (`scripts/openapi_mock.py`) para que el frontend avance en paralelo. Los tres consumen los
microservicios **por Eureka** (Feign + Resilience4j; librería común `libs/bff-common`) y **reenvían el token del usuario** (token
relay): la autorización final siempre la aplica el servicio dueño del dato; el BFF nunca usa credenciales propias más amplias.

| Aspecto | bff-web | bff-mobile | bff-atm |
|---|---|---|---|
| Autenticación | Authorization Code + **PKCE**; **sesión en servidor** y cookie `HttpOnly; Secure; SameSite=Lax` (el navegador nunca ve tokens) | Authorization Code + PKCE, **access token de 5 min** + refresh rotado (el BFF guarda el secreto del cliente y hace el canje) | **mTLS** (`client-auth: need`, CN del certificado = ATM-id) + `client_credentials` con scope `atm:operate` |
| Autorización | `web:read` / `web:write` | `mobile:read` / `mobile:transfer` | `atm:operate`; límites de monto por retiro y por día |
| Datos | Dashboard agregado (cliente + perfil + cuentas + movimientos), paginación y filtros | DTO reducido, `fields=`, paginación corta | Solo saldo, retiro y 5 últimos movimientos; cuenta enmascarada |
| Rendimiento | Agregación **en paralelo** (CompletableFuture) | gzip, ETag débil + `Cache-Control: private`, 304 | Timeouts estrictos (0,5 s / 1,5 s), sin agregación |
| Seguridad extra | CSRF (cookie `XSRF-TOKEN` + `X-XSRF-TOKEN`), CORS con lista cerrada | Rate limiting por usuario (token bucket) | **Idempotency-Key obligatoria**, PIN con BCrypt y bloqueo, auditoría de cada operación, alertas a `security.alerts` |

### Reglas transversales

- **Un token de otro canal se rechaza**: cada BFF exige SU scope (los scopes de canal no se solapan) → **403**; en el ATM además se
  publica la alerta `CROSS_CHANNEL_TOKEN_REJECTED`. `bff-web` no es resource server: un `Bearer` ajeno ni siquiera es una credencial
  válida (401). Un test por BFF y la prueba en vivo (`scripts/bff-flow.sh`) lo demuestran.
- **HTTPS en los tres BFF y en el gateway** con certificados de `certs/generate-dev-certs.sh` (CA propia). La gestión (Actuator) va
  en otro puerto HTTP interno (`management.server.ssl.enabled=false` explícito) para que `/actuator/prometheus` no salga por el
  puerto público. En AWS el TLS público lo termina el ALB con ACM `<ARN certificado ACM>`.
- **CSP estricta con excepción solo para la documentación:** toda respuesta de la API lleva `default-src 'none'; frame-ancestors 'none'`, salvo `/swagger-ui/**`, que
  permite `'self'` (si no, el navegador bloquea los scripts y Swagger UI queda en blanco; se detectó al probar las UIs y lo cubre `MobileSecurityIT`).
- **El cajero nunca dispensa sin confirmación**: `dispense=true` solo cuando `payments-service` informa `COMPLETED`; si no hay
  confirmación en 3 s → 202 `PENDING` (dispense=false) y el cajero consulta `GET /atm/withdrawals/{key}`; si payments o accounts
  caen → 503 con mensaje neutro. La `Idempotency-Key` del cajero se propaga a payments (`atm-<key>`), de modo que un reintento
  nunca duplica el retiro ni el pago.
- **Límite diario correcto bajo concurrencia**: los retiros de una tarjeta se serializan con un `pg_advisory_xact_lock` (test con
  6 retiros concurrentes de 100.000 contra un límite de 500.000: exactamente 5 se aprueban).
- **Sin enumeración**: tarjeta desconocida y PIN incorrecto responden idéntico (y con el mismo costo de BCrypt).
- **Propiedad de los datos**: web y móvil resuelven "mi cliente" por `username` (claim `sub`) y verifican que la cuenta sea propia
  (404 en caso contrario, no 403, para no revelar que existe).

## Justificación

- **Escalabilidad y time-to-market:** cada canal escala y se despliega por separado; el equipo móvil no espera al web.
- **Seguridad por canal sin mínimo común:** la sesión con cookie solo tiene sentido en el navegador; el cajero necesita identidad de
  máquina (mTLS), no usuarios; el móvil necesita tokens cortos porque el dispositivo puede perderse.
- **Rendimiento:** medido con `scripts/bff-flow.sh` (bytes y tiempo; `PAYLOADS_OUT=<archivo>` escribe la tabla) con el mismo dato en los tres canales.

## Alternativas descartadas

- **API única con `if (canal)`**: mezcla políticas de seguridad opuestas en un solo código y un solo riesgo de fallo.
- **GraphQL gateway**: resuelve la sobre-extracción pero no la seguridad por canal (mTLS, sesión, límites) y añade otra tecnología.
- **Guardar tokens en el navegador (SPA pública)**: expone refresh tokens a XSS; el patrón BFF existe para evitarlo.
- **Token vinculado al certificado (RFC 8705)** en el ATM: es el siguiente paso natural (hoy mTLS identifica al cajero y el JWT
  autoriza la operación, pero no están criptográficamente atados). Spring Authorization Server lo soporta con
  `tls_client_auth`; se deja como mejora.

## Limitaciones conocidas (y siguiente paso)

- Rate limiting y sesiones web **en memoria**: con varias réplicas hay que compartirlos (Spring Session + Redis / ElastiCache,
  rate limiter en el gateway con Redis). El estado del cajero (tarjetas, retiros) sí está en PostgreSQL y es seguro con réplicas.
- La revocación de cajeros es una lista de configuración; en producción, CRL/OCSP.
- La emisión de tarjetas es **simulada** (`/atm/internal/cards`, solo scope `internal`); el PIN real lo validaría un HSM.
