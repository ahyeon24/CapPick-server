package com.cappick.domain.installation.dto;

import java.time.Instant;

public record InstallationRegisterResponse(String installationId, String credential, Instant expiresAt) {
}
