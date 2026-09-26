package com.cappick.domain.deletion;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.cappick.domain.installation.Installation;

/**
 * 설치 콘텐츠 삭제 요청 추적 (ERD §4, API 명세 §3·§7). "사용량 보호 기록과 별도 정책"이라는
 * ERD 설명대로, 삭제가 완료돼도 {@link com.cappick.domain.usage.UsageEntry}는 건드리지 않는다.
 */
@Entity
@Table(name = "deletion_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeletionRequest {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installation_id", nullable = false)
    private Installation installation;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeletionRequestScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeletionRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Builder
    public DeletionRequest(
        String id, Installation installation, String idempotencyKey, DeletionRequestScope scope, Instant requestedAt
    ) {
        this.id = id;
        this.installation = installation;
        this.idempotencyKey = idempotencyKey;
        this.scope = scope;
        this.status = DeletionRequestStatus.PENDING;
        this.requestedAt = requestedAt;
    }

    public void start() {
        requireStatus(DeletionRequestStatus.PENDING);
        this.status = DeletionRequestStatus.PROCESSING;
    }

    public void complete(Instant completedAt) {
        requireStatus(DeletionRequestStatus.PROCESSING);
        this.status = DeletionRequestStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void fail(String errorCode, Instant completedAt) {
        requireStatus(DeletionRequestStatus.PROCESSING);
        this.status = DeletionRequestStatus.FAILED;
        this.errorCode = errorCode;
        this.completedAt = completedAt;
    }

    private void requireStatus(DeletionRequestStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "deletion request " + id + " expected status " + expected + " but was " + status);
        }
    }
}
