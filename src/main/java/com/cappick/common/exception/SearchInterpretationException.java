package com.cappick.common.exception;

// 검색어 해석 외부 AI 공급자 호출 실패. ContentAnalysisException과 같은 이유로 별도 클래스다
// (컨벤션 #9·#12) — 이미지 분석 실패와 검색 실패는 다른 화면 상태·오류 코드로 이어진다.
public class SearchInterpretationException extends RuntimeException {
    public SearchInterpretationException(String reason) {
        super(reason);
    }

    public SearchInterpretationException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
