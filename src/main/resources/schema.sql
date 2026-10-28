-- Failure #2: the durable idempotency record.
-- The primary key is the atomic "reserve this key" operation: two requests cannot both insert it.
CREATE TABLE IF NOT EXISTS idempotency_records (
    tenant_id           VARCHAR(64)  NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    payment_id          VARCHAR(64)  NOT NULL,
    provider_request_id VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    response_status     INT,
    response_body       VARCHAR(4000),
    locked_until        TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS payments (
    payment_id         VARCHAR(64) PRIMARY KEY,
    tenant_id          VARCHAR(64) NOT NULL,
    order_id           VARCHAR(64) NOT NULL,
    amount             BIGINT      NOT NULL,
    currency           CHAR(3)     NOT NULL,
    status             VARCHAR(16) NOT NULL,
    provider_charge_id VARCHAR(64) NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Failure #5: the version column backs the ETag / If-Match contract.
CREATE TABLE IF NOT EXISTS refunds (
    id         VARCHAR(64) PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL,
    amount     BIGINT      NOT NULL,
    currency   CHAR(3)     NOT NULL,
    status     VARCHAR(16) NOT NULL,
    version    BIGINT      NOT NULL,
    updated_by VARCHAR(64)
);
