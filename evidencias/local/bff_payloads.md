# Comparación de tamaño de payload por canal (C4)

> Generado por `scripts/bff-flow.sh` (2026-10-10 01:02). Mismo cliente, mismas 2 cuentas y los mismos últimos movimientos.
> Los bytes son los del **cuerpo tal como viaja por la red** (`size_download` de curl).

| Variante | Llamadas del cliente | Bytes | Reducción vs. sin BFF |
|---|---:|---:|---:|
| Sin BFF: el cliente arma el resumen llamando al gateway (cliente + perfil + cuentas + movimientos de cada cuenta abierta) | 5 | 3268 | — |
| **BFF web** `GET /web/dashboard?movements=5` (cliente + perfil + cuentas + movimientos agregados en paralelo) | 1 | 2373 | 27 % |
| BFF web con gzip | 1 | 2373 | 27 % |
| **BFF móvil** `GET /mobile/home` (solo saldo total y campos esenciales por cuenta) | 1 | 252 | 92 % |
| BFF móvil con gzip (`Accept-Encoding: gzip`) | 1 | 252 | 92 % |
| BFF móvil `GET /mobile/accounts?fields=number,balance` (respuesta parcial) | 1 | 91 | 97 % |
| BFF móvil con `If-None-Match` (ETag vigente) | 1 | 0 (HTTP 304) | 100 % |
| **BFF ATM** `POST /atm/balance` (cuenta enmascarada, saldo y moneda) | 1 | 96 | 97 % |

**Tiempo de obtener el resumen (misma máquina, gateway local):** sin BFF, 5 llamadas secuenciales ≈ **61 ms**; con el BFF web, 1 llamada
(servicios consultados en paralelo) ≈ **26 ms**. En el canal web la ventaja es menos viajes de red y latencia, no menos bytes: necesita los datos completos.

**Lectura:** el canal web recibe los datos completos y agregados en una sola llamada; el móvil recibe lo mínimo y, además, comprimido,
con validación por ETag y campos a pedido; el cajero recibe solo lo imprescindible para operar. Las cifras dependen de los datos de la
corrida (nombres, cantidad de cuentas y movimientos), pero la relación entre canales se mantiene.
