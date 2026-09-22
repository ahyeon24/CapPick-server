-- API 명세 §4, ERD §4 Job. input_reference/lease_until 등은 아직 없다 (Job.java 클래스 주석 참고 —
-- 별도 워커 프로세스·블롭 스토리지가 생기기 전까지는 result_json에 결과를 직접 저장한다).
CREATE TABLE job (
    id VARCHAR(64) PRIMARY KEY,
    installation_id VARCHAR(64) NOT NULL REFERENCES installation(id),
    client_reference VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    kind VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    content_revision INT NOT NULL,
    result_json TEXT,
    error_code VARCHAR(50),
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    result_expires_at TIMESTAMPTZ,
    UNIQUE (installation_id, idempotency_key)
);

CREATE INDEX job_on_installation_status ON job (installation_id, status);
