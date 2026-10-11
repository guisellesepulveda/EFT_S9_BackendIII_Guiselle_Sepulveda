# syntax=docker/dockerfile:1.7
# customers-service — imagen multi-stage. Contexto de build: RAÍZ del repo (docker compose build customers-service).
# La etapa "build" es idéntica en todos los módulos: BuildKit la cachea y el reactor Maven se compila una sola vez.

# ---------- Etapa 1: compilación (Maven + JDK 21) ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true package

# ---------- Etapa 2: ejecución (JRE 21 slim, usuario no-root) ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app -h /app app && mkdir -p /app/outputs && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /build/services/customers-service/target/customers-service-*.jar /app/app.jar

# JVM consciente del contenedor: el heap se calcula sobre el límite de memoria del contenedor (no del host)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
USER app
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=6 \
  CMD wget -qO- http://localhost:8081/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
