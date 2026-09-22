package com.cappick.external.attestation.apple;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import com.cappick.common.exception.AttestationVerificationException;

class AppleAuthenticatorDataTest {

    @Test
    void parsesAttestationLayout() {
        byte[] rpIdHash = fill(32, (byte) 0xAA);
        byte[] aaguid = "appattestdevelop".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] credentialId = {1, 2, 3, 4, 5};
        byte[] authData = buildAttestationAuthData(rpIdHash, (byte) 0x40, 0L, aaguid, credentialId);

        AppleAuthenticatorData parsed = AppleAuthenticatorData.parseForAttestation(authData);

        assertArrayEquals(rpIdHash, parsed.rpIdHash());
        assertEquals(0L, parsed.counter());
        assertArrayEquals(aaguid, parsed.aaguid());
        assertArrayEquals(credentialId, parsed.credentialId());
        assertEquals(true, parsed.rpIdHashMatches(rpIdHash));
    }

    @Test
    void parsesAssertionLayoutWithoutCredentialBlock() {
        byte[] rpIdHash = fill(32, (byte) 0x11);
        byte[] authData = buildAssertionAuthData(rpIdHash, (byte) 0x01, 42L);

        AppleAuthenticatorData parsed = AppleAuthenticatorData.parseForAssertion(authData);

        assertArrayEquals(rpIdHash, parsed.rpIdHash());
        assertEquals(42L, parsed.counter());
    }

    @Test
    void rejectsTooShortAttestationAuthData() {
        byte[] tooShort = new byte[10];
        assertThrows(AttestationVerificationException.class, () -> AppleAuthenticatorData.parseForAttestation(tooShort));
    }

    @Test
    void rejectsWrongLengthAssertionAuthData() {
        byte[] wrongLength = new byte[40];
        assertThrows(AttestationVerificationException.class, () -> AppleAuthenticatorData.parseForAssertion(wrongLength));
    }

    private static byte[] fill(int length, byte value) {
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static byte[] buildAttestationAuthData(byte[] rpIdHash, byte flags, long counter, byte[] aaguid, byte[] credentialId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(rpIdHash);
        out.write(flags);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) counter).array());
        out.writeBytes(aaguid);
        out.writeBytes(ByteBuffer.allocate(2).putShort((short) credentialId.length).array());
        out.writeBytes(credentialId);
        return out.toByteArray();
    }

    private static byte[] buildAssertionAuthData(byte[] rpIdHash, byte flags, long counter) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(rpIdHash);
        out.write(flags);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) counter).array());
        return out.toByteArray();
    }
}
