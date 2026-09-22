package com.cappick.external.contentanalysis.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param apiKey 환경변수 GEMINI_API_KEY로 주입(application.properties 참고). 로그에 남기지 않는다.
 * @param model 관찰 근거·검색 텍스트를 생성하는 vision 모델. 스파이크에서 쓴 것과 동일하게 "flash" 계열.
 * @param embeddingModel 검색 벡터 생성 모델.
 */
@ConfigurationProperties(prefix = "cappick.ai.gemini")
public record GeminiProperties(String apiKey, String model, String embeddingModel) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
