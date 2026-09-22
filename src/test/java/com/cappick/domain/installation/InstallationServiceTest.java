package com.cappick.domain.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cappick.common.exception.AttestationVerificationException;
import com.cappick.common.exception.ChallengeAlreadyUsedException;
import com.cappick.common.exception.ChallengeExpiredException;
import com.cappick.common.exception.ChallengeNotFoundException;
import com.cappick.common.exception.InstallationNotFoundException;
import com.cappick.external.attestation.AssertionOutcome;
import com.cappick.external.attestation.AttestationOutcome;
import com.cappick.external.attestation.AttestationVerifier;

@ExtendWith(MockitoExtension.class)
class InstallationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Mock
    private InstallationRepository installations;
    @Mock
    private InstallationChallengeRepository challenges;
    @Mock
    private AttestationVerifier attestationVerifier;

    private InstallationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new InstallationService(installations, challenges, attestationVerifier, clock);
    }

    @Test
    void createChallengeSavesAFiveMinuteChallenge() {
        InstallationService.ChallengeIssued issued = service.createChallenge();

        ArgumentCaptor<InstallationChallenge> captor = ArgumentCaptor.forClass(InstallationChallenge.class);
        verify(challenges).save(captor.capture());
        assertEquals(issued.challengeId(), captor.getValue().getId());
        assertEquals(issued.challenge(), captor.getValue().getChallenge());
        assertEquals(NOW.plusSeconds(300), captor.getValue().getExpiresAt());
        assertTrue(!captor.getValue().isUsed());
    }

    @Test
    void registerRejectsUnknownChallenge() {
        when(challenges.findById("missing")).thenReturn(Optional.empty());

        assertThrows(ChallengeNotFoundException.class,
            () -> service.register("missing", "key-1", new byte[]{1}));
    }

    @Test
    void registerRejectsExpiredChallenge() {
        InstallationChallenge expired = challenge(NOW.minusSeconds(600), NOW.minusSeconds(1));
        when(challenges.findById("c1")).thenReturn(Optional.of(expired));

        assertThrows(ChallengeExpiredException.class,
            () -> service.register("c1", "key-1", new byte[]{1}));
        verify(installations, never()).save(any());
    }

    @Test
    void registerRejectsAlreadyUsedChallenge() {
        InstallationChallenge used = challenge(NOW.minusSeconds(10), NOW.plusSeconds(290));
        used.markUsed(NOW.minusSeconds(5));
        when(challenges.findById("c1")).thenReturn(Optional.of(used));

        assertThrows(ChallengeAlreadyUsedException.class,
            () -> service.register("c1", "key-1", new byte[]{1}));
    }

    @Test
    void registerRejectsAlreadyRegisteredKeyId() {
        InstallationChallenge fresh = challenge(NOW, NOW.plusSeconds(300));
        when(challenges.findById("c1")).thenReturn(Optional.of(fresh));
        when(installations.existsByKeyId("key-1")).thenReturn(true);

        assertThrows(AttestationVerificationException.class,
            () -> service.register("c1", "key-1", new byte[]{1}));
        // 같은 challenge를 다른 키로 재시도할 수 없게, 실패해도 challenge는 소비된 상태로 남는다.
        assertTrue(fresh.isUsed());
    }

    @Test
    void registerStoresHashedCredentialNotPlaintext() {
        InstallationChallenge fresh = challenge(NOW, NOW.plusSeconds(300));
        when(challenges.findById("c1")).thenReturn(Optional.of(fresh));
        when(installations.existsByKeyId("key-1")).thenReturn(false);
        when(attestationVerifier.verifyAttestation(any())).thenReturn(new AttestationOutcome("key-1", "pk-base64"));

        InstallationService.InstallationRegistered result = service.register("c1", "key-1", new byte[]{1, 2, 3});

        ArgumentCaptor<Installation> captor = ArgumentCaptor.forClass(Installation.class);
        verify(installations).save(captor.capture());
        Installation saved = captor.getValue();

        assertEquals("key-1", saved.getKeyId());
        assertEquals("pk-base64", saved.getPublicKeyBase64());
        assertEquals(sha256Hex(result.credential()), saved.getCredentialHash());
        assertNotEquals(result.credential(), saved.getCredentialHash(), "평문 자격을 그대로 저장하면 안 된다");
        assertEquals(NOW.plusSeconds(60L * 60 * 24 * 30), result.expiresAt());
        assertTrue(fresh.isUsed());
    }

    @Test
    void renewRejectsUnknownCredential() {
        when(installations.findByCredentialHash(any())).thenReturn(Optional.empty());

        assertThrows(InstallationNotFoundException.class,
            () -> service.renew("some-token", "c1", new byte[]{1}));
        verify(challenges, never()).findById(any());
    }

    @Test
    void renewRejectsRevokedInstallation() {
        Installation revoked = installation("i1", "key-1", "pk", 3);
        revoked.revoke();
        when(installations.findByCredentialHash(any())).thenReturn(Optional.of(revoked));

        assertThrows(InstallationNotFoundException.class,
            () -> service.renew("some-token", "c1", new byte[]{1}));
    }

    @Test
    void renewRotatesCredentialAndAdvancesSignCount() {
        Installation active = installation("i1", "key-1", "pk-base64", 3);
        when(installations.findByCredentialHash(any())).thenReturn(Optional.of(active));
        InstallationChallenge fresh = challenge(NOW, NOW.plusSeconds(300));
        when(challenges.findById("c1")).thenReturn(Optional.of(fresh));
        when(attestationVerifier.verifyAssertion(any())).thenReturn(new AssertionOutcome(4L));

        InstallationService.CredentialRenewed result = service.renew("old-token", "c1", new byte[]{9});

        assertEquals(4L, active.getSignCount());
        assertEquals(sha256Hex(result.credential()), active.getCredentialHash());
        assertEquals(NOW, active.getLastSeenAt());
        assertTrue(fresh.isUsed());
    }

    private static InstallationChallenge challenge(Instant createdAt, Instant expiresAt) {
        return InstallationChallenge.builder()
            .id("c1")
            .challenge("challenge-value")
            .createdAt(createdAt)
            .expiresAt(expiresAt)
            .build();
    }

    private static Installation installation(String id, String keyId, String publicKey, long signCount) {
        Installation installation = Installation.builder()
            .id(id)
            .credentialHash("old-hash")
            .keyId(keyId)
            .publicKeyBase64(publicKey)
            .createdAt(NOW.minusSeconds(1000))
            .build();
        installation.rotateCredential("old-hash", signCount, NOW.minusSeconds(1000));
        return installation;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
