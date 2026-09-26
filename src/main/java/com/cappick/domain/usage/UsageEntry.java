package com.cappick.domain.usage;

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
import com.cappick.domain.job.Job;

/**
 * 사용량 원장 한 줄 (ERD §4). Job 제출과 함께 RESERVED로 생성되고, Job이 끝나면 CONFIRMED(성공,
 * 과금) 또는 RELEASED(실패, 과금 안 함)로 전이한다 — "실패 비용" 정책 자체는 아직 미정이라
 * "실패하면 과금 안 함"을 잠정 기본값으로 둔다(API 명세 §5 "사용량 정책은 실패 원인별로
 * 확정한다"). {@code operationKey}는 Job의 idempotencyKey를 그대로 재사용해서, Job 하나당
 * UsageEntry가 정확히 하나만 생기게 한다(같은 키로 재제출해도 중복 예약되지 않음).
 *
 * <p>{@code units}는 지금 "Job 1건 = 1 unit"으로 잠정 고정했다 — 실제 과금 단위는 ERD §6·API
 * 명세 §8이 명시적으로 미정이라고 못박은 항목이라 임의로 확정하지 않는다.
 */
@Entity
@Table(name = "usage_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageEntry {

    @Id
    @Column(length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installation_id", nullable = false)
    private Installation installation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id")
    private Job job;

    @Column(name = "operation_key", nullable = false)
    private String operationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UsageCategory category;

    @Column(nullable = false)
    private int units;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UsageEntryState state;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    public UsageEntry(
        String id, Installation installation, Job job, String operationKey,
        UsageCategory category, int units, Instant createdAt
    ) {
        this.id = id;
        this.installation = installation;
        this.job = job;
        this.operationKey = operationKey;
        this.category = category;
        this.units = units;
        this.state = UsageEntryState.RESERVED;
        this.createdAt = createdAt;
    }

    public void confirm() {
        requireStatus(UsageEntryState.RESERVED);
        this.state = UsageEntryState.CONFIRMED;
    }

    public void release() {
        requireStatus(UsageEntryState.RESERVED);
        this.state = UsageEntryState.RELEASED;
    }

    private void requireStatus(UsageEntryState expected) {
        if (state != expected) {
            throw new IllegalStateException(
                "usage entry " + id + " expected state " + expected + " but was " + state);
        }
    }
}
