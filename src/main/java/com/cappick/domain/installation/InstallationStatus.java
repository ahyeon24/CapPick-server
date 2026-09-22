package com.cappick.domain.installation;

// ERD §4는 값 집합을 명시하지 않는다 — 구현에 필요한 최소값(ACTIVE/REVOKED)만 정의.
public enum InstallationStatus {
    ACTIVE,
    REVOKED
}
