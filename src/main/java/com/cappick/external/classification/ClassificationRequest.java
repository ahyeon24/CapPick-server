package com.cappick.external.classification;

import java.util.List;

// API 명세 §4 classification 입력과 대응. content_analysis가 이미 뽑아둔 텍스트만 쓰고 이미지는
// 다시 보내지 않는다 — AI-03 판단은 "관찰 가능한 사실"(ocrText·observedEvidence) 대 "폴더 기준"의
// 비교이지, 원본을 다시 보고 판단하는 게 아니다.
public record ClassificationRequest(
    String ocrText,
    List<String> observedEvidence,
    List<ClassificationFolderCriteria> folders
) {
}
