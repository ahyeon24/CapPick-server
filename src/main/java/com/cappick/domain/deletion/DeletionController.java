package com.cappick.domain.deletion;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.cappick.common.AuthorizationHeaders;
import com.cappick.common.exception.InvalidRequestException;
import com.cappick.domain.deletion.dto.DataDeletionRequestDTO;
import com.cappick.domain.deletion.dto.DeletionStatusResponse;
import com.cappick.domain.deletion.dto.DeletionSubmitResponse;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.installation.InstallationService;

// API 명세 §3·§7. DELETE /v1/jobs/{job_id}/content(개별 Job 콘텐츠 삭제)는 이번 범위 밖이다.
@RestController
@RequestMapping("/v1/data-deletions")
public class DeletionController {

    private final DeletionService deletionService;
    private final InstallationService installationService;

    public DeletionController(DeletionService deletionService, InstallationService installationService) {
        this.deletionService = deletionService;
        this.installationService = installationService;
    }

    @PostMapping
    public ResponseEntity<DeletionSubmitResponse> requestDeletion(
        @RequestHeader("Authorization") String authorization,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody DataDeletionRequestDTO request
    ) {
        if (request.scope() != DeletionRequestScope.INSTALLATION_CONTENT) {
            throw new InvalidRequestException("지원하지 않는 scope: " + request.scope());
        }
        Installation installation = resolveInstallation(authorization);
        DeletionRequest created = deletionService.requestInstallationDeletion(installation, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new DeletionSubmitResponse(created.getId(), created.getStatus()));
    }

    @GetMapping("/{deletionId}")
    public ResponseEntity<DeletionStatusResponse> getStatus(
        @RequestHeader("Authorization") String authorization,
        @PathVariable String deletionId
    ) {
        Installation installation = resolveInstallation(authorization);
        DeletionRequest request = deletionService.getStatus(installation, deletionId);
        return ResponseEntity.ok(new DeletionStatusResponse(
            request.getId(), request.getStatus(), request.getCompletedAt(), request.getErrorCode()));
    }

    private Installation resolveInstallation(String authorizationHeader) {
        String credential = AuthorizationHeaders.extractBearerToken(authorizationHeader);
        return installationService.resolveByCredential(credential);
    }
}
