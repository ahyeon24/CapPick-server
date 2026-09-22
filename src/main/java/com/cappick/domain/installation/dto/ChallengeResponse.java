package com.cappick.domain.installation.dto;

import java.time.Instant;

public record ChallengeResponse(String challengeId, String challenge, Instant expiresAt) {
}
