package com.cappick.domain.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;

import com.cappick.domain.installation.Installation;
import com.cappick.external.classification.ClassificationDecisionResult;
import com.cappick.external.classification.ClassificationResult;
import com.cappick.external.contentanalysis.ContentAnalysisResult;

@ExtendWith(MockitoExtension.class)
class JobStateTransitionsTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Mock
    private JobRepository jobs;

    private JobStateTransitions transitions;
    private Job job;

    @BeforeEach
    void setUp() {
        transitions = new JobStateTransitions(jobs, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        Installation installation = Installation.builder()
            .id("i1").credentialHash("h").keyId("k").publicKeyBase64("pk").createdAt(NOW)
            .build();
        job = Job.builder()
            .id("job-1").installation(installation).clientReference("ref-1")
            .idempotencyKey("key-1").requestDigest("d").kind(JobKind.CONTENT_ANALYSIS)
            .contentRevision(5).createdAt(NOW)
            .build();
    }

    @Test
    void markRunningMovesQueuedToRunning() {
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));

        transitions.markRunning("job-1");
        assertEquals(JobStatus.RUNNING, job.getStatus());
    }

    @Test
    void markSucceededStoresSerializedResultAndExpiry() {
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        job.start();

        transitions.markSucceeded("job-1", new ContentAnalysisResult(
            "ocr text", List.of("evidence 1"), "search text", List.of(0.1, 0.2), "embed-v1"));

        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertNotNull(job.getResultJson());
        assertEquals(NOW.plusSeconds(3600 * 24), job.getResultExpiresAt());
        assertEquals(true, job.getResultJson().contains("\"analysisRevision\":5"));
    }

    @Test
    void markClassificationSucceededStoresSerializedResultAndExpiry() {
        Job classificationJob = Job.builder()
            .id("job-2").installation(job.getInstallation()).clientReference("ref-2")
            .idempotencyKey("key-2").requestDigest("d").kind(JobKind.CLASSIFICATION)
            .contentRevision(1).createdAt(NOW)
            .build();
        when(jobs.findById("job-2")).thenReturn(Optional.of(classificationJob));
        classificationJob.start();

        transitions.markClassificationSucceeded("job-2", new ClassificationResult(List.of(
            new ClassificationDecisionResult("folder-1", 1, ClassificationDecision.MATCH, "면접 예상 질문 문구가 확인됨"))));

        assertEquals(JobStatus.SUCCEEDED, classificationJob.getStatus());
        assertNotNull(classificationJob.getResultJson());
        assertEquals(NOW.plusSeconds(3600 * 24), classificationJob.getResultExpiresAt());
        assertEquals(true, classificationJob.getResultJson().contains("\"folderId\":\"folder-1\""));
        assertEquals(true, classificationJob.getResultJson().contains("\"decision\":\"match\""));
    }

    @Test
    void markFailedRecordsErrorCodeAndIncrementsAttempts() {
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        job.start();

        transitions.markFailed("job-1", "ANALYSIS_FAILED");

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals("ANALYSIS_FAILED", job.getErrorCode());
        assertEquals(1, job.getAttempts());
    }

    @Test
    void markOnUnknownJobDoesNothing() {
        when(jobs.findById("missing")).thenReturn(Optional.empty());
        transitions.markRunning("missing"); // no exception
    }
}
