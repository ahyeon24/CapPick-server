package com.cappick.domain.job.dto;

import jakarta.validation.constraints.NotBlank;

// POST /v1/jobs (content_analysis)의 multipart "metadata" 파트. 이미지는 별도 파트("image")로 온다.
public record ContentAnalysisMetadataDTO(
    @NotBlank String clientReference,
    int contentRevision,
    String ocrText
) {
}
