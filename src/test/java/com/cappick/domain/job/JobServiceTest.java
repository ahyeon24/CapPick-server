package com.cappick.domain.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.cappick.common.exception.IdempotencyConflictException;
import com.cappick.common.exception.InstallationDeletionInProgressException;
import com.cappick.common.exception.JobNotFoundException;
import com.cappick.domain.deletion.DeletionRequestRepository;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.job.dto.ClassificationAnalysisDTO;
import com.cappick.domain.job.dto.ClassificationDecisionDTO;
import com.cappick.domain.job.dto.ClassificationFolderRequestDTO;
import com.cappick.domain.job.dto.ClassificationRequestDTO;
import com.cappick.domain.job.dto.ClassificationResultDTO;
import com.cappick.domain.job.dto.ContentAnalysisMetadataDTO;
import com.cappick.domain.job.dto.ContentAnalysisResultDTO;
import com.cappick.domain.usage.UsageCategory;
import com.cappick.domain.usage.UsageService;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Mock
    private JobRepository jobs;
    @Mock
    private ContentAnalysisWorker worker;
    @Mock
    private ClassificationWorker classificationWorker;
    @Mock
    private UsageService usageService;
    @Mock
    private DeletionRequestRepository deletionRequests;

    private JobService service;
    private Installation installation;

    @BeforeEach
    void setUp() {
        service = new JobService(
            jobs, worker, classificationWorker, usageService, deletionRequests,
            JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        installation = Installation.builder()
            .id("i1").credentialHash("h").keyId("k").publicKeyBase64("pk").createdAt(NOW)
            .build();
    }

    private static ContentAnalysisMetadataDTO metadata(String clientRef, int revision, String ocrText) {
        return new ContentAnalysisMetadataDTO(clientRef, revision, ocrText);
    }

    private static ClassificationRequestDTO classificationRequest(String clientRef, int analysisRevision) {
        return new ClassificationRequestDTO(
            JobKind.CLASSIFICATION, clientRef, analysisRevision,
            new ClassificationAnalysisDTO("자바 면접 예상 질문", List.of("면접 예상 질문이라는 문구가 있음")),
            List.of(new ClassificationFolderRequestDTO("folder-1", "면접 질문과 면접 안내", 1)));
    }

    @Test
    void submitCreatesQueuedJobAndTriggersWorker() {
        when(jobs.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.empty());
        byte[] image = {1, 2, 3};

        JobService.JobSubmitted submitted = service.submitContentAnalysis(
            installation, "key-1", metadata("ref-1", 1, null), image, "image/jpeg");

        assertEquals(JobStatus.QUEUED, submitted.status());
        verify(jobs).save(any(Job.class));
        verify(worker).run(eq(submitted.jobId()), eq(image), eq("image/jpeg"), eq((String) null));
        verify(usageService).reserve(installation, "key-1", UsageCategory.CONTENT_ANALYSIS, 1);
    }

    @Test
    void submitContentAnalysisBlockedWhileInstallationDeletionInProgress() {
        when(deletionRequests.existsActiveByInstallation(installation)).thenReturn(true);

        assertThrows(InstallationDeletionInProgressException.class, () -> service.submitContentAnalysis(
            installation, "key-1", metadata("ref-1", 1, null), new byte[]{1}, "image/jpeg"));
        verify(jobs, never()).save(any());
        verify(worker, never()).run(anyString(), any(), any(), any());
    }

    @Test
    void resubmittingSameKeyAndBodyReturnsExistingJobWithoutRerunning() {
        byte[] image = {1, 2, 3};
        Job existing = Job.builder()
            .id("job-1").installation(installation).clientReference("ref-1")
            .idempotencyKey("key-1").requestDigest(digestOf(image, 1, null))
            .kind(JobKind.CONTENT_ANALYSIS).contentRevision(1).createdAt(NOW)
            .build();
        when(jobs.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.of(existing));

        JobService.JobSubmitted submitted = service.submitContentAnalysis(
            installation, "key-1", metadata("ref-1", 1, null), image, "image/jpeg");

        assertEquals("job-1", submitted.jobId());
        verify(jobs, never()).save(any());
        verify(worker, never()).run(anyString(), any(), any(), any());
    }

    @Test
    void resubmittingSameKeyWithDifferentBodyConflicts() {
        Job existing = Job.builder()
            .id("job-1").installation(installation).clientReference("ref-1")
            .idempotencyKey("key-1").requestDigest("some-other-digest")
            .kind(JobKind.CONTENT_ANALYSIS).contentRevision(1).createdAt(NOW)
            .build();
        when(jobs.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.of(existing));

        assertThrows(IdempotencyConflictException.class, () -> service.submitContentAnalysis(
            installation, "key-1", metadata("ref-1", 1, null), new byte[]{9, 9}, "image/jpeg"));
        verify(worker, never()).run(anyString(), any(), any(), any());
    }

    @Test
    void getStatusReturnsParsedResultWhenSucceeded() throws Exception {
        Job job = Job.builder()
            .id("job-1").installation(installation).clientReference("ref-1")
            .idempotencyKey("key-1").requestDigest("d").kind(JobKind.CONTENT_ANALYSIS)
            .contentRevision(3).createdAt(NOW)
            .build();
        job.start();
        JsonMapper mapper = JsonMapper.builder().build();
        String resultJson = mapper.writeValueAsString(new ContentAnalysisResultDTO(
            3, "ocr", java.util.List.of("evidence 1"), "search text", java.util.List.of(0.1, 0.2), "embed-v1"));
        job.succeed(resultJson, NOW, NOW.plusSeconds(3600));
        when(jobs.findByIdAndInstallation("job-1", installation)).thenReturn(Optional.of(job));

        var response = service.getStatus(installation, "job-1");

        assertEquals(JobStatus.SUCCEEDED, response.status());
        ContentAnalysisResultDTO result = (ContentAnalysisResultDTO) response.result();
        assertEquals("ocr", result.ocrText());
        assertEquals("search text", result.searchText());
    }

    @Test
    void getStatusThrowsWhenJobNotFoundOrNotOwned() {
        when(jobs.findByIdAndInstallation("missing", installation)).thenReturn(Optional.empty());
        assertThrows(JobNotFoundException.class, () -> service.getStatus(installation, "missing"));
    }

    @Test
    void submitClassificationCreatesQueuedJobAndTriggersWorker() {
        when(jobs.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.empty());

        JobService.JobSubmitted submitted = service.submitClassification(
            installation, "key-1", classificationRequest("ref-1", 1));

        assertEquals(JobStatus.QUEUED, submitted.status());
        verify(jobs).save(any(Job.class));
        verify(classificationWorker).run(
            eq(submitted.jobId()), eq("자바 면접 예상 질문"), eq(List.of("면접 예상 질문이라는 문구가 있음")),
            eq(List.of(new ClassificationFolderInput("folder-1", "면접 질문과 면접 안내", 1))));
        verify(usageService).reserve(installation, "key-1", UsageCategory.CLASSIFICATION, 1);
    }

    @Test
    void submitClassificationBlockedWhileInstallationDeletionInProgress() {
        when(deletionRequests.existsActiveByInstallation(installation)).thenReturn(true);

        assertThrows(InstallationDeletionInProgressException.class, () -> service.submitClassification(
            installation, "key-1", classificationRequest("ref-1", 1)));
        verify(jobs, never()).save(any());
        verify(classificationWorker, never()).run(anyString(), any(), any(), any());
    }

    @Test
    void resubmittingClassificationWithSameKeyAndBodyReturnsExistingJobWithoutRerunning() {
        ClassificationRequestDTO request = classificationRequest("ref-1", 1);
        Job existing = Job.builder()
            .id("job-1").installation(installation).clientReference("ref-1")
            .idempotencyKey("key-1").requestDigest(classificationDigestOf(request))
            .kind(JobKind.CLASSIFICATION).contentRevision(1).createdAt(NOW)
            .build();
        when(jobs.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.of(existing));

        JobService.JobSubmitted submitted = service.submitClassification(installation, "key-1", request);

        assertEquals("job-1", submitted.jobId());
        verify(jobs, never()).save(any());
        verify(classificationWorker, never()).run(anyString(), any(), any(), any());
    }

    @Test
    void resubmittingClassificationWithSameKeyButDifferentBodyConflicts() {
        Job existing = Job.builder()
            .id("job-1").installation(installation).clientReference("ref-1")
            .idempotencyKey("key-1").requestDigest("some-other-digest")
            .kind(JobKind.CLASSIFICATION).contentRevision(1).createdAt(NOW)
            .build();
        when(jobs.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.of(existing));

        assertThrows(IdempotencyConflictException.class, () -> service.submitClassification(
            installation, "key-1", classificationRequest("ref-1", 1)));
        verify(classificationWorker, never()).run(anyString(), any(), any(), any());
    }

    @Test
    void getStatusReturnsParsedClassificationResultWhenSucceeded() throws Exception {
        Job job = Job.builder()
            .id("job-1").installation(installation).clientReference("ref-1")
            .idempotencyKey("key-1").requestDigest("d").kind(JobKind.CLASSIFICATION)
            .contentRevision(1).createdAt(NOW)
            .build();
        job.start();
        JsonMapper mapper = JsonMapper.builder().build();
        String resultJson = mapper.writeValueAsString(new ClassificationResultDTO(List.of(
            new ClassificationDecisionDTO("folder-1", 1, ClassificationDecision.MATCH, "면접 예상 질문 문구가 확인됨"))));
        job.succeed(resultJson, NOW, NOW.plusSeconds(3600));
        when(jobs.findByIdAndInstallation("job-1", installation)).thenReturn(Optional.of(job));

        var response = service.getStatus(installation, "job-1");

        assertEquals(JobStatus.SUCCEEDED, response.status());
        ClassificationResultDTO result = (ClassificationResultDTO) response.result();
        assertEquals(1, result.decisions().size());
        assertEquals(ClassificationDecision.MATCH, result.decisions().get(0).decision());
    }

    private static String classificationDigestOf(ClassificationRequestDTO request) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(Integer.toString(request.analysisRevision()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(request.analysis().ocrText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String evidence : request.analysis().observedEvidence()) {
                digest.update(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            for (ClassificationFolderRequestDTO folder : request.folders()) {
                digest.update(folder.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(folder.criteriaText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Integer.toString(folder.criteriaVersion()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String digestOf(byte[] image, int contentRevision, String ocrText) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(image);
            digest.update(Integer.toString(contentRevision).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((ocrText == null ? "" : ocrText).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
