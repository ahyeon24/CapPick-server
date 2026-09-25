package com.cappick.domain.job;

// ClassificationWorker 경계에서 쓰는 순수 데이터 — ContentAnalysisWorker.run()이 byte[]/String
// 같은 원시 타입만 받는 것과 같은 이유로, API 요청 DTO(jakarta.validation 붙은 record)를 그대로
// worker 인터페이스까지 끌고 가지 않는다.
public record ClassificationFolderInput(String id, String criteriaText, int criteriaVersion) {
}
