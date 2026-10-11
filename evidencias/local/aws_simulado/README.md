# Simulación local del despliegue en EC2

Estos logs **no son evidencia de AWS**: son la prueba, en el portátil, del mismo flujo que se ejecutará en la instancia, hecha en una **copia limpia
del repositorio** (sin `.env`, certificados ni caché) con `PUBLIC_HOST=localhost` y el override `infra/aws/docker-compose.aws.yml`:

| Archivo | Qué es |
|---|---|
| `deploy.log` | `infra/aws/ec2/deploy.sh --public-host localhost`: .env y certificados desde cero, build, infraestructura, apps y escalado 3/2/2 (puertos 443/8441/8442/8443/9000) |
| `compose-ps.log`, `docker-stats.log` | `verify.sh`: réplicas y memoria real por contenedor frente a su límite (presupuesto de `despliegue.md`) |
| `scale-demo.log` | `verify.sh`: Eureka 3/2/2, reparto por `X-Instance-Id`, caída brusca de una réplica y recuperación |
| `circuit-breakers.log` | `verify.sh`: estado de los circuit breakers (gateway, payments, accounts) |
| `smoke-test-remote.log` | `scripts/smoke-test.sh --host localhost --certs-dir certs/out`: el smoke test **remoto** validando el certificado con la CA (77 ✔, 0 ✘) |
| `resumen.log` | encabezados de `verify.sh` |

Las evidencias reales de AWS irán en `evidencias/aws/` (ver su `README.md`).
