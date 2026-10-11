# ADR-001 — Versiones de Spring Boot y Spring Cloud

- **Estado:** Aceptada (2026-10-09)
- **Criterios de la pauta:** C2 (decisiones justificadas), apoya C5

## Contexto

El enunciado exige Java 21, Spring Boot 3.x y un release train de Spring Cloud compatible, verificado en documentación oficial.
Según <https://spring.io/projects/spring-cloud> (consultado el 2026-10-09):

| Release train | Boot compatible | Estado |
|---|---|---|
| 2025.1.x "Oakwood" (última 2025.1.3) | 4.0.x, 4.1.x | Vigente |
| 2025.0.x "Northfields" (última 2025.0.3) | 3.5.x | End of life |

Verificado en Maven Central: `spring-boot-starter-parent:3.5.16` existe y es la última 3.5.x (3.5.17 no existe);
`spring-cloud-dependencies:2025.0.3` existe.

## Decisión

Fijar **Spring Boot 3.5.16 + Spring Cloud 2025.0.3**, Java 21, y las versiones que Boot gestiona
(Spring Batch 5.2.6, Spring Security 6.5.11, Spring Authorization Server 1.5.8, Spring Kafka 3.3.16,
Flyway 11.7.2, Testcontainers 1.21.4). springdoc-openapi **2.8.17** (línea 2.8.x para Boot 3).

## Justificación

- Respeta el requisito explícito "Spring Boot 3.x" del enunciado.
- Combina APIs estables y ampliamente documentadas (Spring Batch 5, Authorization Server como proyecto independiente),
  lo que reduce el riesgo de incompatibilidades en un proyecto académico con plazo cerrado.

## Consecuencias / riesgos

- La línea 3.5.x/2025.0.x está **fuera de soporte OSS**: no recibirá parches de seguridad gratuitos. Es aceptable para una
  demo académica; en producción se debe planificar la migración.
- Migración futura a Boot 4.x / Cloud 2025.1.x implica Spring Batch 6, Spring Security 7 (Authorization Server integrado),
  Jackson 3 y starters modularizados. Se documenta como "próximo paso" (mejoras del informe técnico, que se entrega por separado).
- JDK local de desarrollo: el equipo tiene JDK 17 por defecto; las compilaciones con `release 21` se hacen con un JDK 21+
  (p. ej. GraalVM 24 instalado) o dentro de Docker (imagen Maven con JDK 21).

## Alternativas descartadas

- **Boot 4.1.1 + Cloud 2025.1.3:** vigente y con soporte, pero se aparta del "3.x" pedido y añade riesgo de migración de APIs.
