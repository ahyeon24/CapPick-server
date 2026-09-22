package com.cappick.domain.search.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// API 명세 §6. embeddingModelVersion은 클라이언트의 로컬 캡처들이 임베딩된 모델 버전 — 서버가
// 지금 쓰는 것과 다르면 SearchModelMismatchException(409)으로 거절한다.
public record SearchPrepareRequest(
    @NotBlank String query,
    @NotNull Instant now,
    @NotBlank String timezone,
    @NotBlank String embeddingModelVersion
) {
}
