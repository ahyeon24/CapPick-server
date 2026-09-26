package com.cappick.domain.usage;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cappick.domain.installation.Installation;

@Service
public class UsageService {

    private final UsageEntryRepository usageEntries;
    private final Clock clock;

    public UsageService(UsageEntryRepository usageEntries, Clock clock) {
        this.usageEntries = usageEntries;
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
