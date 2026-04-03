package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xAI Grok 에이전트.
 * /v1/responses 엔드포인트 사용, web_search + x_search 툴 활성화.
 * 담당: L3 Capital Flow + L5 Derivatives + SNS/X 실시간 센티먼트
 */
@Component
public class GrokAgent implements AiAgent {

    private static final String BASE_URL = "https://api.x.ai/v1";
    // R1 분석용: 내부 멀티에이전트 협업 모델 (웹/X 검색 포함)
    static final String ANALYSIS_MODEL = "grok-4.20-multi-agent-0309";
    // R2 교차비판용: 빠르고 저렴한 모델
    static final String CRITIQUE_MODEL = "grok-4-1-fast";

    // 한국 금융 신뢰 도메인 — 이 사이트만 웹검색
    private static final List<String> ALLOWED_DOMAINS = List.of(
        "dart.fss.or.kr",
        "finance.naver.com",
        "mk.co.kr",
        "hankyung.com",
        "krx.co.kr",
        "investing.com",
        "fnguide.com",
        "kisline.com"
    );

    private final AiProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public GrokAgent(AiProperties props, RestTemplate restTemplate, ObjectMapper mapper) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    @Override
    public String name() { return "grok"; }

    @Override
    public boolean isAvailable() {
        return props.grokApiKey() != null && !props.grokApiKey().isBlank();
    }

    /** R1 분석용 — 웹검색 + X검색 활성화 (grok-4.20-multi-agent) */
    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        return callResponses(ANALYSIS_MODEL, systemPrompt, userMessage, true);
    }

    /** R2 교차비판용 — 저렴한 모델, 웹검색 없이 추론 (grok-4-1-fast) */
    public AgentResult critique(String systemPrompt, String userMessage) {
        return callResponses(CRITIQUE_MODEL, systemPrompt, userMessage, false);
    }

    /**
     * xAI Responses API 호출.
     * enableSearch=true 시 web_search + x_search 툴 활성화.
     */
    private AgentResult callResponses(String model, String systemPrompt, String userMessage, boolean enableSearch) {
        long start = System.currentTimeMillis();

        if (!isAvailable()) {
            return AgentResult.skipped(name(), "API key not configured");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + props.grokApiKey());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_output_tokens", 4096);
            body.put("input", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)
            ));

            // 검색 툴 (R1 분석 모드에서만 활성화)
            if (enableSearch) {
                String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
                List<Map<String, Object>> tools = new ArrayList<>();

                // 웹검색 — 한국 금융 신뢰 도메인 제한
                tools.add(Map.of(
                    "type", "web_search",
                    "allowed_domains", ALLOWED_DOMAINS
                ));

                // X(트위터) 검색 — 오늘부터 실시간
                tools.add(Map.of(
                    "type", "x_search",
                    "from_date", today
                ));

                body.put("tools", tools);
            }

            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                BASE_URL + "/responses", HttpMethod.POST, request, String.class);

            return parseResponsesApiResult(response.getBody(), model, start);

        } catch (Exception e) {
            return AgentResult.error(name(), model, e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    /**
     * xAI Responses API 응답 파싱.
     * 응답 구조: { output: [{ type: "message", content: [{ type: "output_text", text: "..." }] }],
     *              usage: { input_tokens, output_tokens } }
     */
    private AgentResult parseResponsesApiResult(String responseBody, String model, long start) throws Exception {
        JsonNode root = mapper.readTree(responseBody);

        // output 배열에서 텍스트 추출
        String content = "";
        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                if ("message".equals(item.path("type").asText())) {
                    JsonNode contentArr = item.path("content");
                    if (contentArr.isArray()) {
                        for (JsonNode c : contentArr) {
                            if ("output_text".equals(c.path("type").asText())) {
                                content = c.path("text").asText("");
                                break;
                            }
                        }
                    }
                    if (!content.isEmpty()) break;
                }
            }
        }

        // 폴백: choices 구조도 허용 (API 호환성)
        if (content.isEmpty()) {
            content = root.path("choices").path(0).path("message").path("content").asText("");
        }

        // 인용 추출 (web_search/x_search 결과)
        List<String> citations = new ArrayList<>();
        JsonNode citationsNode = root.path("citations");
        if (citationsNode.isArray()) {
            for (JsonNode c : citationsNode) {
                String url = c.path("url").asText("");
                if (!url.isEmpty()) citations.add(url);
            }
        }

        // 인용이 있으면 콘텐츠 끝에 append
        if (!citations.isEmpty()) {
            StringBuilder sb = new StringBuilder(content);
            sb.append("\n\n**📎 검색 출처 (").append(citations.size()).append("건)**\n");
            for (int i = 0; i < Math.min(citations.size(), 10); i++) {
                sb.append("- ").append(citations.get(i)).append("\n");
            }
            content = sb.toString();
        }

        String actualModel = root.path("model").asText(model);
        int inputTokens = root.path("usage").path("input_tokens").asInt(
            root.path("usage").path("prompt_tokens").asInt(0)
        );
        int outputTokens = root.path("usage").path("output_tokens").asInt(
            root.path("usage").path("completion_tokens").asInt(0)
        );

        return new AgentResult(name(), content, actualModel, inputTokens, outputTokens,
            "success", null, System.currentTimeMillis() - start);
    }
}
