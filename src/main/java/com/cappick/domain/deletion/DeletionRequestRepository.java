package com.cappick.domain.deletion;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cappick.domain.installation.Installation;

public interface DeletionRequestRepository extends JpaRepository<DeletionRequest, String> {

    // 소유권 스코프 조회 (컨벤션 #3).
    Optional<DeletionRequest> findByIdAndInstallation(String id, Installation installation);

    Optional<DeletionRequest> findByInstallationAndIdempotencyKey(Installation installation, String idempotencyKey);

    boolean existsByInstallationAndStatusIn(Installation installation, Collection<DeletionRequestStatus> statuses);

    // JobService가 "이 설치에 진행 중인 삭제가 있는지" 확인할 때 쓴다(PENDING·PROCESSING을
    // active로 간주) — Job 제출 자체를 막는 용도라 DeletionService 없이도 바로 쓸 수 있게 여기 둔다.
    default boolean existsActiveByInstallation(Installation installation) {
        return existsByInstallationAndStatusIn(
            installation, Set.of(DeletionRequestStatus.PENDING, DeletionRequestStatus.PROCESSING));
    }
}
