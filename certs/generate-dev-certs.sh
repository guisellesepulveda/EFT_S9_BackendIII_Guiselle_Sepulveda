#!/usr/bin/env bash
# Certificados de DESARROLLO para HTTPS y mTLS (no usar en producción; en AWS se reemplazan por ACM).
#   scripts: certs/generate-dev-certs.sh [directorio_salida]      (por defecto certs/out, ignorado por git)
# Genera:
#   ca.crt / ca.key        → autoridad certificadora propia (CA)
#   server.p12             → certificado de SERVIDOR firmado por la CA (SAN: localhost, 127.0.0.1 y los nombres de servicio
#                            del compose) usado por api-gateway, bff-web, bff-mobile y bff-atm
#   atm-0001.p12           → certificado de CLIENTE del cajero ATM-0001 (CN=ATM-0001) para mTLS contra bff-atm
#   rogue-atm.p12          → certificado de cliente firmado por OTRA CA (para probar que bff-atm lo rechaza)
#   truststore.p12         → almacén de confianza con la CA (lo usan los servidores para validar certificados de cliente)
# CERT_EXTRA_SAN="IP:203.0.113.10,DNS:ec2-x.compute.amazonaws.com" agrega nombres al certificado de servidor (despliegue en EC2).
# Contraseñas: KEYSTORE_PASSWORD y TRUSTSTORE_PASSWORD del entorno (.env); si faltan, se leen de ../.env.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-certs/out}"

if [[ -z "${KEYSTORE_PASSWORD:-}" || -z "${TRUSTSTORE_PASSWORD:-}" ]] && [[ -f .env ]]; then
  set -a; source .env; set +a
fi
: "${KEYSTORE_PASSWORD:?defina KEYSTORE_PASSWORD (ver .env.example)}"
: "${TRUSTSTORE_PASSWORD:?defina TRUSTSTORE_PASSWORD (ver .env.example)}"

mkdir -p "$OUT"
cd "$OUT"
rm -f ./*.p12 ./*.crt ./*.key ./*.csr ./*.srl ./*.cnf

echo "== CA de desarrollo"
openssl req -x509 -newkey rsa:4096 -sha256 -days 825 -nodes -keyout ca.key -out ca.crt \
  -subj "/O=Banco XYZ (dev)/CN=Banco XYZ Dev CA" 2>/dev/null

echo "== Certificado de servidor (gateway y BFF)"
cat > server.cnf <<CNF
[req]
distinguished_name = dn
prompt = no
[dn]
O = Banco XYZ (dev)
CN = localhost
[ext]
subjectAltName = DNS:localhost, DNS:api-gateway, DNS:bff-web, DNS:bff-mobile, DNS:bff-atm, IP:127.0.0.1${CERT_EXTRA_SAN:+, $CERT_EXTRA_SAN}
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
CNF
openssl req -new -newkey rsa:2048 -nodes -keyout server.key -out server.csr -config server.cnf 2>/dev/null
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 365 -sha256 -extfile server.cnf -extensions ext -out server.crt 2>/dev/null
openssl pkcs12 -export -in server.crt -inkey server.key -certfile ca.crt -name server -out server.p12 -passout "pass:$KEYSTORE_PASSWORD"

echo "== Certificado de cliente del cajero ATM-0001 (mTLS)"
cat > client.cnf <<CNF
[req]
distinguished_name = dn
prompt = no
[dn]
O = Banco XYZ (dev)
OU = Cajeros
CN = ATM-0001
[ext]
keyUsage = digitalSignature
extendedKeyUsage = clientAuth
CNF
openssl req -new -newkey rsa:2048 -nodes -keyout atm-0001.key -out atm-0001.csr -config client.cnf 2>/dev/null
openssl x509 -req -in atm-0001.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 365 -sha256 -extfile client.cnf -extensions ext -out atm-0001.crt 2>/dev/null
openssl pkcs12 -export -in atm-0001.crt -inkey atm-0001.key -certfile ca.crt -name atm-0001 -out atm-0001.p12 -passout "pass:$KEYSTORE_PASSWORD"

echo "== Certificado de cliente NO confiable (CA ajena) para pruebas negativas"
openssl req -x509 -newkey rsa:2048 -sha256 -days 30 -nodes -keyout rogue-ca.key -out rogue-ca.crt -subj "/O=Atacante/CN=Rogue CA" 2>/dev/null
sed 's/CN = ATM-0001/CN = ATM-ROGUE/' client.cnf > rogue.cnf
openssl req -new -newkey rsa:2048 -nodes -keyout rogue.key -out rogue.csr -config rogue.cnf 2>/dev/null
openssl x509 -req -in rogue.csr -CA rogue-ca.crt -CAkey rogue-ca.key -CAcreateserial -days 30 -sha256 -extfile rogue.cnf -extensions ext -out rogue.crt 2>/dev/null
openssl pkcs12 -export -in rogue.crt -inkey rogue.key -name rogue-atm -out rogue-atm.p12 -passout "pass:$KEYSTORE_PASSWORD"

echo "== Truststore (solo la CA de Banco XYZ)"
keytool -importcert -noprompt -alias banco-xyz-ca -file ca.crt -keystore truststore.p12 -storetype PKCS12 \
  -storepass "$TRUSTSTORE_PASSWORD" >/dev/null

rm -f ./*.csr ./*.srl ./*.cnf rogue-ca.key
chmod 600 ./*.key ./*.p12
echo "Listo en $OUT:"; ls -1 | sed 's/^/  /'
echo "Servidor : $(openssl x509 -in server.crt -noout -subject -ext subjectAltName 2>/dev/null | tr '\n' ' ')"
echo "Cliente  : $(openssl x509 -in atm-0001.crt -noout -subject)"
