package com.cappick.domain.job;

import com.fasterxml.jackson.annotation.JsonValue;

// API 명세 §4 classification: 폴더당 판정은 match/no_match/uncertain 셋뿐이다. AI 응답에서
// 나온 값은 GeminiClassificationClient가 직접 파싱하고(Jackson 역직렬화를 거치지 않음), 서버가
// 만들어 클라이언트로 내보내는 방향에만 이 값을 JSON으로 쓰므로 JobStatus처럼 출력용
// @JsonValue만 있으면 된다.
public enum ClassificationDecision {
    MATCH,
    NO_MATCH,
    UNCERTAIN;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
