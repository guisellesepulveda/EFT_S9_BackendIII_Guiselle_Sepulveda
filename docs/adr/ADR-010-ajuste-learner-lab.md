# ADR-010 — Ajuste del despliegue a AWS Academy Learner Lab

- **Estado:** Aceptada (2026-10-10) · **Modifica a [ADR-009](ADR-009-despliegue-aws-en-dos-pasos.md)** (no lo reemplaza: su contenido se conserva y queda marcado como "Modificada por ADR-010")
- **Criterios de la pauta:** C6 (Docker y escalabilidad horizontal en la nube), C2 (justificación de decisiones)

## Contexto

El ADR-009 diseñó el despliegue mínimo para **una instancia EC2 t3.xlarge** (4 vCPU y 16 GiB). Al ejecutarlo en **AWS Academy Learner Lab**
aparecieron restricciones del entorno que el diseño no había considerado:

| Restricción de Learner Lab | Efecto sobre el diseño |
|---|---|
| Solo instancias de tamaño **`large` o menor** (máx. 9 concurrentes) | La t3.xlarge **no se puede lanzar**; hay que cambiar de tipo |
| Región **us-east-1**, key pair `vockey`, perfil `LabInstanceProfile`, **sin crear roles IAM** | Sin ECR/ECS ni roles propios: se mantiene el despliegue sin servicios que exijan IAM nuevo |
| Sesión de ~**4 h** con **credenciales temporales**; al expirar el lab detiene las instancias | La IP pública cambiaba al reiniciar y el certificado de servidor (con la IP en el SAN) dejaba de ser válido |
| Presupuesto de créditos limitado | Pocas instancias y limpieza explícita |

## Cómo se verificó

Con `aws ec2 run-instances --dry-run` (Paso A.3 de `despliegue.md`) sobre `r6i.large`, `r5.large`, `m5.large` y `t3.large`: la CLI responde
`DryRunOperation` si la llamada habría sido autorizada, sin crear nada. **Resultado: un tipo con 2 vCPU y 16 GiB está permitido** (`r6i.large`, o `r5.large`),
lo que corresponde al **Plan A** de `despliegue.md`. El resultado lo ejecutó quien despliega; el repositorio no ejecuta comandos que creen recursos en AWS.

## Decisión

1. **Un solo host `r6i.large` (o `r5.large`): 2 vCPU y 16 GiB.** Se mantiene el diseño de ADR-009 (EC2 + Docker Compose + `--scale`, escalado 3/2/2) y el
   **presupuesto de memoria de 16 GiB** (10,1 GiB de límites sin `observability`, 10,75 GiB con ella; ~5,25 GiB de holgura), **sin swap**: el presupuesto cierra sin contarlo.
2. **Mitigación del paso de 4 a 2 vCPU** (la memoria no cambia; cambian el tiempo de arranque y de compilación):
   - `deploy.sh` levanta **por oleadas** (infraestructura → config/Eureka/auth → un contenedor por servicio → réplicas → gateway, BFF y batch), espera a que cada una esté
     sana y **mide su duración**; antes arrancaban todos a la vez.
   - Los *healthchecks* del override de AWS usan una ventana de arranque más larga (`start_period` 240 s, 12 reintentos, intervalo de 15 s).
   - La compilación Maven ocurre en la instancia y es más lenta; sus tiempos **se midieron** en la simulación con 2 vCPU y 16 GiB (simulación en un contenedor `docker:dind` limitado a 2 vCPU y 16 GiB), no se estimaron.
3. **IP elástica** asociada a la instancia: la IP no cambia cuando el lab detiene la instancia al expirar la sesión, de modo que el certificado con la IP en el SAN,
   las reglas del Security Group y el túnel SSH siguen siendo válidos. Se libera en la limpieza (una EIP sin asociar sigue cobrando).
4. **Un único Security Group** `banco-xyz-apps-sg` (22, 8443 y 9000 solo desde la IP de quien opera; 443 y 8441–8442 públicos).
5. **Operación en el lab:** el paso a paso (`despliegue.md` Parte A) incluye cómo reanudar tras expirar la sesión (*Start Lab*, credenciales nuevas, `start-instances`).
6. `verify.sh` registra `nproc`, `free -h` y `docker stats --no-stream` como evidencia directa de que el presupuesto de recursos se cumple.

**Contingencia (Plan B), no aplicada:** si solo estuvieran permitidos 8 GiB (`t3.large`/`m5.large`), se usarían **dos hosts con roles fijos**: un `data-host`
(PostgreSQL, Kafka, Kafka UI, Zipkin) y un `apps-host` con todo lo Spring (las réplicas se registran en Eureka con su IP de contenedor, que solo es alcanzable dentro del mismo host),
con el override dividido en `docker-compose.aws-data.yml` y `docker-compose.aws-apps.yml`, un swapfile de 4 GiB solo como red de seguridad y reglas de Security Group que limiten 5432, 9092 y 9411 al apps-host.
Está descrita en `despliegue.md` y no se implementó porque el Plan A es suficiente.

## Consecuencias

- **Arranque y compilación más lentos** que con 4 vCPU; con 2 vCPU compartidas por ~14 JVM, bajo carga sostenida habría **contención de CPU** (el escalado no agrega CPU: agrega contenedores sobre los mismos 2 vCPU).
  Es una limitación de la demostración (`despliegue.md`, Parte C), no del diseño: en ECS cada tarea tendría su propia CPU.
- La memoria **no** es el cuello de botella: el consumo real medido por contenedor queda muy por debajo de los límites (ver `docker-stats.log`).
- Sigue siendo **un solo host**: sin tolerancia a la caída de la máquina (ya declarado en ADR-009).
- Cualquier cambio de tipo exige recalcular el presupuesto de memoria (tabla en `despliegue.md`).
- El tipo exacto (`r6i.large` o `r5.large`) lo fija la verificación de la sesión; ambos son equivalentes para este diseño (2 vCPU, 16 GiB).

## Alternativas descartadas

- **Tres o más hosts** (un host por grupo de servicios): más costo y más coordinación (Eureka registra IPs de contenedor no alcanzables entre hosts sin una red *overlay*), y consume presupuesto del lab sin necesidad.
- **Bajar el número de réplicas sin medir** (p. ej. 2/1/1): se perdería lo que exige C6 (3 microservicios escalados) para resolver un problema que **no es de memoria**; primero se midió, y 16 GiB alcanzan.
- **Quedarse con t3.xlarge en otra cuenta o salir de Learner Lab:** requiere una cuenta propia con costo real y deja el entorno que provee el curso; además el despliegue debe ser reproducible por quien evalúa.
- **Mantener los healthchecks y el arranque simultáneo** de ADR-009: con 2 vCPU produce reinicios por *timeouts* de arranque y un arranque total más lento; las oleadas son más predecibles y medibles.

## Resultado de la ejecución (2026-10-11)

- **Verificación (`--dry-run`):** `r6i.large`, `r5.large`, `m5.large` y `t3.large` resultaron **permitidos** en la cuenta del lab. Se aplicó el **Plan A** con **`r6i.large`** (2 vCPU, 16 GiB), instancia `i-06f6ae68fbb6c00ea` en us-east-1a, con IP elástica.
- **Hallazgos al desplegar:** (1) `r6i.large` no está disponible en us-east-1e, que era la zona de la subred por defecto elegida; el runbook ahora escoge la zona con `describe-instance-type-offerings`. (2) Compose v5 exigía `buildx` ≥ 0.17 y Amazon Linux 2023 trae 0.12.1; `user-data.sh` instala una versión reciente (0.38).
- **Medido con 2 vCPU:** construcción de imágenes 59 s y despliegue por oleadas 437 s; 19 contenedores en ejecución (18 *healthy*; Kafka UI no define *healthcheck*); memoria real en contenedores 5.347 MiB (5,22 GiB) sin swap, con 9,2 GiB disponibles en el host.
