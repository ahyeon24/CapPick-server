package com.cappick.external.classification.gemini;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.cappick.common.exception.ClassificationException;
import com.cappick.domain.job.ClassificationDecision;
import com.cappick.external.classification.ClassificationClient;
import com.cappick.external.classification.ClassificationDecisionResult;
import com.cappick.external.classification.ClassificationFolderCriteria;
import com.cappick.external.classification.ClassificationRequest;
import com.cappick.external.classification.ClassificationResult;
import com.cappick.external.contentanalysis.gemini.GeminiProperties;

/**
 * Gemini 기반 구현. {@link com.cappick.external.contentanalysis.gemini.GeminiContentAnalysisClient}·
 * {@link com.cappick.external.searchinterpretation.gemini.GeminiSearchInterpretationClient}와
 * generateContent 호출 방식(구조화 출력, x-goog-api-key 헤더)은 같지만 코드는 공유하지 않는다 —
 * 같은 이유(공급자가 서로 독립적으로 바뀔 수 있어야 함, 컨벤션 #8)다. GeminiProperties만 재사용한다.
 *
 * <p>모델이 폴더를 잘못 가리키거나 일부 폴더를 응답에서 빠뜨릴 가능성을 신뢰하지 않는다 — 응답을
 * 요청한 폴더 집합과 대조해 (1) 요청에 없는 폴더 번호는 버리고 (2) 응답에 없는 폴더는
 * uncertain으로 채워 넣어, 항상 요청한 폴더 수만큼 정확히 하나씩의 판정을 돌려준다는 불변식을
 * 여기서 보장한다(API 명세 §4: "누락된 판정은 성공한 no_match로 간주하지 않는다").
 *
 * <p>2026-10-08: 출력 토큰(=비용) 절감을 위해 두 가지를 바꿨다. 비용이 폴더 수에 비례해 늘던
 * 원인이 폴더마다 써내던 출력이었다. (1) 모델과는 실제 folder_id(36자 UUID) 대신 요청 순서대로
 * 매긴 번호(1부터)만 주고받고, 여기서 다시 folder_id로 되돌린다. (2) evidence는 match일 때만
 * 쓰게 한다 — 클라이언트가 저장하는 근거는 match뿐이라 no_match·uncertain의 근거 문장은 버려지던
 * 출력이었다. 그 경우 evidence는 빈 문자열로 나간다.
 */
@Service
public class GeminiClassificationClient implements ClassificationClient {

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/{model}:generateContent";

    // 기능명세서 §3.3.1 "직접 근거 기반 분류 규칙"을 그대로 프롬프트에 옮겼다 — AI가 새 폴더를
    // 추천·생성하지 않고, 간접 연관성만으로 소속을 추측하지 않게 하는 게 이 기능의 핵심 제약이다.
    private static final String RULES = """
        아래 규칙에 따라 이 캡처(스크린샷)가 각 폴더의 분류 기준에 실제로 해당하는지 판단하세요.

        1. 텍스트(ocr_text)와 관찰된 시각적 내용(observed_evidence)에서 확인 가능한 사실만 근거로 삼습니다.
        2. 폴더 기준에 직접적인 근거가 있을 때만 match입니다. 간접적인 연관성만으로 추측하지 않습니다.
        3. 여러 폴더에 각각 직접 근거가 있으면 모두 match일 수 있습니다. 우선순위를 두지 않습니다.
        4. 직접 근거가 없어 판단하기 애매하면 uncertain입니다. no_match는 명백히 해당하지 않을 때만 씁니다.
        5. match일 때만 evidence에 실제로 관찰된 근거를 한 문장(40자 내외)으로 적습니다. 사용자의 의도·목적을 추측해서 적지 않습니다.
        6. no_match와 uncertain에는 evidence를 쓰지 않습니다(생략).

        예: "자바 면접 예상 질문"이라는 텍스트가 있고 폴더 기준이 "면접 질문·자기소개·면접 안내"면 match.
        같은 폴더 기준에 면접 언급 없는 자바 코드만 있으면 no_match(간접 연관성만 있음).
        신발 제품 사진은 폴더 기준이 "구매 예정 물건"이면 구매 의도를 추측할 수 없어 no_match지만,
        폴더 기준이 "신발 제품 사진을 모두 포함"처럼 관찰 가능한 특징 자체를 기준으로 명시했다면 match입니다.
        """;

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiClassificationClient(
        RestClient.Builder restClientBuilder, GeminiProperties properties, ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ClassificationResult classify(ClassificationRequest request) {
        if (!properties.isConfigured()) {
            throw new ClassificationException("GEMINI_API_KEY가 설정되지 않았습니다.");
        }
        if (request.folders().isEmpty()) {
            return new ClassificationResult(List.of());
        }

        JsonNode response = callGemini(request);
        // 키는 buildPrompt가 매긴 폴더 번호(1부터)다.
        Map<Integer, ClassificationDecisionResult> byFolderNumber = new HashMap<>();
        response.path("decisions").forEach(node -> {
            int folderNumber = node.path("folder").asInt(0);
            if (folderNumber < 1 || folderNumber > request.folders().size()) {
                return;
            }
            ClassificationDecision decision = parseDecision(node.path("decision").asString("uncertain"));
            byFolderNumber.put(folderNumber, new ClassificationDecisionResult(
                request.folders().get(folderNumber - 1).id(), 0, decision, node.path("evidence").asString("")));
        });

        // 요청한 폴더 집합을 기준으로 재구성한다 — 모델이 지어낸 폴더 번호는 위에서 이미 버렸고
        // 애초에 아래 루프가 요청 목록만 순회한다. 응답에서 빠진 폴더는 uncertain으로 채운다.
        // criteriaVersion은 모델 응답이 아니라 항상 요청 값을 쓴다.
        List<ClassificationDecisionResult> decisions = new ArrayList<>();
        for (int i = 0; i < request.folders().size(); i++) {
            ClassificationFolderCriteria folder = request.folders().get(i);
            ClassificationDecisionResult found = byFolderNumber.get(i + 1);
            if (found != null) {
                decisions.add(new ClassificationDecisionResult(
                    folder.id(), folder.criteriaVersion(), found.decision(), found.evidence()));
            } else {
                decisions.add(new ClassificationDecisionResult(
                    folder.id(), folder.criteriaVersion(), ClassificationDecision.UNCERTAIN,
                    "AI가 이 폴더에 대한 판정을 반환하지 않았습니다."));
            }
        }
        return new ClassificationResult(decisions);
    }

    private JsonNode callGemini(ClassificationRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ArrayNode parts = contents.addObject().putArray("parts");
        parts.addObject().put("text", buildPrompt(request));

        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.set("responseSchema", decisionSchema());
        // 같은 캡처·같은 폴더 기준이면 매번 같은 판정이 나오게 무작위성을 끈다(2026-10-08). 기본값으로
        // 같은 요청을 4번씩 보내 보니 폴더 판정 90개 중 9~10개가 회차마다 갈렸다(서버 CLAUDE.md 참고).
        generationConfig.put("temperature", 0);

        // content_analysis(properties.model())와 다른 모델을 쓴다 — 2026-09-26 결정,
        // GeminiProperties.classificationModel() 문서 참고(무료 티어 RPD가 모델별로 분리돼 있어서
        // 분류를 더 가벼운 모델로 옮기면 하루 처리량이 실질적으로 늘어난다).
        JsonNode response = call(GENERATE_CONTENT_PATH, properties.classificationModel(), body);
        String text = response.path("candidates").path(0).path("content").path("parts").path(0)
            .path("text").asString(null);
        if (text == null) {
            throw new ClassificationException("Gemini generateContent 응답에 text 파트가 없습니다: " + response);
        }
        try {
            return objectMapper.readTree(text);
        } catch (JacksonException e) {
            throw new ClassificationException("Gemini 응답 JSON 파싱 실패", e);
        }
    }

    private String buildPrompt(ClassificationRequest request) {
        StringBuilder sb = new StringBuilder(RULES);
        sb.append("\n캡처에서 관찰된 내용:\n");
        sb.append("- ocr_text: ").append(request.ocrText() == null || request.ocrText().isBlank()
            ? "(글자 없음)" : request.ocrText()).append('\n');
        sb.append("- observed_evidence:\n");
        if (request.observedEvidence().isEmpty()) {
            sb.append("  (없음)\n");
        } else {
            for (String evidence : request.observedEvidence()) {
                sb.append("  - ").append(evidence).append('\n');
            }
        }
        sb.append("\n판단할 폴더 목록:\n");
        for (int i = 0; i < request.folders().size(); i++) {
            sb.append("- 폴더 ").append(i + 1)
                .append(", 분류 기준: ").append(request.folders().get(i).criteriaText()).append('\n');
        }
        sb.append("\n위 폴더 번호 각각에 대해 decisions 배열에 판정을 하나씩 담아 JSON으로 응답하세요. folder에는 폴더 번호를 숫자로 적습니다.");
        return sb.toString();
    }

    private JsonNode call(String path, String model, ObjectNode body) {
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
                throw new ClassificationException("Gemini 응답이 비어 있습니다: " + path);
            }
            return response;
        } catch (ClassificationException e) {
            throw e;
        } catch (Exception e) {
            // 원인 메시지에 OCR·관찰 내용 원문은 없다 — 예외 자체가 HTTP 클라이언트가 던진 것뿐(컨벤션 #13).
            throw new ClassificationException("Gemini 호출 실패: " + path, e);
        }
    }

    private JsonNode decisionSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "OBJECT");
        ObjectNode props = schema.putObject("properties");
        ObjectNode decisions = props.putObject("decisions");
        decisions.put("type", "ARRAY");
        ObjectNode item = decisions.putObject("items");
        item.put("type", "OBJECT");
        ObjectNode itemProps = item.putObject("properties");
        itemProps.putObject("folder").put("type", "INTEGER");
        ObjectNode decisionEnum = itemProps.putObject("decision");
        decisionEnum.put("type", "STRING");
        ArrayNode enumValues = decisionEnum.putArray("enum");
        enumValues.add("match");
        enumValues.add("no_match");
        enumValues.add("uncertain");
        itemProps.putObject("evidence").put("type", "STRING");
        ArrayNode itemRequired = item.putArray("required");
        itemRequired.add("folder");
        itemRequired.add("decision");
        // evidence는 required가 아니다 — match일 때만 쓰게 한다(클래스 주석 참고).
        ArrayNode required = schema.putArray("required");
        required.add("decisions");
        return schema;
    }

    private static ClassificationDecision parseDecision(String value) {
        return switch (value) {
            case "match" -> ClassificationDecision.MATCH;
            case "no_match" -> ClassificationDecision.NO_MATCH;
            default -> ClassificationDecision.UNCERTAIN;
        };
    }
}
