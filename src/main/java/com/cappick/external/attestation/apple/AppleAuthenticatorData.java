package com.cappick.external.attestation.apple;

import java.nio.ByteBuffer;
import java.util.Arrays;

import com.cappick.common.exception.AttestationVerificationException;

/**
 * App Attest authenticator data 레이아웃 (Apple "Validating apps that connect to your server" 문서 기준).
 * attestation(등록)은 credential 블록까지 있고, assertion(갱신)은 카운터까지만 있다.
 */
record AppleAuthenticatorData(byte[] rpIdHash, byte flags, long counter, byte[] aaguid, byte[] credentialId) {

    private static final int RP_ID_HASH_LEN = 32;
    private static final int COUNTER_LEN = 4;
    private static final int AAGUID_LEN = 16;
    private static final int CRED_ID_LEN_FIELD = 2;

    static AppleAuthenticatorData parseForAttestation(byte[] authData) {
        int minLength = RP_ID_HASH_LEN + 1 + COUNTER_LEN + AAGUID_LEN + CRED_ID_LEN_FIELD;
        if (authData.length < minLength) {
            throw new AttestationVerificationException("authData too short for attestation: " + authData.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(authData);
        byte[] rpIdHash = readBytes(buffer, RP_ID_HASH_LEN);
        byte flags = buffer.get();
        long counter = readUnsignedInt(buffer);
        byte[] aaguid = readBytes(buffer, AAGUID_LEN);
        int credIdLen = Short.toUnsignedInt(buffer.getShort());
        if (buffer.remaining() < credIdLen) {
            throw new AttestationVerificationException("authData credentialId truncated");
        }
        byte[] credentialId = readBytes(buffer, credIdLen);
        return new AppleAuthenticatorData(rpIdHash, flags, counter, aaguid, credentialId);
    }

    static AppleAuthenticatorData parseForAssertion(byte[] authData) {
        int expectedLength = RP_ID_HASH_LEN + 1 + COUNTER_LEN;
        if (authData.length != expectedLength) {
            throw new AttestationVerificationException("unexpected assertion authData length: " + authData.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(authData);
        byte[] rpIdHash = readBytes(buffer, RP_ID_HASH_LEN);
        byte flags = buffer.get();
        long counter = readUnsignedInt(buffer);
        return new AppleAuthenticatorData(rpIdHash, flags, counter, null, null);
    }

    boolean rpIdHashMatches(byte[] expected) {
        return Arrays.equals(rpIdHash, expected);
    }

    private static byte[] readBytes(ByteBuffer buffer, int length) {
        byte[] out = new byte[length];
        buffer.get(out);
        return out;
    }

    private static long readUnsignedInt(ByteBuffer buffer) {
        return Integer.toUnsignedLong(buffer.getInt());
    }
}
