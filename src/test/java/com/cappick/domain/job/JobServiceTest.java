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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;

import com.cappick.common.exception.IdempotencyConflictException;
import com.cappick.common.exception.JobNotFoundException;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.job.dto.ContentAnalysisMetadataDTO;
import com.cappick.domain.job.dto.ContentAnalysisResultDTO;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Mock
    private JobRepository jobs;
    @Mock
    private ContentAnalysisWorker worker;

    private JobService service;
    private Installation installation;

    @BeforeEach
    void setUp() {
        service = new JobService(jobs, worker, JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        installation = Installation.builder()
            .id("i1").credentialHash("h").keyId("k").publicKeyBase64("pk").createdAt(NOW)
            .build();
    }

    private static ContentAnalysisMetadataDTO metadata(String clientRef, int revision, String ocrText) {
        return new ContentAnalysisMetadataDTO(clientRef, revision, ocrText);
    }

    @Test
    void submitCreatesQueuedJobAndTriggersWorker() {
        when(jobs.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.empty());
        byte[] image = {1, 2, 3};

        JobService.JobSubmitted submitted = service.submitContentAnalysis(
            installation, "key-1", metadata("ref-1", 1, null), image);

        assertEquals(JobStatus.QUEUED, submitted.status());
        verify(jobs).save(any(Job.class));
        verify(worker).run(eq(submitted.jobId()), eq(image), eq((String) null));
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
            installation, "key-1", metadata("ref-1", 1, null), image);

        assertEquals("job-1", submitted.jobId());
        verify(jobs, never()).save(any());
        verify(worker, never()).run(anyString(), any(), any());
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
            installation, "key-1", metadata("ref-1", 1, null), new byte[]{9, 9}));
        verify(worker, never()).run(anyString(), any(), any());
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
        assertEquals("ocr", response.result().ocrText());
        assertEquals("search text", response.result().searchText());
    }

    @Test
    void getStatusThrowsWhenJobNotFoundOrNotOwned() {
        when(jobs.findByIdAndInstallation("missing", installation)).thenReturn(Optional.empty());
        assertThrows(JobNotFoundException.class, () -> service.getStatus(installation, "missing"));
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
