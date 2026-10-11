# ADR-009 — Despliegue en AWS en dos pasos: EC2 + Docker Compose ahora, ECS Fargate como objetivo

- **Estado:** Aceptada (2026-10-10) · **Modificada por [ADR-010](ADR-010-ajuste-learner-lab.md)** (el tipo de instancia t3.xlarge y su 4 vCPU no están permitidos en Learner Lab; el resto de la decisión —EC2 + Compose, ECR y ECS como objetivo— se mantiene)
- **Criterios de la pauta:** C2 (escalabilidad, disponibilidad, costo), C6 (Docker + escalabilidad horizontal en la nube), C7

## Contexto

La pauta exige contenedores y **escalabilidad horizontal de 3 microservicios en la nube**. La arquitectura "de libro" en AWS (ECS Fargate + ALB + RDS +
MSK + Secrets Manager + ECR) requiere servicios que un entorno académico (AWS Academy Learner Lab) suele restringir, tiene un costo mayor y mucha más
configuración que verificar. Además, quien evalúa necesita poder **reproducir** el despliegue.

## Decisión

1. **Despliegue real y mínimo:** una instancia **EC2 t3.xlarge** con **Docker Compose** y `--scale` (accounts×3, payments×2, customers×2), exactamente las mismas imágenes y el
   mismo `docker-compose.yml` que corren en el portátil, más un override (`infra/aws/docker-compose.aws.yml`) que fija límites de memoria, perfil Spring `aws`, los puertos públicos
   (gateway 443, BFF 8441/8442, ATM 8443 con mTLS, auth 9000) y deja la infraestructura accesible solo por **túnel SSH**.
2. **Imágenes construidas en la instancia** (sin ECR): evita depender de permisos IAM que Learner Lab no concede; ECR queda como paso productivo.
3. **Todo automatizado y verificable:** `user-data.sh` (bootstrap idempotente), `push.sh` (código + secretos + certificados con la IP en el SAN), `deploy.sh`, `verify.sh` y
   `smoke-test.sh --host` desde el portátil. El mismo flujo se **probó localmente** en una copia limpia del repositorio (simulación en una copia limpia del repositorio).
4. **Arquitectura objetivo documentada, no desplegada** (`despliegue.md`, Parte B; ejemplos en `infra/aws/target-architecture/`) con la tabla "Compose → servicio gestionado" y el trade-off de
   Eureka vs. Cloud Map / Service Connect y de mTLS en ALB vs. NLB passthrough.
5. **Limitaciones declaradas** (Parte C): un solo host, certificados autofirmados, auth-server por HTTP restringido por Security Group, Kafka/PostgreSQL sin replicación.

## Justificación

- **Valida lo esencial a bajo costo:** portabilidad de los contenedores, escalado horizontal con balanceo y descubrimiento, tolerancia a la caída de una réplica y seguridad por canal (mTLS) funcionando en una red real.
- **Reproducible y barato:** una sola máquina, pocos permisos, se apaga al terminar; el costo se estima con la calculadora de AWS (no se fijan precios en el repositorio).
- **Camino de migración claro:** los servicios ya leen sus endpoints (Kafka, PostgreSQL, Eureka, Zipkin) por variables de entorno en el perfil `aws`; pasar a MSK/RDS/Cloud Map es cambiar valores, no código.

## Alternativas descartadas

- **ECS Fargate desde el inicio:** correcto para producción, pero exige ECR, ALB, RDS, MSK, roles y redes; en Learner Lab varios están restringidos y el costo y la superficie de error crecen mucho para la demostración.
- **Kubernetes (EKS):** sobredimensionado para el alcance.
- **Solo documentar sin desplegar:** no demostraría la funcionalidad en la nube que pide C6.

## Consecuencias

- El escalado es de **contenedores**, no de máquinas: si el host cae, cae todo (Parte C). La arquitectura objetivo lo resuelve con ECS multi-AZ.
- `deploy.sh` y el override de memoria están dimensionados para 16 GiB; con otro tamaño de instancia hay que recalcular el presupuesto (tabla en `despliegue.md`).
