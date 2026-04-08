package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final String NAVER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final List<String> TOP_STOCK_CODES = List.of(
        // 1-10: 삼성전자, SK하이닉스, LG에너지솔루션, 삼성바이오로직스, 현대차
        "005930", "000660", "373220", "207940", "005380",
        // 기아, 셀트리온, POSCO홀딩스, KB금융, 신한지주
        "000270", "068270", "005490", "105560", "055550",
        // 11-20: NAVER, 카카오, LG화학, 삼성SDI, 현대모비스
        "035420", "035720", "051910", "006400", "012330",
        // HD현대중공업, 한화에어로스페이스, SK이노베이션, 삼성물산, 한국전력
        "329180", "012450", "096770", "028260", "015760",
        // 21-30: 크래프톤, 하이브, SK텔레콤, 삼성생명, 삼성화재
        "259960", "352820", "017670", "032830", "000810",
        // 카카오뱅크, 두산에너빌리티, 메리츠금융지주, SK스퀘어, LG전자
        "323410", "034020", "138040", "402340", "066570",
        // 31-40: KT, HD한국조선해양, 한화오션, 우리금융지주, 하나금융지주
        "030200", "009540", "042660", "316140", "086790",
        // 대한항공, 한미반도체, 삼성SDS, LG이노텍, 포스코퓨처엠
        "003490", "042700", "018260", "011070", "003670",
        // 41-50: SK, 에코프로비엠, HLB, 고려아연, 한미약품
        "034730", "247540", "028300", "010130", "128940",
        // 현대건설, 기업은행, 한국타이어앤테크놀로지, 엔씨소프트, LG
        "000720", "024110", "161390", "036570", "003550"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;

    public MarketDataService(RestTemplate restTemplate, ObjectMapper mapper) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    private HttpHeaders naverHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", NAVER_UA);
        headers.set("Accept", "application/json, text/plain, */*");
        headers.set("Referer", "https://m.stock.naver.com/");
        return headers;
    }

    private JsonNode fetchJson(String url) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(naverHeaders()), String.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return mapper.readTree(resp.getBody());
            }
        } catch (Exception e) {
            log.warn("Fetch failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    private double parseNumber(JsonNode node, String field) {
        if (node == null || !node.has(field)) return 0;
        String val = node.path(field).asText("0").replace(",", "");
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return 0; }
    }

    public String fetchFormattedMarketData() {
        String now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        NumberFormat fmt = NumberFormat.getInstance(Locale.KOREA);

        StringBuilder text = new StringBuilder();
        text.append("\n\u2501\u2501\u2501 \uc2e4\uc2dc\uac04 \uc2dc\uc7a5 \ub370\uc774\ud130 (").append(now).append(" KST \uae30\uc900) \u2501\u2501\u2501\n\n");

        // Market indices
        text.append("\uD83D\uDCC8 \uc2dc\uc7a5 \uc9c0\uc218\n");
        appendIndex(text, "KOSPI", fmt);
        appendIndex(text, "KOSDAQ", fmt);

        // USD/KRW
        JsonNode fx = fetchJson("https://m.stock.naver.com/api/exchange/FX_USDKRW/basic");
        if (fx == null) fx = fetchJson("https://m.stock.naver.com/api/index/FX_USDKRW/basic");
        if (fx != null) {
            double val = parseNumber(fx, "closePrice");
            double chg = parseNumber(fx, "compareToPreviousClosePrice");
            if (val > 0) {
                text.append("  USD/KRW: ").append(fmt.format(val))
                    .append(" (").append(chg >= 0 ? "+" : "").append(fmt.format(chg)).append(")\n");
            }
        }
        text.append("\n");

        // ★ 오늘의 동적 랭킹 (고정 목록 대신 실시간 상위 종목)
        boolean dynamicOk = false;
        try {
            String gainers = fetchDynamicGainers(fmt);
            String volume = fetchDynamicVolume(fmt);
            if (!gainers.isEmpty() || !volume.isEmpty()) {
                text.append("\uD83D\uDD25 \uC624\uB298\uC758 \uC2E4\uC2DC\uAC04 \uC2DC\uc7a5 \ub7ad\ud0b9 (\ub124\uc774\ubc84\uae08\uc735)\n");
                text.append("\u26A0\uFE0F \uBC18\ub4dc\uc2dc \uc544\ub798 \uc2e4\uc2dc\uac04 \ub370\uc774\ud130\uc5d0 \ub4f1\uc7a5\ud55c \uc885\ubaa9 \uc911\uc5d0\uc11c\ub9cc \ucd94\ucc9c \uc120\ud0dd\ud558\uc138\uc694!\n\n");
                if (!gainers.isEmpty()) text.append(gainers);
                if (!volume.isEmpty()) text.append(volume);
                dynamicOk = true;
            }
        } catch (Exception e) {
            log.warn("[MarketData] \ub3d9\uc801 \ub7ad\ud0b9 \ud76c \uc2e4\ud328, \uace0\uc815 \ubaa9\ub85d \ud3f4\ubc31: {}", e.getMessage());
        }

        // 동적 랭킹 실패 시 폴백 — 정적 목록 상위 20개만
        if (!dynamicOk) {
            text.append("\uD83C\uDFE2 \uc8fc\uc694 \uc885\ubaa9 \ud604\uc7ac\uac00 (\uc2dc\ucd1d \uc0c1\uc704 20)\n");
            for (String code : TOP_STOCK_CODES.subList(0, 20)) {
                JsonNode stock = fetchJson("https://m.stock.naver.com/api/stock/" + code + "/basic");
                if (stock != null) {
                    String name = stock.path("stockName").asText(code);
                    double price = parseNumber(stock, "closePrice");
                    double pct = parseNumber(stock, "fluctuationsRatio");
                    text.append("  ").append(name).append("(").append(code).append("): ")
                        .append(fmt.format(price)).append("\uc6d0 (")
                        .append(pct >= 0 ? "+" : "").append(pct).append("%)\n");
                }
            }
        }

        text.append("\n\u2501\u2501\u2501 [\uc704 \ub370\uc774\ud130\ub294 \ub124\uc774\ubc84\uae08\uc735 \uc2e4\uc2dc\uac04 \uc2dc\uc138\uc785\ub2c8\ub2e4. \ubc18\ub4dc\uc2dc \uc774 \uac00\uaca9\uc744 \uae30\uc900\uc73c\ub85c \ubd84\uc11d\ud558\uc138\uc694!] \u2501\u2501\u2501\n");
        return text.toString();
    }

    /**
     * KOSPI+KOSDAQ 시총 상위 200종목 스냅샷 (페이지 캐시).
     * 네이버 모바일 /api/index/{KOSPI|KOSDAQ}/stocks?rankType=... 가 2026-04-08 부로 404.
     * 대안: /api/stocks/marketValue/{market}?page=N&pageSize=100 (살아있음).
     * 응답에 fluctuationsRatio / accumulatedTradingVolume / accumulatedTradingValue 가 모두 포함되어
     * 클라이언트 단에서 정렬하여 상승률·거래량 랭킹을 만든다.
     */
    private List<JsonNode> fetchMarketSnapshot() {
        List<JsonNode> all = new ArrayList<>();
        for (String market : List.of("KOSPI", "KOSDAQ")) {
            for (int page = 1; page <= 2; page++) {
                JsonNode data = fetchJson("https://m.stock.naver.com/api/stocks/marketValue/"
                    + market + "?page=" + page + "&pageSize=100");
                if (data == null) continue;
                JsonNode arr = data.has("stocks") ? data.get("stocks") : null;
                if (arr == null || !arr.isArray()) continue;
                for (JsonNode s : arr) all.add(s);
            }
        }
        return all;
    }

    /** 오늘 KOSPI+KOSDAQ 상승률 상위 25종목 */
    private String fetchDynamicGainers(NumberFormat fmt) {
        List<JsonNode> stocks = new ArrayList<>(fetchMarketSnapshot());
        if (stocks.isEmpty()) return "";

        // 상승률 desc 정렬 (시총 풀 200개 중 상위 25개)
        stocks.sort((a, b) -> Double.compare(
            parseNumber(b, "fluctuationsRatio"),
            parseNumber(a, "fluctuationsRatio")));

        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCC8 \uc0c1\uc2b9\ub960 \uc0c1\uc704 \uc885\ubaa9 (KOSPI+KOSDAQ \ud569\uc0b0)\n");
        int count = 0;
        for (JsonNode s : stocks) {
            if (count >= 25) break;
            String code = s.path("stockCode").asText(s.path("itemCode").asText(""));
            String name = s.path("stockName").asText(s.path("itemName").asText(""));
            double price = parseNumber(s, "closePrice");
            double pct   = parseNumber(s, "fluctuationsRatio");
            long vol     = (long) parseNumber(s, "accumulatedTradingVolume");
            if (code.isEmpty() || name.isEmpty() || price <= 0) continue;
            sb.append("  ").append(name).append("(").append(code).append("): ")
              .append(fmt.format(price)).append("\uc6d0 (")
              .append(pct >= 0 ? "+" : "").append(pct).append("%)");
            if (vol > 0) sb.append(" | \uac70\ub798\ub7c9 ").append(fmt.format(vol)).append("\uc8fc");
            sb.append("\n");
            count++;
        }
        return count > 0 ? sb.append("\n").toString() : "";
    }

    /** 오늘 KOSPI+KOSDAQ 거래량 상위 20종목 */
    private String fetchDynamicVolume(NumberFormat fmt) {
        List<JsonNode> stocks = new ArrayList<>(fetchMarketSnapshot());
        if (stocks.isEmpty()) return "";

        // 거래량 desc 정렬 (시총 풀 200개 중 상위 20개)
        stocks.sort((a, b) -> Long.compare(
            (long) parseNumber(b, "accumulatedTradingVolume"),
            (long) parseNumber(a, "accumulatedTradingVolume")));

        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCB0 \uac70\ub798\ub7c9 \uc0c1\uc704 \uc885\ubaa9\n");
        int count = 0;
        for (JsonNode s : stocks) {
            if (count >= 20) break;
            String code = s.path("stockCode").asText(s.path("itemCode").asText(""));
            String name = s.path("stockName").asText(s.path("itemName").asText(""));
            double price = parseNumber(s, "closePrice");
            double pct   = parseNumber(s, "fluctuationsRatio");
            long vol     = (long) parseNumber(s, "accumulatedTradingVolume");
            if (code.isEmpty() || name.isEmpty() || price <= 0) continue;
            sb.append("  ").append(name).append("(").append(code).append("): ")
              .append(fmt.format(price)).append("\uc6d0 (")
              .append(pct >= 0 ? "+" : "").append(pct).append("%) | \uac70\ub798\ub7c9 ")
              .append(fmt.format(vol)).append("\uc8fc\n");
            count++;
        }
        return count > 0 ? sb.append("\n").toString() : "";
    }

    private void appendIndex(StringBuilder text, String indexCode, NumberFormat fmt) {
        JsonNode data = fetchJson("https://m.stock.naver.com/api/index/" + indexCode + "/basic");
        if (data != null) {
            double val = parseNumber(data, "closePrice");
            double chg = parseNumber(data, "compareToPreviousClosePrice");
            double pct = parseNumber(data, "fluctuationsRatio");
            text.append("  ").append(indexCode).append(": ").append(fmt.format(val))
                .append(" (").append(chg >= 0 ? "+" : "").append(fmt.format(chg))
                .append(", ").append(pct >= 0 ? "+" : "").append(pct).append("%)\n");
        }
    }

    public Map<String, String> fetchStockPrices(List<String> codes) {
        Map<String, String> prices = new HashMap<>();
        NumberFormat fmt = NumberFormat.getInstance(Locale.KOREA);
        for (String code : codes) {
            JsonNode stock = fetchJson("https://m.stock.naver.com/api/stock/" + code + "/basic");
            if (stock != null) {
                double price = parseNumber(stock, "closePrice");
                if (price > 0) prices.put(code, fmt.format(price));
            }
        }
        return prices;
    }

    // ────────────────────────────────────────────────
    // Phase 2: 강화 시장 데이터 (수급/ETF)
    // ────────────────────────────────────────────────

    /** 투자자별 수급 데이터 */
    public record InvestorFlow(
        long foreignNet, long institutionNet, long individualNet
    ) {}

    /** 종목의 투자자별 매매동향 조회 */
    public InvestorFlow fetchInvestorData(String code) {
        try {
            JsonNode data = fetchJson("https://m.stock.naver.com/api/stock/" + code + "/investor");
            if (data == null) return null;

            JsonNode investors = data.has("investors") ? data.get("investors")
                : data.has("data") ? data.get("data") : data;
            if (!investors.isArray() || investors.isEmpty()) return null;

            JsonNode latest = investors.get(0);
            long foreignNet = latest.path("foreignNet").asLong(latest.path("frgn").path("net").asLong(0));
            long institutionNet = latest.path("institutionNet").asLong(latest.path("organ").path("net").asLong(0));
            long individualNet = latest.path("individualNet").asLong(latest.path("indv").path("net").asLong(0));

            return new InvestorFlow(foreignNet, institutionNet, individualNet);
        } catch (Exception e) {
            log.debug("Investor data fetch failed for {}: {}", code, e.getMessage());
            return null;
        }
    }

    // 2026-04-08 dead-code 제거:
    //   - public String fetchInvestorDataText()  : 호출처 0건 (grep 검증)
    //   - record ETFInclusion + fetchETFInclusion(String) : 호출처 0건
    // 필요해지면 git history 에서 복원 가능 (커밋 해시 참조).
}
