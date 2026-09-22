-- ERD v0.2 §4 Installation + App Attest 등록에 필요한 challenge 테이블(ERD에는 없는 구현 세부사항,
-- API 명세 §3 "설치 등록 검증 준비" 단계를 위해 추가). credential은 원문을 저장하지 않고 해시만 저장한다.

CREATE TABLE installation (
    id VARCHAR(64) PRIMARY KEY,
    credential_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    key_id VARCHAR(255) NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE installation_challenge (
    id VARCHAR(64) PRIMARY KEY,
    challenge VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

CREATE INDEX installation_challenge_expires_at ON installation_challenge (expires_at);
