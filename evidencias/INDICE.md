# Índice de evidencias



| Criterio | Qué demuestra |
|---|---|
| C1 | Los 5 procesos clave: batch, microservicios, BFF, seguridad OAuth2 y Kafka |
| C2 | Arquitectura y decisiones justificadas (ADRs en `docs/adr/`) |
| C3 | 3 jobs batch con errores avanzados, paralelismo y escalabilidad |
| C4 | 3 BFF con rendimiento y seguridad por canal |
| C5 | 3 microservicios con Spring Cloud, Resilience4j y Kafka |
| C6 | Docker y escalabilidad horizontal (local y nube) |
| C7 | Documentación (`readme.md`, `instrucciones.md`, `despliegue.md`, informe) |
| C8 | Guion del video (`docs/guion_video.md`) |

## Diagramas (fuente Mermaid `.mmd` junto a cada PNG)

| Estado | Evidencia | Qué muestra | Criterio | Informe |
|---|---|---|---|---|
| ✅ | [`01-arquitectura-general.png`](../docs/diagramas/01-arquitectura-general.png) | Arquitectura general: canales → BFF → gateway → servicios → BD/Kafka; Spring Cloud y observabilidad | C1, C2, C5 | §3, Anexo |
| ✅ | [`02-componentes-servicios.png`](../docs/diagramas/02-componentes-servicios.png) | Componentes internos de customers, accounts y payments (controladores, saga, outbox, clientes resilientes) | C5 | §6 |
| ✅ | [`03-casos-de-uso.png`](../docs/diagramas/03-casos-de-uso.png) | Casos de uso: cliente web, cliente móvil, usuario de cajero, operador batch y auditor | C2, C4 | §3, Anexo |
| ✅ | [`04-secuencia-transferencia-saga.png`](../docs/diagramas/04-secuencia-transferencia-saga.png) | Secuencia: transferencia con saga coreografiada + outbox + Kafka (compensación y DLT) | C1 (proceso 5), C5 | §6 |
| ✅ | [`05-secuencia-retiro-atm.png`](../docs/diagramas/05-secuencia-retiro-atm.png) | Secuencia: retiro en el cajero con mTLS, PIN, límites, Idempotency-Key y fallback | C1 (proceso 3), C4 | §5 |
| ✅ | [`06-flujo-jobs-batch.png`](../docs/diagramas/06-flujo-jobs-batch.png) | Flujo de los 3 jobs batch: steps, skip/retry, particiones, restart | C1 (proceso 1), C3 | §4 |
| ✅ | [`07-despliegue-aws-ec2.png`](../docs/diagramas/07-despliegue-aws-ec2.png) | Despliegue real: EC2 + Docker Compose con escalado 3/2/2 y túnel SSH | C6 | §7 |
| ✅ | [`08-arquitectura-aws-objetivo.png`](../docs/diagramas/08-arquitectura-aws-objetivo.png) | Arquitectura AWS objetivo (ECS Fargate, ALB, RDS, MSK) — conceptual | C2, C6 | §7 |
| ✅ | [`09-legacy-vs-nuevo.png`](../docs/diagramas/09-legacy-vs-nuevo.png) | Comparación legacy (mainframe/COBOL/Shell) vs plataforma nueva | C1, C2 | §9 |

## Capturas de las interfaces (Playwright)

| Estado | Evidencia | Qué muestra | Criterio | Informe |
|---|---|---|---|---|
| ✅ | [`01-eureka-instancias-escaladas.png`](../evidencias/local/capturas/01-eureka-instancias-escaladas.png) | Dashboard de Eureka con las réplicas 3/2/2 | C5, C6 | §7 |
| ✅ | [`02-swagger-customers-service.png`](../evidencias/local/capturas/02-swagger-customers-service.png) | Swagger UI de customers-service | C5 | §6 |
| ✅ | [`03-swagger-accounts-service.png`](../evidencias/local/capturas/03-swagger-accounts-service.png) | Swagger UI de accounts-service | C5 | §6 |
| ✅ | [`04-swagger-payments-service.png`](../evidencias/local/capturas/04-swagger-payments-service.png) | Swagger UI de payments-service | C5 | §6 |
| ✅ | [`05-swagger-batch-service.png`](../evidencias/local/capturas/05-swagger-batch-service.png) | Swagger UI de batch-service (API de lanzamiento de jobs) | C3 | §4 |
| ✅ | [`06-swagger-bff-web.png`](../evidencias/local/capturas/06-swagger-bff-web.png) | Swagger UI de bff-web | C4 | §5 |
| ✅ | [`07-swagger-bff-mobile.png`](../evidencias/local/capturas/07-swagger-bff-mobile.png) | Swagger UI de bff-mobile | C4 | §5 |
| ✅ | [`08-swagger-bff-atm.png`](../evidencias/local/capturas/08-swagger-bff-atm.png) | Swagger UI de bff-atm (accedida con certificado de cliente mTLS) | C4 | §5 |
| ✅ | [`09-kafka-ui-topicos.png`](../evidencias/local/capturas/09-kafka-ui-topicos.png) | Kafka UI: tópicos con 3 particiones y sus DLT | C1 (proceso 5), C5 | §6 |
| ✅ | [`10-kafka-ui-mensajes-transactions-completed.png`](../evidencias/local/capturas/10-kafka-ui-mensajes-transactions-completed.png) | Kafka UI: mensajes del tópico transactions.completed (sobre versionado) | C5 | §6 |
| ✅ | [`15-kafka-ui-dlt.png`](../evidencias/local/capturas/15-kafka-ui-dlt.png) | Kafka UI: mensaje inválido en security.alerts.DLT | C5 | §6 |
| ✅ | [`11-zipkin-traza-completa.png`](../evidencias/local/capturas/11-zipkin-traza-completa.png) | Zipkin: traza completa gateway → payments → accounts → customers | C5 | §8 |
| ✅ | [`12-grafana-plataforma.png`](../evidencias/local/capturas/12-grafana-plataforma.png) | Grafana: dashboard de la plataforma (instancias, req/s, latencia, heap, circuit breakers) | C5, C6 | §8 |
| ✅ | [`13-prometheus-targets.png`](../evidencias/local/capturas/13-prometheus-targets.png) | Prometheus: todas las réplicas descubiertas por DNS | C6 | §8 |
| ✅ | [`14-circuitbreaker-open.png`](../evidencias/local/capturas/14-circuitbreaker-open.png) | /actuator/circuitbreakers de payments con el circuito de accounts en OPEN | C5 | §6 |

## Evidencias de terminal (PNG renderizados desde los logs)

| Estado | Evidencia | Log fuente | Qué muestra | Criterio | Informe |
|---|---|---|---|---|---|
| ✅ | [`01-compose-ps-escalado.png`](../evidencias/local/terminal/01-compose-ps-escalado.png) | [`compose_ps_escalado.log`](../evidencias/local/compose_ps_escalado.log) | docker compose ps con las réplicas escaladas | C6 | §7 |
| ✅ | [`02-batch-jobs-conteos.png`](../evidencias/local/terminal/02-batch-jobs-conteos.png) | [`smoke-test.log`](../evidencias/local/smoke-test.log) | Ejecución de los 3 jobs con conteos leídos/escritos/omitidos/filtrados | C3 | §4 |
| ✅ | [`03-batch-rechazados.png`](../evidencias/local/terminal/03-batch-rechazados.png) | [`batch_rejected.log`](../evidencias/local/batch_rejected.log) | Tabla de rechazados persistidos (batch_rejected_record) | C3 | §4 |
| ✅ | [`04-batch-restart-tras-fallo.png`](../evidencias/local/terminal/04-batch-restart-tras-fallo.png) | [`batch_demo.log`](../evidencias/local/batch_demo.log) | Job con fallo forzado y restart desde el último chunk | C3 | §4 |
| ✅ | [`05-smoke-test-inicio.png`](../evidencias/local/terminal/05-smoke-test-inicio.png) | [`smoke-test.log`](../evidencias/local/smoke-test.log) | Smoke test (1/2): contenedores, tokens, flujo de negocio | C4, C5, C6 | §7 |
| ✅ | [`06-smoke-test-final.png`](../evidencias/local/terminal/06-smoke-test-final.png) | [`smoke-test.log`](../evidencias/local/smoke-test.log) | Smoke test (2/2): jobs batch y resultado | C3, C6 | §7 |
| ✅ | [`07-rechazo-cruzado-entre-canales.png`](../evidencias/local/terminal/07-rechazo-cruzado-entre-canales.png) | [`cross_channel.log`](../evidencias/local/cross_channel.log) | Matriz de seguridad por canal: token de otro canal rechazado | C4 | §5 |
| ✅ | [`08-mvn-verify.png`](../evidencias/local/terminal/08-mvn-verify.png) | [`mvn_verify.log`](../evidencias/local/mvn_verify.log) | mvn verify: pruebas unitarias y de integración | C3, C4, C5 | §4–§6 |
| ✅ | [`09-benchmark-batch.png`](../evidencias/local/terminal/09-benchmark-batch.png) | [`batch_benchmark.md`](../evidencias/local/batch_benchmark.md) | Benchmark batch: 1 hilo vs N hilos/particiones | C3 | §4, §9 |
| ✅ | [`10-payloads-por-canal.png`](../evidencias/local/terminal/10-payloads-por-canal.png) | [`bff_payloads.md`](../evidencias/local/bff_payloads.md) | Comparación de tamaño de payload web / móvil / ATM | C4 | §5 |
| ✅ | [`11-resiliencia-circuit-breaker.png`](../evidencias/local/terminal/11-resiliencia-circuit-breaker.png) | [`resilience_demo_docker.log`](../evidencias/local/resilience_demo_docker.log) | Resilience4j: PENDING degradado, circuito OPEN, fallback del gateway y recuperación | C5 | §6 |
| ✅ | [`12-escalado-balanceo.png`](../evidencias/local/terminal/12-escalado-balanceo.png) | [`scale_demo.log`](../evidencias/local/scale_demo.log) | Escalado 3/2/2 y reparto por X-Instance-Id | C6 | §7 |
| ✅ | [`13-escalado-tolerancia-fallos.png`](../evidencias/local/terminal/13-escalado-tolerancia-fallos.png) | [`scale_demo.log`](../evidencias/local/scale_demo.log) | Caída brusca de una réplica: el tráfico continúa | C6 | §7 |
| ✅ | [`14-flujo-e2e-gateway.png`](../evidencias/local/terminal/14-flujo-e2e-gateway.png) | [`stage2_e2e_flow.log`](../evidencias/local/stage2_e2e_flow.log) | Flujo de negocio por el gateway (saga, reglas, seguridad, trazabilidad) | C5 | §6 |
| ✅ | [`15-bff-atm-mtls.png`](../evidencias/local/terminal/15-bff-atm-mtls.png) | [`bff_flow.log`](../evidencias/local/bff_flow.log) | BFF ATM: mTLS, PIN, límites, Idempotency-Key, rechazo entre canales | C4 | §5 |
| ✅ | [`16-bff-movil-y-web.png`](../evidencias/local/terminal/16-bff-movil-y-web.png) | [`bff_flow.log`](../evidencias/local/bff_flow.log) | BFF móvil (ETag, gzip, fields) y BFF web (sesión, CSRF) | C4 | §5 |

## Simulación local del despliegue en EC2 (no es evidencia de AWS)

| Estado | Evidencia | Qué muestra | Criterio | Informe |
|---|---|---|---|---|
| ✅ | [`README.md`](../evidencias/local/aws_simulado/README.md) | Simulación local del flujo de despliegue en EC2 (deploy + verify + smoke remoto sobre copia limpia) | C6 | §7 |
| ✅ | [`smoke-test-remote.log`](../evidencias/local/aws_simulado/smoke-test-remote.log) | Smoke test remoto (--host) con validación del certificado: 77 ✔ | C6 | §7 |
| ✅ | [`scale-demo.log`](../evidencias/local/aws_simulado/scale-demo.log) | Escalado y tolerancia a fallos con el override de AWS | C6 | §7 |
| ✅ | [`docker-stats.log`](../evidencias/local/aws_simulado/docker-stats.log) | Memoria real por contenedor vs presupuesto de 16 GiB (simulación en copia limpia) | C6 | §7 |

## Evidencias de AWS (despliegue real en EC2)

Generadas desde la ejecución real (`despliegue.md`); solo las capturas de la consola web (01 y 02) las toma quien despliega. Cómo tomar cada una: [`evidencias/aws/README.md`](aws/README.md). Los logs de `verify.sh` y del smoke test remoto van en `evidencias/aws/logs/` y `scripts/generar-evidencias.sh --aws` los convierte a PNG.

| Estado | Placeholder | Criterio |
|---|---|---|
| ⏳ pendiente | ![PENDIENTE-AWS: instancia ec2 running con tipo y región](evidencias/aws/01-ec2.png) | C6 |
| ⏳ pendiente | ![PENDIENTE-AWS: reglas del security group](evidencias/aws/02-sg.png) | C6 |
| ✅ | ![docker compose ps con réplicas en ec2](evidencias/aws/03-compose-ps.png) | C6 |
| ✅ | ![eureka vía túnel con 3/2/2 instancias](evidencias/aws/04-eureka.png) | C6 |
| ✅ | ![smoke test remoto exitoso desde laptop](evidencias/aws/05-smoke.png) | C6 |
| ✅ | ![reparto de requests por x-instance-id](evidencias/aws/06-balanceo.png) | C6 |
| ✅ | ![tolerancia a caída de réplica](evidencias/aws/07-failover.png) | C6 |
| ✅ | ![atm rechaza conexión sin certificado cliente](evidencias/aws/08-mtls.png) | C6 |
| ✅ | ![traza distribuida en zipkin](evidencias/aws/09-zipkin.png) | C6 |
| ✅ | ![docker stats: memoria y cpu reales de los contenedores (generada desde el log)](evidencias/aws/10-docker-stats.png) | C6 |
