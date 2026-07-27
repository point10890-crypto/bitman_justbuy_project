package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.performance.JonggaPerformanceResponse;
import com.bitman.justbuy.service.JonggaPerformanceService;
import com.bitman.justbuy.service.JonggaV2SearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kr/jongga-v2")
public class JonggaV2Controller {

    private final JonggaV2SearchService jonggaV2SearchService;
    private final JonggaPerformanceService jonggaPerformanceService;

    public JonggaV2Controller(JonggaV2SearchService jonggaV2SearchService,
                              JonggaPerformanceService jonggaPerformanceService) {
        this.jonggaV2SearchService = jonggaV2SearchService;
        this.jonggaPerformanceService = jonggaPerformanceService;
    }

    @GetMapping("/latest")
    public ResponseEntity<JsonNode> latest() {
        return ResponseEntity.ok(jonggaV2SearchService.latest());
    }

    @GetMapping("/dates")
    public ResponseEntity<ObjectNode> dates() {
        return ResponseEntity.ok(jonggaV2SearchService.dates());
    }

    @GetMapping("/history/{date}")
    public ResponseEntity<JsonNode> history(@PathVariable String date) {
        return ResponseEntity.ok(jonggaV2SearchService.history(date));
    }

    /** 추천종목 히스토리 + 익일 성과. 기본 구간은 최근 30일. */
    @GetMapping("/performance")
    public ResponseEntity<JonggaPerformanceResponse> performance(@RequestParam(required = false) String from,
                                                                 @RequestParam(required = false) String to) {
        return ResponseEntity.ok(jonggaPerformanceService.getPerformance(from, to));
    }

    @GetMapping("/search")
    public ResponseEntity<ObjectNode> search(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) String date,
                                             @RequestParam(required = false) String grade,
                                             @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(jonggaV2SearchService.search(q, date, grade, limit));
    }
}
