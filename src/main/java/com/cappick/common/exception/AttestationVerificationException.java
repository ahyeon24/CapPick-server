package com.cappick.common.exception;

public class AttestationVerificationException extends RuntimeException {
    public AttestationVerificationException(String reason) {
        super(reason);
    }

    public AttestationVerificationException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
