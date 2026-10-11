-- payments-service: base propia (database per service)
CREATE TABLE payment (
    id               UUID          PRIMARY KEY,
    type             VARCHAR(12)   NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    from_account_id  UUID,
    to_account_id    UUID,
    amount           NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    currency         VARCHAR(3)    NOT NULL DEFAULT 'CLP',
    status           VARCHAR(14)   NOT NULL CHECK (status IN ('PENDING', 'DEBITED', 'COMPLETED', 'FAILED', 'COMPENSATING', 'COMPENSATED')),
    description      VARCHAR(200),
    idempotency_key  VARCHAR(100)  UNIQUE,              -- cabecera Idempotency-Key del cliente
    request_hash     VARCHAR(64),                        -- huella del cuerpo para detectar reutilización de la clave
    degraded         BOOLEAN       NOT NULL DEFAULT FALSE, -- se validó con caché o se difirió la validación
    degraded_reason  VARCHAR(100),
    failure_code     VARCHAR(60),
    failure_reason   VARCHAR(300),
    request_attempts INT           NOT NULL DEFAULT 1,
    requested_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);
CREATE INDEX idx_payment_from ON payment (from_account_id, requested_at DESC);
CREATE INDEX idx_payment_to ON payment (to_account_id, requested_at DESC);
CREATE INDEX idx_payment_status_updated ON payment (status, updated_at) WHERE status IN ('PENDING', 'COMPENSATING');

-- Historial (auditoría) de cada cambio de estado del pago
CREATE TABLE payment_history (
    id         BIGSERIAL    PRIMARY KEY,
    payment_id UUID         NOT NULL REFERENCES payment (id),
    status     VARCHAR(14)  NOT NULL,
    detail     VARCHAR(300),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_history_payment ON payment_history (payment_id, id);

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
    last_error   TEXT
);
CREATE INDEX idx_outbox_pending ON outbox_event (id) WHERE published_at IS NULL;

CREATE TABLE processed_event (
    consumer     VARCHAR(100) NOT NULL,
    event_id     VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
