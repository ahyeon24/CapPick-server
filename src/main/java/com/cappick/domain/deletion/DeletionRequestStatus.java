package com.cappick.domain.deletion;

import com.fasterxml.jackson.annotation.JsonValue;

// API 명세 §7: pending -> processing -> completed 또는 failed.
public enum DeletionRequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
