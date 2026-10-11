# ADR-006 — Seguridad distribuida con OAuth2/OIDC y separación por canal

- **Estado:** Aceptada (2026-10-10)
- **Criterios de la pauta:** C1 (proceso 4), C2 (seguridad y cumplimiento por canal), C4, C5

## Decisión

Un **Authorization Server** propio (Spring Authorization Server) emite JWT firmados (RS256, JWKS público). Un cliente por canal,
con grants y scopes distintos, más un cliente para comunicación entre servicios:

| Cliente | Grant | Scopes | Notas |
|---|---|---|---|
| `web-client` | Authorization Code + **PKCE**, refresh | `openid web:read web:write` | Confidencial: lo usa `bff-web` (sesión en servidor, cookie HttpOnly) |
| `mobile-client` | Authorization Code + **PKCE**, refresh **rotado** | `openid mobile:read mobile:transfer` | Access token de **5 min**. Confidencial (secreto en `bff-mobile`): Spring Authorization Server **no emite refresh tokens a clientes públicos** |
| `atm-client` | `client_credentials` | `atm:operate` | En la Etapa 3 se autentica además con **mTLS** |
| `internal-service` | `client_credentials` | `internal` | Solo servicio→servicio (Feign adjunta el token) |

- **Resource servers:** el gateway y cada microservicio validan firma y expiración por `jwk-set-uri` (no `issuer-uri`: el emisor visto
  desde el navegador y desde la red Docker difiere).
- **Autorización:** `@PreAuthorize` con anotaciones compartidas: `@CanRead` (web:read, mobile:read, atm:operate, internal),
  `@CanWrite` (web:write, mobile:transfer, atm:operate, internal), `@InternalOnly` (débitos/créditos directos y reembolsos),
  `@AdminOnly` (`ROLE_ADMIN`, endpoints `/admin/**` separados). Los roles viajan en el claim `roles`; el canal en `channel`.
- **Defensa en profundidad:** gateway (JWT válido) + servicio (scope/rol) + base (CHECK de saldo, operaciones idempotentes).
- **Alertas:** cada autenticación fallida (usuario o cliente) publica en `security.alerts`; 5 fallos en 5 min del mismo sujeto
  escalan a `HIGH` (`BRUTE_FORCE_SUSPECTED`). Un reembolso rechazado también genera una alerta `HIGH`.
- **Secretos:** solo por variables de entorno (`.env`, no versionado) y, en AWS, Secrets Manager; ningún secreto en el repo.

## Justificación

Un token de un canal no sirve en otro porque los scopes no se solapan (verificado por tests: el cajero no puede pedir scopes web;
un canal no puede debitar directamente; un token sin `ROLE_ADMIN` recibe 403 en `/admin`). Los cambios de credenciales se
centralizan en un solo emisor y los servicios nunca ven contraseñas.

## Limitaciones conocidas (y siguiente paso)

- Las claves RSA se generan al arrancar (los tokens vigentes se invalidan al reiniciar el auth-server); producción: claves
  persistentes y rotadas desde KMS/Secrets Manager.
- Usuarios y clientes en memoria (demo); producción: base de datos o IdP corporativo.
- No hay autorización por propiedad del recurso (ver ADR-004); requiere el claim `customer_id`.
- `/actuator/*` de servicios es público dentro de la red interna (no se publica por el gateway).
