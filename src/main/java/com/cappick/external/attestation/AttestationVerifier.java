package com.cappick.external.attestation;

import com.cappick.common.exception.AttestationVerificationException;

/**
 * 설치 검증 경계 (시스템 설계 §7). 실제 공급자(Apple App Attest)나 개발용 우회 구현을
 * 이 인터페이스 뒤로 숨긴다 — 서비스 로직은 어느 쪽이 붙어 있는지 모른다.
 */
public interface AttestationVerifier {

    /** 신규 설치 등록(attestKey 결과) 검증. 실패 시 {@link AttestationVerificationException}. */
    AttestationOutcome verifyAttestation(AttestationRequest request);

    /** 자격 갱신(generateAssertion 결과) 검증. 실패 시 {@link AttestationVerificationException}. */
    AssertionOutcome verifyAssertion(AssertionRequest request);
}
