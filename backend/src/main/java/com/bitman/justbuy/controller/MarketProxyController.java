package com.bitman.justbuy.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@RestController
@RequestMapping("/api/market")
public class MarketProxyController {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
