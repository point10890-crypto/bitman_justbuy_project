package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.dto.condition.EvidenceVerdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1차 AI 검증 에이전트 (하이브리드 스택의 FAST_VERIFY 단계).
 *
 * 룰 엔진이 만든 증거 패킷 batch를 DeepSeek flash 에 한 번에 넘겨 JSON-only 로 검증한다.
 * - AI 비활성 / 호출 실패 / 파싱 실패 → 빈 맵 반환 (fail-open). 호출자는 룰 점수만으로 신호를 만든다.
 * - 패킷에 없는 수치 생성 금지, 매수 지시 금지, 리스크/무효화 조건 필수 — 프롬프트로 강제.
 */
@Service
public class DeepSeekConditionVerifier {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekConditionVerifier.class);

    private static final String SYSTEM_PROMPT =
        "너는 한국 주식 조건검색 결과를 검증하는 시스템이다. 종목을 새로 상상하지 않는다.\n"
        + "입력으로 받은 후보 증거 패킷만 보고 각 후보를 검증한다.\n\n"
        + "절대 규칙:\n"
        + "1. 입력 패킷에 없는 수치(가격/수급/거래대금 등)를 만들어내지 마라.\n"
        + "2. 매수 지시가 아니라 '조건검색 결과'로 표현하라. '반드시 사라' 같은 단정 금지.\n"
        + "3. 각 후보에 리스크(riskFlags)와 무효화 조건(invalidation)을 반드시 포함하라.\n"
        + "4. 응답은 오직 JSON 으로만 한다. 설명 문장, 마크다운, 코드펜스 없이 JSON 객체 하나만 출력한다.\n\n"
        + "verdict 허용값: VALID(조건 충족 견고), WATCH(관찰), OVERHEATED(과열 주의), REJECT(부적합).\n"
        + "displayStatus 는 한국어 짧은 라벨(예: 포착, 관찰, 과열주의, 제외).\n"
        + "aiScore 는 0~99 정수.\n\n"
        + "출력 스키마:\n"
        + "{\"signals\":[{\"stockCode\":\"000000\",\"aiScore\":82,\"verdict\":\"VALID\",\"summary\":\"...\",\"evidence\":[\"...\"],\"riskFlags\":[\"...\"],\"invalidation\":\"...\",\"displayStatus\":\"포착\"}]}";

    private final DeepSeekAgent deepSeekAgent;
    private final ObjectMapper mapper;

    public DeepSeekConditionVerifier(DeepSeekAgent deepSeekAgent, ObjectMapper mapper) {
        this.deepSeekAgent = deepSeekAgent;
        this.mapper = mapper;
    }

    public boolean isAvailable() {
        return deepSeekAgent != null && deepSeekAgent.isAvailable();
    }

    /**
     * 후보 증거 패킷 batch 를 검증한다.
     *
     * @return stockCode -> EvidenceVerdict. 실패/비활성 시 빈 맵.
     */
    public Map<String, EvidenceVerdict> verify(List<EvidencePacket> packets) {
        Map<String, EvidenceVerdict> verdicts = new LinkedHashMap<>();
        if (packets == null || packets.isEmpty() || !isAvailable()) {
            return verdicts;
        }

        try {
            String userMessage = buildUserMessage(packets);
            AgentResult result = deepSeekAgent.analyze(SYSTEM_PROMPT, userMessage);
            if (!"success".equals(result.status()) || result.content() == null || result.content().isBlank()) {
                log.warn("[ConditionVerifier] DeepSeek verify 실패 (status={}) — 룰 점수 폴백", result.status());
                return verdicts;
            }
            return parse(result.content(), packets);
        } catch (Exception e) {
            log.warn("[ConditionVerifier] verify 예외 — 룰 점수 폴백: {}", e.getMessage());
            return verdicts;
        }
    }

    String buildUserMessage(List<EvidencePacket> packets) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("section", packets.get(0).section());
            payload.put("asOf", packets.get(0).asOf());
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (EvidencePacket packet : packets) {
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("stockCode", packet.stockCode());
                candidate.put("stockName", packet.stockName());
                candidate.put("currentPrice", packet.currentPrice());
                candidate.put("changeRate", packet.changeRate());
                candidate.put("ruleScore", packet.ruleScore());
                candidate.put("ruleEvidence", packet.ruleEvidence());
                candidate.put("metrics", packet.metrics());
                candidates.add(candidate);
            }
            payload.put("candidates", candidates);
            payload.put("constraints", List.of(
                "입력에 없는 수치를 만들지 말 것",
                "매수 지시가 아니라 조건검색 결과로 표현할 것",
                "리스크와 무효화 조건을 반드시 포함할 것"
            ));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    Map<String, EvidenceVerdict> parse(String content, List<EvidencePacket> packets) {
        Map<String, EvidenceVerdict> verdicts = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(stripToJson(content));
            JsonNode array = root.isArray() ? root : root.path("signals");
            if (!array.isArray()) array = root.path("results");
            if (!array.isArray() && root.has("stockCode")) {
                EvidenceVerdict single = toVerdict(root);
                if (single != null) verdicts.put(single.stockCode(), single);
                return verdicts;
            }
            if (!array.isArray()) {
                log.warn("[ConditionVerifier] JSON 배열 미발견 — 룰 점수 폴백");
                return verdicts;
            }
            for (JsonNode node : array) {
                EvidenceVerdict verdict = toVerdict(node);
                if (verdict != null && verdict.stockCode() != null && !verdict.stockCode().isBlank()) {
                    verdicts.put(verdict.stockCode(), verdict);
                }
            }
            log.info("[ConditionVerifier] {}개 후보 중 {}개 AI 판정 파싱", packets.size(), verdicts.size());
        } catch (Exception e) {
            log.warn("[ConditionVerifier] JSON 파싱 실패 — 룰 점수 폴백: {}", e.getMessage());
        }
        return verdicts;
    }

    private EvidenceVerdict toVerdict(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        String code = node.path("stockCode").asText("");
        if (code.isBlank()) return null;
        int aiScore = clamp(node.path("aiScore").asInt(0), 0, 99);
        String verdict = node.path("verdict").asText("WATCH");
        String summary = node.path("summary").asText("");
        String invalidation = node.path("invalidation").asText("");
        String displayStatus = node.path("displayStatus").asText("");
        return new EvidenceVerdict(
            code,
            aiScore,
            verdict,
            summary,
            stringList(node.path("evidence")),
            stringList(node.path("riskFlags")),
            invalidation,
            displayStatus
        );
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("");
                if (!value.isBlank()) values.add(value);
            }
        }
        return values;
    }

    static String stripToJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        int firstBrace = s.indexOf('{');
        int firstBracket = s.indexOf('[');
        int start;
        if (firstBracket >= 0 && (firstBrace < 0 || firstBracket < firstBrace)) {
            start = firstBracket;
        } else {
            start = firstBrace;
        }
        int end = Math.max(s.lastIndexOf('}'), s.lastIndexOf(']'));
        if (start < 0 || end <= start) return s;
        return s.substring(start, end + 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
