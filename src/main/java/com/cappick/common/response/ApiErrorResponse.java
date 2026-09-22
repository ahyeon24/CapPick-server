package com.cappick.common.response;

public record ApiErrorResponse(ApiErrorBody error, String requestId) {
}
