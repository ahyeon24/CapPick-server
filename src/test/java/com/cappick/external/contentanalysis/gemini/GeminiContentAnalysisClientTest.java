package com.cappick.external.contentanalysis.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import com.cappick.common.exception.ContentAnalysisException;
import com.cappick.external.contentanalysis.ContentAnalysisRequest;
import com.cappick.external.contentanalysis.ContentAnalysisResult;

// 실제 네트워크 대신 MockRestServiceServer로 스텁한다. 요청·응답 형태는 이 세션에서 Python으로
// 실제 Gemini API에 돌려본 결과를 그대로 옮긴 것이다(GeminiContentAnalysisClient 클래스 주석 참고) —
// 즉 "진짜로 검증된 형태"를 여기서 회귀 테스트로 고정하는 것이지, 형태 자체를 새로 지어낸 게 아니다.
class GeminiContentAnalysisClientTest {

    private static final String CONFIGURED_KEY = "test-api-key";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private GeminiContentAnalysisClient client;

    private void setUp(GeminiProperties properties) {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GeminiContentAnalysisClient(builder, properties, JsonMapper.builder().build());
    }

    @Test
    void analyzeCallsGenerateContentThenEmbedContentAndAssemblesResult() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andExpect(header("x-goog-api-key", CONFIGURED_KEY))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"ocr_text\\":\\"실습 1\\",\\"observed_evidence\\":[\\"행렬곱 문제\\"],\\"search_text\\":\\"알고리즘 실습 자료\\"}"}]}}]}
                """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent"))
            .andExpect(header("x-goog-api-key", CONFIGURED_KEY))
            .andRespond(withSuccess("""
                {"embedding":{"values":[0.1,0.2,0.3]}}
                """, MediaType.APPLICATION_JSON));

        ContentAnalysisResult result = client.analyze(
            new ContentAnalysisRequest(new byte[]{1, 2, 3}, "image/png", null));

        assertEquals("실습 1", result.ocrText());
        assertEquals("알고리즘 실습 자료", result.searchText());
        assertEquals(1, result.observedEvidence().size());
        assertEquals("행렬곱 문제", result.observedEvidence().get(0));
        assertEquals(java.util.List.of(0.1, 0.2, 0.3), result.embedding());
        assertEquals("gemini-embedding-001", result.embeddingModelVersion());
        server.verify();
    }

    @Test
    void missingApiKeyFailsFastWithoutCallingNetwork() {
        setUp(new GeminiProperties("", "gemini-flash-latest", "gemini-embedding-001"));

        assertThrows(ContentAnalysisException.class, () -> client.analyze(
            new ContentAnalysisRequest(new byte[]{1}, "image/png", null)));
    }

    @Test
    void serverErrorIsWrappedAsContentAnalysisException() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withServerError());

        assertThrows(ContentAnalysisException.class, () -> client.analyze(
            new ContentAnalysisRequest(new byte[]{1}, "image/png", null)));
    }

    @Test
    void malformedJsonInTextPartIsWrappedAsContentAnalysisException() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"not valid json"}]}}]}
                """, MediaType.APPLICATION_JSON));

        assertThrows(ContentAnalysisException.class, () -> client.analyze(
            new ContentAnalysisRequest(new byte[]{1}, "image/png", null)));
    }
}
