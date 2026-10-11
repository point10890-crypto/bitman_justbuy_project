package com.bitman.justbuy.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic interpretation of explicit stock-search conditions. */
@Component
public class KeywordSearchQueryParser {

    private static final BigDecimal MAX_THRESHOLD = new BigDecimal("1000000000000000000");
    private static final String NUMBER = "[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?";
    private static final Pattern NUMERIC = Pattern.compile(
        "(?<![0-9A-Za-z.,+\\-])(?:(가격|현재가|주가|등락률|상승률|거래량|거래대금)\\s*)?("
            + NUMBER + ")\\s*(만원|억원|원|만주|억주|주|%|만|억)\\s*(이상|이하|초과|미만|같음)(?![가-힣A-Za-z0-9])");
    private static final Pattern MARKET = Pattern.compile("코스피|코스닥|(?<![A-Za-z])KOSPI(?![A-Za-z])|(?<![A-Za-z])KOSDAQ(?![A-Za-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOW = Pattern.compile("최근\\s*([0-9]+)\\s*거래일(?:간)?");
    private static final Pattern TODAY = Pattern.compile("오늘");
    private static final Pattern RANKING = Pattern.compile(
        "(?:(거래량|거래대금|상승률|등락률)\\s*)?(상위|TOP)\\s*([0-9]+)?\\s*(?:종목|개|건)?(?![가-힣A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("(?<![0-9A-Za-z])([0-9]{6})(?![0-9A-Za-z])");
    private static final Pattern FOREIGN = Pattern.compile("외국인(?:과|와|이|가|은|는)?");
    private static final Pattern INSTITUTION = Pattern.compile("기관(?:과|와|이|가|은|는)?");
    private static final Pattern NET_BUY = Pattern.compile("순매수(?:하는|한|중인|중)?");
    private static final Pattern DUAL_BUY = Pattern.compile("쌍끌이");
    private static final Pattern DIRECTION = Pattern.compile("(?<![가-힣A-Za-z])(상승|하락)(?:하는|한)?(?=\\s|종목|$|[(),;:])");
    private static final Pattern GENERIC = Pattern.compile(
        "종목(?:들을|들|을|만)?|조건\\s*검색|종목\\s*검색|검색|조건|찾아\\s*줘|보여\\s*줘|알려\\s*줘|찾기|해\\s*주세요|해주세요|해줘");
    private static final Pattern CONNECTOR = Pattern.compile("(?<![가-힣A-Za-z0-9])(?:과|와|및|그리고|인|한)(?![가-힣A-Za-z0-9])");
    private static final Pattern VALUATION = Pattern.compile("(?<![A-Za-z0-9])(?i:PER|PBR)\\s*("+NUMBER+")\\s*(?:배)?\\s*(이상|이하|초과|미만|같음)(?![가-힣A-Za-z0-9])");
    private static final Pattern MA_ALIGNMENT = Pattern.compile("(?:이평선|이동평균선?)\\s*정배열(?![가-힣A-Za-z0-9])");
    private static final Pattern BREAKOUT_20 = Pattern.compile("20\\s*(?:일|거래일)\\s*(?:고점\\s*)?돌파(?![가-힣A-Za-z0-9])");
    private static final Pattern VOLUME_RATIO_20 = Pattern.compile("거래량\\s*20\\s*(?:일|거래일)\\s*평균(?:\\s*대비)?\\s*("+NUMBER+")\\s*배\\s*(이상|이하|초과|미만|같음)(?![가-힣A-Za-z0-9])");
    private static final Pattern UNHANDLED_CONDITION = Pattern.compile(
        "%|가격|현재가|주가|등락률|상승률|상승|하락|거래량|거래대금|순매수|외국인|기관|최근|거래일|이상|이하|초과|미만|같음|상위|(?i:TOP)|(?i:(?<![A-Za-z])(?:PER|PBR)(?![A-Za-z]))|이평선|이동평균|정배열|평균");
    private static final Pattern LEFTOVER_AMOUNT = Pattern.compile(
        NUMBER + "\\s*(만원|억원|원|만주|억주|주|%|만|억)|(?<![가-힣A-Za-z0-9])[0-9]+(?:[,.][0-9]+)?(?![가-힣A-Za-z0-9])");
    private static final List<Pattern> UNSUPPORTED = List.of(
        Pattern.compile("신고가|저평가|공시|뉴스|테마|관련주|급증|전고점|돌파"),
        Pattern.compile("제외|아닌|아니고|말고|순매도|매도|않는|않은|없는|없이|또는"),
        Pattern.compile("(?<![A-Za-z])OR(?![A-Za-z])", Pattern.CASE_INSENSITIVE)
    );

    public record Filter(String field, String operator, BigDecimal threshold, String label) {}

    public record Spec(List<Filter> filters, String market, String stockCode, String nameContains,
                       String sortBy, boolean descending, boolean rankingExplicit, int limit, int flowDays, boolean todayOnly,
                       List<String> appliedConditions, List<String> unsupportedTerms) {
        public Spec {
            filters = List.copyOf(filters);
            appliedConditions = List.copyOf(appliedConditions);
            unsupportedTerms = List.copyOf(unsupportedTerms);
        }
    }

    public Spec parse(String query) {
        State state = new State();
        if (query == null || query.isBlank()) return state.invalid("검색어를 입력해 주세요");
        if (query.length() > 200) return state.invalid("검색어는 200자 이내로 입력해 주세요");
        String input = parseResearch(normalize(query),state);
        for (Pattern pattern : UNSUPPORTED) {
            Matcher matcher = pattern.matcher(input);
            while (matcher.find()) state.unsupported.add("미지원 조건: " + matcher.group());
        }
        if (!state.unsupported.isEmpty()) return state.spec();

        String remaining = parseMarket(input, state);
        remaining = parseWindow(remaining, state);
        remaining = parseRanking(remaining, state);
        remaining = parseNumeric(remaining, state);
        remaining = parseFlow(remaining, state);
        remaining = parseDirection(remaining, state);
        remaining = parseCode(remaining, state);
        remaining = GENERIC.matcher(remaining).replaceAll(" ");
        remaining = CONNECTOR.matcher(remaining).replaceAll(" ");
        remaining = normalize(remaining.replaceAll("[(),;:·\\\"']", " "));
        if (!remaining.isBlank()) {
            if (UNHANDLED_CONDITION.matcher(remaining).find() || LEFTOVER_AMOUNT.matcher(remaining).find()
                || !remaining.matches("[가-힣A-Za-z0-9][가-힣A-Za-z0-9&.\\- ]*") || !remaining.matches(".*[가-힣A-Za-z].*")) {
                state.unsupported.add("해석할 수 없는 조건: " + remaining);
            } else if (state.unsupported.isEmpty()) {
                state.nameContains = remaining;
                state.applied.add("종목명에 " + remaining + " 포함");
            }
        }
        if (state.windowExplicit && !state.hasFlow()) state.unsupported.add("최근 거래일 조건은 순매수 수급에만 적용할 수 있습니다");
        detectContradictions(state);
        if (state.applied.isEmpty() && state.unsupported.isEmpty()) state.unsupported.add("종목명 또는 지원되는 검색 조건을 입력해 주세요");
        if (state.unsupported.isEmpty()) state.applied.add(state.rankingExplicit
            ? labelForField(state.sortBy) + " 상위 " + state.limit + "종목" : "결과 최대 " + state.limit + "종목");
        return state.spec();
    }

    private String parseResearch(String input,State state) {
        Matcher ratio=VALUATION.matcher(input);
        while(ratio.find()) {
            String field=ratio.group().trim().substring(0,3).toUpperCase(java.util.Locale.ROOT);
            addResearchNumber(state,field,ratio.group(1),ratio.group(2),field);
        }
        String remaining=ratio.replaceAll(" ");
        Matcher aligned=MA_ALIGNMENT.matcher(remaining);
        while(aligned.find()) state.add(new Filter("MA_ALIGNED","EQ",BigDecimal.ONE,"이평선 정배열 (완료 60일봉 MA5 > MA20 > MA60)"));
        remaining=aligned.replaceAll(" ");
        Matcher breakout=BREAKOUT_20.matcher(remaining);
        while(breakout.find()) state.add(new Filter("BREAKOUT20","EQ",BigDecimal.ONE,"완료 일봉 고가가 이전 20거래일 고가 돌파"));
        remaining=breakout.replaceAll(" ");
        Matcher volume=VOLUME_RATIO_20.matcher(remaining);
        while(volume.find()) addResearchNumber(state,"VOLUME_RATIO20",volume.group(1),volume.group(2),"거래량 완료 20일 평균 대비");
        return volume.replaceAll(" ");
    }
    private void addResearchNumber(State state,String field,String raw,String comparison,String label) {
        BigDecimal value=new BigDecimal(raw.replace(",",""));
        if(value.signum()<0 || value.compareTo(MAX_THRESHOLD)>0) {state.unsupported.add("지원 범위를 벗어난 배수 조건: "+label);return;}
        String operator=switch(comparison){case "이상"->"GTE";case "이하"->"LTE";case "초과"->"GT";case "미만"->"LT";default->"EQ";};
        state.add(new Filter(field,operator,value.stripTrailingZeros(),label+" "+value.stripTrailingZeros().toPlainString()+"배 "+comparison));
    }

    private String parseMarket(String input, State state) {
        Matcher matcher = MARKET.matcher(input);
        Set<String> markets = new LinkedHashSet<>();
        while (matcher.find()) markets.add(matcher.group().equalsIgnoreCase("KOSDAQ") || matcher.group().equals("코스닥") ? "KOSDAQ" : "KOSPI");
        if (markets.size() > 1) state.unsupported.add("코스피와 코스닥을 동시에 지정할 수 없습니다");
        else if (!markets.isEmpty()) {
            state.market = markets.iterator().next();
            state.applied.add(state.market.equals("KOSPI") ? "코스피" : "코스닥");
        }
        return matcher.replaceAll(" ");
    }

    private String parseWindow(String input, State state) {
        Matcher matcher = WINDOW.matcher(input);
        Set<Integer> windows = new LinkedHashSet<>();
        while (matcher.find()) {
            try {
                int days = Integer.parseInt(matcher.group(1));
                if (days < 1 || days > 10) state.unsupported.add("수급 기간은 최근 1~10거래일만 지원합니다");
                else windows.add(days);
            } catch (NumberFormatException ignored) {
                state.unsupported.add("수급 기간은 최근 1~10거래일만 지원합니다");
            }
            state.windowExplicit = true;
        }
        if (windows.size() > 1) state.unsupported.add("수급 기간을 하나만 지정해 주세요");
        else if (!windows.isEmpty()) state.flowDays = windows.iterator().next();
        String remaining = matcher.replaceAll(" ");
        if (TODAY.matcher(remaining).find()) {
            if (state.windowExplicit && state.flowDays != 1) state.unsupported.add("오늘과 최근 여러 거래일 조건을 함께 지정할 수 없습니다");
            state.todayOnly = true;
            state.flowDays = 1;
            state.applied.add("오늘 데이터만");
            remaining = TODAY.matcher(remaining).replaceAll(" ");
        }
        return remaining;
    }

    private String parseRanking(String input, State state) {
        Matcher matcher = RANKING.matcher(input);
        String selectedSort = null;
        Integer selectedLimit = null;
        while (matcher.find()) {
            String sort = matcher.group(1) == null ? "TRADING_VALUE" : fieldFor(matcher.group(1));
            int limit = 10;
            if (matcher.group(3) != null) {
                BigDecimal requested = new BigDecimal(matcher.group(3));
                if (requested.signum() <= 0) state.unsupported.add("상위 종목 수는 1개 이상이어야 합니다");
                else limit = requested.min(BigDecimal.valueOf(20)).intValueExact();
            }
            if (selectedSort != null && (!selectedSort.equals(sort) || !selectedLimit.equals(limit))) {
                state.unsupported.add("정렬 기준과 상위 종목 수를 하나만 지정해 주세요");
            }
            selectedSort = sort;
            selectedLimit = limit;
            state.applied.add(labelForField(sort) + " 상위 " + limit + "종목");
        }
        if (selectedSort != null) {
            state.sortBy = selectedSort;
            state.limit = selectedLimit;
            state.rankingExplicit = true;
        }
        return matcher.replaceAll(" ");
    }

    private String parseNumeric(String input, State state) {
        Matcher matcher = NUMERIC.matcher(input);
        boolean hasFlowWords = FOREIGN.matcher(input).find() || INSTITUTION.matcher(input).find()
            || NET_BUY.matcher(input).find() || DUAL_BUY.matcher(input).find();
        while (matcher.find()) {
            String unit = matcher.group(3);
            String field = matcher.group(1) == null ? fieldFromUnit(unit) : fieldFor(matcher.group(1));
            if (!validUnit(field, unit) || (matcher.group(1) == null && (hasFlowWords || unit.equals("만") || unit.equals("억")))) {
                state.unsupported.add("단위 또는 대상이 명확하지 않은 조건: " + matcher.group());
                continue;
            }
            BigDecimal value = new BigDecimal(matcher.group(2).replace(",", "")).multiply(multiplier(unit));
            if (value.abs().compareTo(MAX_THRESHOLD) > 0 || (!field.equals("CHANGE_PCT")
                && (value.signum() < 0 || value.stripTrailingZeros().scale() > 0))) {
                state.unsupported.add("지원 범위를 벗어난 숫자 조건: " + matcher.group());
                continue;
            }
            String operator = switch (matcher.group(4)) {
                case "이상" -> "GTE";
                case "이하" -> "LTE";
                case "초과" -> "GT";
                case "미만" -> "LT";
                default -> "EQ";
            };
            String displayUnit = field.equals("CHANGE_PCT") ? "%" : field.equals("VOLUME") ? "주" : "원";
            String label = labelForField(field) + " " + value.stripTrailingZeros().toPlainString() + displayUnit + " " + matcher.group(4);
            state.add(new Filter(field, operator, value.stripTrailingZeros(), label));
        }
        return matcher.replaceAll(" ");
    }

    private String parseFlow(String input, State state) {
        boolean foreign = FOREIGN.matcher(input).find();
        boolean institution = INSTITUTION.matcher(input).find();
        boolean dual = DUAL_BUY.matcher(input).find();
        boolean buy = NET_BUY.matcher(input).find();
        if (dual || ((foreign || institution) && buy)) {
            if (dual || foreign) state.add(new Filter("FOREIGN_NET", "GT", BigDecimal.ZERO, "외국인 순매수"));
            if (dual || institution) state.add(new Filter("INSTITUTION_NET", "GT", BigDecimal.ZERO, "기관 순매수"));
            state.applied.add(state.todayOnly ? "오늘 수급" : "최근 실제 " + state.flowDays + "거래일 수급 합산");
        } else if (foreign || institution || buy) {
            state.unsupported.add("수급 조건은 외국인 또는 기관 순매수로 지정해 주세요");
        }
        String remaining = FOREIGN.matcher(input).replaceAll(" ");
        remaining = INSTITUTION.matcher(remaining).replaceAll(" ");
        remaining = DUAL_BUY.matcher(remaining).replaceAll(" ");
        return NET_BUY.matcher(remaining).replaceAll(" ");
    }

    private String parseDirection(String input, State state) {
        Matcher matcher = DIRECTION.matcher(input);
        while (matcher.find()) {
            boolean rise = matcher.group(1).equals("상승");
            state.add(new Filter("CHANGE_PCT", rise ? "GT" : "LT", BigDecimal.ZERO, rise ? "등락률 0% 초과" : "등락률 0% 미만"));
        }
        return matcher.replaceAll(" ");
    }

    private String parseCode(String input, State state) {
        Matcher matcher = CODE.matcher(input);
        Set<String> codes = new LinkedHashSet<>();
        while (matcher.find()) codes.add(matcher.group(1));
        if (codes.size() > 1) state.unsupported.add("종목 코드는 하나만 지정해 주세요");
        else if (!codes.isEmpty()) {
            state.stockCode = codes.iterator().next();
            state.applied.add("종목 코드 " + state.stockCode);
        }
        return matcher.replaceAll(" ");
    }

    private void detectContradictions(State state) {
        for (Filter lower : state.filters) for (Filter upper : state.filters) {
            if (!lower.field().equals(upper.field())) continue;
            boolean lowerBound = lower.operator().equals("GT") || lower.operator().equals("GTE") || lower.operator().equals("EQ");
            boolean upperBound = upper.operator().equals("LT") || upper.operator().equals("LTE") || upper.operator().equals("EQ");
            if (!lowerBound || !upperBound) continue;
            int compared = lower.threshold().compareTo(upper.threshold());
            if (compared > 0 || (compared == 0 && (lower.operator().equals("GT") || upper.operator().equals("LT")))) {
                state.unsupported.add(labelForField(lower.field()) + " 조건 범위가 서로 충돌합니다");
            }
        }
    }

    private static String fieldFor(String label) {
        return switch (label) {
            case "가격", "현재가", "주가" -> "PRICE";
            case "등락률", "상승률" -> "CHANGE_PCT";
            case "거래량" -> "VOLUME";
            case "거래대금" -> "TRADING_VALUE";
            default -> throw new IllegalArgumentException("Unsupported metric");
        };
    }

    private static String fieldFromUnit(String unit) {
        if (unit.equals("%")) return "CHANGE_PCT";
        return unit.endsWith("주") ? "VOLUME" : "PRICE";
    }

    private static boolean validUnit(String field, String unit) {
        return field.equals("CHANGE_PCT") ? unit.equals("%") : field.equals("VOLUME") ? unit.endsWith("주") : unit.endsWith("원") || unit.equals("만") || unit.equals("억");
    }

    private static BigDecimal multiplier(String unit) {
        if (unit.startsWith("만")) return BigDecimal.valueOf(10000);
        if (unit.startsWith("억")) return BigDecimal.valueOf(100000000);
        return BigDecimal.ONE;
    }

    private static String labelForField(String field) {
        return switch (field) {
            case "PRICE" -> "현재가";
            case "CHANGE_PCT" -> "등락률";
            case "VOLUME" -> "거래량";
            case "TRADING_VALUE" -> "거래대금";
            case "PER", "PBR" -> field;
            case "VOLUME_RATIO20" -> "거래량 20일 평균 대비 배수";
            default -> field;
        };
    }

    private static String normalize(String input) {
        return input.replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    private static final class State {
        final List<Filter> filters = new ArrayList<>();
        final Set<String> applied = new LinkedHashSet<>();
        final Set<String> unsupported = new LinkedHashSet<>();
        String market;
        String stockCode;
        String nameContains;
        String sortBy = "TRADING_VALUE";
        int limit = 10;
        int flowDays = 3;
        boolean todayOnly;
        boolean windowExplicit;
        boolean rankingExplicit;

        void add(Filter filter) {
            if (filters.stream().noneMatch(existing -> existing.field().equals(filter.field())
                && existing.operator().equals(filter.operator()) && existing.threshold().compareTo(filter.threshold()) == 0)) {
                filters.add(filter);
                applied.add(filter.label());
            }
        }

        boolean hasFlow() {
            return filters.stream().anyMatch(filter -> filter.field().equals("FOREIGN_NET") || filter.field().equals("INSTITUTION_NET"));
        }

        Spec invalid(String message) {
            unsupported.add(message);
            return spec();
        }

        Spec spec() {
            return new Spec(filters, market, stockCode, nameContains, sortBy, true, rankingExplicit, limit, flowDays, todayOnly,
                new ArrayList<>(applied), new ArrayList<>(unsupported));
        }
    }
}
