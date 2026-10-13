package com.cappick.domain.usage.dto;

import java.time.Instant;

import com.cappick.domain.usage.UsageCategory;

// API 명세 §7 GET /v1/usage 응답의 categories 배열 원소. limit_units는 설치별 한도
// (UsageQuotaProperties)가 설정된 카테고리에만 값이 있고(2026-10-13, 지금은 content_analysis),
// 없으면 null이다. resets_at은 항상 null — 주기적으로 초기화되는 한도가 아직 없다. can_submit은
// 설치별 한도 기준이며 전체 하루 예산 소진 여부는 반영하지 않는다.
public record UsageCategorySummaryDTO(
    UsageCategory category,
    int usedUnits,
    int reservedUnits,
    Integer limitUnits,
    Instant resetsAt,
    boolean canSubmit
) {
}
