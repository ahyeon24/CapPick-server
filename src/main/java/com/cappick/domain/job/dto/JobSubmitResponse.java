package com.cappick.domain.job.dto;

import com.cappick.domain.job.JobStatus;

public record JobSubmitResponse(String jobId, JobStatus status, int pollAfterSeconds) {
}
