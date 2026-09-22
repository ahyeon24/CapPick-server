package com.cappick.domain.job.dto;

import java.util.List;

// API 명세 §4 content_analysis 완료 result와 같은 모양. Job.resultJson에 이 형태로 저장하고,
// GET /v1/jobs/{id} 응답에도 그대로 쓴다.
//
// analysisRevision: API 명세는 서버가 이 값을 만든다고만 되어 있고 규칙은 없다 — 서버가 캡처
// 이력을 갖고 있지 않으니 요청에 실린 content_revision을 그대로 돌려주는 것으로 해석했다
// (JobService 참고). 서버 계약이 구체화되면 확인이 필요하다.
public record ContentAnalysisResultDTO(
    int analysisRevision,
    String ocrText,
    List<String> observedEvidence,
    String searchText,
    List<Double> embedding,
    String embeddingModelVersion
) {
}
