#!/bin/bash
# Database per service: una base y un usuario por servicio (ninguno puede leer la base de otro).
# Se ejecuta UNA sola vez, al inicializar el volumen de PostgreSQL. Las contraseñas vienen del entorno (.env).
set -euo pipefail

create_db() { # $1 base, $2 usuario, $3 contraseña
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
    CREATE USER $2 WITH PASSWORD '$3';
    CREATE DATABASE $1 OWNER $2;
    REVOKE ALL ON DATABASE $1 FROM PUBLIC;
EOSQL
}

create_db customersdb customers "${CUSTOMERS_DB_PASSWORD:?falta CUSTOMERS_DB_PASSWORD}"
create_db accountsdb  accounts  "${ACCOUNTS_DB_PASSWORD:?falta ACCOUNTS_DB_PASSWORD}"
create_db paymentsdb  payments  "${PAYMENTS_DB_PASSWORD:?falta PAYMENTS_DB_PASSWORD}"
create_db batchdb     batch     "${BATCH_DB_PASSWORD:?falta BATCH_DB_PASSWORD}"
create_db atmdb       atm       "${ATM_DB_PASSWORD:?falta ATM_DB_PASSWORD}"
