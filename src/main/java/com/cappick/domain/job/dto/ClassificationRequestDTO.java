package com.cappick.domain.job.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.cappick.domain.job.JobKind;

// POST /v1/jobs (classification), API 명세 §4. content_analysis(multipart)와 달리 이 kind는
// application/json 바디 전체가 이 레코드 하나다 — JobController가 kind 필드로 구분해 이 타입으로
// 바인딩한다(지금은 JSON kind가 이거 하나뿐이라 가능한 단순화. folder_criteria·search_document가
// 생기면 이 컨트롤러 메서드를 나눠야 한다).
public record ClassificationRequestDTO(
    @NotNull JobKind kind,
    @NotBlank String clientReference,
    int analysisRevision,
    @NotNull @Valid ClassificationAnalysisDTO analysis,
    @NotEmpty List<@Valid ClassificationFolderRequestDTO> folders
) {
}
