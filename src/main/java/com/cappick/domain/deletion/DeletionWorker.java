package com.cappick.domain.deletion;

// ContentAnalysisWorker/ClassificationWorker와 같은 이유로 인터페이스 뒤에 둔다 — 테스트에서
// 실제 @Async 스레드를 안 띄우고 Mockito로 대체할 수 있게.
public interface DeletionWorker {
    void run(String deletionRequestId);
}
