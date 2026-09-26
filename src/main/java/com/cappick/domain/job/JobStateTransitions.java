package com.cappick.domain.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.cappick.domain.job.dto.ClassificationDecisionDTO;
import com.cappick.domain.job.dto.ClassificationResultDTO;
import com.cappick.domain.job.dto.ContentAnalysisResultDTO;
import com.cappick.domain.usage.UsageService;
import com.cappick.external.classification.ClassificationResult;
import com.cappick.external.contentanalysis.ContentAnalysisResult;

/**
 * Job 상태 전이만 담당하는 별도 빈. {@link AsyncContentAnalysisWorker}가 이걸 자기 자신의
 * 메서드로 두면(같은 클래스 안에서 this.markRunning() 식으로 호출) Spring AOP의
 * self-invocation 문제로 {@code @Transactional}이 걸리지 않는다 — 프록시를 거치지 않기
 * 때문이다. 실제로 이 버그가 있었다: 이미지 분석을 실행하기 전 markRunning()을 호출해도
 * 트랜잭션이 없어서 상태가 DB에 반영되지 않았고, 그 뒤 markFailed()가 QUEUED 상태를 보고
 * "RUNNING이어야 하는데 아니다"로 실패했다. Mockito 단위 테스트는 프록시 자체가 없어서
 * 이 문제를 못 잡았고, 실제 서버를 띄워 진짜 Gemini 호출까지 돌려본 뒤에야 드러났다.
 */
@Service
public class JobStateTransitions {

    // 잠정치 — 결과 보존 기간은 API 명세 §8 "확정 전 필수 작업"의 미정 항목이다.
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    private final JobRepository jobs;
    private final UsageService usageService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JobStateTransitions(JobRepository jobs, UsageService usageService, ObjectMapper objectMapper, Clock clock) {
        this.jobs = jobs;
        this.usageService = usageService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void markRunning(String jobId) {
        jobs.findById(jobId).ifPresent(Job::start);
    }

    @Transactional
    public void markSucceeded(String jobId, ContentAnalysisResult result) {
        jobs.findById(jobId).ifPresent(job -> {
            ContentAnalysisResultDTO dto = new ContentAnalysisResultDTO(
                job.getContentRevision(), result.ocrText(), result.observedEvidence(),
                result.searchText(), result.embedding(), result.embeddingModelVersion());
            finishSucceeded(job, dto);
        });
    }

    @Transactional
    public void markClassificationSucceeded(String jobId, ClassificationResult result) {
        jobs.findById(jobId).ifPresent(job -> {
            List<ClassificationDecisionDTO> decisions = result.decisions().stream()
                .map(d -> new ClassificationDecisionDTO(d.folderId(), d.criteriaVersion(), d.decision(), d.evidence()))
                .toList();
            finishSucceeded(job, new ClassificationResultDTO(decisions));
        });
    }

    @Transactional
    public void markFailed(String jobId, String errorCode) {
        jobs.findById(jobId).ifPresent(job -> {
            job.fail(errorCode, clock.instant());
            usageService.release(job.getInstallation(), job.getIdempotencyKey());
        });
    }

    // 성공 시 사용량을 확정(confirm)하고, 결과 직렬화가 실패해 실제로는 FAILED로 끝나면 확정
    // 대신 해제(release)한다 — "성공 경로로 들어왔다"가 아니라 Job의 최종 상태를 보고 판단한다.
    private void finishSucceeded(Job job, Object resultDto) {
        try {
            String json = objectMapper.writeValueAsString(resultDto);
            Instant now = clock.instant();
            job.succeed(json, now, now.plus(RESULT_TTL));
            usageService.confirm(job.getInstallation(), job.getIdempotencyKey());
        } catch (JacksonException e) {
            job.fail("RESULT_SERIALIZATION_FAILED", clock.instant());
            usageService.release(job.getInstallation(), job.getIdempotencyKey());
        }
    }
}
