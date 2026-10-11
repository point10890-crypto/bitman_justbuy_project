package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.dto.condition.EvidenceVerdict;
import com.bitman.justbuy.service.KisApiService.ConditionCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KeywordConditionAnalysisService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String MODE_DUAL_NET_BUY = "KEYWORD_DUAL_NET_BUY";

    private final KisApiService kisApiService;
    private final DeepSeekAgent deepSeekAgent;
    private final ObjectMapper mapper;
    private KisSourceExpansionService sourceExpansion;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureSourceExpansion(KisSourceExpansionService expansion) { this.sourceExpansion=expansion; }

    public KeywordConditionAnalysisService(KisApiService kisApiService,
                                           DeepSeekAgent deepSeekAgent,
                                           ObjectMapper mapper) {
        this.kisApiService = kisApiService;
        this.deepSeekAgent = deepSeekAgent;
        this.mapper = mapper;
    }

    public boolean supports(String query, String mode) {
        String normalized = normalize(query + " " + mode);
        boolean hasDualBuy = normalized.contains("쌍끌이")
            || (normalized.contains("외국인") && normalized.contains("기관") && normalized.contains("매수"));
        boolean asksForStockSearch = normalized.contains("종목")
            || normalized.contains("검색")
            || normalized.contains("조건")
            || normalized.contains("수급");
        return hasDualBuy && asksForStockSearch;
    }

    public AnalysisResponse analyze(String query, String requestedMode) {
        long start = System.currentTimeMillis();
        List<ConditionCandidate> candidates = verifiedCandidates(kisApiService.fetchDoubleNetBuyCandidates(5, 3));
        String checkedAt = Instant.now().toString();
        String additionalEvidence="";
        Map<String,Object> sourceMetadata=null;
        if(sourceExpansion!=null && !candidates.isEmpty()) {
            try {
                var enrichment=sourceExpansion.enrich(candidates.stream().map(ConditionCandidate::code).toList());
                if(enrichment!=null) { additionalEvidence=enrichment.prompt(); sourceMetadata=enrichment.metadata(); }
            } catch(RuntimeException ignored) { sourceMetadata=Map.of("status","UNAVAILABLE"); }
        }

        String detectorContent = buildDetectorContent(query, candidates);
        AgentResult detector = new AgentResult(
            "kis-condition",
            detectorContent,
            "KIS OpenAPI keyword detector",
            0,
            0,
            candidates.isEmpty() ? "skipped" : "success",
            candidates.isEmpty() ? "No double-net-buy candidates detected in current KIS seed universe." : null,
            System.currentTimeMillis() - start
        );

        AgentResult analyst = candidates.isEmpty()
            ? AgentResult.skipped("deepseek", "No verified KIS candidates")
            : runDeepSeekAnalyst(query, candidates, checkedAt, additionalEvidence);
        List<AgentResult> round1 = List.of(detector, analyst);
        List<StockPick> picks = candidates.stream()
            .map(candidate -> toStockPick(candidate, checkedAt))
            .toList();

        String finalContent = buildTextReport(query, candidates, analyst);

        int succeeded = (int) round1.stream().filter(r -> "success".equals(r.status())).count();
        return new AnalysisResponse(
            MODE_DUAL_NET_BUY,
            query,
            round1,
            null,
            finalContent,
            picks,
            null,
            Instant.now().toString(),
            !picks.isEmpty(),
            new AnalysisResponse.Metadata(System.currentTimeMillis() - start, round1.size(), succeeded,null,sourceMetadata)
        );
    }

    private List<ConditionCandidate> verifiedCandidates(List<ConditionCandidate> input) {
        Map<String, ConditionCandidate> verified = new LinkedHashMap<>();
        if (input != null) for (ConditionCandidate candidate : input) {
            if (candidate == null || candidate.code() == null || !candidate.code().matches("[0-9]{6}")
                || candidate.name() == null || candidate.name().isBlank()
                || candidate.source() == null || candidate.source().isBlank()
                || positivePrice(candidate.currentPrice()) == null) continue;
            verified.putIfAbsent(candidate.code(), candidate);
            if (verified.size() == 5) break;
        }
        return List.copyOf(verified.values());
    }

    private AgentResult runDeepSeekAnalyst(String query, List<ConditionCandidate> candidates, String checkedAt, String additionalEvidence) {
        if (!deepSeekAgent.isAvailable()) {
            return AgentResult.skipped("deepseek", "API key not configured");
        }

        String system = """
            You are BitMan's Korean stock condition-search analyst.
            Use only the provided KIS OpenAPI candidate data.
            Do not invent stock names, stock codes, foreign/institution values, targets, or news.
            Return one JSON object only. Do not use markdown or prose outside JSON.
            Use only candidate stockCode values. aiScore must be an integer from 0 to 99.
            verdict must be VALID, WATCH, OVERHEATED or REJECT. Do not give buy instructions.
            Every signal needs nonempty Korean summary, evidence, riskFlags and invalidation.
            Schema: {"signals":[{"stockCode":"000000","aiScore":82,"verdict":"WATCH","summary":"...","evidence":["..."],"riskFlags":["..."],"invalidation":"...","displayStatus":"관찰"}]}
            """;
        String user = "사용자 검색어: " + query + "\n"
            + "KST 기준일: " + DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.now().atZone(KST)) + "\n"
            + "KIS 후보 데이터:\n" + toJson(candidatePayload(candidates))
            + (additionalEvidence==null?"":additionalEvidence);
        AgentResult result = deepSeekAgent.analyze(system, user);
        if (!"success".equals(result.status())) return safeAnalystResult(result, "", result.status(), result.error());
        List<EvidencePacket> packets = candidates.stream().map(candidate -> new EvidencePacket(
            "KEYWORD", MODE_DUAL_NET_BUY, checkedAt, candidate.code(), candidate.name(),
            candidate.currentPrice(), candidate.changeRate(), 0, List.of(candidate.reason()),
            Map.of("source", candidate.source(), "foreignNet", candidate.foreignNet(),
                "institutionNet", candidate.institutionNet(), "totalSmartNet", candidate.totalSmartNet())
        )).toList();
        Map<String, EvidenceVerdict> verdicts = new DeepSeekConditionVerifier(deepSeekAgent, mapper)
            .parse(result.content(), packets);
        if (verdicts.isEmpty()) return safeAnalystResult(result, "", "error", "Invalid candidate evidence response");
        StringBuilder safeContent = new StringBuilder();
        for (ConditionCandidate candidate : candidates) {
            EvidenceVerdict verdict = verdicts.get(candidate.code());
            if (verdict == null) continue;
            safeContent.append(displayName(candidate)).append("\n")
                .append("판정: ").append(verdict.verdict()).append("\n")
                .append(verdict.summary()).append("\n")
                .append("근거: ").append(String.join(", ", verdict.evidence())).append("\n")
                .append("리스크: ").append(String.join(", ", verdict.riskFlags())).append("\n")
                .append("무효화: ").append(verdict.invalidation()).append("\n\n");
        }
        return safeAnalystResult(result, safeContent.toString().trim(), "success", null);
    }

    private AgentResult safeAnalystResult(AgentResult result, String content, String status, String error) {
        return new AgentResult(result.agent(), content, result.model(), result.inputTokens(), result.outputTokens(),
            status, error, result.durationMs(), result.thoughtTokens(), result.totalTokens());
    }

    private List<Map<String, Object>> candidatePayload(List<ConditionCandidate> candidates) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ConditionCandidate c : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", c.name());
            item.put("code", c.code());
            item.put("currentPrice", c.currentPrice());
            item.put("changeRate", c.changeRate());
            item.put("foreignNet", c.foreignNet());
            item.put("institutionNet", c.institutionNet());
            item.put("individualNet", c.individualNet());
            item.put("totalSmartNet", c.totalSmartNet());
            item.put("source", c.source());
            item.put("reason", c.reason());
            payload.add(item);
        }
        return payload;
    }

    private StockPick toStockPick(ConditionCandidate candidate, String checkedAt) {
        return new StockPick(
            candidate.name(),
            candidate.code(),
            formatPriceText(candidate.currentPrice()),
            "",
            "",
            "주목",
            candidate.reason(),
            0,
            "",
            "KIS_DOUBLE_NET_BUY",
            candidate.source(),
            checkedAt
        );
    }

    private String buildDetectorContent(String query, List<ConditionCandidate> candidates) {
        return "query=" + query + ", candidates=" + candidates.size();
    }

    private String buildTextReport(String query, List<ConditionCandidate> candidates, AgentResult analyst) {
        StringBuilder sb = new StringBuilder();
        sb.append("검색식 해석\n");
        sb.append("'").append(query).append("'는 외국인과 기관이 동시에 순매수한 쌍끌이 매수 조건으로 해석했습니다.\n\n");
        if (candidates.isEmpty()) {
            sb.append("KIS 검출 결과: 현재 확인한 KIS 후보군 안에서는 조건을 동시에 만족하는 종목을 찾지 못했습니다.\n");
            sb.append("확인 범위: 외국인 순매수, 거래량 상위, 상승률 상위 후보를 기준으로 최근 3일 외국인/기관 합산 수급을 재확인했습니다.\n");
            sb.append("다음 액션: 장중 데이터 갱신 후 다시 조회하거나, '외국인 기관 동시 순매수 거래대금 상위'처럼 범위를 좁혀 조회하세요.\n");
        } else {
            sb.append("KIS 검출 종목\n");
            for (int i = 0; i < candidates.size(); i++) {
                ConditionCandidate c = candidates.get(i);
                sb.append(i + 1).append(". ").append(displayName(c)).append("\n")
                    .append("현재가 ").append(formatPriceText(c.currentPrice()))
                    .append(", 등락률 ").append(formatChangeRateText(c.changeRate())).append("\n")
                    .append("외국인 순매수 ").append(formatShares(c.foreignNet()))
                    .append(", 기관 순매수 ").append(formatShares(c.institutionNet()))
                    .append(", 스마트머니 합계 ").append(formatShares(c.totalSmartNet())).append("\n")
                    .append("판단: ").append(c.reason()).append("\n\n");
            }
        }
        String analystText = analyst != null && "success".equals(analyst.status()) ? analyst.content() : "";
        if (analystText != null && !analystText.isBlank()) {
            sb.append("DeepSeek 애널리스트 코멘트\n")
                .append(analystText.trim()).append("\n\n");
        }
        sb.append("투자 유의: 본 결과는 조건검색과 AI 요약을 위한 참고 정보이며 매수/매도 추천이나 수익 보장이 아닙니다.");
        return sb.toString();
    }

    private String displayName(ConditionCandidate candidate) {
        String name = valueOrDash(candidate.name());
        if (name.equals(candidate.code())) return candidate.code();
        return name + "(" + candidate.code() + ")";
    }

    private String formatShares(long value) {
        return String.format(Locale.KOREA, "%,d주", value);
    }

    private String formatPriceText(String value) {
        Long price = positivePrice(value);
        return price == null ? "미확인" : String.format(Locale.KOREA, "%,d원", price);
    }

    private Long positivePrice(String value) {
        if (value == null) return null;
        String numeric = value.trim().replaceFirst("원$", "").trim();
        if (!numeric.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)")) return null;
        try {
            long price = Long.parseLong(numeric.replace(",", ""));
            return price > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatChangeRateText(String value) {
        if (value == null || value.isBlank()) return "-";
        String cleaned = value.replace("%", "").trim();
        if (cleaned.isBlank()) return "-";
        try {
            double rate = Double.parseDouble(cleaned);
            if (!Double.isFinite(rate)) return "미확인";
            return (rate > 0 ? "+" : "") + cleaned + "%";
        } catch (NumberFormatException e) {
            return value.trim();
        }
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.KOREA).replaceAll("\\s+", "");
    }

    private String toJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
