-- API 명세 §7, ERD §4 UsageEntry·DeletionRequest.
CREATE TABLE usage_entry (
    id VARCHAR(64) PRIMARY KEY,
    installation_id VARCHAR(64) NOT NULL REFERENCES installation(id),
    job_id VARCHAR(64) REFERENCES job(id),
    operation_key VARCHAR(255) NOT NULL,
    category VARCHAR(30) NOT NULL,
    units INT NOT NULL,
    state VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX usage_entry_on_installation_operation_key ON usage_entry (installation_id, operation_key);

CREATE TABLE deletion_request (
    id VARCHAR(64) PRIMARY KEY,
    installation_id VARCHAR(64) NOT NULL REFERENCES installation(id),
    idempotency_key VARCHAR(255) NOT NULL,
    scope VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(50),
    UNIQUE (installation_id, idempotency_key)
);

CREATE INDEX deletion_request_on_installation_status ON deletion_request (installation_id, status);
