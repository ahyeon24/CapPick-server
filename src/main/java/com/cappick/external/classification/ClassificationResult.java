package com.cappick.external.classification;

import java.util.List;

// 요청한 모든 폴더에 대해 정확히 하나씩의 판정을 담는다(API 명세 §4: "누락된 판정은 성공한
// no_match로 간주하지 않는다") — 구현체(GeminiClassificationClient)가 이 불변식을 보장한다.
public record ClassificationResult(List<ClassificationDecisionResult> decisions) {
}
