package com.cappick.domain.deletion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * ContentAnalysisWorker와 같은 3단계 트랜잭션 경계(컨벤션 #11): PROCESSING 전환 커밋 -> 실제
 * 삭제 -> 완료 커밋. 삭제 자체는 외부 I/O가 아니라 DB 작업뿐이지만, 커밋을 나눠두는 이유는
 * 같다 — {@link DeletionStateTransitions#deleteContent}가 중간에 던지면 이미 커밋된
 * PROCESSING 상태를 보고 {@link DeletionStateTransitions#markFailed}가 안전하게 전이할 수
 * 있어야 한다. 상태 전이는 {@link DeletionStateTransitions}(별도 빈)에 맡긴다 — 이유는 그
 * 클래스 주석 참고(순환 참조 방지, self-invocation과는 다른 원인).
 */
@Service
public class AsyncDeletionWorker implements DeletionWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncDeletionWorker.class);

    private final DeletionStateTransitions transitions;

    public AsyncDeletionWorker(DeletionStateTransitions transitions) {
        this.transitions = transitions;
    }

    @Override
    @Async("jobExecutor")
    public void run(String deletionRequestId) {
        transitions.markProcessing(deletionRequestId);
        try {
            transitions.deleteContent(deletionRequestId);
        } catch (Exception e) {
            log.warn("deletion request {} failed", deletionRequestId, e);
            transitions.markFailed(deletionRequestId, "DELETION_FAILED");
        }
    }
}
