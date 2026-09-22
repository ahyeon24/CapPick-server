package com.cappick.common.exception;

public class ChallengeAlreadyUsedException extends RuntimeException {
    public ChallengeAlreadyUsedException(String challengeId) {
        super("challenge already used: " + challengeId);
    }
}
