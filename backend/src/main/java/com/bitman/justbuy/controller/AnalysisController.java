package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.AnalysisRequest;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.AnalysisService;
import com.bitman.justbuy.service.AsyncJobManager;
import com.bitman.justbuy.service.SubscriptionService;
import com.bitman.justbuy.service.AsyncJobManager.JobEntry;
import com.bitman.justbuy.service.AsyncJobManager.JobStatus;
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
    private final AsyncJobManager jobManager;
    private final SubscriptionService subscriptionService;

    public AnalysisController(AnalysisService analysisService, UserRepository userRepository,
                               AsyncJobManager jobManager, SubscriptionService subscriptionService) {
        this.analysisService = analysisService;
        this.userRepository = userRepository;
        this.jobManager = jobManager;
        this.subscriptionService = subscriptionService;
    }

    private void requireProSubscription(UUID userId) {
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));

        if (!subscriptionService.isActivePro(user)) {
            // 만료된 PRO인지 일반 FREE인지 구분해서 메시지 제공
            boolean isExpired = user.getSubscription() == SubscriptionStatus.PRO
                && user.getSubscriptionEndDate() != null
                && user.getSubscriptionEndDate().isBefore(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
            String msg = isExpired
                ? "PRO 구독이 만료되었습니다. 재구독 신청을 해주세요."
                : "PRO 구독자만 사용 가능합니다.";
            throw new ApiException(HttpStatus.FORBIDDEN, msg);
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

    /**
     * 비동기 라이브 분석 시작 — 즉시 jobId 반환.
     * Render 30초 HTTP 타임아웃 회피.
     */
    @PostMapping("/live")
    public ResponseEntity<?> liveAnalysis(@AuthenticationPrincipal UUID userId,
                                                              @Valid @RequestBody AnalysisRequest request) {
        requireProSubscription(userId);

        if (!analysisService.isValidMode(request.mode())) {
            throw new IllegalArgumentException("Invalid mode: " + request.mode());
        }

        // 서버 캐시 확인 (30분 TTL) — 캐시 적중 시 AI 호출 없이 즉시 반환
        AnalysisResponse cached = analysisService.getCachedLive(request.query(), request.mode());
        if (cached != null) {
            log.info("[API] Cache hit: mode={}, query={}", request.mode(), request.query());
            return ResponseEntity.ok(cached);
        }

        log.info("[API] Live analysis started: mode={}, query={}", request.mode(), request.query());
        String jobId = jobManager.createJob();

        // 백그라운드 스레드에서 분석 실행
        Thread.startVirtualThread(() -> {
            try {
                jobManager.markRunning(jobId);
                AnalysisResponse result = analysisService.runLiveAnalysis(request.query(), request.mode());
                jobManager.markComplete(jobId, result);
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("[API] Live analysis failed for job {}: {}", jobId, errMsg, e);
                jobManager.markError(jobId, errMsg);
            }
        });

        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "pending"));
    }

    /**
     * 비동기 작업 상태 폴링.
     */
    @GetMapping("/job/{jobId}")
    public ResponseEntity<?> getJobStatus(@AuthenticationPrincipal UUID userId,
                                           @PathVariable String jobId) {
        requireProSubscription(userId);

        JobEntry job = jobManager.getJob(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Job not found: " + jobId);
        }

        return switch (job.status()) {
            case COMPLETE -> {
                // AnalysisResponse를 직접 반환 — finalContent 필드로 프론트엔드가 완료 감지
                var result = job.result();
                if (result != null) {
                    yield ResponseEntity.ok(result);
                } else {
                    yield ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("status", "error", "error", "분석 결과가 비어 있습니다."));
                }
            }
            case ERROR -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", "error", "error", job.error() != null ? job.error() : "Unknown error"));
            default -> ResponseEntity.ok(Map.of("status", job.status().name().toLowerCase()));
        };
    }
}
