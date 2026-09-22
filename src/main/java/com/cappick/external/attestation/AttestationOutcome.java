package com.cappick.external.attestation;

/**
 * @param keyId 검증된 키 식별자 (base64) — 요청의 keyId와 동일해야 함(구현이 재확인)
 * @param publicKeyBase64 등록된 공개키(원시 EC 포인트). 이후 assertion 서명 검증에 저장해 둔다.
 */
public record AttestationOutcome(String keyId, String publicKeyBase64) {
}
