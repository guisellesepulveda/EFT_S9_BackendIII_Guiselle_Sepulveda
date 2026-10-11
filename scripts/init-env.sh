#!/usr/bin/env bash
# Prepara el entorno local en un paso: .env con secretos aleatorios (a partir de .env.example) y certificados de desarrollo
# (certs/out). No sobreescribe un .env ni unos certificados existentes.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  echo ".env ya existe: no se modifica (bórrelo para regenerarlo)"
else
while IFS= read -r line; do
  while [[ "$line" == *CHANGE_ME* ]]; do
    line="${line/CHANGE_ME/$(openssl rand -hex 16)}"
  done
  printf '%s\n' "$line"
done < .env.example > .env
chmod 600 .env
echo ".env generado con secretos aleatorios (no se versiona)"
fi

if [[ -f certs/out/server.p12 ]]; then
  echo "certs/out ya existe: no se regenera (bórrelo para regenerar)"
else
  bash certs/generate-dev-certs.sh
fi
