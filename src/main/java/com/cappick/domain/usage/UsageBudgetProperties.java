package com.cappick.domain.usage;

import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카테고리별 하루 전체 예산(설치 전체 합산) 한도. AI 공급자 이름(Gemini)을 여기 domain 계층에
 * 끌어들이지 않으려고 일부러 GeminiProperties와 분리했다(컨벤션 #4 "외부 의존성 격리") — 값
 * 자체는 지금 유일한 공급자인 Gemini 무료 티어 RPD에서 나왔지만, 이 클래스는 "그 이름"을 몰라도
 * 된다. 값이 없거나(null) 0 이하면 그 카테고리는 가드를 걸지 않는다(로컬 개발·테스트 기본값).
 *
 * <p>설정값은 실측 RPD보다 살짝 낮게 잡는 안전 마진이다(2026-09-27: content_analysis 18,
 * classification 450 — 실측 RPD 20·500 대비 여유). 이 마진 자체는 임의로 고른 엔지니어링
 * 여유값이지 확정된 제품 정책이 아니다 — 실측 RPD가 바뀌면 이 값도 다시 맞춰야 한다.
 *
 * <p>⚠️ content_analysis 카테고리는 실제로 Gemini 호출을 두 번(generateContent + embedContent,
 * {@link com.cappick.external.contentanalysis.gemini.GeminiContentAnalysisClient} 참고) 한다.
 * 여기 한도는 generateContent 모델(gemini-3.5-flash)의 실측 RPD 기준이고, embedContent가 쓰는
 * embedding-model의 실제 RPD는 아직 확인 안 했다 — 더 낮은 것으로 확인되면 이 값을 그에 맞춰
 * 낮춰야 한다(과대평가하지 않도록 지금은 모른다고 명시해둔다).
 */
@ConfigurationProperties(prefix = "cappick.usage.daily-budget")
public record UsageBudgetProperties(Integer contentAnalysis, Integer classification) {

    public Optional<Integer> limitFor(UsageCategory category) {
        Integer value = switch (category) {
            case CONTENT_ANALYSIS -> contentAnalysis;
            case CLASSIFICATION -> classification;
        };
        return value == null || value <= 0 ? Optional.empty() : Optional.of(value);
    }
}
