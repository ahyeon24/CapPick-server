package com.cappick.external.searchinterpretation;

import java.time.Instant;
import java.util.List;

// API 명세 §6 응답 필드 중 embedding·embedding_model_version을 뺀 나머지 — 그 둘은 SearchService가
// SearchInterpretationClient.embed()를 따로 불러 채운다(질의 해석과 임베딩은 별도 Gemini 호출).
public record SearchInterpretationResult(
    String normalizedQuery,
    List<String> lexicalTerms,
    Instant capturedFrom,
    Instant capturedBefore,
    String dateInterpretation
) {
}
