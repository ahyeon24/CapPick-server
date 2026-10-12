package com.cappick.external.contentanalysis.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
    void analyzeCallsGenerateContentOnceAndAssemblesResult() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "unused-classification-model"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andExpect(header("x-goog-api-key", CONFIGURED_KEY))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"ocr_text\\":\\"실습 1\\",\\"observed_evidence\\":[\\"행렬곱 문제\\"],\\"search_text\\":\\"알고리즘 실습 자료\\"}"}]}}]}
                """, MediaType.APPLICATION_JSON));

        ContentAnalysisResult result = client.analyze(
            new ContentAnalysisRequest(new byte[]{1, 2, 3}, "image/png", null));

        assertEquals("실습 1", result.ocrText());
        assertEquals("알고리즘 실습 자료", result.searchText());
        assertEquals(1, result.observedEvidence().size());
        assertEquals("행렬곱 문제", result.observedEvidence().get(0));
        server.verify();
    }

    @Test
    void clientOcrHintIsReturnedAsIsAndModelIsNotAskedForOcrText() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "unused-classification-model"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andExpect(content().string(not(containsString("ocr_text"))))
            .andExpect(content().string(not(containsString("기기에서 읽은 글자"))))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"observed_evidence\\":[\\"행렬곱 문제\\"],\\"search_text\\":\\"알고리즘 실습 자료\\"}"}]}}]}
                """, MediaType.APPLICATION_JSON));
        ContentAnalysisResult result = client.analyze(
            new ContentAnalysisRequest(new byte[]{1, 2, 3}, "image/jpeg", "기기에서 읽은 글자"));

        assertEquals("기기에서 읽은 글자", result.ocrText());
        assertEquals("알고리즘 실습 자료", result.searchText());
        assertEquals(java.util.List.of("행렬곱 문제"), result.observedEvidence());
        server.verify();
    }

    @Test
    void blankClientOcrHintFallsBackToModelOcr() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "unused-classification-model"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andExpect(content().string(containsString("ocr_text")))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"ocr_text\\":\\"모델이 읽은 글자\\",\\"observed_evidence\\":[],\\"search_text\\":\\"s\\"}"}]}}]}
                """, MediaType.APPLICATION_JSON));
        ContentAnalysisResult result = client.analyze(
            new ContentAnalysisRequest(new byte[]{1}, "image/jpeg", "  "));

        assertEquals("모델이 읽은 글자", result.ocrText());
        server.verify();
    }

    @Test
    void missingApiKeyFailsFastWithoutCallingNetwork() {
        setUp(new GeminiProperties("", "gemini-flash-latest", "unused-classification-model"));

        assertThrows(ContentAnalysisException.class, () -> client.analyze(
            new ContentAnalysisRequest(new byte[]{1}, "image/png", null)));
    }

    @Test
    void serverErrorIsWrappedAsContentAnalysisException() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "unused-classification-model"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withServerError());

        assertThrows(ContentAnalysisException.class, () -> client.analyze(
            new ContentAnalysisRequest(new byte[]{1}, "image/png", null)));
    }

    @Test
    void malformedJsonInTextPartIsWrappedAsContentAnalysisException() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "unused-classification-model"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"not valid json"}]}}]}
                """, MediaType.APPLICATION_JSON));

        assertThrows(ContentAnalysisException.class, () -> client.analyze(
            new ContentAnalysisRequest(new byte[]{1}, "image/png", null)));
    }
}
