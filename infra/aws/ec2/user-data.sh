#!/bin/bash
# Bootstrap de la instancia (Amazon Linux 2023). Se pasa como --user-data al lanzar la EC2 (ver despliegue.md) y corre UNA vez
# como root al primer arranque. Es idempotente: se puede volver a ejecutar a mano (sudo bash user-data.sh) sin efectos duplicados.
# Instala Docker + Compose + Git + Java 21 (keytool: certs/generate-dev-certs.sh crea el truststore) y deja el repo listo.
# NO levanta nada: eso lo hace deploy.sh. Log: /var/log/banco-xyz-bootstrap.log
set -euo pipefail
exec > >(tee -a /var/log/banco-xyz-bootstrap.log) 2>&1
echo "=== bootstrap banco-xyz $(date -u +%FT%TZ) ==="

APP_USER="${APP_USER:-ec2-user}"
APP_DIR="/home/$APP_USER/banco-xyz"
REPO_URL="${REPO_URL:-}"     # URL del repositorio en GitHub (opcional): el flujo documentado sube el código con infra/aws/ec2/push.sh y no necesita clonar

# --- paquetes base ---
dnf install -y docker git java-21-amazon-corretto-headless python3 openssl rsync jq

# --- Docker ---
systemctl enable --now docker
id -nG "$APP_USER" | grep -qw docker || usermod -aG docker "$APP_USER"

# --- Docker Compose v2 (plugin). Se toma la última versión estable; para fijarla: COMPOSE_VERSION=vX.Y.Z ---
PLUGIN_DIR=/usr/local/lib/docker/cli-plugins
if ! docker compose version >/dev/null 2>&1; then
  mkdir -p "$PLUGIN_DIR"
  ARCH="$(uname -m)"   # x86_64 | aarch64
  if [[ -n "${COMPOSE_VERSION:-}" ]]; then
    URL="https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-${ARCH}"
  else
    URL="https://github.com/docker/compose/releases/latest/download/docker-compose-linux-${ARCH}"
  fi
  curl -fsSL "$URL" -o "$PLUGIN_DIR/docker-compose"
  chmod +x "$PLUGIN_DIR/docker-compose"
fi
docker compose version

# --- Docker Buildx (BuildKit): los Dockerfile usan RUN --mount=type=cache. Se toma la última versión; para fijarla: BUILDX_VERSION=vX.Y.Z ---
# Compose v5 exige buildx >= 0.17 y el paquete docker de AL2023 trae 0.12.1: se reemplaza si es más antiguo (el de /usr/local tiene prioridad).
BX_CUR="$(docker buildx version 2>/dev/null | awk '{print $2}' | sed 's/^v//')"
if [[ -z "$BX_CUR" || "$(printf '0.17.0\n%s\n' "$BX_CUR" | sort -V | head -1)" != "0.17.0" ]]; then
  mkdir -p "$PLUGIN_DIR"
  BX_TAG="${BUILDX_VERSION:-$(curl -fsSL https://api.github.com/repos/docker/buildx/releases/latest | jq -r .tag_name)}"
  case "$(uname -m)" in x86_64) BX_ARCH=amd64 ;; aarch64) BX_ARCH=arm64 ;; *) echo "arquitectura no soportada: $(uname -m)"; exit 1 ;; esac
  curl -fsSL "https://github.com/docker/buildx/releases/download/${BX_TAG}/buildx-${BX_TAG}.linux-${BX_ARCH}" -o "$PLUGIN_DIR/docker-buildx"
  chmod +x "$PLUGIN_DIR/docker-buildx"
fi
docker buildx version

# --- Repositorio (opcional: también se puede subir con push.sh desde el portátil) ---
if [[ -n "$REPO_URL" ]]; then
  if [[ -d "$APP_DIR/.git" ]]; then
    sudo -u "$APP_USER" git -C "$APP_DIR" pull --ff-only
  else
    sudo -u "$APP_USER" git clone "$REPO_URL" "$APP_DIR"
  fi
else
  echo "REPO_URL no definida: suba el código con infra/aws/ec2/push.sh"
  sudo -u "$APP_USER" mkdir -p "$APP_DIR"
fi

echo "=== bootstrap terminado: conéctese por SSH y ejecute infra/aws/ec2/deploy.sh (ver despliegue.md) ==="
