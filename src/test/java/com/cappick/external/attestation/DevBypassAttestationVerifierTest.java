package com.cappick.external.attestation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.cappick.common.exception.AttestationVerificationException;

class DevBypassAttestationVerifierTest {

    private final DevBypassAttestationVerifier verifier = new DevBypassAttestationVerifier();

    @Test
    void attestationSucceedsWithNonEmptyPayloadAndEchoesKeyId() {
        AttestationOutcome outcome = verifier.verifyAttestation(
            new AttestationRequest(new byte[]{1}, "key-1", new byte[]{9, 9}));

        assertEquals("key-1", outcome.keyId());
    }

    @Test
    void attestationRejectsEmptyPayload() {
        assertThrows(AttestationVerificationException.class, () ->
            verifier.verifyAttestation(new AttestationRequest(new byte[]{1}, "key-1", new byte[0])));
    }

    @Test
    void assertionAlwaysIncrementsCounter() {
        AssertionOutcome outcome = verifier.verifyAssertion(
            new AssertionRequest(new byte[]{1}, "key-1", "pk", 7L, new byte[]{9}));

        assertEquals(8L, outcome.signCount());
    }

    @Test
    void assertionRejectsEmptyPayload() {
        assertThrows(AttestationVerificationException.class, () ->
            verifier.verifyAssertion(new AssertionRequest(new byte[]{1}, "key-1", "pk", 0L, new byte[0])));
    }
}
