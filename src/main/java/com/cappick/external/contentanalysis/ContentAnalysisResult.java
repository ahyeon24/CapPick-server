package com.cappick.external.contentanalysis;

import java.util.List;

// 검색 벡터(embedding)는 2026-10-08부터 여기 없다 — 앱이 기기에서 직접 만든다.
// API 명세 §4 content_analysis 결과 필드와 대응(analysis_revision 제외 — 그건 서버가 아니라
// 요청의 content_revision을 그대로 돌려주는 JobService 쪽 책임).
public record ContentAnalysisResult(
    String ocrText,
    List<String> observedEvidence,
    String searchText
) {
}
