package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.EvidenceFact;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic claim checking. Agreement proves packet fidelity, not investment accuracy. */
public final class EvidenceClaimGuard {
    private static final int MAX_FACTS = 256;
    private static final int MAX_CLAIMS = 16;
    private static final Set<String> USABLE = Set.of("AVAILABLE", "PARTIAL", "PARTIAL_COVERAGE", "DETERMINISTIC");
    private static final Set<String> CONTEXT = Set.of("source", "environment", "observedDate", "period", "coverageStart",
        "coverageEnd", "unit", "priceUnit", "volumeUnit", "quantityUnit", "netBuyUnit", "status", "observationTimeBasis");
    private static final Set<String> STRING_NUMBERS = Set.of("currentPrice", "changeRate", "maxReturnPct");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?");
    private static final Pattern DIGIT = Pattern.compile("\\p{N}");
    private static final String QUANTITY_END = "(?=$|[^가-힣]|으로|에서|까지|부터|보다|만큼|이상|이하|초과|미만|가량|정도|로|를|을|은|는|가|이|에|의|만|도|와|과)";
    private static final Pattern KOREAN_NUMBER = Pattern.compile(
        "[영공일이삼사오육칠팔구십백천만억조]+\\s*(?:원|주|퍼센트|프로|%|배|일|개월|년|억|조)" + QUANTITY_END
            + "|[일이삼사오육칠팔구십백천]+(?:만|억|조)(?=\\s|$)"
            + "|(?<![가-힣])(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|스무|서른|마흔|쉰|예순|일흔|여든|아흔)\\s*(?:원|주|퍼센트|프로|%|배|일|달|개월|년)" + QUANTITY_END);

    public record Check(boolean valid, List<String> evidence) {
        static Check rejected() { return new Check(false, List.of()); }
    }

    public List<EvidenceFact> catalog(EvidencePacket packet) {
        if (packet == null || packet.stockCode() == null || !packet.stockCode().matches("[0-9]{6}")) return List.of();
        var facts = new LinkedHashMap<String,EvidenceFact>();
        var base = new LinkedHashMap<String,String>();
        base.put("source", "INPUT_PACKET");
        if (packet.asOf() != null && packet.asOf().length() <= 80) base.put("asOf", packet.asOf());
        BigDecimal price = number(packet.currentPrice(), false);
        if (price != null && price.signum() > 0 && price.scale() <= 0)
            add(facts, packet.stockCode(), "/currentPrice", price, with(base, "unit", "KRW"));
        BigDecimal change = number(packet.changeRate(), true);
        if (change != null) add(facts, packet.stockCode(), "/changeRate", change, with(base, "unit", "%"));
        add(facts, packet.stockCode(), "/ruleScore", BigDecimal.valueOf(packet.ruleScore()), with(base, "unit", "score"));
        walk(packet.metrics(), "/metrics", packet.stockCode(), base, facts, 0);
        return List.copyOf(facts.values());
    }

    private void walk(Object value, String path, String code, Map<String,String> inherited,
                      Map<String,EvidenceFact> facts, int depth) {
        if (value == null || depth > 8 || facts.size() >= MAX_FACTS) return;
        if (value instanceof Map<?,?> map) {
            for (String identity : List.of("stockCode", "requestedStockCode"))
                if (map.containsKey(identity) && !code.equals(map.get(identity))) return;
            Object status = map.get("status");
            // Queued, unavailable and undated estimates are not completed observation facts.
            if (status != null && (!(status instanceof String text) || !USABLE.contains(text))) return;
            var context = new LinkedHashMap<>(inherited);
            for (String key : CONTEXT) if (map.get(key) instanceof String text && !text.isBlank() && text.length() <= 128)
                context.put(key, text);
            map.entrySet().stream().filter(e -> e.getKey() instanceof String).sorted(Comparator.comparing(e -> (String)e.getKey()))
                .limit(100).forEach(e -> {
                    String key = (String)e.getKey();
                    if (key.length() > 64 || key.toLowerCase(Locale.ROOT).matches(".*(?:secret|token|password|apikey|credential).*")) return;
                    String child = path + "/" + key.replace("~", "~0").replace("/", "~1");
                    if (CONTEXT.contains(key) && e.getValue() instanceof String text && !text.isBlank() && text.length() <= 128)
                        add(facts, code, child, text, context);
                    else if (STRING_NUMBERS.contains(key) && e.getValue() instanceof String text) {
                        BigDecimal numeric = number(text, !"currentPrice".equals(key));
                        if (numeric != null) add(facts, code, child, numeric, context);
                    } else walk(e.getValue(), child, code, context, facts, depth + 1);
                });
        } else if (value instanceof List<?> list) {
            for (int i=0; i<Math.min(32,list.size()); i++) walk(list.get(i),path+"/"+i,code,inherited,facts,depth+1);
        } else if (value instanceof Number numeric) {
            BigDecimal parsed = decimal(numeric.toString());
            if (parsed != null) add(facts,code,path,parsed,inherited);
        } else if (value instanceof Boolean) add(facts,code,path,value,inherited);
    }

    private static Map<String,String> with(Map<String,String> context, String key, String value) {
        var copy = new LinkedHashMap<>(context); copy.put(key,value); return copy;
    }
    private static void add(Map<String,EvidenceFact> facts,String code,String path,Object value,Map<String,String> context) {
        if (facts.size() >= MAX_FACTS) return;
        String id = code + ":" + path;
        facts.put(id,new EvidenceFact(id,code,path,value,Collections.unmodifiableMap(new TreeMap<>(context))));
    }

    public Check check(JsonNode claims,List<EvidenceFact> catalog) {
        if (claims == null || !claims.isArray() || claims.isEmpty() || claims.size() > MAX_CLAIMS) return Check.rejected();
        var indexed = new HashMap<String,EvidenceFact>();
        catalog.forEach(fact -> indexed.put(fact.factId(),fact));
        var seen = new HashSet<String>(); var evidence = new ArrayList<String>();
        for (JsonNode claim : claims) {
            // No model-selected source, date, unit or environment override is accepted.
            if (!claim.isObject() || claim.size()!=2 || !claim.path("factId").isTextual() || !claim.has("value")) return Check.rejected();
            String id = claim.path("factId").textValue();
            EvidenceFact fact = indexed.get(id);
            if (fact == null || !seen.add(id) || !same(fact.value(),claim.get("value"))) return Check.rejected();
            evidence.add(render(fact));
        }
        return new Check(true,List.copyOf(evidence));
    }

    private static boolean same(Object original,JsonNode proposed) {
        if (proposed == null || proposed.isNull() || proposed.isContainerNode()) return false;
        if (original instanceof BigDecimal decimal) {
            BigDecimal parsed = proposed.isNumber() ? decimal(proposed.asText())
                : proposed.isTextual() ? number(proposed.textValue(),false) : null;
            return parsed != null && decimal.compareTo(parsed)==0;
        }
        if (original instanceof Boolean bool) return proposed.isBoolean() && bool.equals(proposed.booleanValue());
        return original instanceof String text && proposed.isTextual() && text.equals(proposed.textValue());
    }
    private static String render(EvidenceFact fact) {
        Object value = fact.value();
        String scalar = value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : contextValue(value.toString());
        if (value instanceof Boolean bool) scalar=bool?"예":"아니오";
        StringBuilder text = new StringBuilder(label(fact.field())).append(" ").append(scalar);
        fact.context().forEach((key,item) -> text.append(" · ").append(contextLabel(key)).append(" ").append(contextValue(item)));
        return text.toString();
    }

    private static String label(String path) {
        String key=path.substring(path.lastIndexOf('/')+1);
        return switch(key) {
            case "currentPrice" -> "현재가";
            case "changeRate", "changePct" -> "당일 등락률";
            case "ruleScore" -> "룰 점수";
            case "foreignNet", "foreignNetBuy", "foreignNetBuyQty", "foreignNetBuyShares" -> "외국인 순매수";
            case "institutionNet", "institutionNetBuy", "institutionNetBuyQty", "institutionNetBuyShares" -> "기관 순매수";
            case "foreignNetBuy5dShares" -> "5거래일 외국인 순매수";
            case "institutionNetBuy5dShares" -> "5거래일 기관 순매수";
            case "totalSmartNet" -> "외국인·기관 합계";
            case "ma5" -> "5일 이동평균";
            case "ma20" -> "20일 이동평균";
            case "ma60" -> "60일 이동평균";
            case "maAligned" -> "이동평균 정배열";
            case "bars", "barCount", "derivedConsecutiveBars" -> "완료 일봉 수";
            case "atr14", "atr14Krw" -> "14일 평균 변동폭";
            case "atr14Pct", "atrPct14" -> "14일 평균 변동폭 비율";
            case "realizedVolatility20Pct", "realizedVolatility20", "volatility20Pct", "volatility20AnnualizedPct" -> "20일 실현 변동성";
            case "hv20AnnualizedPct" -> "20일 실현 변동성 (연율)";
            case "volume" -> "거래량";
            case "averageVolume20" -> "20일 평균 거래량";
            case "volumeRatio20" -> "20일 평균 대비 거래량 배율";
            case "tradingValueKrw" -> "거래대금";
            case "per", "PER" -> "주가수익비율";
            case "pbr", "PBR" -> "주가순자산비율";
            case "roe", "ROE" -> "자기자본이익률";
            case "previous20High" -> "직전 20일 고가";
            case "breakout20" -> "직전 20일 고가 돌파";
            case "contraction" -> "가격·거래량 수축";
            case "maxReturnPct" -> "입력 수익률 지표";
            case "source", "environment", "period", "observedDate", "coverageStart", "coverageEnd", "unit", "status" -> contextLabel(key);
            default -> "입력 지표";
        };
    }
    private static String contextLabel(String key) {
        return switch(key) {
            case "source" -> "출처";
            case "environment" -> "환경";
            case "observedDate" -> "관측일";
            case "asOf" -> "입력 기준 시각";
            case "period" -> "관측기간";
            case "coverageStart" -> "자료 시작일";
            case "coverageEnd" -> "자료 종료일";
            case "unit" -> "단위";
            case "priceUnit" -> "가격 단위";
            case "volumeUnit", "quantityUnit", "netBuyUnit" -> "수량 단위";
            case "status" -> "자료 상태";
            case "observationTimeBasis" -> "관측시각 기준";
            default -> "자료 기준";
        };
    }
    private static String contextValue(String value) {
        return switch(value) {
            case "INPUT_PACKET" -> "입력 근거";
            case "KIWOOM_REST_MOCK" -> "키움 REST (MOCK)";
            case "KIWOOM_REST" -> "키움 REST (REAL)";
            case "NAVER_FINANCE" -> "네이버 금융";
            case "MOCK" -> "모의 서버 (MOCK)";
            case "REAL" -> "실전 서버 (REAL)";
            case "KRW" -> "원";
            case "shares" -> "주";
            case "percent" -> "%";
            case "ratio" -> "배";
            case "score" -> "점";
            case "POINT_IN_TIME" -> "단일 시점";
            case "AVAILABLE" -> "확인됨";
            case "PARTIAL", "PARTIAL_COVERAGE" -> "일부 확인";
            case "DATE_ONLY" -> "날짜 기준";
            default -> value;
        };
    }

    /** Numbers belong only in server-generated evidence; prose cannot relabel their date or unit. */
    public boolean qualitative(String value) {
        if (value == null || value.length()>240 || DIGIT.matcher(value).find()) return false;
        String normalized = Normalizer.normalize(value,Normalizer.Form.NFKC);
        return !DIGIT.matcher(normalized).find() && !KOREAN_NUMBER.matcher(normalized).find();
    }
    private static BigDecimal number(String text,boolean percent) {
        if (text == null || text.length()>80) return null;
        String normalized = Normalizer.normalize(text,Normalizer.Form.NFKC).replace('−','-').trim();
        if (percent && normalized.endsWith("%")) normalized=normalized.substring(0,normalized.length()-1).trim();
        if (!NUMBER.matcher(normalized).matches()) return null;
        return decimal(normalized.replace(",",""));
    }
    private static BigDecimal decimal(String text) {
        try {
            BigDecimal value = new BigDecimal(text).stripTrailingZeros();
            return value.precision()<=30 && Math.abs(value.scale())<=18 ? value : null;
        } catch (RuntimeException ignored) { return null; }
    }
}
