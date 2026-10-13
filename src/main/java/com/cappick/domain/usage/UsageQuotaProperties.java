package com.cappick.domain.usage;

import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설치 하나가 평생 쓸 수 있는 카테고리별 한도. {@link UsageBudgetProperties}(전체 설치 합산·하루
 * 단위, AI 공급자 한도 보호용)와 목적이 다르다 — 이쪽은 "이 설치에 몇 장까지 분석해 줄 것인가"라는
 * 제품 정책 자리다.
 *
 * <p>2026-10-13: 처음 만든 이유는 실제 기기 테스트용 임시 한도(사용자 결정, 100장)지만, 유료 결제
 * 전환 때 "처음 N장 무료"의 N으로 그대로 쓸 수 있게 설정값으로 뒀다. 무료 체험 장수·결제 후
 * 한도 증액·재설치 우회 방지는 아직 정해지지도 구현되지도 않았다(§14 미확정 사항).
 * 값이 없거나(null) 0 이하면 한도를 걸지 않는다. 지금은 content_analysis만 대상이다 — 분류는
 * 이미 분석된 캡처에만 나가므로 따로 막지 않는다.
 */
@ConfigurationProperties(prefix = "cappick.usage.installation-quota")
public record UsageQuotaProperties(Integer contentAnalysis) {

    public Optional<Integer> limitFor(UsageCategory category) {
        Integer value = category == UsageCategory.CONTENT_ANALYSIS ? contentAnalysis : null;
        return value == null || value <= 0 ? Optional.empty() : Optional.of(value);
    }
}
