package com.cappick.domain.deletion;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// API 명세 §3 POST /v1/data-deletions의 scope 필드. 지금은 "설치 콘텐츠 전체 삭제" 하나뿐이다
// — DELETE /v1/jobs/{job_id}/content(개별 Job 콘텐츠 삭제)는 이번 범위 밖이라 값을 안 늘렸다.
public enum DeletionRequestScope {
    INSTALLATION_CONTENT;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static DeletionRequestScope fromJson(String value) {
        return DeletionRequestScope.valueOf(value.toUpperCase());
    }
}
