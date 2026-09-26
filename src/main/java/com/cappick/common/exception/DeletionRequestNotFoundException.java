package com.cappick.common.exception;

public class DeletionRequestNotFoundException extends RuntimeException {
    public DeletionRequestNotFoundException(String deletionRequestId) {
        super("deletion request not found: " + deletionRequestId);
    }
}
