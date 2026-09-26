package com.cappick.domain.deletion;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.cappick.common.exception.DeletionRequestNotFoundException;
import com.cappick.common.exception.InstallationDeletionInProgressException;
import com.cappick.domain.installation.Installation;

// 상태 전이(markProcessing/deleteContent/markFailed)는 DeletionStateTransitions로 분리했다 —
// 이 클래스가 비동기 디스패치를 위해 DeletionWorker를 의존하는데, AsyncDeletionWorker가 전이
// 호출을 위해 다시 이 클래스를 의존하면 Spring 빈 순환 참조가 생긴다(그 클래스 주석 참고).
@Service
public class DeletionService {

    private final DeletionRequestRepository deletionRequests;
    private final DeletionWorker deletionWorker;
    private final Clock clock;

    public DeletionService(DeletionRequestRepository deletionRequests, DeletionWorker deletionWorker, Clock clock) {
        this.deletionRequests = deletionRequests;
        this.deletionWorker = deletionWorker;
        this.clock = clock;
    }

    /**
     * API 명세 §3 POST /v1/data-deletions. 요청 바디가 사실상 고정값(scope 하나)이라 Job
     * 제출처럼 digest를 비교하지 않는다 — 같은 Idempotency-Key면 그냥 기존 요청을 반환한다
     * (단순화 지점, 다른 kind가 생기면 재검토). 이미 진행 중인 삭제가 있으면(다른 키라도) 새
     * 삭제 요청도 막는다 — 동시에 두 삭제가 겹칠 때의 정확한 처리는 잠정 정책이다(§14 컨벤션).
     */
    @Transactional
    public DeletionRequest requestInstallationDeletion(Installation installation, String idempotencyKey) {
        Optional<DeletionRequest> existing =
            deletionRequests.findByInstallationAndIdempotencyKey(installation, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (deletionRequests.existsActiveByInstallation(installation)) {
            throw new InstallationDeletionInProgressException(installation.getId());
        }

        DeletionRequest request = DeletionRequest.builder()
            .id(UUID.randomUUID().toString())
            .installation(installation)
            .idempotencyKey(idempotencyKey)
            .scope(DeletionRequestScope.INSTALLATION_CONTENT)
            .requestedAt(clock.instant())
            .build();
        deletionRequests.save(request);
        // JobService.dispatchAfterCommit과 같은 이유로 커밋 후에 워커를 띄운다 — 지금 두
        // 곳뿐이라 공유 유틸로 추출하지 않는다. 셋째 사용처가 생기면 그때 추출한다.
        dispatchAfterCommit(() -> deletionWorker.run(request.getId()));
        return request;
    }

    private void dispatchAfterCommit(Runnable dispatch) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    @Transactional(readOnly = true)
    public DeletionRequest getStatus(Installation installation, String deletionRequestId) {
        return deletionRequests.findByIdAndInstallation(deletionRequestId, installation)
            .orElseThrow(() -> new DeletionRequestNotFoundException(deletionRequestId));
    }
}
