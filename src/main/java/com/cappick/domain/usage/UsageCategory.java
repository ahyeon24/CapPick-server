package com.cappick.domain.usage;

import com.fasterxml.jackson.annotation.JsonValue;

import com.cappick.domain.job.JobKind;

// ERD §4 UsageEntry.category. JobKind와 1:1로 대응하는 값만 있다 — folder_criteria·
// search_document Job kind가 생기면 여기도 값을 늘린다.
public enum UsageCategory {
    CONTENT_ANALYSIS,
    CLASSIFICATION;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    public static UsageCategory from(JobKind kind) {
        return switch (kind) {
            case CONTENT_ANALYSIS -> CONTENT_ANALYSIS;
            case CLASSIFICATION -> CLASSIFICATION;
        };
    }
}
