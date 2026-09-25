package com.cappick.domain.job;

import java.util.List;

// JobService가 이 인터페이스 뒤로 실행을 넘긴다 — ContentAnalysisWorker와 같은 이유로 테스트에서
// 실제 비동기·AI 호출 없이 "실행이 요청됐는지"만 검증할 수 있게 분리했다.
public interface ClassificationWorker {
    void run(String jobId, String ocrText, List<String> observedEvidence, List<ClassificationFolderInput> folders);
}
