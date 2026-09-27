package com.cappick.common.exception;

// 설치 전체 합산으로 카테고리별 하루 예산(무료 티어 RPD 근사치)에 도달했을 때 새 Job 제출을
// 막는다 — 2026-09-27, 실제로 여러 설치가 하나의 공유 Gemini 키를 나눠 쓰는 구조라 RPD를 그냥
// 넘겨버리면 서버가 확인 불가능한 503으로 전부 실패하는 대신, 명확한 사유로 먼저 거부한다.
public class DailyBudgetExhaustedException extends RuntimeException {
    public DailyBudgetExhaustedException(String category) {
        super("daily budget exhausted for category: " + category);
    }
}
