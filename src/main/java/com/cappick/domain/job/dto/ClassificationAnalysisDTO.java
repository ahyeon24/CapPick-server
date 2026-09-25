package com.cappick.domain.job.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

// POST /v1/jobs (classification)의 "analysis" 필드 — content_analysis 작업이 이미 뽑아둔
// 관찰 내용을 그대로 실어 보낸다(API 명세 §4 예시). 이미지는 다시 보내지 않는다. observedEvidence는
// 없으면 빈 배열로 보내야 한다 — null이면 이후 digest 계산·프롬프트 조립에서 NPE가 난다.
public record ClassificationAnalysisDTO(String ocrText, @NotNull List<String> observedEvidence) {
}
