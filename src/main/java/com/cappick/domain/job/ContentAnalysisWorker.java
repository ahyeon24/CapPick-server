package com.cappick.domain.job;

// JobService가 이 인터페이스 뒤로 실행을 넘긴다 — 테스트에서 실제 비동기·AI 호출 없이
// "실행이 요청됐는지"만 검증할 수 있게 분리했다.
public interface ContentAnalysisWorker {
    void run(String jobId, byte[] imageBytes, String mimeType, String ocrTextHint);
}
