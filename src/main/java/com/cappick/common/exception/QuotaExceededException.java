package com.cappick.common.exception;

// 설치별 한도(UsageQuotaProperties)에 도달했을 때 새 Job 제출을 막는다 — API 명세 §2의
// QUOTA_EXCEEDED. 하루가 지나도 풀리지 않는다는 점이 DailyBudgetExhaustedException과 다르다.
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String category) {
        super("installation quota exceeded for category: " + category);
    }
}
