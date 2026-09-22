package com.cappick.domain.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cappick.common.exception.SearchModelMismatchException;
import com.cappick.domain.installation.Installation;
import com.cappick.domain.search.dto.SearchPrepareRequest;
import com.cappick.domain.search.dto.SearchPrepareResponse;
import com.cappick.external.contentanalysis.gemini.GeminiProperties;
import com.cappick.external.searchinterpretation.SearchInterpretationClient;
import com.cappick.external.searchinterpretation.SearchInterpretationResult;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Mock
    private SearchInterpretationClient interpretationClient;

    private SearchService service;
    private Installation installation;

    @BeforeEach
    void setUp() {
        GeminiProperties properties = new GeminiProperties("key", "gemini-flash-latest", "gemini-embedding-001");
        service = new SearchService(interpretationClient, properties);
        installation = Installation.builder()
            .id("i1").credentialHash("h").keyId("k").publicKeyBase64("pk").createdAt(NOW)
            .build();
    }

    @Test
    void prepareCallsInterpretThenEmbedAndAssemblesResponse() {
        SearchPrepareRequest request = new SearchPrepareRequest(
            "지난달 저장한 알고리즘 문제", NOW, "Asia/Seoul", "gemini-embedding-001");
        when(interpretationClient.interpret("지난달 저장한 알고리즘 문제", NOW, ZoneId.of("Asia/Seoul")))
            .thenReturn(new SearchInterpretationResult(
                "지난달 저장한 알고리즘 문제", List.of("알고리즘"),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), "지난달"));
        when(interpretationClient.embed("지난달 저장한 알고리즘 문제")).thenReturn(List.of(0.1, 0.2));

        SearchPrepareResponse response = service.prepare(installation, request);

        assertEquals("지난달 저장한 알고리즘 문제", response.normalizedQuery());
        assertEquals(List.of("알고리즘"), response.lexicalTerms());
        assertEquals(List.of(0.1, 0.2), response.embedding());
        assertEquals("gemini-embedding-001", response.embeddingModelVersion());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), response.capturedFrom());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), response.capturedBefore());
        assertEquals("지난달", response.dateInterpretation());
    }

    @Test
    void embeddingModelMismatchFailsBeforeCallingGeminiAtAll() {
        SearchPrepareRequest request = new SearchPrepareRequest("질문", NOW, "Asia/Seoul", "old-model-v1");

        assertThrows(SearchModelMismatchException.class, () -> service.prepare(installation, request));

        verify(interpretationClient, never()).interpret(anyString(), any(), any());
        verify(interpretationClient, never()).embed(anyString());
    }
}
