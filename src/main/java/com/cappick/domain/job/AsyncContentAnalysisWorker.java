package com.cappick.domain.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.cappick.external.contentanalysis.ContentAnalysisClient;
import com.cappick.external.contentanalysis.ContentAnalysisRequest;
import com.cappick.external.contentanalysis.ContentAnalysisResult;

/**
 * 트랜잭션 3단계로 나눈다(컨벤션 #11): RUNNING 전환 커밋 -> 외부 AI 호출(트랜잭션 밖) -> 결과 저장 커밋.
 * 상태 전이 자체는 {@link JobStateTransitions}(별도 빈)에 맡긴다 — 이유는 그 클래스 주석 참고
 * (self-invocation 때문에 같은 클래스 안에 두면 @Transactional이 안 걸림).
 * 재시도 가능한 오류/불가능한 오류를 구분하지 않고 전부 FAILED로 끝낸다 — 재시도 정책은 아직 없다
 * (§14 미확정 사항). 중복 실행 전제(컨벤션 #10)도 아직 없다: 이 서버 인스턴스 안에서만 한 번
 * 실행되고, 별도 워커의 재수집·lease는 AsyncConfig 주석대로 다음 단계다.
 */
@Service
public class AsyncContentAnalysisWorker implements ContentAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncContentAnalysisWorker.class);

    private final JobStateTransitions transitions;
    private final ContentAnalysisClient contentAnalysisClient;

    public AsyncContentAnalysisWorker(JobStateTransitions transitions, ContentAnalysisClient contentAnalysisClient) {
        this.transitions = transitions;
        this.contentAnalysisClient = contentAnalysisClient;
    }

    @Override
    @Async("jobExecutor")
    public void run(String jobId, byte[] imageBytes, String ocrTextHint) {
        transitions.markRunning(jobId);
        try {
            ContentAnalysisResult result = contentAnalysisClient.analyze(
                new ContentAnalysisRequest(imageBytes, "image/png", ocrTextHint));
            transitions.markSucceeded(jobId, result);
        } catch (Exception e) {
            // 이미지·OCR 내용은 로그에 남기지 않는다(컨벤션 #13) — jobId만 남긴다.
            log.warn("content_analysis job {} failed", jobId, e);
            transitions.markFailed(jobId, "ANALYSIS_FAILED");
        }
    }
}
