package com.cappick.domain.installation;

import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.cappick.common.AuthorizationHeaders;
import com.cappick.common.exception.InvalidRequestException;
import com.cappick.domain.installation.dto.ChallengeResponse;
import com.cappick.domain.installation.dto.CredentialRenewRequest;
import com.cappick.domain.installation.dto.CredentialRenewResponse;
import com.cappick.domain.installation.dto.InstallationRegisterRequest;
import com.cappick.domain.installation.dto.InstallationRegisterResponse;

// API 명세 §3. 로그인·회원가입 API가 아니다 — 앱 설치 단위 요청 보호 절차 (시스템 설계 §7).
@RestController
@RequestMapping("/v1/installations")
public class InstallationController {

    private final InstallationService installationService;

    public InstallationController(InstallationService installationService) {
        this.installationService = installationService;
    }

    @PostMapping("/challenges")
    public ResponseEntity<ChallengeResponse> createChallenge() {
        InstallationService.ChallengeIssued issued = installationService.createChallenge();
        return ResponseEntity.ok(new ChallengeResponse(issued.challengeId(), issued.challenge(), issued.expiresAt()));
    }

    @PostMapping
    public ResponseEntity<InstallationRegisterResponse> register(@Valid @RequestBody InstallationRegisterRequest request) {
        InstallationService.InstallationRegistered registered = installationService.register(
            request.challengeId(), request.keyId(), decodeBase64(request.attestationObject()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new InstallationRegisterResponse(
            registered.installationId(), registered.credential(), registered.expiresAt()));
    }

    @PostMapping("/credentials/renew")
    public ResponseEntity<CredentialRenewResponse> renew(
        @RequestHeader("Authorization") String authorization,
        @Valid @RequestBody CredentialRenewRequest request
    ) {
        String credential = AuthorizationHeaders.extractBearerToken(authorization);
        InstallationService.CredentialRenewed renewed = installationService.renew(
            credential, request.challengeId(), decodeBase64(request.assertionObject()));
        return ResponseEntity.ok(new CredentialRenewResponse(renewed.credential(), renewed.expiresAt()));
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("malformed base64 field");
        }
    }
}
