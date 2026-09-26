package com.cappick.domain.job;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cappick.domain.installation.Installation;

public interface JobRepository extends JpaRepository<Job, String> {

    // 소유권 스코프 조회 (컨벤션 #3) — 다른 Installation의 Job은 존재 여부도 노출하지 않는다.
    Optional<Job> findByIdAndInstallation(String id, Installation installation);

    Optional<Job> findByInstallationAndIdempotencyKey(Installation installation, String idempotencyKey);

    // 설치 콘텐츠 전체 삭제(DeletionService.deleteContent)용. 지금 Job은 이미지 바이트를 안 갖고
    // 있고 result_json만 있어서, 행 삭제가 곧 콘텐츠 삭제다 — 삭제된 뒤 늦게 도착한 워커 결과는
    // JobStateTransitions의 findById(...).ifPresent(...)가 조용히 무시한다.
    void deleteByInstallation(Installation installation);
}
