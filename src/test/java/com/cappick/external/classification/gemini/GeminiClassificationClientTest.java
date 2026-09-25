package com.cappick.external.classification.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import com.cappick.common.exception.ClassificationException;
import com.cappick.domain.job.ClassificationDecision;
import com.cappick.external.classification.ClassificationFolderCriteria;
import com.cappick.external.classification.ClassificationRequest;
import com.cappick.external.classification.ClassificationResult;
import com.cappick.external.contentanalysis.gemini.GeminiProperties;

// GeminiContentAnalysisClientTest와 같은 이유로 실제 네트워크 대신 MockRestServiceServer를 쓴다.
// 다만 이 요청/응답 형태는 GeminiContentAnalysisClient와 달리 아직 실제 Gemini 응답으로 검증된
// 적이 없다 — 서버 CLAUDE.md에 명시된 대로, 실제 무료 한도가 회복되면 진짜 응답으로 한 번 확인하고
// 필요하면 이 스텁도 맞춰 갱신해야 한다.
class GeminiClassificationClientTest {

    private static final String CONFIGURED_KEY = "test-api-key";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private GeminiClassificationClient client;

    private void setUp(GeminiProperties properties) {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GeminiClassificationClient(builder, properties, JsonMapper.builder().build());
    }

    @Test
    void classifyReturnsOneDecisionPerRequestedFolderUsingRequestedCriteriaVersion() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andExpect(header("x-goog-api-key", CONFIGURED_KEY))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"decisions\\":[{\\"folder_id\\":\\"folder-1\\",\\"decision\\":\\"match\\",\\"evidence\\":\\"면접 예상 질문 문구가 확인됨\\"}]}"}]}}]}
                """, MediaType.APPLICATION_JSON));

        ClassificationResult result = client.classify(new ClassificationRequest(
            "자바 면접 예상 질문", List.of("면접 예상 질문이라는 문구가 있음"),
            List.of(new ClassificationFolderCriteria("folder-1", "면접 질문과 면접 안내", 7))));

        assertEquals(1, result.decisions().size());
        assertEquals("folder-1", result.decisions().get(0).folderId());
        assertEquals(7, result.decisions().get(0).criteriaVersion());
        assertEquals(ClassificationDecision.MATCH, result.decisions().get(0).decision());
        server.verify();
    }

    @Test
    void folderMissingFromResponseBecomesUncertainNotSilentNoMatch() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"decisions\\":[]}"}]}}]}
                """, MediaType.APPLICATION_JSON));

        ClassificationResult result = client.classify(new ClassificationRequest(
            "ocr", List.of(),
            List.of(new ClassificationFolderCriteria("folder-1", "기준", 1))));

        assertEquals(1, result.decisions().size());
        assertEquals(ClassificationDecision.UNCERTAIN, result.decisions().get(0).decision());
    }

    @Test
    void folderIdHallucinatedByModelButNotRequestedIsDropped() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"decisions\\":[{\\"folder_id\\":\\"folder-1\\",\\"decision\\":\\"match\\",\\"evidence\\":\\"e\\"},{\\"folder_id\\":\\"not-requested\\",\\"decision\\":\\"match\\",\\"evidence\\":\\"e\\"}]}"}]}}]}
                """, MediaType.APPLICATION_JSON));

        ClassificationResult result = client.classify(new ClassificationRequest(
            "ocr", List.of(),
            List.of(new ClassificationFolderCriteria("folder-1", "기준", 1))));

        assertEquals(1, result.decisions().size());
        assertEquals("folder-1", result.decisions().get(0).folderId());
    }

    @Test
    void missingApiKeyFailsFastWithoutCallingNetwork() {
        setUp(new GeminiProperties("", "gemini-flash-latest", "gemini-embedding-001"));

        assertThrows(ClassificationException.class, () -> client.classify(new ClassificationRequest(
            "ocr", List.of(), List.of(new ClassificationFolderCriteria("folder-1", "기준", 1)))));
    }

    @Test
    void serverErrorIsWrappedAsClassificationException() {
        setUp(new GeminiProperties(CONFIGURED_KEY, "gemini-flash-latest", "gemini-embedding-001"));

        server.expect(requestTo(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"))
            .andRespond(withServerError());

        assertThrows(ClassificationException.class, () -> client.classify(new ClassificationRequest(
            "ocr", List.of(), List.of(new ClassificationFolderCriteria("folder-1", "기준", 1)))));
    }
}
