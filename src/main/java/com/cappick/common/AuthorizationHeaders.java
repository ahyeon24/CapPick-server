package com.cappick.common;

import com.cappick.common.exception.InstallationNotFoundException;

// Authorization: Bearer <credential> 파싱. 설치 인증이 필요한 모든 컨트롤러가 공유한다.
public final class AuthorizationHeaders {

    private AuthorizationHeaders() {
    }

    public static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new InstallationNotFoundException("missing bearer credential");
        }
        return authorizationHeader.substring("Bearer ".length());
    }
}
