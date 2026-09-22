package com.cappick.domain.job;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

import com.cappick.common.AuthorizationHeaders;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.installation.InstallationService;
import com.cappick.domain.job.dto.ContentAnalysisMetadataDTO;
import com.cappick.domain.job.dto.JobStatusResponse;
import com.cappick.domain.job.dto.JobSubmitResponse;

// API 명세 §3·§4. content_analysis만 구현 — 다른 kind는 API 명세가 application/json이라고
// 정해뒀지만(§4), 이 컨트롤러는 지금 multipart 전용이다. kind가 늘어나면 나눠야 한다.
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
        JobService.JobSubmitted submitted = jobService.submitContentAnalysis(
            installation, idempotencyKey, metadata, readBytes(image));
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
