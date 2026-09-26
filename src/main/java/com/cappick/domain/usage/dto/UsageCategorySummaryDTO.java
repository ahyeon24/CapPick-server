package com.cappick.domain.usage.dto;

import java.time.Instant;

import com.cappick.domain.usage.UsageCategory;

// API 명세 §7 GET /v1/usage 응답의 categories 배열 원소. limit_units·resets_at은 한도 정책이
// 미정이라 항상 null(비적용이 아니라 "아직 안 정함"이라는 뜻 — API 명세가 명시한 규칙),
// can_submit도 항상 true(한도를 아직 안 막음).
public record UsageCategorySummaryDTO(
    UsageCategory category,
    int usedUnits,
    int reservedUnits,
    Integer limitUnits,
    Instant resetsAt,
    boolean canSubmit
) {
}
