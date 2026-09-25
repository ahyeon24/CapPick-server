package com.cappick.domain.job;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

// API 명세 §4의 4종류 중 content_analysis·classification 구현. 나머지(folder_criteria,
// search_document)는 추가할 때 여기 값을 늘린다. classification은 클라이언트가 JSON 바디의
// "kind" 필드로 값을 보내므로(예: "classification") JobStatus와 같은 방식으로 소문자 스네이크
// 케이스와 양방향 변환한다 — content_analysis는 지금 multipart 전용 엔드포인트라 바디에 kind가
// 없지만, 같은 enum을 쓰니 변환 규칙도 같이 맞춘다.
public enum JobKind {
    CONTENT_ANALYSIS,
    CLASSIFICATION;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static JobKind fromJson(String value) {
        return JobKind.valueOf(value.toUpperCase());
    }
}
