package com.cappick.external.attestation;

import java.util.Base64;

import com.cappick.common.exception.AttestationVerificationException;

/**
 * 로컬 개발 전용. Apple 서버 왕복 검증을 완전히 건너뛰고 요청 형태만 확인한다.
 * {@code cappick.attestation.mode=bypass}일 때만 선택되며, 운영 프로필에서는
 * {@code AttestationConfig}가 시작 시점에 이 빈이 선택되지 못하게 막는다.
 */
public class DevBypassAttestationVerifier implements AttestationVerifier {

    @Override
    public AttestationOutcome verifyAttestation(AttestationRequest request) {
        if (request.attestationObject().length == 0) {
            throw new AttestationVerificationException("empty attestation object");
        }
        // 실제 공개키가 없으므로 keyId 자체를 자리표시자 공개키로 둔다 — 검증 없이 저장만 한다는 뜻.
        String placeholderPublicKey = Base64.getEncoder().encodeToString(request.keyId().getBytes());
        return new AttestationOutcome(request.keyId(), placeholderPublicKey);
    }

    @Override
    public AssertionOutcome verifyAssertion(AssertionRequest request) {
        if (request.assertionObject().length == 0) {
            throw new AttestationVerificationException("empty assertion object");
        }
        // 서명 검증 없이 카운터만 진행시킨다. 재전송 방지 효과는 없다 — 개발 전용이라 허용.
        return new AssertionOutcome(request.previousSignCount() + 1);
    }
}
