# ADR-005 — Mensajería con Kafka: saga coreografiada, Transactional Outbox, idempotencia y DLT

- **Estado:** Aceptada (2026-10-10)
- **Criterios de la pauta:** C1 (proceso 5), C2 (disponibilidad/consistencia), C5

## Contexto

Una transferencia toca dos cuentas (posiblemente de clientes distintos) y un servicio de pagos. No hay transacción
distribuida (cada servicio tiene su base) y los servicios pueden estar caídos en cualquier momento.

## Decisión

**Saga coreografiada** con eventos versionados (`eventId`, `eventType`, `occurredAt`, `version`, `payload`; clave = `paymentId`,
por lo que el orden por pago está garantizado dentro de una partición):

```
payments: PENDING ──(outbox)──► transactions.requested
accounts: DÉBITO origen ──► transactions.debited ──► CRÉDITO destino ──► transactions.credited
payments: ──(outbox)──► transactions.completed ──► accounts (confirma movimientos), customers (notificación)
cualquier paso rechazado ──► transactions.failed ──► payments: FAILED, o COMPENSATING → reembolso idempotente → COMPENSATED
```

| Tema | Decisión |
|---|---|
| Tópicos | Los 5 de la especificación (`transactions.completed`, `transactions.failed`, `security.alerts`, `accounts.lifecycle`, `batch.job.events`) más **3 propios de la saga** (`transactions.requested`, `.debited`, `.credited`), todos con 3 particiones y su `<topic>.DLT`. Desviación consciente de la tabla original: `transactions.failed` también lo produce `accounts-service` (es quien detecta saldo insuficiente o cuenta cerrada); payments es el consumidor y ejecuta la compensación |
| Consistencia productor | **Transactional Outbox** en payments y accounts: el cambio de negocio y el evento se confirman en la MISMA transacción; un publicador (`FOR UPDATE SKIP LOCKED`, seguro con varias instancias) los envía a Kafka. Entrega "al menos una vez" |
| Idempotencia consumidor | `processed_event(consumer, eventId)` en la misma transacción que el efecto + operaciones idempotentes por `operationId` (`<paymentId>:DEBIT`, `:CREDIT`, `:REFUND`) en el libro mayor; transiciones de estado condicionales en payments |
| Errores | `DefaultErrorHandler` con backoff exponencial y `DeadLetterPublishingRecoverer` → `<topic>.DLT`; mensajes ilegibles van directo a la DLT (no reintentables) |
| Compensación | Si falla el crédito tras el débito, payments reembolsa con una llamada interna idempotente. Si accounts está caído: Resilience4j (4 intentos) → reintento de Kafka → DLT; el pago queda `COMPENSATING` y el reintentador programado lo retoma. Reembolso rechazado → `FAILED` + alerta `HIGH` en `security.alerts` |
| Pagos sin avance | `PaymentRetrier` reemite `transaction.requested` de pagos `PENDING` viejos (accounts reproduce el resultado sin mover dinero otra vez); agotados los intentos → `FAILED` `TIMEOUT_REVIEW_REQUIRED` |
| Idempotencia del cliente | Cabecera `Idempotency-Key` + huella del cuerpo (misma clave y cuerpo → mismo pago; cuerpo distinto → 422) |
| Trazas | El `traceparent` se guarda con el evento del outbox y se restaura al publicar; Kafka observation continúa la traza en los consumidores (una sola traza gateway → payments → accounts → customers) |

## Justificación

- Disponibilidad: el alta de un pago nunca depende de que accounts esté arriba; el evento espera en Kafka/outbox.
- Corrección del dinero: el débito es un `UPDATE` condicional atómico (`balance >= monto`) con `CHECK (balance >= 0)`; la
  idempotencia por `operationId` hace seguro cualquier reintento, reentrega o reproceso.
- Se descartó 2PC/XA por acoplamiento y disponibilidad, y una saga **orquestada** porque obliga a un servicio central a conocer
  todos los pasos (aquí cada servicio solo conoce los eventos que consume).

## Consecuencias

- Los estados intermedios son visibles (`PENDING`, `DEBITED`, `COMPENSATING`): los clientes consultan `GET /payments/{id}`; el BFF ATM
  **nunca dispensa dinero hasta ver `COMPLETED`**.
- accounts publica por outbox, así que un crash entre commit y publicación no pierde el evento.
- Riesgo residual documentado: un pago que agota reintentos sin respuesta queda `FAILED` pendiente de revisión manual (no se
  asume que no se debitó). El plan de mejora es una conciliación periódica contra accounts.

## Alternativas descartadas

- **Publicar a Kafka dentro de la transacción de negocio (dual write):** puede perder o duplicar eventos si Kafka o la BD fallan entre medio.
- **Debezium/CDC sobre el outbox:** más robusto a gran escala, pero añade componentes (Connect); el publicador programado basta aquí.
- **Mensajes con esquema Avro/Schema Registry:** más estricto; se prefirió JSON versionado por simplicidad operativa.
