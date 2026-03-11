package com.bitman.justbuy.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 분석 피드백 API — 사용자 만족도 수집.
 * 향후 분석 품질 개선 및 에이전트 가중치 조정에 활용.
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    @PostMapping
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody Map<String, Object> payload) {
        String mode = (String) payload.getOrDefault("mode", "unknown");
        Number rating = (Number) payload.getOrDefault("rating", 0);
        String comment = (String) payload.getOrDefault("comment", "");
        String analysisId = (String) payload.getOrDefault("analysisId", "");

        log.info("[Feedback] mode={}, rating={}, analysisId={}, comment={}",
            mode, rating, analysisId,
            comment != null && comment.length() > 50 ? comment.substring(0, 50) + "..." : comment);

        // TODO: 향후 DB 저장 → 에이전트 가중치 피드백 루프 연동
        // 현재는 로그 기록만 수행

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
