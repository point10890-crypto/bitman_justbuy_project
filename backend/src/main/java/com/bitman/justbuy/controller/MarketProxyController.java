package com.bitman.justbuy.controller;

import com.bitman.justbuy.service.MarketDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
public class MarketProxyController {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final MarketDataService marketDataService;

    public MarketProxyController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    /** 종목 코드 리스트로 실시간 현재가 조회 */
    @GetMapping("/prices")
    public ResponseEntity<Map<String, String>> getStockPrices(
            @RequestParam String codes) {
        List<String> codeList = Arrays.stream(codes.split(","))
                .map(String::trim)
                .filter(s -> s.matches("\\d{6}"))
                .toList();
        if (codeList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "유효한 종목코드가 없습니다."));
        }
        return ResponseEntity.ok(marketDataService.fetchStockPrices(codeList));
    }

    @GetMapping("/chart/{symbol}")
    public ResponseEntity<String> chart(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "2d") String range,
            @RequestParam(defaultValue = "1d") String interval) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                    + java.net.URLEncoder.encode(symbol, "UTF-8")
                    + "?range=" + range + "&interval=" + interval;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(response.body());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
