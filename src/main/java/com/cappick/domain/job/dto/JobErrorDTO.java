package com.cappick.domain.job.dto;

public record JobErrorDTO(String code, String message, boolean retryable) {
}
