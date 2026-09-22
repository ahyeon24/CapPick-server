package com.cappick.domain.search.dto;

import java.time.Instant;
import java.util.List;

// API 명세 §6 응답. capturedFrom/capturedBefore/dateInterpretation은 질의에 날짜 조건이 없으면
// null이다 — 실제 캡처 검색은 서버가 아니라 기기에서 이 필드들을 가지고 수행한다.
public record SearchPrepareResponse(
    String normalizedQuery,
    List<String> lexicalTerms,
    List<Double> embedding,
    String embeddingModelVersion,
    Instant capturedFrom,
    Instant capturedBefore,
    String dateInterpretation
) {
}
