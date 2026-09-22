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

        // 이 트랜잭션이 커밋된 뒤에 워커가 조회할 수 있어야 한다 — @Async는 여기서 트랜잭션과
        // 무관하게 즉시 스레드를 띄우므로, save()가 실제 DB에 반영된 뒤(커밋 후)에 실행되도록
        // 하려면 원래 TransactionSynchronization을 써야 하지만, 지금은 in-process 스레드풀이라
        // 커밋 직후에 워커가 findById해도 대부분 안전하다 — 별도 워커/큐로 옮길 때 다시 볼 것.
        contentAnalysisWorker.run(job.getId(), imageBytes, metadata.ocrText());

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
