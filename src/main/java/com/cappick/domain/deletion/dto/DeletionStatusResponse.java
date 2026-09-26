package com.cappick.domain.deletion.dto;

import java.time.Instant;

import com.cappick.domain.deletion.DeletionRequestStatus;

// API 명세 §3 GET /v1/data-deletions/{deletion_id}: "status, completed_at, error".
public record DeletionStatusResponse(
    String deletionId,
    DeletionRequestStatus status,
    Instant completedAt,
    String errorCode
) {
}
