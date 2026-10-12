package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.dto.condition.EvidenceVerdict;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 1차 AI 검증 에이전트 (하이브리드 스택의 FAST_VERIFY 단계).
 *
 * 룰 엔진이 만든 증거 패킷 batch를 DeepSeek flash 에 한 번에 넘겨 JSON-only 로 검증한다.
 * - AI 비활성 / 호출 실패 / 파싱 실패 → 빈 맵 반환 (fail-open). 호출자는 룰 점수만으로 신호를 만든다.
 * - factId/value claims를 원 패킷에 대조한다. 수치 표시 근거는 서버가 생성한다.
 * - 판정 수치 일치 검증은 투자 정확도나 수익성이 검증됐다는 의미가 아니다.
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
        + "aiScore 는 0~99 정수.\n"
        + "반증 검증 역할: 자료 결측·출처·환경·관측 기간·단위를 먼저 확인하고 근거 부족은 조건 보류 사유로 설명한다.\n"
        + "KIS와 Kiwoom 근거는 독립 보존한다. MOCK은 서버 환경이다. 기간이 다른 수급 합산이나 가격 평균은 금지한다.\n"
        + "facts 카탈로그에 있는 factId와 value만 claims에 그대로 복사한다. claims는 최소 하나, 최대 열여섯 개이며 중복은 금지한다.\n"
        + "claim에는 factId/value 외 기간·출처·환경·단위 등을 추가하지 않는다. 원 출처와 기간은 서버가 보존한다.\n"
        + "summary/riskFlags/invalidation/displayStatus에는 숫자나 한글 수량·가격·기간을 쓰지 않는다. 수치는 claims에만 넣는다.\n"
        + "evidence 텍스트를 작성하지 않는다. 서버가 원사실을 표시한다. claims 누락 또는 불일치는 해당 AI 판정 미사용이다.\n"
        + "PENDING/자료 미확인 값은 사실로 주장하지 않는다. 원 패킷 일치 검증을 수익성 검증으로 표현하지 않는다.\n\n"
        + "출력 스키마:\n"
        + "{\"signals\":[{\"stockCode\":\"000000\",\"aiScore\":82,\"verdict\":\"WATCH\",\"summary\":\"...\",\"claims\":[{\"factId\":\"카탈로그 factId\",\"value\":\"카탈로그 원값\"}],\"riskFlags\":[\"...\"],\"invalidation\":\"...\",\"displayStatus\":\"관찰\"}]}";

    private final DeepSeekAgent deepSeekAgent;
    private final ObjectMapper mapper;
    private final EvidenceClaimGuard claimGuard = new EvidenceClaimGuard();
    private AnalysisEvidenceJournalService evidenceJournal;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureEvidenceJournal(AnalysisEvidenceJournalService journal) { this.evidenceJournal = journal; }

    public DeepSeekConditionVerifier(DeepSeekAgent deepSeekAgent, ObjectMapper mapper) {
        this.deepSeekAgent = deepSeekAgent;
        this.mapper = mapper;
    }

    public boolean isAvailable() {
        return deepSeekAgent != null && deepSeekAgent.isAvailable();
    }

    /** Shared by consumers that retain provider usage metadata from their single paid call. */
    static String verificationInstructions() { return SYSTEM_PROMPT; }

    /**
     * 후보 증거 패킷 batch 를 검증한다.
     *
     * @return stockCode -> EvidenceVerdict. 실패/비활성 시 빈 맵.
     */
    public Map<String, EvidenceVerdict> verify(List<EvidencePacket> packets) {
        Map<String, EvidenceVerdict> verdicts = new LinkedHashMap<>();
        if (packets == null || packets.isEmpty()) {
            return verdicts;
        }
        capture(packets, Map.of());

        try {
            if (!isAvailable()) return verdicts;
            String userMessage = buildUserMessage(packets);
            AgentResult result = deepSeekAgent.analyze(SYSTEM_PROMPT, userMessage);
            if (result == null || !"success".equals(result.status()) || result.content() == null || result.content().isBlank()) {
                log.warn("[ConditionVerifier] Provider response unavailable — rule fallback");
                return verdicts;
            }
            verdicts = parse(result.content(), packets);
            capture(packets, verdicts);
            return verdicts;
        } catch (Exception e) {
            log.warn("[ConditionVerifier] Verification unavailable — rule fallback ({})", e.getClass().getSimpleName());
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
                candidate.put("asOf", packet.asOf());
                candidate.put("facts", claimGuard.catalog(packet));
                candidates.add(candidate);
            }
            payload.put("candidates", candidates);
            payload.put("constraints", List.of(
                "입력에 없는 수치를 만들지 말 것",
                "매수 지시가 아니라 조건검색 결과로 표현할 것",
                "리스크와 무효화 조건을 반드시 포함할 것",
                "claims는 facts의 factId/value만 복사, 원 출처·기간·환경·단위를 변경하지 말 것",
                "자유 설명은 숫자 없는 정성 설명만 사용, legacy evidence 텍스트는 검증 근거로 사용되지 않음"
            ));
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Evidence serialization unavailable");
        }
    }

    Map<String, EvidenceVerdict> parse(String content, List<EvidencePacket> packets) {
        Map<String, EvidenceVerdict> verdicts = new LinkedHashMap<>();
        try {
            if (content == null || content.length() > 131072 || packets == null || packets.isEmpty()) return verdicts;
            Map<String,EvidencePacket> candidates = new LinkedHashMap<>();
            Set<String> duplicateInputs = new HashSet<>();
            for (EvidencePacket packet : packets) {
                if (packet == null || packet.stockCode() == null) continue;
                if (candidates.putIfAbsent(packet.stockCode(),packet) != null) duplicateInputs.add(packet.stockCode());
            }
            duplicateInputs.forEach(candidates::remove);
            Set<String> duplicateCodes = new HashSet<>();
            Set<String> seenCodes = new HashSet<>();
            JsonNode root = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(stripToJson(content));
            if (root == null) return verdicts;
            JsonNode array = root.isArray() ? root : root.path("signals");
            if (!array.isArray()) array = root.path("results");
            if (!array.isArray() && root.has("stockCode")) {
                EvidenceVerdict single = toVerdict(root, candidates.get(root.path("stockCode").asText("")));
                if (single != null) verdicts.put(single.stockCode(), single);
                return verdicts;
            }
            if (!array.isArray() || array.size() > 100) {
                log.warn("[ConditionVerifier] JSON 배열 미발견 — 룰 점수 폴백");
                return verdicts;
            }
            for (JsonNode node : array) {
                EvidenceVerdict verdict = toVerdict(node, candidates.get(node.path("stockCode").asText("")));
                String code = node.path("stockCode").asText("");
                if (duplicateCodes.contains(code)) continue;
                if (!seenCodes.add(code)) {
                    verdicts.remove(code);
                    duplicateCodes.add(code);
                    continue;
                }
                if (verdict != null) {
                    verdicts.put(verdict.stockCode(), verdict);
                }
            }
            log.info("[ConditionVerifier] {}개 후보 중 {}개 AI 판정 파싱", packets.size(), verdicts.size());
        } catch (Exception e) {
            log.warn("[ConditionVerifier] JSON/claims invalid — rule fallback ({})", e.getClass().getSimpleName());
        }
        return verdicts;
    }

    private EvidenceVerdict toVerdict(JsonNode node, EvidencePacket packet) {
        if (node == null || !node.isObject() || packet == null) return null;
        if (!node.path("stockCode").isTextual()) return null;
        String code = node.path("stockCode").asText("");
        if (!code.matches("[0-9]{6}")) return null;
        if (!node.path("aiScore").isIntegralNumber() || !node.path("aiScore").canConvertToInt()) return null;
        int aiScore = node.path("aiScore").asInt();
        if (aiScore < 0 || aiScore > 99) return null;
        String verdict = node.path("verdict").asText("");
        if (!Set.of("VALID", "WATCH", "OVERHEATED", "REJECT").contains(verdict)) return null;
        String summary = node.path("summary").asText("");
        String invalidation = node.path("invalidation").asText("");
        String displayStatus = node.path("displayStatus").asText("");
        var checked = claimGuard.check(node.path("claims"),claimGuard.catalog(packet));
        if (!checked.valid()) return null;
        List<String> evidence = checked.evidence();
        List<String> risks = stringList(node.path("riskFlags"));
        if (summary.isBlank() || risks.isEmpty() || invalidation.isBlank()
            || !claimGuard.qualitative(summary) || !claimGuard.qualitative(invalidation)
            || !claimGuard.qualitative(displayStatus) || risks.stream().anyMatch(risk -> !claimGuard.qualitative(risk))) return null;
        return new EvidenceVerdict(
            code,
            aiScore,
            verdict,
            summary,
            evidence,
            risks,
            invalidation,
            displayStatus
        );
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray() && node.size() <= 8) {
            for (JsonNode item : node) {
                if (!item.isTextual()) return List.of();
                String value = item.asText("");
                if (!value.isBlank()) values.add(value);
            }
        }
        return values;
    }

    void capture(List<EvidencePacket> packets, Map<String,EvidenceVerdict> verdicts) {
        if (evidenceJournal == null) return;
        try {
            var safePackets = packets.stream().filter(java.util.Objects::nonNull).limit(20).toList();
            if (safePackets.isEmpty()) return;
            var decisions = verdicts.values().stream().limit(20).map(verdict -> Map.<String,Object>of(
                "stockCode",verdict.stockCode(),"aiScore",verdict.aiScore(),"verdict",verdict.verdict())).toList();
            evidenceJournal.capture("CONDITION_VERIFY",safePackets.get(0).mode(),
                safePackets.stream().map(EvidencePacket::stockCode).filter(java.util.Objects::nonNull).toList(),
                Map.of("packets",safePackets,"factCatalogs",safePackets.stream().map(claimGuard::catalog).toList()),decisions);
        } catch (RuntimeException ignored) { /* Journaling cannot stop a source-backed result. */ }
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

}
