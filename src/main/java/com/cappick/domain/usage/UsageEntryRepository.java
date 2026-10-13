package com.cappick.domain.usage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cappick.domain.installation.Installation;

public interface UsageEntryRepository extends JpaRepository<UsageEntry, String> {

    Optional<UsageEntry> findByInstallationAndOperationKey(Installation installation, String operationKey);

    // 집계용 — 지금 규모(설치당 건수)에서는 자바에서 그룹핑한다. 건수가 커지면 카테고리별 합산
    // 쿼리로 바꿀 것.
    List<UsageEntry> findByInstallation(Installation installation);

    // 전체 설치 합산 — 하루 예산 가드용(UsageService.requireDailyBudgetAvailable). 설치별이 아니라
    // 프로젝트 전체 RPD를 근사하는 값이라 일부러 installation으로 좁히지 않는다. RELEASED(실패)
    // 엔트리도 포함한다 — Gemini가 실패 응답도 요청 자체는 받은 걸로 카운트할 가능성이 있어(확인
    // 안 됨) 안전한 쪽(과소평가 대신 과대평가)으로 셌다.
    long countByCategoryAndCreatedAtGreaterThanEqual(UsageCategory category, Instant since);

    // 설치별 한도용(UsageService.requireInstallationQuotaAvailable). 하루 예산 가드와 달리
    // RELEASED(실패)는 세지 않는다 — 실패한 분석이 사용자의 한도를 깎으면 안 된다.
    long countByInstallationAndCategoryAndStateIn(
        Installation installation, UsageCategory category, Collection<UsageEntryState> states);
}
