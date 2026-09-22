package com.cappick.external.attestation.apple;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.cappick.common.exception.AttestationVerificationException;

class AppleNonceExtensionTest {

    @Test
    void extractsNonceFromWellFormedExtension() {
        byte[] nonce = new byte[32];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) i;
        }

        byte[] extensionValue = encodeExtension(nonce);

        assertArrayEquals(nonce, AppleNonceExtension.extractNonce(extensionValue));
    }

    @Test
    void rejectsWrongOuterTag() {
        byte[] malformed = {0x05, 0x02, 0x00, 0x00};
        assertThrows(AttestationVerificationException.class, () -> AppleNonceExtension.extractNonce(malformed));
    }

    @Test
    void rejectsTruncatedData() {
        byte[] truncated = {0x04, 0x10, 0x01, 0x02};
        assertThrows(AttestationVerificationException.class, () -> AppleNonceExtension.extractNonce(truncated));
    }

    /** 테스트 전용 인코더 — 실제 코드(AppleNonceExtension)가 파싱하는 구조를 거꾸로 만든다. */
    private static byte[] encodeExtension(byte[] nonce) {
        byte[] octetString = tlv(0x04, nonce);
        byte[] context = tlv(0xA1, octetString);
        byte[] sequence = tlv(0x30, context);
        return tlv(0x04, sequence);
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (content.length < 128) {
            out.write(content.length);
        } else {
            byte[] lengthBytes = encodeLongLength(content.length);
            out.write(0x80 | lengthBytes.length);
            out.writeBytes(lengthBytes);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] encodeLongLength(int length) {
        byte[] full = {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length};
        int start = 0;
        while (start < 3 && full[start] == 0) {
            start++;
        }
        return Arrays.copyOfRange(full, start, full.length);
    }
}
