package com.cappick.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.cappick.external.attestation.AttestationVerifier;
import com.cappick.external.attestation.DevBypassAttestationVerifier;
import com.cappick.external.attestation.apple.AppleAppAttestProperties;
import com.cappick.external.attestation.apple.AppleAppAttestVerifier;

/**
 * {@code cappick.attestation.mode}에 따라 실제 구현을 고른다. bypass는 개발 전용이라, "production"
 * 스프링 프로필이 켜져 있으면 시작 시점에 실패시켜 운영에서 실수로 켜지는 걸 막는다.
 */
@Configuration
@EnableConfigurationProperties(AppleAppAttestProperties.class)
public class AttestationConfig {

    @Bean
    public AttestationVerifier attestationVerifier(
        @Value("${cappick.attestation.mode}") String mode,
        AppleAppAttestProperties appleProperties,
        Environment environment
    ) {
        if ("bypass".equalsIgnoreCase(mode)) {
            if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production"))) {
                throw new IllegalStateException(
                    "cappick.attestation.mode=bypass는 production 프로필에서 쓸 수 없다");
            }
            return new DevBypassAttestationVerifier();
        }
        if ("development".equalsIgnoreCase(mode) || "production".equalsIgnoreCase(mode)) {
            return new AppleAppAttestVerifier(appleProperties);
        }
        throw new IllegalStateException("알 수 없는 cappick.attestation.mode: " + mode);
    }
}
