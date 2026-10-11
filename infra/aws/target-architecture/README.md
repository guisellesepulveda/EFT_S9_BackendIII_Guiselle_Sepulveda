# Arquitectura objetivo en AWS (CONCEPTUAL, no desplegada)

Esta carpeta documenta hacia dónde evoluciona la solución. **Lo que se desplegó realmente** es el entorno EC2 + Docker Compose de
`../ec2/` y `../docker-compose.aws.yml` (ver `despliegue.md`, Parte A). Estos archivos son ejemplos revisables, no se ejecutan.

| Archivo | Contenido |
|---|---|
| `ecs-task-definition-accounts.json` | Task definition Fargate de `accounts-service`: imagen en ECR, secretos desde Secrets Manager, health check y logs a CloudWatch. Un servicio ECS por microservicio/BFF. |
| `ecs-autoscaling-policy.json` | Service Auto Scaling por seguimiento de objetivo: CPU al 60 % y requests por target del ALB. Equivale a `--scale` de Compose, pero automático. |

El diagrama y la tabla "Compose → servicio gestionado" están en `despliegue.md`, Parte B.
