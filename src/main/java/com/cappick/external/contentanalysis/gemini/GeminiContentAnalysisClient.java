package com.cappick.external.contentanalysis.gemini;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.cappick.common.exception.ContentAnalysisException;
import com.cappick.external.contentanalysis.ContentAnalysisClient;
import com.cappick.external.contentanalysis.ContentAnalysisRequest;
import com.cappick.external.contentanalysis.ContentAnalysisResult;

/**
 * Gemini(Google) 기반 구현. 2026-09-22 이 세션에서 Python 스크립트로 동일한 프롬프트·스키마·
 * generateContent/embedContent 호출을 실제 스크린샷 여러 장에 돌려 응답 형태를 확인한 뒤 그대로
 * 옮겼다 — last-safe-server의 TagoBusPositionClient처럼, 실제 API로 검증된 형태를 기반으로
 * 옮긴 구현이라는 뜻이다. 다만 그 스크립트 실행 자체는 무료 티어 속도 제한(분당 5회)에 자주
 * 걸렸다 — 운영 트래픽에서는 재시도·속도 조절이 필요할 수 있고 아직 없다.
 *
 * AI 공급자는 잠정 결정이다(사용자 결정: 우선 Gemini로 시작, 추후 OpenAI와 비용·품질 비교 예정).
 * 이 클래스를 통째로 교체하는 시나리오를 염두에 두고 {@link ContentAnalysisClient} 뒤에 숨겼다.
 */
@Service
public class GeminiContentAnalysisClient implements ContentAnalysisClient {

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/{model}:generateContent";
    private static final String EMBED_CONTENT_PATH = "/v1beta/models/{model}:embedContent";

    // 2026-09-27: 출력 토큰(=비용) 절감을 위해 observed_evidence·search_text에 분량 제한을 명시했다.
    // ocr_text는 건드리지 않는다 — "있는 그대로 전부" 추출이 OCR의 핵심 기능이라, 텍스트 밀도가
    // 높은 캡처(문서·책 페이지 등)는 원래도 길게 나오는 게 정상이고 여기를 줄이면 검색 품질이
    // 떨어진다. observed_evidence 개수 제한은 스키마(analysisSchema의 maxItems)로도 강제한다 —
    // 프롬프트 지시만으로는 모델이 안 지킬 수 있어서 이중 안전장치를 둔다.
    private static final String PROMPT = """
        이 이미지는 사용자의 휴대폰 스크린샷입니다. 아래 세 가지를 JSON으로 생성하세요.
        1. ocr_text: 이미지에서 읽을 수 있는 글자를 있는 그대로 추출합니다. 글자가 없으면 빈 문자열입니다.
        2. observed_evidence: 이미지에서 실제로 관찰 가능한 사실만, 분류 판단에 필요한 만큼만 나열합니다. 최대 5개, 각 항목은 한 문장(30자 내외)으로 짧게 씁니다. 사용자의 의도·목적을 추측하거나 이미지에 없는 내용을 추가하지 마세요.
        3. search_text: 나중에 사용자가 자연어로 검색했을 때 이 캡처를 찾을 수 있도록 주제와 맥락을 요약합니다. 1~2문장, 60자 이내의 짧은 한국어 텍스트입니다.""";

    // 2026-10-08: 클라이언트가 기기에서 OCR한 텍스트(ocrTextHint)를 보내면 모델에게 ocr_text를
    // 아예 시키지 않는다 — 출력 토큰의 절반가량이 OCR 받아쓰기였고, gemini-3.5-flash-lite는 글자가
    // 많은 화면에서 OCR을 빠뜨리는 게 실측으로 확인됐다(서버 CLAUDE.md 참고). 이미지는 그대로
    // 보내므로 관찰·요약은 모델이 직접 화면을 보고 한다. OCR 텍스트는 입력으로도 다시 넣지 않는다.
    private static final String PROMPT_WITHOUT_OCR = """
        이 이미지는 사용자의 휴대폰 스크린샷입니다. 아래 두 가지를 JSON으로 생성하세요.
        1. observed_evidence: 이미지에서 실제로 관찰 가능한 사실만, 분류 판단에 필요한 만큼만 나열합니다. 최대 5개, 각 항목은 한 문장(30자 내외)으로 짧게 씁니다. 사용자의 의도·목적을 추측하거나 이미지에 없는 내용을 추가하지 마세요.
        2. search_text: 나중에 사용자가 자연어로 검색했을 때 이 캡처를 찾을 수 있도록 주제와 맥락을 요약합니다. 1~2문장, 60자 이내의 짧은 한국어 텍스트입니다.""";

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiContentAnalysisClient(
        RestClient.Builder restClientBuilder, GeminiProperties properties, ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ContentAnalysisResult analyze(ContentAnalysisRequest request) {
        if (!properties.isConfigured()) {
            throw new ContentAnalysisException("GEMINI_API_KEY가 설정되지 않았습니다.");
        }
        // 힌트가 비어 있으면(클라이언트 OCR 실패·글자 없음) 예전처럼 모델이 OCR까지 한다.
        boolean hasClientOcr = request.ocrTextHint() != null && !request.ocrTextHint().isBlank();
        JsonNode analysis = generateAnalysis(request, hasClientOcr);
        String searchText = analysis.path("search_text").asString("");
        List<Double> embedding = embed(searchText);

        List<String> evidence = new ArrayList<>();
        analysis.path("observed_evidence").forEach(node -> evidence.add(node.asString()));

        return new ContentAnalysisResult(
            hasClientOcr ? request.ocrTextHint() : analysis.path("ocr_text").asString(""),
            evidence,
            searchText,
            embedding,
            properties.embeddingModel()
        );
    }

    private JsonNode generateAnalysis(ContentAnalysisRequest request, boolean hasClientOcr) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        parts.addObject().put("text", hasClientOcr ? PROMPT_WITHOUT_OCR : PROMPT);
        ObjectNode inlineData = parts.addObject().putObject("inline_data");
        inlineData.put("mime_type", request.mimeType());
        inlineData.put("data", Base64.getEncoder().encodeToString(request.imageBytes()));

        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.set("responseSchema", analysisSchema(!hasClientOcr));

        JsonNode response = callGemini(GENERATE_CONTENT_PATH, properties.model(), body);
        String text = response.path("candidates").path(0).path("content").path("parts").path(0)
            .path("text").asString(null);
        if (text == null) {
            throw new ContentAnalysisException("Gemini generateContent 응답에 text 파트가 없습니다: " + response);
        }
        try {
            return objectMapper.readTree(text);
        } catch (JacksonException e) {
            throw new ContentAnalysisException("Gemini 응답 JSON 파싱 실패", e);
        }
    }

    private List<Double> embed(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode content = body.putObject("content");
        content.putArray("parts").addObject().put("text", text);

        JsonNode response = callGemini(EMBED_CONTENT_PATH, properties.embeddingModel(), body);
        List<Double> values = new ArrayList<>();
        response.path("embedding").path("values").forEach(v -> values.add(v.asDouble()));
        return values;
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
                throw new ContentAnalysisException("Gemini 응답이 비어 있습니다: " + path);
            }
            return response;
        } catch (ContentAnalysisException e) {
            throw e;
        } catch (Exception e) {
            // 원인 메시지에 요청 바이트(이미지)는 없다 — 예외 자체가 HTTP 클라이언트가 던진 것뿐.
            throw new ContentAnalysisException("Gemini 호출 실패: " + path, e);
        }
    }

    private JsonNode analysisSchema(boolean includeOcrText) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "OBJECT");
        ObjectNode props = schema.putObject("properties");
        if (includeOcrText) {
            props.putObject("ocr_text").put("type", "STRING");
        }
        ObjectNode evidence = props.putObject("observed_evidence");
        evidence.put("type", "ARRAY");
        // maxItems는 Gemini structured output이 실제로 강제하는 필드다(문서화됨) — 프롬프트 지시가
        // 안 지켜져도 5개를 넘지 않는다는 걸 보장한다. items.maxLength는 강제 여부가 불확실해서
        // best-effort 힌트로만 추가한다(안 지켜져도 손해는 없음, 프롬프트의 "30자 내외"가 주된 수단).
        evidence.put("maxItems", 5);
        ObjectNode evidenceItems = evidence.putObject("items");
        evidenceItems.put("type", "STRING");
        evidenceItems.put("maxLength", 60);
        ObjectNode searchText = props.putObject("search_text");
        searchText.put("type", "STRING");
        searchText.put("maxLength", 120);
        ArrayNode required = schema.putArray("required");
        if (includeOcrText) {
            required.add("ocr_text");
        }
        required.add("observed_evidence");
        required.add("search_text");
        return schema;
    }
}
