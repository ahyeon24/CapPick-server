package com.cappick.common.exception;

// API 명세 §6: 클라이언트가 보낸 embedding_model_version이 서버가 지금 쓰는 임베딩 모델과
// 다르면 검색어 벡터와 캡처 벡터의 차원·공간이 다를 수 있어 비교 자체가 무의미하다 -> 409.
public class SearchModelMismatchException extends RuntimeException {
    public SearchModelMismatchException(String requested, String configured) {
        super("embedding model mismatch: requested=" + requested + " configured=" + configured);
    }
}
