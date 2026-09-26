package com.cappick.common.exception;

// 설치 콘텐츠 전체 삭제가 진행 중일 때(PENDING·PROCESSING) 새 Job 제출·새 삭제 요청을 막는다
// (API 명세 §7 "처리 중 앱도 신규 제출을 멈춘다").
public class InstallationDeletionInProgressException extends RuntimeException {
    public InstallationDeletionInProgressException(String installationId) {
        super("installation content deletion in progress: " + installationId);
    }
}
