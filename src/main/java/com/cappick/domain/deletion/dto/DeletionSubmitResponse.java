package com.cappick.domain.deletion.dto;

import com.cappick.domain.deletion.DeletionRequestStatus;

public record DeletionSubmitResponse(String deletionId, DeletionRequestStatus status) {
}
