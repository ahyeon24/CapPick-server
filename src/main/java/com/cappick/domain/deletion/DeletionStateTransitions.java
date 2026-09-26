package com.cappick.domain.deletion;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cappick.common.exception.DeletionRequestNotFoundException;
import com.cappick.domain.job.JobRepository;

/**
 * DeletionRequest 상태 전이만 담당하는 별도 빈. {@link JobStateTransitions}와 같은 이유로
 * {@link DeletionService}에서 분리했다 — 다만 self-invocation 문제가 아니라 순환 참조
 * 문제였다: {@link DeletionService}가 비동기 디스패치를 위해 {@link DeletionWorker}를
 * 의존하는데, {@link AsyncDeletionWorker}가 전이 메서드 호출을 위해 다시 {@code DeletionService}를
 * 의존하면 Spring 빈 생성 순환(BeanCurrentlyInCreationException)이 생긴다 — 실제로
 * `./gradlew test`의 contextLoads()에서 재현됐다. `AsyncDeletionWorker`가 이 클래스를
 * 의존하게 해서 순환을 끊었다.
 */
@Service
public class DeletionStateTransitions {

    private final DeletionRequestRepository deletionRequests;
    private final JobRepository jobs;
    private final Clock clock;

    public DeletionStateTransitions(DeletionRequestRepository deletionRequests, JobRepository jobs, Clock clock) {
        this.deletionRequests = deletionRequests;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional
    public void markProcessing(String deletionRequestId) {
        deletionRequests.findById(deletionRequestId).ifPresent(DeletionRequest::start);
    }

    /**
     * 실제 삭제. 지금 Job은 이미지 바이트를 저장하지 않고 result_json만 갖고 있어서, Job 행
     * 자체를 지우는 게 곧 콘텐츠 삭제다. UsageEntry는 안 건드린다 — 사용량 보호 기록은 콘텐츠
     * 삭제와 별도 정책(ERD §4). 이 삭제 덕분에 늦게 도착한 Job 결과 저장도 따로 막을 필요가
     * 없다 — JobStateTransitions의 모든 전이 메서드가 findById(...).ifPresent(...)라서, 삭제된
     * Job에 도착한 결과는 조용히 no-op된다.
     */
    @Transactional
    public void deleteContent(String deletionRequestId) {
        DeletionRequest request = deletionRequests.findById(deletionRequestId)
            .orElseThrow(() -> new DeletionRequestNotFoundException(deletionRequestId));
        jobs.deleteByInstallation(request.getInstallation());
        request.complete(clock.instant());
    }

    @Transactional
    public void markFailed(String deletionRequestId, String errorCode) {
        deletionRequests.findById(deletionRequestId)
            .ifPresent(request -> request.fail(errorCode, clock.instant()));
    }
}
