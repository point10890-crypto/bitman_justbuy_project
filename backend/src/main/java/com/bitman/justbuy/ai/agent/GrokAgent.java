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
 * /v1/responses 엔드포인트 사용, 모드별로 검색 툴 활성화 정책을 차별화.
 * 담당: L3 Capital Flow + L5 Derivatives + SNS/X 실시간 센티먼트
 *
 * v2.8.3 토큰 절감 (2026-04-25):
 *  - 모드별 SearchPolicy 도입 (NONE/WEB_ONLY/BOTH)
 *  - KIS 데이터로 충분한 모드(BREAKOUT)는 검색 비활성 + 저렴 모델로 우회
 *  - X검색은 센티먼트 활용 모드(REVERSAL_EDGE/CATALYST_BURST)에서만 사용
 */
@Component
public class GrokAgent implements AiAgent {

    private static final String BASE_URL = "https://api.x.ai/v1";
    // R1 분석용: 내부 멀티에이전트 협업 모델 (웹/X 검색 포함)
    static final String ANALYSIS_MODEL = "grok-4.20-multi-agent-0309";
    // R2 교차비판용 / 검색 비활성 모드 우회용: 빠르고 저렴한 모델
    static final String CRITIQUE_MODEL = "grok-4-1-fast-reasoning";

    // 한국 금융 신뢰 도메인 — Grok API 최대 5개 제한
    private static final List<String> ALLOWED_DOMAINS = List.of(
        "krx.co.kr",
        "finance.naver.com",
        "dart.fss.or.kr",
        "hankyung.com",
        "mk.co.kr"
    );

    /** 모드별 검색 정책 — 토큰 폭발 방지 */
    public enum SearchPolicy { NONE, WEB_ONLY, BOTH }

    /**
     * 모드별 검색 정책 매핑.
     *  - BREAKOUT: KIS 실시간 시세·거래량으로 충분 → 검색 OFF + 저렴 모델 우회
     *  - FLOW_LEADER: 외국인/기관 수급 검색 필요, X센티먼트는 불필요 → web_search ONLY
     *  - CATALYST_BURST: 실시간 뉴스/공시 + 사회반응 둘 다 필요 → BOTH
     *  - REVERSAL_EDGE: X피드 센티먼트가 핵심 → BOTH
     */
    private static final Map<String, SearchPolicy> MODE_SEARCH_POLICY = Map.of(
        "BREAKOUT",       SearchPolicy.NONE,
        "FLOW_LEADER",    SearchPolicy.WEB_ONLY,
        "CATALYST_BURST", SearchPolicy.BOTH,
        "REVERSAL_EDGE",  SearchPolicy.BOTH
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

    /**
     * R1 분석용 (기본) — 웹검색 + X검색 활성화 (grok-4.20-multi-agent).
     * 모드 정보가 없는 일반 호출 경로. 토큰 폭발이 우려되는 경우 analyzeForMode 사용.
     */
    @Override
    public AgentResult analyze(String systemPrompt, String userMessage) {
        return callResponses(ANALYSIS_MODEL, systemPrompt, userMessage, SearchPolicy.BOTH);
    }

    /**
     * 모드별 검색 정책을 적용한 분석.
     *  - BREAKOUT 등 KIS 데이터가 충분한 모드는 검색 OFF + 저렴 모델 우회
     *  - 나머지는 모드별 SearchPolicy 적용
     */
    public AgentResult analyzeForMode(String systemPrompt, String userMessage, String mode) {
        SearchPolicy policy = MODE_SEARCH_POLICY.getOrDefault(mode, SearchPolicy.BOTH);
        // 검색 비활성 모드는 저렴 모델로 우회 (multi-agent 사용할 이유 없음)
        String model = (policy == SearchPolicy.NONE) ? CRITIQUE_MODEL : ANALYSIS_MODEL;
        return callResponses(model, systemPrompt, userMessage, policy);
    }

    /** R2 교차비판용 — 저렴한 모델, 웹검색 없이 추론 (grok-4-1-fast) */
    public AgentResult critique(String systemPrompt, String userMessage) {
        return callResponses(CRITIQUE_MODEL, systemPrompt, userMessage, SearchPolicy.NONE);
    }

    /**
     * xAI Responses API 호출.
     * SearchPolicy 에 따라 web_search / x_search 툴을 선택적으로 활성화.
     */
    private AgentResult callResponses(String model, String systemPrompt, String userMessage, SearchPolicy policy) {
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

            // 검색 툴 — SearchPolicy 에 따라 차별 활성
            //   NONE     : 검색 OFF (KIS 데이터로 충분, 토큰 폭발 방지)
            //   WEB_ONLY : 웹검색만 (외국인/기관 수급 등)
            //   BOTH     : 웹+X 검색 (실시간 뉴스+사회반응)
            if (policy != SearchPolicy.NONE) {
                String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
                List<Map<String, Object>> tools = new ArrayList<>();

                // 웹검색 — 한국 금융 신뢰 도메인 제한
                tools.add(Map.of(
                    "type", "web_search",
                    "allowed_domains", ALLOWED_DOMAINS
                ));

                // X(트위터) 검색 — BOTH 모드에서만 활성 (센티먼트 활용 모드)
                if (policy == SearchPolicy.BOTH) {
                    tools.add(Map.of(
                        "type", "x_search",
                        "from_date", today
                    ));
                }

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
