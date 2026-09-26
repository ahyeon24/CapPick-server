package com.cappick.domain.deletion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

import com.cappick.common.exception.DeletionRequestNotFoundException;
import com.cappick.common.exception.InstallationDeletionInProgressException;
import com.cappick.domain.installation.Installation;

@ExtendWith(MockitoExtension.class)
class DeletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");

    @Mock
    private DeletionRequestRepository deletionRequests;
    @Mock
    private DeletionWorker deletionWorker;

    private DeletionService service;
    private Installation installation;

    @BeforeEach
    void setUp() {
        service = new DeletionService(deletionRequests, deletionWorker, Clock.fixed(NOW, ZoneOffset.UTC));
        installation = Installation.builder()
            .id("i1").credentialHash("h").keyId("k").publicKeyBase64("pk").createdAt(NOW)
            .build();
    }

    @Test
    void requestCreatesPendingDeletionAndTriggersWorker() {
        when(deletionRequests.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.empty());
        when(deletionRequests.existsActiveByInstallation(installation)).thenReturn(false);

        DeletionRequest created = service.requestInstallationDeletion(installation, "key-1");

        assertEquals(DeletionRequestStatus.PENDING, created.getStatus());
        verify(deletionRequests).save(any(DeletionRequest.class));
        verify(deletionWorker).run(created.getId());
    }

    @Test
    void resubmittingSameKeyReturnsExistingRequestWithoutCreatingDuplicate() {
        DeletionRequest existing = DeletionRequest.builder()
            .id("d1").installation(installation).idempotencyKey("key-1")
            .scope(DeletionRequestScope.INSTALLATION_CONTENT).requestedAt(NOW)
            .build();
        when(deletionRequests.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.of(existing));

        DeletionRequest result = service.requestInstallationDeletion(installation, "key-1");

        assertSame(existing, result);
        verify(deletionRequests, never()).save(any());
        verify(deletionWorker, never()).run(any());
    }

    @Test
    void requestBlockedWhenAnotherDeletionAlreadyActive() {
        when(deletionRequests.findByInstallationAndIdempotencyKey(installation, "key-1")).thenReturn(Optional.empty());
        when(deletionRequests.existsActiveByInstallation(installation)).thenReturn(true);

        assertThrows(InstallationDeletionInProgressException.class,
            () -> service.requestInstallationDeletion(installation, "key-1"));
        verify(deletionRequests, never()).save(any());
    }

    @Test
    void getStatusThrowsWhenNotFoundOrNotOwned() {
        when(deletionRequests.findByIdAndInstallation("missing", installation)).thenReturn(Optional.empty());
        assertThrows(DeletionRequestNotFoundException.class, () -> service.getStatus(installation, "missing"));
    }
}
