package com.cappick.common.exception;

// API 명세 §2: 같은 Idempotency-Key + 다른 본문 -> 409 IDEMPOTENCY_CONFLICT.
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency key reused with a different request body: " + idempotencyKey);
    }
}
