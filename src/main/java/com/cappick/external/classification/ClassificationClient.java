package com.cappick.external.classification;

import com.cappick.common.exception.ClassificationException;

/**
 * 폴더 자동 분류 판정 경계 (AI-03, 기능명세서 §3.3.1). Service는 이 인터페이스만 알고 구체적인
 * 공급자를 모른다 — {@link com.cappick.external.contentanalysis.ContentAnalysisClient}와 같은
 * 이유로 나눴다(컨벤션 #8).
 */
public interface ClassificationClient {
    ClassificationResult classify(ClassificationRequest request) throws ClassificationException;
}
