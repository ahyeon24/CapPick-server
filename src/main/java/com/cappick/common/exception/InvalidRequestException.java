package com.cappick.common.exception;

public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String reason) {
        super(reason);
    }
}
