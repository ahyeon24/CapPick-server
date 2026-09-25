package com.cappick.common.exception;

// 자동 분류 판정 호출 실패(ContentAnalysisException·SearchInterpretationException과 같은 이유로
// 별도 타입 — 컨벤션 #9: 외부 AI 공급자 장애를 정상 판정 결과와 혼동하지 않는다).
public class ClassificationException extends RuntimeException {
    public ClassificationException(String reason) {
        super(reason);
    }

    public ClassificationException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
