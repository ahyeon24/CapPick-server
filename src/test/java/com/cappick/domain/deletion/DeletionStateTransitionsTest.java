package com.cappick.domain.deletion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.cappick.common.exception.DeletionRequestNotFoundException;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.job.JobRepository;

@ExtendWith(MockitoExtension.class)
class DeletionStateTransitionsTest {

    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");

    @Mock
    private DeletionRequestRepository deletionRequests;
    @Mock
    private JobRepository jobs;

    private DeletionStateTransitions transitions;
    private Installation installation;

    @BeforeEach
    void setUp() {
        transitions = new DeletionStateTransitions(deletionRequests, jobs, Clock.fixed(NOW, ZoneOffset.UTC));
        installation = Installation.builder()
            .id("i1").credentialHash("h").keyId("k").publicKeyBase64("pk").createdAt(NOW)
            .build();
    }

    @Test
    void markProcessingMovesPendingToProcessing() {
        DeletionRequest request = DeletionRequest.builder()
            .id("d1").installation(installation).idempotencyKey("key-1")
            .scope(DeletionRequestScope.INSTALLATION_CONTENT).requestedAt(NOW)
            .build();
        when(deletionRequests.findById("d1")).thenReturn(Optional.of(request));

        transitions.markProcessing("d1");

        assertEquals(DeletionRequestStatus.PROCESSING, request.getStatus());
    }

    @Test
    void deleteContentDeletesInstallationJobsAndCompletesRequest() {
        DeletionRequest request = DeletionRequest.builder()
            .id("d1").installation(installation).idempotencyKey("key-1")
            .scope(DeletionRequestScope.INSTALLATION_CONTENT).requestedAt(NOW)
            .build();
        request.start();
        when(deletionRequests.findById("d1")).thenReturn(Optional.of(request));

        transitions.deleteContent("d1");

        verify(jobs).deleteByInstallation(installation);
        assertEquals(DeletionRequestStatus.COMPLETED, request.getStatus());
        assertEquals(NOW, request.getCompletedAt());
    }

    @Test
    void deleteContentThrowsWhenRequestMissing() {
        when(deletionRequests.findById("missing")).thenReturn(Optional.empty());
        assertThrows(DeletionRequestNotFoundException.class, () -> transitions.deleteContent("missing"));
    }

    @Test
    void markFailedRecordsErrorCode() {
        DeletionRequest request = DeletionRequest.builder()
            .id("d1").installation(installation).idempotencyKey("key-1")
            .scope(DeletionRequestScope.INSTALLATION_CONTENT).requestedAt(NOW)
            .build();
        request.start();
        when(deletionRequests.findById("d1")).thenReturn(Optional.of(request));

        transitions.markFailed("d1", "DELETION_FAILED");

        assertEquals(DeletionRequestStatus.FAILED, request.getStatus());
        assertEquals("DELETION_FAILED", request.getErrorCode());
    }

    @Test
    void markOnUnknownRequestDoesNothing() {
        when(deletionRequests.findById("missing")).thenReturn(Optional.empty());
        transitions.markProcessing("missing"); // no exception
    }
}
