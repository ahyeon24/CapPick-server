package com.cappick.external.searchinterpretation.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import com.cappick.common.exception.SearchInterpretationException;
import com.cappick.external.contentanalysis.gemini.GeminiProperties;
import com.cappick.external.searchinterpretation.SearchInterpretationResult;

// MockRestServiceServer로 실제 네트워크 없이 검증한다 — GeminiContentAnalysisClientTest와 같은 패턴.
class GeminiSearchInterpretationClientTest {

    private static final String CONFIGURED_KEY = "test-api-key";
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private GeminiSearchInterpretationClient client;

    private void setUp(GeminiProperties properties) {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GeminiSearchInterpretationClient(builder, properties, JsonMapper.builder().build());
    }

    @Test
    void interpretParsesNormalizedQueryLexicalTermsAndDateRange() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andExpect(header("x-goog-api-key", CONFIGURED_KEY))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"normalized_query\\":\\"지난달 저장한 알고리즘 문제\\",\\"lexical_terms\\":[\\"알고리즘\\",\\"행렬곱\\"],\\"captured_from\\":\\"2026-08-01T00:00:00Z\\",\\"captured_before\\":\\"2026-09-01T00:00:00Z\\",\\"date_interpretation\\":\\"지난달(8월 1일~31일)\\"}"}]}}]}
                """, MediaType.APPLICATION_JSON));

        SearchInterpretationResult result = client.interpret("지난달 저장한 알고리즘 문제", NOW, SEOUL);

        assertEquals("지난달 저장한 알고리즘 문제", result.normalizedQuery());
        assertEquals(List.of("알고리즘", "행렬곱"), result.lexicalTerms());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), result.capturedFrom());
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), result.capturedBefore());
        assertEquals("지난달(8월 1일~31일)", result.dateInterpretation());
        server.verify();
    }

    @Test
    void interpretTreatsMissingDateFieldsAsNoDateCondition() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"normalized_query\\":\\"면접 질문\\",\\"lexical_terms\\":[\\"면접\\"]}"}]}}]}
                """, MediaType.APPLICATION_JSON));

        SearchInterpretationResult result = client.interpret("면접 질문", NOW, SEOUL);

        assertNull(result.capturedFrom());
        assertNull(result.capturedBefore());
        assertNull(result.dateInterpretation());
    }

    @Test
    void embedCallsEmbedContentAndParsesValues() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent"))
            .andExpect(header("x-goog-api-key", CONFIGURED_KEY))
            .andRespond(withSuccess("""
                {"embedding":{"values":[0.1,0.2,0.3]}}
                """, MediaType.APPLICATION_JSON));

        List<Double> embedding = client.embed("면접 질문");

        assertEquals(List.of(0.1, 0.2, 0.3), embedding);
        server.verify();
    }

    @Test
    void missingApiKeyFailsFastWithoutCallingNetwork() {
        setUp(new GeminiProperties("", "gemini-flash-latest", "gemini-embedding-001"));

        assertThrows(SearchInterpretationException.class, () -> client.interpret("질문", NOW, SEOUL));
        assertThrows(SearchInterpretationException.class, () -> client.embed("질문"));
    }

    @Test
    void serverErrorIsWrappedAsSearchInterpretationException() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withServerError());

        assertThrows(SearchInterpretationException.class, () -> client.interpret("질문", NOW, SEOUL));
    }

    @Test
    void malformedJsonInTextPartIsWrappedAsSearchInterpretationException() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"not valid json"}]}}]}
                """, MediaType.APPLICATION_JSON));

        assertThrows(SearchInterpretationException.class, () -> client.interpret("질문", NOW, SEOUL));
    }
}
