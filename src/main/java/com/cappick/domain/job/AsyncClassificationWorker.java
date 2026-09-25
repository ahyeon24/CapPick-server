package com.cappick.domain.job;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.cappick.external.classification.ClassificationClient;
import com.cappick.external.classification.ClassificationFolderCriteria;
import com.cappick.external.classification.ClassificationRequest;
import com.cappick.external.classification.ClassificationResult;

/**
 * AsyncContentAnalysisWorker와 같은 트랜잭션 3단계 패턴(컨벤션 #11): RUNNING 전환 커밋 -> 외부
 * AI 호출(트랜잭션 밖) -> 결과 저장 커밋. 상태 전이는 여기서 직접 하지 않고 {@link JobStateTransitions}
 * (별도 빈)에 맡긴다 — self-invocation 문제 때문(그 클래스 주석·AsyncContentAnalysisWorker 주석 참고).
 */
@Service
public class AsyncClassificationWorker implements ClassificationWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncClassificationWorker.class);

    private final JobStateTransitions transitions;
    private final ClassificationClient classificationClient;

    public AsyncClassificationWorker(JobStateTransitions transitions, ClassificationClient classificationClient) {
        this.transitions = transitions;
        this.classificationClient = classificationClient;
    }

    @Override
    @Async("jobExecutor")
    public void run(String jobId, String ocrText, List<String> observedEvidence, List<ClassificationFolderInput> folders) {
        transitions.markRunning(jobId);
        try {
            List<ClassificationFolderCriteria> criteria = folders.stream()
                .map(f -> new ClassificationFolderCriteria(f.id(), f.criteriaText(), f.criteriaVersion()))
                .toList();
            ClassificationResult result = classificationClient.classify(
                new ClassificationRequest(ocrText, observedEvidence, criteria));
            transitions.markClassificationSucceeded(jobId, result);
        } catch (Exception e) {
            // OCR·관찰 내용·폴더 기준 원문은 로그에 남기지 않는다(컨벤션 #13) — jobId만 남긴다.
            log.warn("classification job {} failed", jobId, e);
            transitions.markFailed(jobId, "CLASSIFICATION_FAILED");
        }
    }
}
