package com.cappick.common.exception;

public class ChallengeExpiredException extends RuntimeException {
    public ChallengeExpiredException(String challengeId) {
        super("challenge expired: " + challengeId);
    }
}
