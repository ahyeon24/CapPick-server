package com.cappick.external.searchinterpretation;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.cappick.common.exception.SearchInterpretationException;

/**
 * 자연어 검색어 해석 경계 (API 명세 §6). {@link com.cappick.external.contentanalysis.ContentAnalysisClient}와
 * 같은 이유로 인터페이스 뒤에 숨긴다 — 이미지 분석과 검색어 해석은 서로 다른 AI 공급자로 독립적으로
 * 바뀔 수 있어야 해서 구현을 공유하지 않는다(둘 다 지금은 Gemini를 쓰지만 결합하지 않는다).
 */
public interface SearchInterpretationClient {
    SearchInterpretationResult interpret(String query, Instant now, ZoneId timezone) throws SearchInterpretationException;

    List<Double> embed(String text) throws SearchInterpretationException;
}
