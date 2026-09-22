package com.cappick.domain.installation.dto;

import jakarta.validation.constraints.NotBlank;

/** assertionObject는 App Attest generateAssertion()이 반환한 CBOR를 base64로 인코딩한 값. */
public record CredentialRenewRequest(@NotBlank String challengeId, @NotBlank String assertionObject) {
}
