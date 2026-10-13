package com.cappick.common.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.cappick.common.response.ApiErrorBody;
import com.cappick.common.response.ApiErrorResponse;

// API 명세 §2 공통 오류 형식으로 변환. 내부 예외 메시지·stack trace를 그대로 노출하지 않는다
// (컨벤션 #12) — 민감할 수 있는 검증 실패 사유는 로그로만 남기고 클라이언트엔 일반화된 메시지를 준다.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ChallengeNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleChallengeNotFound(ChallengeNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND", "challenge를 찾을 수 없습니다.", false);
    }

    @ExceptionHandler(ChallengeExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleChallengeExpired(ChallengeExpiredException e) {
        return error(HttpStatus.BAD_REQUEST, "CHALLENGE_EXPIRED", "challenge가 만료됐습니다. 새로 발급받아 주세요.", true);
    }

    @ExceptionHandler(ChallengeAlreadyUsedException.class)
    public ResponseEntity<ApiErrorResponse> handleChallengeUsed(ChallengeAlreadyUsedException e) {
        return error(HttpStatus.CONFLICT, "CHALLENGE_ALREADY_USED", "이미 사용된 challenge입니다.", false);
    }

    @ExceptionHandler(AttestationVerificationException.class)
    public ResponseEntity<ApiErrorResponse> handleAttestationFailed(AttestationVerificationException e) {
        return error(HttpStatus.UNAUTHORIZED, "ATTESTATION_INVALID", "설치 검증에 실패했습니다.", false);
    }

    @ExceptionHandler(InstallationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleInstallationNotFound(InstallationNotFoundException e) {
        return error(HttpStatus.UNAUTHORIZED, "INSTALLATION_NOT_FOUND", "설치 자격을 확인할 수 없습니다.", false);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), false);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(IdempotencyConflictException e) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "같은 Idempotency-Key를 다른 요청 내용으로 재사용했습니다.", false);
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleJobNotFound(JobNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "작업을 찾을 수 없습니다.", false);
    }

    @ExceptionHandler(ContentAnalysisException.class)
    public ResponseEntity<ApiErrorResponse> handleContentAnalysisFailed(ContentAnalysisException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "CONTENT_ANALYSIS_UNAVAILABLE", "분석 서비스가 일시적으로 불안정합니다.", true);
    }

    @ExceptionHandler(ClassificationException.class)
    public ResponseEntity<ApiErrorResponse> handleClassificationFailed(ClassificationException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "CLASSIFICATION_UNAVAILABLE", "분류 서비스가 일시적으로 불안정합니다.", true);
    }

    @ExceptionHandler(DeletionRequestNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleDeletionRequestNotFound(DeletionRequestNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "DELETION_NOT_FOUND", "삭제 요청을 찾을 수 없습니다.", false);
    }

    @ExceptionHandler(InstallationDeletionInProgressException.class)
    public ResponseEntity<ApiErrorResponse> handleDeletionInProgress(InstallationDeletionInProgressException e) {
        return error(HttpStatus.CONFLICT, "DELETION_IN_PROGRESS", "설치 데이터 삭제가 진행 중입니다.", true);
    }

    @ExceptionHandler(DailyBudgetExhaustedException.class)
    public ResponseEntity<ApiErrorResponse> handleDailyBudgetExhausted(DailyBudgetExhaustedException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "DAILY_BUDGET_EXHAUSTED", "오늘 처리 가능한 요청을 모두 사용했습니다. 내일 다시 시도해 주세요.", true);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleQuotaExceeded(QuotaExceededException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", "분석 한도에 도달했습니다.", false);
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message, boolean retryable) {
        String requestId = "req_" + UUID.randomUUID();
        return ResponseEntity.status(status).body(new ApiErrorResponse(new ApiErrorBody(code, message, retryable), requestId));
    }
}
