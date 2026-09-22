package com.cappick.domain.job.dto;

import java.time.Instant;

import com.cappick.domain.job.JobStatus;

// GET /v1/jobs/{job_id} (API 명세 §5). queued/running이면 result·error는 null.
public record JobStatusResponse(
    String jobId,
    JobStatus status,
    String clientReference,
    ContentAnalysisResultDTO result,
    Instant resultExpiresAt,
    JobErrorDTO error
) {
}
