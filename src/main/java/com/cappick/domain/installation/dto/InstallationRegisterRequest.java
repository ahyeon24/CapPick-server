package com.cappick.domain.installation.dto;

import jakarta.validation.constraints.NotBlank;

/** attestationObject는 App Attest attestKey()가 반환한 CBOR를 base64로 인코딩한 값. */
public record InstallationRegisterRequest(
    @NotBlank String challengeId,
    @NotBlank String keyId,
    @NotBlank String attestationObject
) {
}
