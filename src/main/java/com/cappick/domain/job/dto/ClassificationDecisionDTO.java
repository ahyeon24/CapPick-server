package com.cappick.domain.job.dto;

import com.cappick.domain.job.ClassificationDecision;

// GET /v1/jobs/{id} classification 완료 result의 decisions 배열 원소(API 명세 §5 예시).
public record ClassificationDecisionDTO(
    String folderId,
    int criteriaVersion,
    ClassificationDecision decision,
    String evidence
) {
}
