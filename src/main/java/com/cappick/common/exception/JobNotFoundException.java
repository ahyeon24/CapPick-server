package com.cappick.common.exception;

public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String jobId) {
        super("job not found: " + jobId);
    }
}
