package com.cappick.common.exception;

public class InstallationNotFoundException extends RuntimeException {
    public InstallationNotFoundException(String reason) {
        super(reason);
    }
}
