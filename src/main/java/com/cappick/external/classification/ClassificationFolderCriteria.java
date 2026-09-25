package com.cappick.external.classification;

// 판정 대상 폴더 하나 — 이름이 아니라 기준 텍스트로 비교한다(AI-03: 표시 이름만으로 판단하지 않음).
public record ClassificationFolderCriteria(String id, String criteriaText, int criteriaVersion) {
}
