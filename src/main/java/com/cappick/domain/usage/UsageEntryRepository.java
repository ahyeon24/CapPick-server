package com.cappick.domain.usage;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cappick.domain.installation.Installation;

public interface UsageEntryRepository extends JpaRepository<UsageEntry, String> {

    Optional<UsageEntry> findByInstallationAndOperationKey(Installation installation, String operationKey);

    // 집계용 — 지금 규모(설치당 건수)에서는 자바에서 그룹핑한다. 건수가 커지면 카테고리별 합산
    // 쿼리로 바꿀 것.
    List<UsageEntry> findByInstallation(Installation installation);
}
