package com.cappick.common.response;

// API 명세 §2 공통 오류 형식.
public record ApiErrorBody(String code, String message, boolean retryable) {
}
