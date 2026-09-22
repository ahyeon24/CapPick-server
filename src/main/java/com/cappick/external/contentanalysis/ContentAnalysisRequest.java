package com.cappick.external.contentanalysis;

/**
 * @param imageBytes 원본 이미지 바이트
 * @param mimeType 예: "image/png"
 * @param ocrTextHint 클라이언트가 이미 OCR한 텍스트가 있으면 힌트로 전달(API 명세 §4 "선택 ocr_text")
 */
public record ContentAnalysisRequest(byte[] imageBytes, String mimeType, String ocrTextHint) {
}
