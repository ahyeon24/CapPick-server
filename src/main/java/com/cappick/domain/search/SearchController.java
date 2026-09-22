package com.cappick.domain.search;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.cappick.common.AuthorizationHeaders;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.installation.InstallationService;
import com.cappick.domain.search.dto.SearchPrepareRequest;
import com.cappick.domain.search.dto.SearchPrepareResponse;

// API 명세 §6. content_analysis(POST /v1/jobs)와 달리 Job 큐잉이 없는 동기 엔드포인트다.
@RestController
@RequestMapping("/v1/search")
public class SearchController {

    private final SearchService searchService;
    private final InstallationService installationService;

    public SearchController(SearchService searchService, InstallationService installationService) {
        this.searchService = searchService;
        this.installationService = installationService;
    }

    @PostMapping("/prepare")
    public ResponseEntity<SearchPrepareResponse> prepare(
        @RequestHeader("Authorization") String authorization,
        @Valid @RequestBody SearchPrepareRequest request
    ) {
        Installation installation = resolveInstallation(authorization);
        return ResponseEntity.ok(searchService.prepare(installation, request));
    }

    private Installation resolveInstallation(String authorizationHeader) {
        String credential = AuthorizationHeaders.extractBearerToken(authorizationHeader);
        return installationService.resolveByCredential(credential);
    }
}
