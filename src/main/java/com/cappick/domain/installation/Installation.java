package com.cappick.domain.installation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원이 아니라 검증된 앱 설치의 서버 요청 주체 (ERD v0.2 §4, 시스템 설계 §7).
 * credential은 원문을 저장하지 않고 SHA-256 해시만 저장한다 — 유출돼도 재사용할 수 없게.
 * keyId는 App Attest로 등록한 키 식별자로, 자격 갱신 시 새 assertion을 이 키로 검증한다.
 */
@Entity
@Table(name = "installation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Installation {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "credential_hash", nullable = false, unique = true, length = 64)
    private String credentialHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InstallationStatus status;

    @Column(name = "key_id", nullable = false, unique = true)
    private String keyId;

    /** App Attest 키의 공개키(원시 EC 포인트, Base64). 자격 갱신 시 assertion 서명 검증에 쓴다. */
    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKeyBase64;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Builder
    public Installation(String id, String credentialHash, String keyId, String publicKeyBase64, Instant createdAt) {
        this.id = id;
        this.credentialHash = credentialHash;
        this.keyId = keyId;
        this.publicKeyBase64 = publicKeyBase64;
        this.status = InstallationStatus.ACTIVE;
        this.signCount = 0;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
    }

    /** 자격 갱신 (API 명세 §3 credentials/renew). 새 assertion의 카운터로 signCount를 갱신한다. */
    public void rotateCredential(String newCredentialHash, long newSignCount, Instant now) {
        requireActive();
        this.credentialHash = newCredentialHash;
        this.signCount = newSignCount;
        this.lastSeenAt = now;
    }

    /** App Attest assertion의 카운터가 이전 값보다 커야 한다 — 같거나 작으면 키 복제·재전송 의심 (Apple 문서 기준). */
    public boolean isReplayedCounter(long counter) {
        return counter <= this.signCount;
    }

    public void touch(Instant now) {
        this.lastSeenAt = now;
    }

    public void revoke() {
        this.status = InstallationStatus.REVOKED;
    }

    public boolean isActive() {
        return status == InstallationStatus.ACTIVE;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("revoked installation: " + id);
        }
    }
}
