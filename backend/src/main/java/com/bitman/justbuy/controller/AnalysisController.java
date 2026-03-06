package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.AnalysisRequest;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.AnalysisService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);
    private final AnalysisService analysisService;
    private final UserRepository userRepository;

    public AnalysisController(AnalysisService analysisService, UserRepository userRepository) {
        this.analysisService = analysisService;
        this.userRepository = userRepository;
    }

    private void requireProSubscription(UUID userId) {
        boolean isPro = userRepository.findById(userId)
            .map(user -> user.getSubscription() == SubscriptionStatus.PRO)
            .orElse(false);
        if (!isPro) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PRO 구독자만 사용 가능합니다.");
        }
    }

    @GetMapping("/{mode}")
    public ResponseEntity<AnalysisResponse> getPrecomputed(@AuthenticationPrincipal UUID userId,
                                                            @PathVariable String mode) {
        requireProSubscription(userId);
        String decodedMode = java.net.URLDecoder.decode(mode, java.nio.charset.StandardCharsets.UTF_8);

        if (!analysisService.isValidMode(decodedMode)) {
            throw new IllegalArgumentException("Invalid mode: " + decodedMode);
        }

        AnalysisResponse data = analysisService.getPrecomputed(decodedMode);
        if (data == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No pre-computed result available for mode: " + decodedMode);
        }

        return ResponseEntity.ok(data);
    }

    @PostMapping("/live")
    public ResponseEntity<AnalysisResponse> liveAnalysis(@AuthenticationPrincipal UUID userId,
                                                          @Valid @RequestBody AnalysisRequest request) {
        requireProSubscription(userId);

        if (!analysisService.isValidMode(request.mode())) {
            throw new IllegalArgumentException("Invalid mode: " + request.mode());
        }

        log.info("[API] Live analysis: mode={}, query={}", request.mode(), request.query());
        AnalysisResponse result = analysisService.runLiveAnalysis(request.query(), request.mode());
        return ResponseEntity.ok(result);
    }
}
