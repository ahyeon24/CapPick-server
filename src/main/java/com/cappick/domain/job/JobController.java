package com.cappick.domain.job;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

import com.cappick.common.AuthorizationHeaders;
import com.cappick.common.exception.InvalidRequestException;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.installation.InstallationService;
import com.cappick.domain.job.dto.ClassificationRequestDTO;
import com.cappick.domain.job.dto.ContentAnalysisMetadataDTO;
import com.cappick.domain.job.dto.JobStatusResponse;
import com.cappick.domain.job.dto.JobSubmitResponse;

// API 명세 §3·§4. content_analysis(multipart)·classification(json) 구현 — 나머지(folder_criteria,
// search_document)도 application/json이라 늘어나면 submitJson()을 kind로 더 분기해야 한다(지금은
// json kind가 classification 하나뿐이라 바로 그 DTO로 바인딩한다).
@RestController
@RequestMapping("/v1/jobs")
public class JobController {

    private final JobService jobService;
    private final InstallationService installationService;

    public JobController(JobService jobService, InstallationService installationService) {
        this.jobService = jobService;
        this.installationService = installationService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobSubmitResponse> submitContentAnalysis(
        @RequestHeader("Authorization") String authorization,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestPart("metadata") @Valid ContentAnalysisMetadataDTO metadata,
        @RequestPart("image") MultipartFile image
    ) {
        Installation installation = resolveInstallation(authorization);
        // 클라이언트가 실제로 선언한 형식을 그대로 쓴다 — "image/png"로 하드코딩했다가 앱이 보내는
        // 실제 JPEG 바이트와 Gemini에 알리는 mime_type이 어긋나는 버그가 있었다(2026-09-25 발견).
        String mimeType = image.getContentType() != null ? image.getContentType() : MediaType.IMAGE_JPEG_VALUE;
        JobService.JobSubmitted submitted = jobService.submitContentAnalysis(
            installation, idempotencyKey, metadata, readBytes(image), mimeType);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            new JobSubmitResponse(submitted.jobId(), submitted.status(), submitted.pollAfterSeconds()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JobSubmitResponse> submitClassification(
        @RequestHeader("Authorization") String authorization,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody ClassificationRequestDTO request
    ) {
        if (request.kind() != JobKind.CLASSIFICATION) {
            throw new InvalidRequestException("지원하지 않는 kind: " + request.kind());
        }
        Installation installation = resolveInstallation(authorization);
        JobService.JobSubmitted submitted = jobService.submitClassification(installation, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            new JobSubmitResponse(submitted.jobId(), submitted.status(), submitted.pollAfterSeconds()));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getStatus(
        @RequestHeader("Authorization") String authorization,
        @PathVariable String jobId
    ) {
        Installation installation = resolveInstallation(authorization);
        return ResponseEntity.ok(jobService.getStatus(installation, jobId));
    }

    private Installation resolveInstallation(String authorizationHeader) {
        String credential = AuthorizationHeaders.extractBearerToken(authorizationHeader);
        return installationService.resolveByCredential(credential);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read uploaded image", e);
        }
    }
}
