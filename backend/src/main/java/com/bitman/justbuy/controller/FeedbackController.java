package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.FeedbackRequest;
import jakarta.validation.Valid;
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
    public ResponseEntity<Map<String, String>> submitFeedback(@Valid @RequestBody FeedbackRequest payload) {
        String mode = payload.mode() != null ? payload.mode() : "unknown";
        Integer rating = payload.rating() != null ? payload.rating() : 0;
        String comment = payload.comment() != null ? payload.comment() : "";
        String analysisId = payload.analysisId() != null ? payload.analysisId() : "";
        int helpfulCount = payload.helpfulStocks() != null ? payload.helpfulStocks().size() : 0;

        log.info("[Feedback] mode={}, rating={}, analysisId={}, helpful={}, comment={}",
            mode, rating, analysisId, helpfulCount,
            comment.length() > 50 ? comment.substring(0, 50) + "..." : comment);

        // TODO: 향후 DB 저장 → 에이전트 가중치 피드백 루프 연동
        // 현재는 로그 기록만 수행

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
