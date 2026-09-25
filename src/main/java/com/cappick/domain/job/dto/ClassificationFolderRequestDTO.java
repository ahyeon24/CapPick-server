package com.cappick.domain.job.dto;

import jakarta.validation.constraints.NotBlank;

// POST /v1/jobs (classification)의 "folders" 배열 원소. id는 실제 로컬 폴더 ID(서버는 이 값을
// 저장하지 않고 응답에 그대로 돌려주기만 한다 — classification은 소속을 저장하는 API가 아니다).
public record ClassificationFolderRequestDTO(
    @NotBlank String id,
    @NotBlank String criteriaText,
    int criteriaVersion
) {
}
