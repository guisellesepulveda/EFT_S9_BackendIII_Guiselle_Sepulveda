-- Esquema de dominio de batch-service. Los metadatos de Spring Batch (BATCH_JOB_INSTANCE, ...)
-- los crea Spring Boot (spring.batch.jdbc.initialize-schema=always) en la misma base.
--
-- Convención: job_instance_id (id de BATCH_JOB_INSTANCE) acota los datos a una instancia de job.
-- Un restart reutiliza la instancia, por lo que las claves únicas con job_instance_id hacen que las
-- escrituras sean idempotentes (ON CONFLICT DO NOTHING) y que un restart nunca duplique datos.

-- ---------------------------------------------------------------- transversales
CREATE TABLE batch_rejected_record (
    id              BIGSERIAL PRIMARY KEY,
    job_name        VARCHAR(100)  NOT NULL,
    job_instance_id BIGINT        NOT NULL,
    job_execution_id BIGINT       NOT NULL,
    step_name       VARCHAR(150)  NOT NULL,
    line_number     BIGINT        NOT NULL,
    disposition     VARCHAR(10)   NOT NULL CHECK (disposition IN ('SKIPPED', 'FILTERED')),
    reason_code     VARCHAR(200)  NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    raw_content     TEXT,
    rejected_at     TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_rejected_line UNIQUE (job_instance_id, line_number)
);
CREATE INDEX idx_rejected_job ON batch_rejected_record (job_name, job_execution_id);

CREATE TABLE job_execution_report (
    id               BIGSERIAL PRIMARY KEY,
    job_name         VARCHAR(100) NOT NULL,
    job_instance_id  BIGINT       NOT NULL,
    job_execution_id BIGINT       NOT NULL,
    step_name        VARCHAR(150) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    read_count       BIGINT       NOT NULL,
    write_count      BIGINT       NOT NULL,
    skip_count       BIGINT       NOT NULL,
    filter_count     BIGINT       NOT NULL,
    retry_count      BIGINT       NOT NULL DEFAULT 0,
    commit_count     BIGINT       NOT NULL,
    duration_ms      BIGINT       NOT NULL,
    reported_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_report UNIQUE (job_execution_id, step_name)
);

-- ---------------------------------------------------------------- dailyTransactionReportJob
CREATE TABLE daily_transaction (
    job_instance_id BIGINT        NOT NULL,
    tx_id           BIGINT        NOT NULL,
    line_number     BIGINT        NOT NULL,
    tx_date         DATE          NOT NULL,
    tipo            VARCHAR(20)   NOT NULL,
    monto           NUMERIC(18,2) NOT NULL,
    outlier         BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (job_instance_id, tx_id)
);

CREATE TABLE daily_transaction_summary (
    job_instance_id BIGINT        NOT NULL,
    summary_date    DATE          NOT NULL,
    tipo            VARCHAR(20)   NOT NULL,
    tx_count        BIGINT        NOT NULL,
    total_amount    NUMERIC(18,2) NOT NULL,
    max_amount      NUMERIC(18,2) NOT NULL,
    PRIMARY KEY (job_instance_id, summary_date, tipo)
);

CREATE TABLE transaction_anomaly (
    id              BIGSERIAL PRIMARY KEY,
    job_instance_id BIGINT        NOT NULL,
    line_number     BIGINT        NOT NULL,
    source_id       VARCHAR(50),
    anomaly_type    VARCHAR(40)   NOT NULL,
    detail          VARCHAR(500),
    detected_at     TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_anomaly UNIQUE (job_instance_id, line_number, anomaly_type)
);

-- ---------------------------------------------------------------- monthlyInterestJob
CREATE TABLE interest_calculation (
    id              BIGSERIAL PRIMARY KEY,
    job_instance_id BIGINT        NOT NULL,
    line_number     BIGINT        NOT NULL,
    cuenta_id       BIGINT        NOT NULL,
    nombre          VARCHAR(100)  NOT NULL,
    tipo            VARCHAR(20)   NOT NULL,
    periodo         VARCHAR(12)   NOT NULL,
    saldo_anterior  NUMERIC(18,2) NOT NULL,
    tasa_anual      NUMERIC(9,6)  NOT NULL,
    interes         NUMERIC(18,2) NOT NULL,
    saldo_nuevo     NUMERIC(18,2) NOT NULL,
    dedup_key       VARCHAR(300)  NOT NULL,
    calculated_at   TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uq_interest_dedup UNIQUE (job_instance_id, dedup_key)
);
CREATE INDEX idx_interest_cuenta ON interest_calculation (job_instance_id, cuenta_id, line_number);

CREATE TABLE account_balance (
    cuenta_id         BIGINT        PRIMARY KEY,
    nombre            VARCHAR(100)  NOT NULL,
    tipo              VARCHAR(20)   NOT NULL,
    saldo             NUMERIC(18,2) NOT NULL,
    last_periodo      VARCHAR(12)   NOT NULL,
    last_job_instance_id BIGINT     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- annualStatementJob
CREATE TABLE annual_transaction (
    job_instance_id BIGINT        NOT NULL,
    line_number     BIGINT        NOT NULL,
    cuenta_id       BIGINT        NOT NULL,
    tx_date         DATE          NOT NULL,
    tipo            VARCHAR(20)   NOT NULL,
    monto           NUMERIC(18,2) NOT NULL,
    descripcion     VARCHAR(300)  NOT NULL,
    dedup_key       VARCHAR(400)  NOT NULL,
    PRIMARY KEY (job_instance_id, line_number),
    CONSTRAINT uq_annual_dedup UNIQUE (job_instance_id, dedup_key)
);
CREATE INDEX idx_annual_cuenta ON annual_transaction (job_instance_id, cuenta_id);

CREATE TABLE annual_statement (
    job_instance_id   BIGINT        NOT NULL,
    cuenta_id         BIGINT        NOT NULL,
    statement_year    INT           NOT NULL,
    total_deposits    NUMERIC(18,2) NOT NULL,
    total_withdrawals NUMERIC(18,2) NOT NULL,
    total_purchases   NUMERIC(18,2) NOT NULL,
    final_balance     NUMERIC(18,2) NOT NULL,
    tx_count          BIGINT        NOT NULL,
    PRIMARY KEY (job_instance_id, cuenta_id, statement_year)
);
