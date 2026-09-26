package com.cappick.domain.deletion.dto;

import jakarta.validation.constraints.NotNull;

import com.cappick.domain.deletion.DeletionRequestScope;

public record DataDeletionRequestDTO(@NotNull DeletionRequestScope scope) {
}
