package com.cappick.domain.usage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cappick.common.exception.DailyBudgetExhaustedException;
import com.cappick.common.exception.QuotaExceededException;
import com.cappick.domain.installation.Installation;

@Service
public class UsageService {

    // Gemini 무료 티어 RPD가 리셋되는 기준(2026-09-27 확인, ai.google.dev/gemini-api/docs/rate-limits
    // "reset daily at midnight Pacific Time") — 서버는 UTC로 도는데(ClockConfig) 예산 가드의 "오늘"은
    // 이 기준을 따라야 실제 리셋 시점과 맞는다.
    private static final ZoneId RPD_RESET_ZONE = ZoneId.of("America/Los_Angeles");

    private final UsageEntryRepository usageEntries;
    private final UsageBudgetProperties budget;
    private final UsageQuotaProperties quota;
    private final Clock clock;

    public UsageService(
        UsageEntryRepository usageEntries, UsageBudgetProperties budget, UsageQuotaProperties quota, Clock clock
    ) {
        this.usageEntries = usageEntries;
        this.budget = budget;
        this.quota = quota;
        this.clock = clock;
    }

    /**
     * Job 제출과 같은 트랜잭션 안에서 호출한다(컨벤션 #6, ERD §5 "작업 접수와 사용량 예약을
     * 원자적으로 처리"). 같은 {@code operationKey}로 이미 예약돼 있으면 새로 만들지 않고 그
     * 엔트리를 그대로 반환한다 — Job의 멱등 재제출과 자연스럽게 맞물린다.
     */
    @Transactional
    public UsageEntry reserve(Installation installation, String operationKey, UsageCategory category, int units) {
        Optional<UsageEntry> existing = usageEntries.findByInstallationAndOperationKey(installation, operationKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        UsageEntry entry = UsageEntry.builder()
            .id(UUID.randomUUID().toString())
            .installation(installation)
            .operationKey(operationKey)
            .category(category)
            .units(units)
            .createdAt(clock.instant())
            .build();
        return usageEntries.save(entry);
    }

    // Job 워커가 중복 실행될 수 있다는 전제(컨벤션 #10)와 같은 이유로, 못 찾거나 이미 종료
    // 상태여도 예외를 던지지 않고 조용히 넘어간다.
    @Transactional
    public void confirm(Installation installation, String operationKey) {
        usageEntries.findByInstallationAndOperationKey(installation, operationKey)
            .filter(entry -> entry.getState() == UsageEntryState.RESERVED)
            .ifPresent(UsageEntry::confirm);
    }

    @Transactional
    public void release(Installation installation, String operationKey) {
        usageEntries.findByInstallationAndOperationKey(installation, operationKey)
            .filter(entry -> entry.getState() == UsageEntryState.RESERVED)
            .ifPresent(UsageEntry::release);
    }

    /**
     * 설치 전체 합산으로 오늘(태평양 시간 자정 기준) 이 카테고리에 몇 건이나 예약·확정·해제됐는지
     * 세어 {@link UsageBudgetProperties}에 설정된 한도 이상이면 거부한다 — Job 제출 트랜잭션
     * 안에서, 새 Job을 만들기 전에 호출한다(JobService 참고). 설치별 공정 분배는 하지 않는다 —
     * 지금은 실사용 설치가 사실상 1개뿐이라 "한 설치가 예산을 독차지해 다른 설치를 막는" 상황이
     * 아직 없고, 진짜 다중 사용자가 생기면 그때 설치별 상한을 별도로 설계한다(서버 CLAUDE.md
     * "출력 토큰 줄이기"/"API 키 예산 가드" 절 참고). 한도가 설정 안 돼 있으면(값 없음) 그냥
     * 통과시킨다 — 로컬 개발·기존 테스트가 이 가드 때문에 깨지지 않게 하려는 잠정 기본값이다.
     */
    @Transactional(readOnly = true)
    public void requireDailyBudgetAvailable(UsageCategory category) {
        budget.limitFor(category).ifPresent(limit -> {
            long usedToday = usageEntries.countByCategoryAndCreatedAtGreaterThanEqual(category, startOfTodayInResetZone());
            if (usedToday >= limit) {
                throw new DailyBudgetExhaustedException(category.toJson());
            }
        });
    }

    /**
     * 이 설치가 지금까지 예약·확정한 건수가 설치별 한도({@link UsageQuotaProperties}) 이상이면
     * 거부한다. 진행 중(RESERVED)도 세는 이유는 동시에 여러 건을 제출해 한도를 넘기는 걸 막기
     * 위해서고, 실패(RELEASED)는 세지 않는다. 하루가 지나도 풀리지 않는다.
     */
    @Transactional(readOnly = true)
    public void requireInstallationQuotaAvailable(Installation installation, UsageCategory category) {
        quota.limitFor(category).ifPresent(limit -> {
            if (countTowardQuota(installation, category) >= limit) {
                throw new QuotaExceededException(category.toJson());
            }
        });
    }

    /** 설치별 한도. 한도가 없는 카테고리는 빈 값. */
    public Optional<Integer> installationQuotaLimit(UsageCategory category) {
        return quota.limitFor(category);
    }

    private long countTowardQuota(Installation installation, UsageCategory category) {
        return usageEntries.countByInstallationAndCategoryAndStateIn(
            installation, category, List.of(UsageEntryState.RESERVED, UsageEntryState.CONFIRMED));
    }

    private Instant startOfTodayInResetZone() {
        return ZonedDateTime.now(clock.withZone(RPD_RESET_ZONE)).truncatedTo(ChronoUnit.DAYS).toInstant();
    }

    // limit_units·resets_at·can_submit처럼 한도 정책이 필요한 값은 여기서 안 채운다(아직 미정,
    // 컨벤션 #14) — 호출자(UsageController)가 이 실측치에 그 잠정값들을 얹어 API 응답을 만든다.
    @Transactional(readOnly = true)
    public List<CategoryUsage> usageSummary(Installation installation) {
        List<UsageEntry> entries = usageEntries.findByInstallation(installation);
        return Arrays.stream(UsageCategory.values())
            .map(category -> {
                int used = entries.stream()
                    .filter(e -> e.getCategory() == category && e.getState() == UsageEntryState.CONFIRMED)
                    .mapToInt(UsageEntry::getUnits).sum();
                int reserved = entries.stream()
                    .filter(e -> e.getCategory() == category && e.getState() == UsageEntryState.RESERVED)
                    .mapToInt(UsageEntry::getUnits).sum();
                return new CategoryUsage(category, used, reserved);
            })
            .toList();
    }

    public record CategoryUsage(UsageCategory category, int usedUnits, int reservedUnits) {
    }
}
