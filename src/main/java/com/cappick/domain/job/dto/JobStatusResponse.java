package com.cappick.domain.job.dto;

import java.time.Instant;

import com.cappick.domain.job.JobStatus;

// GET /v1/jobs/{job_id} (API 명세 §5). queued/running이면 result·error는 null. result는 kind마다
// 모양이 달라서(content_analysis는 ContentAnalysisResultDTO, classification은
// ClassificationResultDTO) Object로 둔다 — JobService.getStatus가 job.kind로 실제 타입을
// 골라 채우고, Jackson은 그 런타임 타입 그대로 직렬화한다.
public record JobStatusResponse(
    String jobId,
    JobStatus status,
    String clientReference,
    Object result,
    Instant resultExpiresAt,
    JobErrorDTO error
) {
}
