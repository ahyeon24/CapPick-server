package com.cappick.domain.usage;

// ERD §4 "예약·확정·해제는 원자적 전이" — RESERVED에서만 CONFIRMED/RELEASED로 갈 수 있다.
public enum UsageEntryState {
    RESERVED,
    CONFIRMED,
    RELEASED
}
