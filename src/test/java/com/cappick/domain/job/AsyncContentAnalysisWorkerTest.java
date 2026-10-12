package com.cappick.domain.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cappick.external.contentanalysis.ContentAnalysisClient;
import com.cappick.external.contentanalysis.ContentAnalysisRequest;
import com.cappick.external.contentanalysis.ContentAnalysisResult;

// 상태 전이의 실제 로직은 JobStateTransitionsTest가 검증한다. 여기서는 run()이 올바른
// 순서로 호출하는지(오케스트레이션)만 본다 — markRunning이 별도 빈(주입된 참조)을 통해
// 호출되는지가 핵심이다: 같은 클래스 안의 this.markRunning()이었다면 이 테스트로는 못 잡았을
// self-invocation 버그가 실제로 있었다(JobStateTransitions 클래스 주석 참고).
@ExtendWith(MockitoExtension.class)
class AsyncContentAnalysisWorkerTest {

    @Mock
    private JobStateTransitions transitions;
    @Mock
    private ContentAnalysisClient contentAnalysisClient;
    @InjectMocks
    private AsyncContentAnalysisWorker worker;

    @Test
    void successfulAnalysisMarksRunningThenSucceeded() {
        ContentAnalysisResult result = new ContentAnalysisResult(
            "ocr", List.of("evidence"), "search");
        when(contentAnalysisClient.analyze(any())).thenReturn(result);

        worker.run("job-1", new byte[]{1, 2, 3}, "image/jpeg", "hint");

        verify(transitions).markRunning("job-1");
        verify(transitions).markSucceeded("job-1", result);
        verify(transitions, never()).markFailed(eq("job-1"), any());
    }

    // 앱이 실제로 보내는 형식(JPEG)을 서버가 Gemini에 "image/png"로 하드코딩해 알려주던 버그가
    // 있었다(2026-09-25 발견) — mimeType이 run() 호출자가 준 값 그대로 전달되는지 고정한다.
    @Test
    void passesThroughTheActualMimeTypeInsteadOfHardcodingIt() {
        ContentAnalysisResult result = new ContentAnalysisResult(
            "ocr", List.of(), "search");
        when(contentAnalysisClient.analyze(any())).thenReturn(result);
        ArgumentCaptor<ContentAnalysisRequest> captor = ArgumentCaptor.forClass(ContentAnalysisRequest.class);

        worker.run("job-1", new byte[]{1, 2, 3}, "image/jpeg", null);

        verify(contentAnalysisClient).analyze(captor.capture());
        assertEquals("image/jpeg", captor.getValue().mimeType());
    }

    @Test
    void failedAnalysisMarksRunningThenFailedWithoutLeakingExceptionDetails() {
        when(contentAnalysisClient.analyze(any())).thenThrow(new RuntimeException("network boom"));

        worker.run("job-1", new byte[]{1, 2, 3}, "image/jpeg", null);

        verify(transitions).markRunning("job-1");
        verify(transitions).markFailed("job-1", "ANALYSIS_FAILED");
        verify(transitions, never()).markSucceeded(eq("job-1"), any());
    }
}
