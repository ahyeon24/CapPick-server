package com.cappick.domain.job;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cappick.domain.installation.Installation;

public interface JobRepository extends JpaRepository<Job, String> {

    // 소유권 스코프 조회 (컨벤션 #3) — 다른 Installation의 Job은 존재 여부도 노출하지 않는다.
    Optional<Job> findByIdAndInstallation(String id, Installation installation);

    Optional<Job> findByInstallationAndIdempotencyKey(Installation installation, String idempotencyKey);
}
