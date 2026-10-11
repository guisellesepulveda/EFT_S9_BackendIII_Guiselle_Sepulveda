#!/usr/bin/env bash
# Genera el Dockerfile multi-stage de cada módulo desplegable a partir de UNA plantilla (misma etapa "build" en todos:
# BuildKit la cachea y el reactor se compila una vez). Uso: scripts/gen-dockerfiles.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# módulo : puerto del health check (el de gestión para gateway y BFF, que sirven HTTPS en el puerto público)
MODULES=(
  "infra/config-server:8888"
  "infra/discovery-server:8761"
  "infra/auth-server:9000"
  "infra/api-gateway:18446"
  "services/customers-service:8081"
  "services/accounts-service:8082"
  "services/payments-service:8083"
  "bff/bff-web:18443"
  "bff/bff-mobile:18444"
  "bff/bff-atm:18445"
  "batch/batch-service:8085"
)

for entry in "${MODULES[@]}"; do
  module="${entry%%:*}"; health="${entry##*:}"; name="$(basename "$module")"
  case "$name" in   # gateway y BFF sirven HTTPS en su puerto público
    api-gateway) expose=8446 ;; bff-web) expose=8443 ;; bff-mobile) expose=8444 ;; bff-atm) expose=8445 ;; *) expose="$health" ;;
  esac
  cat > "$module/Dockerfile" <<EOF
# syntax=docker/dockerfile:1.7
# $name — imagen multi-stage. Contexto de build: RAÍZ del repo (docker compose build $name).
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
COPY --from=build --chown=app:app /build/$module/target/$name-*.jar /app/app.jar

# JVM consciente del contenedor: el heap se calcula sobre el límite de memoria del contenedor (no del host)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
USER app
EXPOSE $expose
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=6 \\
  CMD wget -qO- http://localhost:$health/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
EOF
  echo "generado $module/Dockerfile"
done
