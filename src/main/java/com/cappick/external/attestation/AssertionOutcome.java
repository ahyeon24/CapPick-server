package com.cappick.external.attestation;

/** @param signCount 이번 assertion의 카운터. 저장해 두고 다음 검증의 previousSignCount로 쓴다. */
public record AssertionOutcome(long signCount) {
}
