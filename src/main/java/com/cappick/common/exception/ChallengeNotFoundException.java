package com.cappick.common.exception;

public class ChallengeNotFoundException extends RuntimeException {
    public ChallengeNotFoundException(String challengeId) {
        super("challenge not found: " + challengeId);
    }
}
