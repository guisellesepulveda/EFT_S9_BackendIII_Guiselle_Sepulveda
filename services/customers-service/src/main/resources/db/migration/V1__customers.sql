-- customers-service: base propia (database per service). Ningún otro servicio accede a estas tablas.
CREATE TABLE customer (
    id              UUID         PRIMARY KEY,
    document_number VARCHAR(12)  NOT NULL UNIQUE,   -- RUT chileno sin puntos, p. ej. 12345678-5
    first_name      VARCHAR(80)  NOT NULL,
    last_name       VARCHAR(80)  NOT NULL,
    email           VARCHAR(160) NOT NULL UNIQUE,
    phone           VARCHAR(20),
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0
);

-- Proyección alimentada por el evento accounts.lifecycle (consistencia eventual con accounts-service)
CREATE TABLE customer_profile (
    customer_id          UUID        PRIMARY KEY REFERENCES customer (id) ON DELETE CASCADE,
    accounts_open        INT         NOT NULL DEFAULT 0,
    accounts_closed      INT         NOT NULL DEFAULT 0,
    last_account_event_at TIMESTAMPTZ
);

-- Consumidores idempotentes (por eventId)
CREATE TABLE processed_event (
    consumer     VARCHAR(100) NOT NULL,
    event_id     VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- Notificaciones (transactions.completed): "envío" simulado → registro
CREATE TABLE notification_log (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(64) NOT NULL UNIQUE,
    payment_id  UUID        NOT NULL,
    channel     VARCHAR(20) NOT NULL,
    message     VARCHAR(300) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT now()
);

-- Alertas de seguridad (security.alerts)
CREATE TABLE security_alert (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(64)  NOT NULL UNIQUE,
    source      VARCHAR(50)  NOT NULL,
    severity    VARCHAR(10)  NOT NULL,
    alert_type  VARCHAR(60)  NOT NULL,
    subject     VARCHAR(120),
    detail      VARCHAR(500),
    occurred_at TIMESTAMPTZ  NOT NULL,
    received_at TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_security_alert_subject ON security_alert (subject, occurred_at DESC);

-- Eventos de monitoreo de batch (batch.job.events)
CREATE TABLE batch_job_event (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(64)  NOT NULL UNIQUE,
    event_type  VARCHAR(60)  NOT NULL,
    job_name    VARCHAR(100) NOT NULL,
    status      VARCHAR(20),
    attempt     INT,
    will_retry  BOOLEAN,
    occurred_at TIMESTAMPTZ  NOT NULL,
    payload     TEXT         NOT NULL
);
