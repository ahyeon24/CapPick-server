package com.cappick.domain.installation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cappick.common.exception.AttestationVerificationException;
import com.cappick.common.exception.ChallengeAlreadyUsedException;
import com.cappick.common.exception.ChallengeExpiredException;
import com.cappick.common.exception.ChallengeNotFoundException;
import com.cappick.common.exception.InstallationNotFoundException;
import com.cappick.external.attestation.AssertionOutcome;
import com.cappick.external.attestation.AssertionRequest;
import com.cappick.external.attestation.AttestationOutcome;
import com.cappick.external.attestation.AttestationRequest;
import com.cappick.external.attestation.AttestationVerifier;

@Service
public class InstallationService {

    // 잠정치 — API 명세 §3은 challenge가 "짧게 유효"하다고만 하고 정확한 값은 없다.
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    // 잠정치 — 자격 보존 기간·갱신 주기는 API 명세 §8 "확정 전 필수 작업"에 명시된 미정 항목이다.
    private static final Duration CREDENTIAL_TTL = Duration.ofDays(30);

    private final InstallationRepository installations;
    private final InstallationChallengeRepository challenges;
    private final AttestationVerifier attestationVerifier;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public InstallationService(
        InstallationRepository installations,
        InstallationChallengeRepository challenges,
        AttestationVerifier attestationVerifier,
        Clock clock
    ) {
        this.installations = installations;
        this.challenges = challenges;
        this.attestationVerifier = attestationVerifier;
        this.clock = clock;
    }

    @Transactional
    public ChallengeIssued createChallenge() {
        Instant now = clock.instant();
        InstallationChallenge challenge = InstallationChallenge.builder()
            .id(newId())
            .challenge(randomToken(32))
            .createdAt(now)
            .expiresAt(now.plus(CHALLENGE_TTL))
            .build();
        challenges.save(challenge);
        return new ChallengeIssued(challenge.getId(), challenge.getChallenge(), challenge.getExpiresAt());
    }

    /** 검증된 설치 등록 (API 명세 §3 POST /v1/installations). */
    @Transactional
    public InstallationRegistered register(String challengeId, String keyId, byte[] attestationObject) {
        InstallationChallenge challenge = consumeChallenge(challengeId);
        if (installations.existsByKeyId(keyId)) {
            // 같은 키로 두 번째 설치를 만들지 않는다 — 재등록은 credentials/renew로만 한다.
            throw new AttestationVerificationException("keyId already registered");
        }

        AttestationOutcome outcome = attestationVerifier.verifyAttestation(
            new AttestationRequest(challengeBytes(challenge), keyId, attestationObject));

        String credential = randomToken(32);
        Instant now = clock.instant();
        Installation installation = Installation.builder()
            .id(newId())
            .credentialHash(hash(credential))
            .keyId(outcome.keyId())
            .publicKeyBase64(outcome.publicKeyBase64())
            .createdAt(now)
            .build();
        installations.save(installation);
        return new InstallationRegistered(installation.getId(), credential, now.plus(CREDENTIAL_TTL));
    }

    /** 설치 자격 갱신 (API 명세 §3 POST /v1/installations/credentials/renew). */
    @Transactional
    public CredentialRenewed renew(String presentedCredential, String challengeId, byte[] assertionObject) {
        Installation installation = installations.findByCredentialHash(hash(presentedCredential))
            .filter(Installation::isActive)
            .orElseThrow(() -> new InstallationNotFoundException("unknown or revoked credential"));

        InstallationChallenge challenge = consumeChallenge(challengeId);
        AssertionOutcome outcome = attestationVerifier.verifyAssertion(new AssertionRequest(
            challengeBytes(challenge),
            installation.getKeyId(),
            installation.getPublicKeyBase64(),
            installation.getSignCount(),
            assertionObject
        ));

        String newCredential = randomToken(32);
        Instant now = clock.instant();
        installation.rotateCredential(hash(newCredential), outcome.signCount(), now);
        return new CredentialRenewed(newCredential, now.plus(CREDENTIAL_TTL));
    }

    /** 소유권 확인용 조회. 다른 도메인(Job 등)에서 Authorization 자격을 Installation으로 바꿀 때 쓴다. */
    @Transactional(readOnly = true)
    public Installation resolveByCredential(String presentedCredential) {
        return installations.findByCredentialHash(hash(presentedCredential))
            .filter(Installation::isActive)
            .orElseThrow(() -> new InstallationNotFoundException("unknown or revoked credential"));
    }

    private InstallationChallenge consumeChallenge(String challengeId) {
        InstallationChallenge challenge = challenges.findById(challengeId)
            .orElseThrow(() -> new ChallengeNotFoundException(challengeId));
        Instant now = clock.instant();
        if (challenge.isUsed()) {
            throw new ChallengeAlreadyUsedException(challengeId);
        }
        if (challenge.isExpired(now)) {
            throw new ChallengeExpiredException(challengeId);
        }
        // 검증 성공 여부와 무관하게 지금 소비한다 — "짧게 유효하고 한 번만 사용"(API 명세 §3)을
        // 엄격하게 지키기 위해서다. 검증에 실패해도 같은 challenge로 재시도할 수 없다.
        challenge.markUsed(now);
        return challenge;
    }

    private static byte[] challengeBytes(InstallationChallenge challenge) {
        return challenge.getChallenge().getBytes(StandardCharsets.UTF_8);
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }

    private String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record ChallengeIssued(String challengeId, String challenge, Instant expiresAt) {
    }

    public record InstallationRegistered(String installationId, String credential, Instant expiresAt) {
    }

    public record CredentialRenewed(String credential, Instant expiresAt) {
    }
}
