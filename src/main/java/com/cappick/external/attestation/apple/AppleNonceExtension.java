package com.cappick.external.attestation.apple;

import com.cappick.common.exception.AttestationVerificationException;

/**
 * App Attest credential 인증서의 nonce 확장(OID 1.2.840.113635.100.8.2)을 파싱한다.
 * 구조는 고정돼 있다: {@code SEQUENCE { [1] EXPLICIT OCTET STRING(32) } }, 그 바깥을
 * {@link java.security.cert.X509Certificate#getExtensionValue}가 한 번 더 OCTET STRING으로 감싸서 준다.
 * 범용 ASN.1 라이브러리 없이 이 고정된 모양만 손으로 파싱한다 — 다른 모양이면 검증 실패로 취급한다.
 */
final class AppleNonceExtension {

    static final String OID = "1.2.840.113635.100.8.2";

    private AppleNonceExtension() {
    }

    static byte[] extractNonce(byte[] extensionValue) {
        // 바깥 OCTET STRING(태그 0x04) 한 겹을 벗긴다.
        DerReader outer = new DerReader(extensionValue);
        byte[] inner = outer.readOctetString();

        // SEQUENCE { [1] EXPLICIT OCTET STRING }
        DerReader sequence = new DerReader(inner);
        byte[] sequenceContent = sequence.readTagged(0x30);
        DerReader context = new DerReader(sequenceContent);
        byte[] contextContent = context.readTagged(0xA1);
        DerReader nonceReader = new DerReader(contextContent);
        return nonceReader.readOctetString();
    }

    /** 이 파일 안에서만 쓰는 최소 DER 리더 — TLV(tag-length-value) 하나만 읽는다. */
    private static final class DerReader {
        private final byte[] data;
        private int offset;

        DerReader(byte[] data) {
            this.data = data;
        }

        byte[] readOctetString() {
            return readTagged(0x04);
        }

        byte[] readTagged(int expectedTag) {
            requireRemaining(1);
            int tag = data[offset++] & 0xFF;
            if (tag != expectedTag) {
                throw new AttestationVerificationException(
                    "unexpected DER tag 0x%02X, expected 0x%02X".formatted(tag, expectedTag));
            }
            int length = readLength();
            requireRemaining(length);
            byte[] content = new byte[length];
            System.arraycopy(data, offset, content, 0, length);
            offset += length;
            return content;
        }

        private int readLength() {
            requireRemaining(1);
            int first = data[offset++] & 0xFF;
            if ((first & 0x80) == 0) {
                return first;
            }
            int numBytes = first & 0x7F;
            requireRemaining(numBytes);
            int length = 0;
            for (int i = 0; i < numBytes; i++) {
                length = (length << 8) | (data[offset++] & 0xFF);
            }
            return length;
        }

        private void requireRemaining(int n) {
            if (data.length - offset < n) {
                throw new AttestationVerificationException("malformed nonce extension DER");
            }
        }
    }
}
