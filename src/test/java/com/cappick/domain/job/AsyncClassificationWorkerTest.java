package com.cappick.domain.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cappick.external.classification.ClassificationClient;
import com.cappick.external.classification.ClassificationDecisionResult;
import com.cappick.external.classification.ClassificationResult;

// AsyncContentAnalysisWorkerTest와 같은 이유로 오케스트레이션만 본다 — 실제 상태 전이 로직은
// JobStateTransitionsTest가 검증한다.
@ExtendWith(MockitoExtension.class)
class AsyncClassificationWorkerTest {

    @Mock
    private JobStateTransitions transitions;
    @Mock
    private ClassificationClient classificationClient;
    @InjectMocks
    private AsyncClassificationWorker worker;

    @Test
    void successfulClassificationMarksRunningThenSucceeded() {
        ClassificationResult result = new ClassificationResult(List.of(
            new ClassificationDecisionResult("folder-1", 1, ClassificationDecision.MATCH, "면접 예상 질문 문구가 확인됨")));
        when(classificationClient.classify(any())).thenReturn(result);

        worker.run("job-1", "ocr", List.of("evidence"), List.of(new ClassificationFolderInput("folder-1", "면접 질문", 1)));

        verify(transitions).markRunning("job-1");
        verify(transitions).markClassificationSucceeded("job-1", result);
        verify(transitions, never()).markFailed(eq("job-1"), any());
    }

    @Test
    void failedClassificationMarksRunningThenFailedWithoutLeakingExceptionDetails() {
        when(classificationClient.classify(any())).thenThrow(new RuntimeException("network boom"));

        worker.run("job-1", "ocr", List.of(), List.of(new ClassificationFolderInput("folder-1", "면접 질문", 1)));

        verify(transitions).markRunning("job-1");
        verify(transitions).markFailed("job-1", "CLASSIFICATION_FAILED");
        verify(transitions, never()).markClassificationSucceeded(eq("job-1"), any());
    }
}
