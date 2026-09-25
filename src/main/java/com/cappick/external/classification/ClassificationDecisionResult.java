package com.cappick.external.classification;

import com.cappick.domain.job.ClassificationDecision;

// 요청한 폴더 하나에 대한 판정. criteriaVersion은 AI 응답을 그대로 믿지 않고 서버가 원래 요청의
// 값을 그대로 채운다(GeminiClassificationClient 참고) — 모델이 버전 번호를 잘못 옮겨 적을 이유를
// 만들지 않기 위해서다.
public record ClassificationDecisionResult(
    String folderId,
    int criteriaVersion,
    ClassificationDecision decision,
    String evidence
) {
}
