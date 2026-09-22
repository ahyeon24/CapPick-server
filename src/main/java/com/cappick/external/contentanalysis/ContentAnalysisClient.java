package com.cappick.external.contentanalysis;

import com.cappick.common.exception.ContentAnalysisException;

/**
 * 이미지 분석 경계 (기능명세서 §5: AI 공급자 미선정 상태에서 시작). Service는 이 인터페이스만
 * 알고 구체적인 공급자(Gemini 등)를 모른다 — 나중에 비교·교체할 때 이 뒤의 구현체만 바꾼다.
 */
public interface ContentAnalysisClient {
    ContentAnalysisResult analyze(ContentAnalysisRequest request) throws ContentAnalysisException;
}
