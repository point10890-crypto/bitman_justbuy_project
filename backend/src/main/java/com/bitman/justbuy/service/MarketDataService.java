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
        "005930", "000660", "373220", "207940", "005380",
        "000270", "068270", "005490", "105560", "055550",
        "035420", "035720", "051910", "006400", "012330",
        "329180", "012450", "096770", "028260", "259960"
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
            log.debug("Fetch failed for {}: {}", url, e.getMessage());
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

        // Top stocks
        text.append("\uD83C\uDFE2 \uc2dc\ucd1d \uc0c1\uc704 \uc8fc\uc694 \uc885\ubaa9 \ud604\uc7ac\uac00\n");
        for (String code : TOP_STOCK_CODES) {
            JsonNode stock = fetchJson("https://m.stock.naver.com/api/stock/" + code + "/basic");
            if (stock != null) {
                String name = stock.path("stockName").asText(code);
                double price = parseNumber(stock, "closePrice");
                double chg = parseNumber(stock, "compareToPreviousClosePrice");
                double pct = parseNumber(stock, "fluctuationsRatio");
                long vol = (long) parseNumber(stock, "accumulatedTradingVolume");
                text.append("  ").append(name).append("(").append(code).append("): ")
                    .append(fmt.format(price)).append("\uc6d0 (")
                    .append(chg >= 0 ? "+" : "").append(fmt.format(chg)).append("\uc6d0, ")
                    .append(pct >= 0 ? "+" : "").append(pct).append("%)");
                if (vol > 0) text.append(" | \uac70\ub798\ub7c9 ").append(fmt.format(vol)).append("\uc8fc");
                text.append("\n");
            }
        }

        text.append("\n\u2501\u2501\u2501 [\uc704 \ub370\uc774\ud130\ub294 \ub124\uc774\ubc84\uae08\uc735 \uc2e4\uc2dc\uac04 \uc2dc\uc138\uc785\ub2c8\ub2e4. \ubc18\ub4dc\uc2dc \uc774 \uac00\uaca9\uc744 \uae30\uc900\uc73c\ub85c \ubd84\uc11d\ud558\uc138\uc694!] \u2501\u2501\u2501\n");
        return text.toString();
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

    /** 주요 종목의 수급 데이터를 프롬프트 텍스트로 포맷 */
    public String fetchInvestorDataText() {
        NumberFormat fmt = NumberFormat.getInstance(Locale.KOREA);
        StringBuilder text = new StringBuilder();
        text.append("\n\uD83D\uDC65 \uD22C\uC790\uC790\uBCC4 \uB9E4\uB9E4\uB3D9\uD5A5 (\uC8FC\uC694 \uC885\uBAA9)\n");

        int count = 0;
        for (String code : TOP_STOCK_CODES.subList(0, Math.min(10, TOP_STOCK_CODES.size()))) {
            InvestorFlow flow = fetchInvestorData(code);
            if (flow == null) continue;

            // 종목명 조회
            JsonNode stock = fetchJson("https://m.stock.naver.com/api/stock/" + code + "/basic");
            String name = stock != null ? stock.path("stockName").asText(code) : code;

            String sign = flow.foreignNet >= 0 ? "+" : "";
            String iSign = flow.institutionNet >= 0 ? "+" : "";
            String pSign = flow.individualNet >= 0 ? "+" : "";

            text.append("  ").append(name).append("(").append(code).append("): ")
                .append("\uC678\uAD6D\uC778 ").append(sign).append(fmt.format(flow.foreignNet))
                .append(" | \uAE30\uAD00 ").append(iSign).append(fmt.format(flow.institutionNet))
                .append(" | \uAC1C\uC778 ").append(pSign).append(fmt.format(flow.individualNet))
                .append("\n");
            count++;
        }

        return count > 0 ? text.append("\n").toString() : "";
    }

    /** ETF 편입 정보 */
    public record ETFInclusion(String etfName, String etfCode) {}

    /** 종목의 ETF 편입 현황 조회 */
    public List<ETFInclusion> fetchETFInclusion(String code) {
        try {
            JsonNode data = fetchJson("https://m.stock.naver.com/api/stock/" + code + "/etfItem");
            if (data == null) return List.of();

            JsonNode items = data.has("etfs") ? data.get("etfs")
                : data.has("result") ? data.get("result") : data;
            if (!items.isArray()) return List.of();

            List<ETFInclusion> result = new ArrayList<>();
            for (int i = 0; i < Math.min(5, items.size()); i++) {
                JsonNode item = items.get(i);
                String name = item.path("stockName").asText(item.path("name").asText(""));
                String etfCode = item.path("stockCode").asText(item.path("code").asText(""));
                if (!name.isEmpty()) result.add(new ETFInclusion(name, etfCode));
            }
            return result;
        } catch (Exception e) {
            log.debug("ETF inclusion fetch failed for {}: {}", code, e.getMessage());
            return List.of();
        }
    }
}
