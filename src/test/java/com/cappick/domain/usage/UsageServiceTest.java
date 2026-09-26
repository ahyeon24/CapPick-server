package com.cappick.domain.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
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

import com.cappick.domain.installation.Installation;

@ExtendWith(MockitoExtension.class)
class UsageServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");

    @Mock
    private UsageEntryRepository usageEntries;

    private UsageService service;
    private Installation installation;

    @BeforeEach
    void setUp() {
        service = new UsageService(usageEntries, Clock.fixed(NOW, ZoneOffset.UTC));
        installation = Installation.builder()
            .id("i1").credentialHash("h").keyId("k").publicKeyBase64("pk").createdAt(NOW)
            .build();
    }

    @Test
    void reserveCreatesNewEntryWhenNoneExistsForOperationKey() {
        when(usageEntries.findByInstallationAndOperationKey(installation, "op-1")).thenReturn(Optional.empty());

        service.reserve(installation, "op-1", UsageCategory.CONTENT_ANALYSIS, 1);

        verify(usageEntries).save(any(UsageEntry.class));
    }

    @Test
    void reserveReturnsExistingEntryWithoutCreatingDuplicate() {
        UsageEntry existing = UsageEntry.builder()
            .id("u1").installation(installation).operationKey("op-1")
            .category(UsageCategory.CONTENT_ANALYSIS).units(1).createdAt(NOW)
            .build();
        when(usageEntries.findByInstallationAndOperationKey(installation, "op-1")).thenReturn(Optional.of(existing));

        UsageEntry result = service.reserve(installation, "op-1", UsageCategory.CONTENT_ANALYSIS, 1);

        assertSame(existing, result);
        verify(usageEntries, never()).save(any());
    }

    @Test
    void confirmTransitionsReservedEntryToConfirmed() {
        UsageEntry entry = UsageEntry.builder()
            .id("u1").installation(installation).operationKey("op-1")
            .category(UsageCategory.CONTENT_ANALYSIS).units(1).createdAt(NOW)
            .build();
        when(usageEntries.findByInstallationAndOperationKey(installation, "op-1")).thenReturn(Optional.of(entry));

        service.confirm(installation, "op-1");

        assertEquals(UsageEntryState.CONFIRMED, entry.getState());
    }

    @Test
    void releaseTransitionsReservedEntryToReleased() {
        UsageEntry entry = UsageEntry.builder()
            .id("u1").installation(installation).operationKey("op-1")
            .category(UsageCategory.CONTENT_ANALYSIS).units(1).createdAt(NOW)
            .build();
        when(usageEntries.findByInstallationAndOperationKey(installation, "op-1")).thenReturn(Optional.of(entry));

        service.release(installation, "op-1");

        assertEquals(UsageEntryState.RELEASED, entry.getState());
    }

    @Test
    void confirmOnUnknownOperationKeyDoesNothing() {
        when(usageEntries.findByInstallationAndOperationKey(installation, "missing")).thenReturn(Optional.empty());
        service.confirm(installation, "missing"); // no exception
    }

    @Test
    void usageSummaryAggregatesConfirmedAndReservedUnitsPerCategory() {
        UsageEntry confirmed = UsageEntry.builder()
            .id("u1").installation(installation).operationKey("op-1")
            .category(UsageCategory.CONTENT_ANALYSIS).units(1).createdAt(NOW)
            .build();
        confirmed.confirm();
        UsageEntry reserved = UsageEntry.builder()
            .id("u2").installation(installation).operationKey("op-2")
            .category(UsageCategory.CONTENT_ANALYSIS).units(1).createdAt(NOW)
            .build();
        when(usageEntries.findByInstallation(installation)).thenReturn(List.of(confirmed, reserved));

        List<UsageService.CategoryUsage> summary = service.usageSummary(installation);

        UsageService.CategoryUsage contentAnalysis = summary.stream()
            .filter(s -> s.category() == UsageCategory.CONTENT_ANALYSIS)
            .findFirst().orElseThrow();
        assertEquals(1, contentAnalysis.usedUnits());
        assertEquals(1, contentAnalysis.reservedUnits());
    }
}
