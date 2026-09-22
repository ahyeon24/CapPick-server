package com.cappick.external.attestation;

/**
 * @param challenge 서버가 발급한 challenge 원문 바이트 (base64 디코딩된 상태)
 * @param keyId App Attest generateKey()가 반환한 키 식별자 (base64)
 * @param attestationObject App Attest attestKey()가 반환한 CBOR attestation object
 */
public record AttestationRequest(byte[] challenge, String keyId, byte[] attestationObject) {
}
