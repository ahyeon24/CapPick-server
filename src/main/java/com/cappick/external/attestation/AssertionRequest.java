package com.cappick.external.attestation;

/**
 * @param challenge 서버가 발급한 challenge 원문 바이트
 * @param keyId 등록 당시 저장해 둔 키 식별자
 * @param publicKeyBase64 등록 당시 저장해 둔 공개키
 * @param previousSignCount 직전까지 확인된 카운터 — 이 값보다 커야 재전송·키 복제가 아니라고 본다
 * @param assertionObject App Attest generateAssertion()이 반환한 CBOR
 */
public record AssertionRequest(
    byte[] challenge,
    String keyId,
    String publicKeyBase64,
    long previousSignCount,
    byte[] assertionObject
) {
}
