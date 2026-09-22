package com.cappick.domain.search;

import java.time.ZoneId;

import org.springframework.stereotype.Service;

import com.cappick.common.exception.SearchModelMismatchException;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.search.dto.SearchPrepareRequest;
import com.cappick.domain.search.dto.SearchPrepareResponse;
import com.cappick.external.contentanalysis.gemini.GeminiProperties;
import com.cappick.external.searchinterpretation.SearchInterpretationClient;
import com.cappick.external.searchinterpretation.SearchInterpretationResult;

/**
 * POST /v1/search/prepare(API 명세 §6). Job이 아니다 — 큐잉·폴링 없이 한 번의 요청/응답으로
 * 끝나는 동기 호출이다. 실제 캡처 검색(키워드·의미 결합, 폴더·날짜·접근가능 필터)은 기기에서
 * 하므로 여기서는 어떤 개인 데이터(스크린샷 목록 등)도 다루지 않는다.
 */
@Service
public class SearchService {

    private final SearchInterpretationClient interpretationClient;
    private final GeminiProperties geminiProperties;

    public SearchService(SearchInterpretationClient interpretationClient, GeminiProperties geminiProperties) {
        this.interpretationClient = interpretationClient;
        this.geminiProperties = geminiProperties;
    }

    public SearchPrepareResponse prepare(Installation installation, SearchPrepareRequest request) {
        // Gemini를 부르기 전에 먼저 검사한다 — 어차피 못 쓸 임베딩이면 비용을 안 쓴다.
        if (!request.embeddingModelVersion().equals(geminiProperties.embeddingModel())) {
            throw new SearchModelMismatchException(request.embeddingModelVersion(), geminiProperties.embeddingModel());
        }

        ZoneId timezone = ZoneId.of(request.timezone());
        SearchInterpretationResult interpretation = interpretationClient.interpret(request.query(), request.now(), timezone);
        var embedding = interpretationClient.embed(interpretation.normalizedQuery());

        return new SearchPrepareResponse(
            interpretation.normalizedQuery(),
            interpretation.lexicalTerms(),
            embedding,
            geminiProperties.embeddingModel(),
            interpretation.capturedFrom(),
            interpretation.capturedBefore(),
            interpretation.dateInterpretation()
        );
    }
}
