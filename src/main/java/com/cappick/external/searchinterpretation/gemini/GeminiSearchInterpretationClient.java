package com.cappick.external.searchinterpretation.gemini;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.cappick.common.exception.SearchInterpretationException;
import com.cappick.external.contentanalysis.gemini.GeminiProperties;
import com.cappick.external.searchinterpretation.SearchInterpretationClient;
import com.cappick.external.searchinterpretation.SearchInterpretationResult;

/**
 * Gemini 기반 구현. {@link com.cappick.external.contentanalysis.gemini.GeminiContentAnalysisClient}와
 * generateContent/embedContent 호출 방식(구조화 출력, x-goog-api-key 헤더)은 같지만 코드는
 * 공유하지 않는다 — 이미지 분석과 검색어 해석이 서로 다른 AI 공급자로 독립적으로 바뀔 수 있어야
 * 한다는 설계 의도(둘 다 인터페이스 뒤에 숨긴 이유) 때문이다. 모델 설정은 GeminiProperties를
 * 그대로 재사용한다 — 지금은 같은 Gemini 프로젝트·같은 모델을 쓰지만 우연일 뿐이다.
 */
@Service
public class GeminiSearchInterpretationClient implements SearchInterpretationClient {

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/{model}:generateContent";
    private static final String EMBED_CONTENT_PATH = "/v1beta/models/{model}:embedContent";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiSearchInterpretationClient(
        RestClient.Builder restClientBuilder, GeminiProperties properties, ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchInterpretationResult interpret(String query, Instant now, ZoneId timezone) {
        if (!properties.isConfigured()) {
            throw new SearchInterpretationException("GEMINI_API_KEY가 설정되지 않았습니다.");
        }
        JsonNode result = callGenerateContent(query, now, timezone);

        String normalizedQuery = result.path("normalized_query").asString(query);
        List<String> lexicalTerms = new ArrayList<>();
        result.path("lexical_terms").forEach(node -> lexicalTerms.add(node.asString()));
        Instant capturedFrom = parseInstantOrNull(result.path("captured_from").asString(null));
        Instant capturedBefore = parseInstantOrNull(result.path("captured_before").asString(null));
        String dateInterpretation = blankToNull(result.path("date_interpretation").asString(null));

        return new SearchInterpretationResult(normalizedQuery, lexicalTerms, capturedFrom, capturedBefore, dateInterpretation);
    }

    @Override
    public List<Double> embed(String text) {
        if (!properties.isConfigured()) {
            throw new SearchInterpretationException("GEMINI_API_KEY가 설정되지 않았습니다.");
        }
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode content = body.putObject("content");
        content.putArray("parts").addObject().put("text", text);

        JsonNode response = callGemini(EMBED_CONTENT_PATH, properties.embeddingModel(), body);
        List<Double> values = new ArrayList<>();
        response.path("embedding").path("values").forEach(v -> values.add(v.asDouble()));
        return values;
    }

    private JsonNode callGenerateContent(String query, Instant now, ZoneId timezone) {
        String prompt = """
            사용자가 스크린샷 검색창에 입력한 다음 문장을 해석하세요: "%s"
            기준 시각은 %s(%s)입니다.

            아래를 JSON으로 생성하세요.
            1. normalized_query: 검색에 쓰기 좋게 다듬은 문장. 특별히 고칠 게 없으면 원문 그대로.
            2. lexical_terms: 이미지 속 글자·키워드 검색에 쓸 핵심 단어들(상호명·고유명사·코드 등). 조사·불용어는 뺀다.
            3. captured_from, captured_before: 문장에 "지난달", "어제", "이번 주"처럼 캡처가 저장된 시점에 대한
               날짜 조건이 있으면 기준 시각 기준으로 ISO-8601 UTC 시각(예: 2026-08-01T00:00:00Z)으로 계산한다.
               시작은 포함, 끝은 미포함 구간이다. 날짜 조건이 없으면 둘 다 빈 문자열로 남긴다.
               이미지 속에 적힌 다른 날짜(예: 일정, 행사일)와 혼동하지 않는다 — 오직 "캡처가 언제 저장됐는지"만 본다.
            4. date_interpretation: 위 날짜 조건을 사람이 읽을 짧은 한국어 설명으로 요약(예: "지난달(8월 1일~31일)").
               날짜 조건이 없으면 빈 문자열.
            """.formatted(query, now, timezone.getId());

        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ArrayNode parts = contents.addObject().putArray("parts");
        parts.addObject().put("text", prompt);

        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.set("responseSchema", interpretationSchema());

        JsonNode response = callGemini(GENERATE_CONTENT_PATH, properties.model(), body);
        String text = response.path("candidates").path(0).path("content").path("parts").path(0)
            .path("text").asString(null);
        if (text == null) {
            throw new SearchInterpretationException("Gemini generateContent 응답에 text 파트가 없습니다: " + response);
        }
        try {
            return objectMapper.readTree(text);
        } catch (JacksonException e) {
            throw new SearchInterpretationException("Gemini 응답 JSON 파싱 실패", e);
        }
    }

    private JsonNode callGemini(String path, String model, ObjectNode body) {
        try {
            JsonNode response = restClient.post()
                .uri(uriBuilder -> uriBuilder.path(path).build(model))
                .contentType(MediaType.APPLICATION_JSON)
                // 쿼리 파라미터(?key=)가 아니라 헤더로 보낸다 — URL은 접근 로그에 그대로 남기 쉽다(컨벤션 #13).
                .header("x-goog-api-key", properties.apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
            if (response == null) {
                throw new SearchInterpretationException("Gemini 응답이 비어 있습니다: " + path);
            }
            return response;
        } catch (SearchInterpretationException e) {
            throw e;
        } catch (Exception e) {
            // 원인 메시지에 검색어 원문은 없다 — 예외 자체가 HTTP 클라이언트가 던진 것뿐(컨벤션 #13).
            throw new SearchInterpretationException("Gemini 호출 실패: " + path, e);
        }
    }

    private JsonNode interpretationSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "OBJECT");
        ObjectNode props = schema.putObject("properties");
        props.putObject("normalized_query").put("type", "STRING");
        ObjectNode lexicalTerms = props.putObject("lexical_terms");
        lexicalTerms.put("type", "ARRAY");
        lexicalTerms.putObject("items").put("type", "STRING");
        props.putObject("captured_from").put("type", "STRING");
        props.putObject("captured_before").put("type", "STRING");
        props.putObject("date_interpretation").put("type", "STRING");
        ArrayNode required = schema.putArray("required");
        required.add("normalized_query");
        required.add("lexical_terms");
        return schema;
    }

    private static Instant parseInstantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.from(ISO.parse(value));
        } catch (Exception e) {
            // 날짜 해석이 형식에 안 맞게 오면 조건 없음으로 취급한다 — 검색 자체를 실패시키지 않는다.
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
