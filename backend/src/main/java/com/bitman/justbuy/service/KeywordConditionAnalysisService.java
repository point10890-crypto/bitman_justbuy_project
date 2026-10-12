package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.DeepSeekAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.KeywordSearchResponse;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.dto.condition.EvidenceVerdict;
import com.bitman.justbuy.service.KisApiService.ConditionCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class KeywordConditionAnalysisService {

    private static final String MODE_DUAL_NET_BUY = "KEYWORD_DUAL_NET_BUY";

    private final KisApiService kisApiService;
    private final DeepSeekAgent deepSeekAgent;
    private final ObjectMapper mapper;
    private KisSourceExpansionService sourceExpansion;
    private KeywordConditionSearchService keywordSearch;
    private DeepSeekConditionVerifier evidenceVerifier;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureKeywordSearch(KeywordConditionSearchService keywordSearch) {this.keywordSearch=keywordSearch;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureSourceExpansion(KisSourceExpansionService expansion) { this.sourceExpansion=expansion; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureEvidenceVerifier(DeepSeekConditionVerifier verifier) { this.evidenceVerifier=verifier; }

    public KeywordConditionAnalysisService(KisApiService kisApiService,
                                           DeepSeekAgent deepSeekAgent,
                                           ObjectMapper mapper) {
        this.kisApiService = kisApiService;
        this.deepSeekAgent = deepSeekAgent;
        this.mapper = mapper;
        this.evidenceVerifier = new DeepSeekConditionVerifier(deepSeekAgent, mapper);
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
        if(keywordSearch!=null) return analyzeSharedSearch(query,start);
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
            : runDeepSeekAnalyst(query, candidates, checkedAt, additionalEvidence, Map.of());
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

    /** The legacy AI route consumes the same verified dual-provider facts as the homepage search. */
    private AnalysisResponse analyzeSharedSearch(String query,long start) {
        // Preserve requested conditions and periods; an unsupported term must not silently become a three-day query.
        KeywordSearchResponse search=keywordSearch.search(query);
        var candidates=new ArrayList<ConditionCandidate>();
        var matches=new LinkedHashMap<String,KeywordSearchResponse.Match>();
        if(search!=null && search.matches()!=null) for(var match:search.matches()) {
            if(match==null || match.code()==null || !match.code().matches("[0-9]{6}") || match.name()==null || match.name().isBlank()
                || match.currentPrice()==null || match.currentPrice().signum()<=0 || match.currentPrice().stripTrailingZeros().scale()>0
                || match.quoteCheckedAt()==null || match.evidence()==null) continue;
            var foreign=match.evidence().stream().filter(e->"외국인 순매수".equals(e.condition())&&"주".equals(e.unit())).findFirst().orElse(null);
            var institution=match.evidence().stream().filter(e->"기관 순매수".equals(e.condition())&&"주".equals(e.unit())).findFirst().orElse(null);
            if(foreign==null || institution==null || !foreign.source().equals(institution.source())
                || !java.util.Objects.equals(foreign.environment(),institution.environment()) || !java.util.Objects.equals(foreign.observedAt(),institution.observedAt())) continue;
            try {
                long foreignNet=Long.parseLong(foreign.observedValue()),institutionNet=Long.parseLong(institution.observedValue());
                if(foreignNet<=0||institutionNet<=0)continue;
                String reason="요청한 실제 거래일 외국인·기관 동시 순매수 · "+foreign.source()+" ("+foreign.environment()+") · "+foreign.observedAt();
                long smart=Math.addExact(foreignNet,institutionNet);
                if(matches.putIfAbsent(match.code(),match)!=null)continue;
                candidates.add(new ConditionCandidate(match.name(),match.code(),match.currentPrice().toPlainString(),
                    match.changePct()==null?"":match.changePct().toPlainString(),foreignNet,institutionNet,0,smart,foreign.source(),reason));
                if(candidates.size()==5)break;
            }catch(RuntimeException ignored){/* Never reconstruct unverified or overflowing flow values. */}
        }
        String checkedAt=Instant.now().toString();
        String evidence=search==null?"":"\n[공급자별 독립 조회 근거: 시세와 수급 출처·환경·시각을 유지]\n"+toJson(search);
        AgentResult detector=new AgentResult("condition-tools",buildDetectorContent(query,candidates),"KIS / Kiwoom REST verified condition detector",
            0,0,candidates.isEmpty()?"skipped":"success",candidates.isEmpty()?"No verified double-net-buy matches":null,System.currentTimeMillis()-start);
        AgentResult analyst=candidates.isEmpty()?AgentResult.skipped("deepseek","No verified candidates"):runDeepSeekAnalyst(query,candidates,checkedAt,evidence,matches);
        List<StockPick> picks=candidates.stream().map(candidate->{var match=matches.get(candidate.code());return new StockPick(candidate.name(),candidate.code(),
            formatPriceText(candidate.currentPrice()),"","","주목",candidate.reason(),0,"","DUAL_PROVIDER_CONDITION_SEARCH",
            match.quoteSource(),match.quoteCheckedAt().toString());}).toList();
        Map<String,Object> coverage=new LinkedHashMap<>();coverage.put("status",search==null?"UNAVAILABLE":search.sourceStatus());
        coverage.put("keywordOutcome",search==null?"UNAVAILABLE":search.outcome());coverage.put("providers",search==null?List.of():search.providers());
        coverage.put("warnings",search==null?List.of():search.warnings());coverage.put("scoringEnabled",false);
        var round1=List.of(detector,analyst);int succeeded=(int)round1.stream().filter(r->"success".equals(r.status())).count();
        return new AnalysisResponse(MODE_DUAL_NET_BUY,query,round1,null,buildTextReport(query,candidates,analyst),picks,null,Instant.now().toString(),
            !picks.isEmpty(),new AnalysisResponse.Metadata(System.currentTimeMillis()-start,round1.size(),succeeded,null,coverage));
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

    private AgentResult runDeepSeekAnalyst(String query, List<ConditionCandidate> candidates, String checkedAt,
                                          String additionalEvidence, Map<String,KeywordSearchResponse.Match> matches) {
        List<EvidencePacket> packets = candidates.stream().map(candidate -> keywordPacket(candidate, checkedAt, matches.get(candidate.code()))).toList();
        evidenceVerifier.capture(packets, Map.of());
        AgentResult result;
        try {
            if (deepSeekAgent == null || !deepSeekAgent.isAvailable())
                return AgentResult.skipped("deepseek", "API key not configured");
            String user = evidenceVerifier.buildUserMessage(packets)
                + "\n[사용자 검색 조건: 증거 카탈로그 밖 수치는 주장에 사용하지 않음]\n" + query
                + (additionalEvidence == null ? "" : additionalEvidence);
            result = deepSeekAgent.analyze(DeepSeekConditionVerifier.verificationInstructions(), user);
        } catch (RuntimeException ignored) {
            return AgentResult.error("deepseek", "", "Evidence analysis unavailable", 0);
        }
        if (result == null) return AgentResult.error("deepseek", "", "Evidence analysis unavailable", 0);
        if (!"success".equals(result.status())) return safeAnalystResult(result, "", result.status(), "Evidence analysis unavailable");
        Map<String, EvidenceVerdict> verdicts = evidenceVerifier.parse(result.content(), packets);
        evidenceVerifier.capture(packets, verdicts);
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

    private EvidencePacket keywordPacket(ConditionCandidate candidate, String checkedAt, KeywordSearchResponse.Match match) {
        Map<String,Object> metrics = new LinkedHashMap<>();
        metrics.put("source",candidate.source());
        metrics.put("unit","shares");
        metrics.put("foreignNet",candidate.foreignNet());
        metrics.put("institutionNet",candidate.institutionNet());
        metrics.put("totalSmartNet",candidate.totalSmartNet());
        if (match != null) {
            var flow = match.evidence().stream().filter(item -> "외국인 순매수".equals(item.condition()) && "주".equals(item.unit())).findFirst().orElse(null);
            if (flow != null) {
                if (flow.environment() != null) metrics.put("environment",flow.environment());
                if (flow.observedAt() != null) metrics.put("period",flow.observedAt());
            }
            Map<String,Object> quote = new LinkedHashMap<>();
            if (match.quoteSource() != null) quote.put("source",match.quoteSource());
            if (match.quoteEnvironment() != null) quote.put("environment",match.quoteEnvironment());
            if (match.observedAt() != null) quote.put("observedDate",match.observedAt().toString());
            quote.put("period","POINT_IN_TIME");
            quote.put("unit","KRW");
            quote.put("currentPrice",match.currentPrice());
            metrics.put("primaryQuote",quote);
        }
        return new EvidencePacket("KEYWORD",MODE_DUAL_NET_BUY,checkedAt,candidate.code(),candidate.name(),
            candidate.currentPrice(),candidate.changeRate(),0,List.of(candidate.reason()),metrics);
    }

    private AgentResult safeAnalystResult(AgentResult result, String content, String status, String error) {
        return new AgentResult(result.agent(), content, result.model(), result.inputTokens(), result.outputTokens(),
            status, error, result.durationMs(), result.thoughtTokens(), result.totalTokens());
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
            sb.append("조건 검출 결과: 현재 확인한 후보군 안에서는 조건을 동시에 만족하는 종목을 찾지 못했습니다.\n");
            sb.append("확인 범위: 현재 조회 가능한 실제 후보·수급 자료 안에서 검색하며 요청 기간에 맞는 자료가 부족할 수 있습니다.\n");
            sb.append("다음 액션: 장중 데이터 갱신 후 다시 조회하거나, '외국인 기관 동시 순매수 거래대금 상위'처럼 범위를 좁혀 조회하세요.\n");
        } else {
            sb.append("실데이터 조건 검출 종목\n");
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
