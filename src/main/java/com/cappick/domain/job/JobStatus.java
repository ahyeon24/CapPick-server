package com.cappick.domain.job;

import com.fasterxml.jackson.annotation.JsonValue;

// API 명세 §5: queued -> running -> succeeded/failed. 재시도로 queued에 돌아갈 수 있다는
// 문서 내용은 실제 재시도 정책이 없는 지금은 반영하지 않았다(§14 미확정 사항).
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED;

    // API 명세 예시가 소문자("succeeded")라 JSON에는 소문자로 나가게 한다.
    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
