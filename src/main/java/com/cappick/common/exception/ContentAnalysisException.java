package com.cappick.common.exception;

// 외부 AI 공급자 호출 실패. 네트워크 오류·AI 공급자 장애를 정상 판정 결과와 혼동하지 않는다(컨벤션 #9).
public class ContentAnalysisException extends RuntimeException {
    public ContentAnalysisException(String reason) {
        super(reason);
    }

    public ContentAnalysisException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
