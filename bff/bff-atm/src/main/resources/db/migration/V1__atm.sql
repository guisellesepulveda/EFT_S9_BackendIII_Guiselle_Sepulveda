-- bff-atm: base propia (database per service). Estado mínimo y auditable de las operaciones de cajero.

-- Tarjetas (emisión simulada): el PIN NUNCA se guarda en claro, solo su hash BCrypt
CREATE TABLE atm_card (
    card_id         VARCHAR(32)  PRIMARY KEY,
    account_id      UUID         NOT NULL,
    pin_hash        VARCHAR(100) NOT NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Retiros: la clave de idempotencia es la PK (un reintento del cajero nunca duplica un retiro)
CREATE TABLE atm_withdrawal (
    idempotency_key VARCHAR(64)   PRIMARY KEY,
    atm_id          VARCHAR(40)   NOT NULL,
    card_id         VARCHAR(32)   NOT NULL,
    account_id      UUID          NOT NULL,
    amount          NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    payment_id      UUID,
    status          VARCHAR(12)   NOT NULL CHECK (status IN ('REQUESTED', 'PENDING', 'APPROVED', 'DECLINED')),
    reason          VARCHAR(60),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_withdrawal_card_day ON atm_withdrawal (card_id, created_at);

-- Auditoría de CADA operación (consulta, retiro, PIN fallido, bloqueo, rechazo...). Solo inserciones.
CREATE TABLE atm_audit (
    id              BIGSERIAL    PRIMARY KEY,
    at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atm_id          VARCHAR(40),
    operation       VARCHAR(30)  NOT NULL,
    card_ref        VARCHAR(16),                 -- tarjeta enmascarada (****0001)
    amount          NUMERIC(18,2),
    outcome         VARCHAR(30)  NOT NULL,
    detail          VARCHAR(200),
    payment_id      UUID,
    idempotency_key VARCHAR(64),
    trace_id        VARCHAR(40)
);
CREATE INDEX idx_audit_at ON atm_audit (at DESC);

-- Transactional Outbox (platform-common): alertas a security.alerts atómicas con la auditoría
CREATE TABLE outbox_event (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     VARCHAR(64)  NOT NULL UNIQUE,
    topic        VARCHAR(100) NOT NULL,
    msg_key      VARCHAR(100),
    event_type   VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    published_at TIMESTAMP,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   TEXT,
    traceparent  VARCHAR(100)
);
CREATE INDEX idx_outbox_pending ON outbox_event (id) WHERE published_at IS NULL;
