-- accounts-service: base propia (database per service)
CREATE SEQUENCE account_number_seq START 1000000001;

CREATE TABLE account (
    id          UUID          PRIMARY KEY,
    customer_id UUID          NOT NULL,                 -- referencia lógica a customers-service (sin FK entre bases)
    number      VARCHAR(20)   NOT NULL UNIQUE,
    type        VARCHAR(10)   NOT NULL CHECK (type IN ('SAVINGS', 'CHECKING')),
    currency    VARCHAR(3)    NOT NULL DEFAULT 'CLP',
    balance     NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),   -- nunca sobregiro
    status      VARCHAR(8)    NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    opened_at   TIMESTAMPTZ   NOT NULL,
    closed_at   TIMESTAMPTZ,
    version     BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_account_customer ON account (customer_id);

-- Libro mayor: una fila por operación (clave de idempotencia = operation_id, p. ej. "<paymentId>:DEBIT").
-- Reintentos/reentregas devuelven el resultado ya registrado, sin volver a mover dinero.
CREATE TABLE account_operation (
    operation_id    VARCHAR(100)  PRIMARY KEY,
    account_id      UUID          NOT NULL,
    op_type         VARCHAR(6)    NOT NULL CHECK (op_type IN ('DEBIT', 'CREDIT')),
    amount          NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    outcome         VARCHAR(10)   NOT NULL CHECK (outcome IN ('PENDING', 'APPLIED', 'REJECTED')),
    reason_code     VARCHAR(40),
    balance_after   NUMERIC(18,2),
    reference       VARCHAR(64),                          -- paymentId
    movement_status VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (movement_status IN ('PENDING', 'CONFIRMED', 'REVERSED')),
    description     VARCHAR(200),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_operation_account ON account_operation (account_id, created_at DESC);
CREATE INDEX idx_operation_reference ON account_operation (reference);

-- Transactional Outbox (platform-common) y consumidores idempotentes
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
