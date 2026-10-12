package com.cappick.external.contentanalysis.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey 환경변수 GEMINI_API_KEY로 주입(application.properties 참고). 로그에 남기지 않는다.
 * @param model 관찰 근거·검색 텍스트를 생성하는 vision 모델(content_analysis).
 * @param classificationModel 자동 분류(classification) 전용 모델. content_analysis와 굳이
 *     같은 모델을 쓸 필요가 없다 — 이미 뽑아둔 텍스트를 폴더 기준과 비교하는 반복적인 구조화
 *     판단이라 더 가벼운 모델로 충분하고, 무료 티어 RPD(하루 요청 한도)가 모델별로 완전히
 *     분리돼 있어서(2026-09-26 AI Studio 대시보드로 실측: gemini-3.5-flash·gemini-3.8-flash
 *     둘 다 RPD 20으로 동일, gemini-3.5-flash-lite는 RPD 500) content_analysis와 분리하면
 *     하루 처리 가능한 캡처 수가 실질적으로 2배가 된다(예전엔 두 kind가 같은 20 RPD를
 *     나눠 썼음). 모델을 3.8-flash로 올리지 않은 이유는 RPD가 3.5와 똑같은데 출시 직후 503
 *     과부하를 이미 겪은 전례가 있어서다(CapPick-server/CLAUDE.md "Job 도메인" 절 참고) —
 *     굳이 다시 그 리스크를 질 이유가 없다.
 */
@ConfigurationProperties(prefix = "cappick.ai.gemini")
public record GeminiProperties(String apiKey, String model, String classificationModel) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
