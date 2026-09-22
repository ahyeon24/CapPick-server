package com.cappick.domain.job;

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
 * 비동기 AI 작업 (API 명세 §4, ERD §4). 지금은 별도 워커 프로세스·블롭 스토리지가 없어서
 * ERD의 input_reference/input_expires_at/lease_until은 아직 안 만들었다 — 이미지는 요청과
 * 같은 프로세스 안에서 바로 처리하고 저장하지 않는다. result도 별도 저장소 참조(result_reference)
 * 대신 JSON을 그대로 컬럼에 저장한다(result_json). 별도 워커·블롭 스토리지를 도입하면 그때
 * ERD 필드 구성으로 옮겨간다.
 */
@Entity
@Table(name = "job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installation_id", nullable = false)
    private Installation installation;

    @Column(name = "client_reference", nullable = false)
    private String clientReference;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(name = "content_revision", nullable = false)
    private int contentRevision;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "result_expires_at")
    private Instant resultExpiresAt;

    @Builder
    public Job(
        String id, Installation installation, String clientReference, String idempotencyKey,
        String requestDigest, JobKind kind, int contentRevision, Instant createdAt
    ) {
        this.id = id;
        this.installation = installation;
        this.clientReference = clientReference;
        this.idempotencyKey = idempotencyKey;
        this.requestDigest = requestDigest;
        this.kind = kind;
        this.status = JobStatus.QUEUED;
        this.contentRevision = contentRevision;
        this.attempts = 0;
        this.createdAt = createdAt;
    }

    public void start() {
        requireStatus(JobStatus.QUEUED);
        this.status = JobStatus.RUNNING;
    }

    public void succeed(String resultJson, Instant completedAt, Instant resultExpiresAt) {
        requireStatus(JobStatus.RUNNING);
        this.status = JobStatus.SUCCEEDED;
        this.resultJson = resultJson;
        this.completedAt = completedAt;
        this.resultExpiresAt = resultExpiresAt;
    }

    public void fail(String errorCode, Instant completedAt) {
        requireStatus(JobStatus.RUNNING);
        this.status = JobStatus.FAILED;
        this.errorCode = errorCode;
        this.completedAt = completedAt;
        this.attempts += 1;
    }

    private void requireStatus(JobStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                "job " + id + " expected status " + expected + " but was " + status);
        }
    }
}
