package com.cappick.domain.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.cappick.common.exception.IdempotencyConflictException;
import com.cappick.common.exception.JobNotFoundException;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.job.dto.ContentAnalysisMetadataDTO;
import com.cappick.domain.job.dto.ContentAnalysisResultDTO;
import com.cappick.domain.job.dto.JobErrorDTO;
import com.cappick.domain.job.dto.JobStatusResponse;

@Service
public class JobService {

    // 잠정치 — API 명세는 poll_after_seconds를 "안내"한다고만 하고 값의 근거는 없다.
    private static final int POLL_AFTER_SECONDS = 2;

    private final JobRepository jobs;
    private final ContentAnalysisWorker contentAnalysisWorker;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JobService(
        JobRepository jobs, ContentAnalysisWorker contentAnalysisWorker, ObjectMapper objectMapper, Clock clock
    ) {
        this.jobs = jobs;
        this.contentAnalysisWorker = contentAnalysisWorker;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * API 명세 §4 content_analysis 제출. 같은 Idempotency-Key + 같은 요청이면 기존 작업을 그대로
     * 돌려주고(재실행 안 함), 같은 키에 다른 요청이면 {@link IdempotencyConflictException}.
     */
    @Transactional
    public JobSubmitted submitContentAnalysis(
        Installation installation, String idempotencyKey, ContentAnalysisMetadataDTO metadata, byte[] imageBytes
    ) {
        String digest = requestDigest(imageBytes, metadata.contentRevision(), metadata.ocrText());

        Optional<Job> existing = jobs.findByInstallationAndIdempotencyKey(installation, idempotencyKey);
        if (existing.isPresent()) {
            Job job = existing.get();
            if (!job.getRequestDigest().equals(digest)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return new JobSubmitted(job.getId(), job.getStatus(), POLL_AFTER_SECONDS);
        }

        Job job = Job.builder()
            .id(UUID.randomUUID().toString())
            .installation(installation)
            .clientReference(metadata.clientReference())
            .idempotencyKey(idempotencyKey)
            .requestDigest(digest)
            .kind(JobKind.CONTENT_ANALYSIS)
            .contentRevision(metadata.contentRevision())
            .createdAt(clock.instant())
            .build();
        jobs.save(job);

        // 이 트랜잭션이 커밋된 뒤에야 워커를 띄운다. @Async는 여기서 호출하는 즉시 다른 스레드를
        // 띄우는데, 이 메서드의 트랜잭션은 그 스레드보다 먼저 커밋된다는 보장이 없다 — 실제로
        // 커밋 전에 워커 스레드가 먼저 findById를 실행해 아직 안 보이는 job을 못 찾고(ifPresent가
        // 조용히 no-op), 그 뒤 rows가 커밋된 뒤에야 도는 markFailed가 "RUNNING이어야 하는데
        // QUEUED"로 터지는 레이스를 2026-09-22에 실제 서버로 재현했다. afterCommit()으로 미루면
        // 워커가 findById할 때 job이 항상 보장된다. 동기화가 활성화 안 된 컨텍스트(예: 단위
        // 테스트에서 트랜잭션 프록시 없이 이 메서드를 직접 호출하는 경우)에서는 예전처럼 바로 띄운다.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    contentAnalysisWorker.run(job.getId(), imageBytes, metadata.ocrText());
                }
            });
        } else {
            contentAnalysisWorker.run(job.getId(), imageBytes, metadata.ocrText());
        }

        return new JobSubmitted(job.getId(), job.getStatus(), POLL_AFTER_SECONDS);
    }

    @Transactional(readOnly = true)
    public JobStatusResponse getStatus(Installation installation, String jobId) {
        Job job = jobs.findByIdAndInstallation(jobId, installation)
            .orElseThrow(() -> new JobNotFoundException(jobId));

        ContentAnalysisResultDTO result = null;
        if (job.getStatus() == JobStatus.SUCCEEDED && job.getResultJson() != null) {
            try {
                result = objectMapper.readValue(job.getResultJson(), ContentAnalysisResultDTO.class);
            } catch (JacksonException e) {
                throw new IllegalStateException("stored result_json is not valid JSON for job " + jobId, e);
            }
        }

        JobErrorDTO error = job.getStatus() == JobStatus.FAILED
            ? new JobErrorDTO(job.getErrorCode(), "작업이 실패했습니다.", true)
            : null;

        return new JobStatusResponse(
            job.getId(), job.getStatus(), job.getClientReference(), result, job.getResultExpiresAt(), error);
    }

    // API 명세 §2: "이미지 바이트 등 입력 전체의 digest를 비교한다".
    private static String requestDigest(byte[] imageBytes, int contentRevision, String ocrText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(imageBytes);
            digest.update(Integer.toString(contentRevision).getBytes(StandardCharsets.UTF_8));
            digest.update((ocrText == null ? "" : ocrText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record JobSubmitted(String jobId, JobStatus status, int pollAfterSeconds) {
    }
}
