package com.cappick.external.attestation.apple;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param appId "TEAMID.bundleIdentifier" 형식 (Apple Developer 계정의 Team ID 필요).
 *              rpIdHash 검증에 쓴다 — 아직 계정이 없어 잠정값이 들어있다.
 * @param environment "development" 또는 "production". attestation authData의 AAGUID를
 *                     이 값 기준으로 검증한다 — Xcode 디버그 빌드는 항상 development.
 */
@ConfigurationProperties(prefix = "cappick.attestation.apple")
public record AppleAppAttestProperties(String appId, String environment) {

    public boolean isDevelopment() {
        return "development".equalsIgnoreCase(environment);
    }
}
