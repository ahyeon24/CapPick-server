package com.cappick.domain.installation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설치 등록 challenge (API 명세 §3: "짧게 유효하고 한 번만 사용"). ERD에는 없는 구현 세부사항 —
 * App Attest의 clientDataHash에 들어갈 서버 발급 nonce를 짧게 보관한다.
 */
@Entity
@Table(name = "installation_challenge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstallationChallenge {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String challenge;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Builder
    public InstallationChallenge(String id, String challenge, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.challenge = challenge;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }
}
