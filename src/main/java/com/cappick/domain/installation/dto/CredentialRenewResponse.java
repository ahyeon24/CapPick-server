package com.cappick.domain.installation.dto;

import java.time.Instant;

public record CredentialRenewResponse(String credential, Instant expiresAt) {
}
