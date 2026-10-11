package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.MultiAgentOrchestrator;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final List<String> VALID_MODES = List.of(
        "\uc218\uae09\ubd84\uc11d", "\ubd84\uc11d\ud574\uc918", "\uc885\ubaa9\ubd84\uc11d"
    );

    /** 모드별 프리컴퓨트 결과 유효 시간 (분 단위).
     *  v2.8.3 (2026-04-25, 3회/일 스케줄):
     *   - 08:50 1차 → 11:45 까지 캐시 (175분)
     *   - 11:50 2차 → 14:45 까지 캐시 (175분)
     *   - 14:50 3차 → 장마감(15:30)까지 캐시 (TTL 175 안에 들어가지만 별도 15:30 컷오프로 강제 만료)
     *  TTL 175 = 2시간 55분 (스케줄 간격 3시간 - 5분 여유). */
    private static final Map<String, Long> MODE_TTL_MINUTES = Map.of(
        "BREAKOUT",       175L,
        "FLOW_LEADER",    175L,
        "CATALYST_BURST", 175L,
        "REVERSAL_EDGE",  175L
    );

    /** 장 마감 시각 (KST). 이후엔 당일자 캐시를 만료로 간주 — 14:50 분석 결과 컷오프용. */
    private static final LocalTime MARKET_CLOSE_KST = LocalTime.of(15, 30);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 라이브 분석 서버 캐시 TTL (30분) */
    private static final long LIVE_CACHE_TTL_MS = 30L * 60 * 1000;

    private final MultiAgentOrchestrator orchestrator;
    private final ConditionSearchFormulaCatalog formulaCatalog;
    private final KeywordConditionAnalysisService keywordConditionAnalysisService;
    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Clock clock;
    private TrackRecordService trackRecordService;
    private final Semaphore executionSlots = new Semaphore(4);
    @Value("${bitman.analysis.run-timeout-ms:120000}")
    private long runTimeoutMs = 120_000L;
    private final Set<String> runningAnalyses = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedResult> liveCache = new ConcurrentHashMap<>();

    private record CachedResult(AnalysisResponse response, long timestamp) {
        boolean isExpired() { return System.currentTimeMillis() - timestamp > LIVE_CACHE_TTL_MS; }
    }

    @Autowired
    public AnalysisService(MultiAgentOrchestrator orchestrator,
                           ConditionSearchFormulaCatalog formulaCatalog,
                           KeywordConditionAnalysisService keywordConditionAnalysisService,
                           ObjectMapper mapper,
                           @Value("${bitman.storage.data-dir:./data}") String dataDir) {
        this(orchestrator, formulaCatalog, keywordConditionAnalysisService, mapper, dataDir, Clock.systemUTC());
    }

    @Autowired
    void configureTracking(TrackRecordService trackRecordService) {
        this.trackRecordService = trackRecordService;
    }

    AnalysisService(MultiAgentOrchestrator orchestrator,
                    ConditionSearchFormulaCatalog formulaCatalog,
                    KeywordConditionAnalysisService keywordConditionAnalysisService,
                    ObjectMapper mapper, String dataDir, Clock clock) {
        this.orchestrator = orchestrator;
        this.formulaCatalog = formulaCatalog;
        this.keywordConditionAnalysisService = keywordConditionAnalysisService;
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        this.clock = clock;
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            log.warn("Failed to create data directory: {}", e.getMessage());
        }
    }

    public boolean isValidMode(String mode) {
        return VALID_MODES.contains(mode) || formulaCatalog.isConditionMode(mode);
    }

    public AnalysisResponse getPrecomputed(String mode) {
        Path file = dataDir.resolve(mode + ".json");
        if (!Files.exists(file)) return null;

        try {
            String json = Files.readString(file);
            AnalysisResponse data = mapper.readValue(json, AnalysisResponse.class);

            String invalidReason = cacheValidationError(mode, data, clock.instant());
            if (invalidReason != null) {
                log.info("[Storage] {} fresh cache unavailable: {}", mode, invalidReason);
                return null;
            }
            return data;
        } catch (IOException e) {
            log.error("Failed to load precomputed result for {}: {}", mode, e.getMessage());
            return null;
        }
    }

    public AnalysisResponse getStoredPrecomputed(String mode) {
        Path file = dataDir.resolve(mode + ".json");
        if (!Files.exists(file)) return null;

        try {
            String json = Files.readString(file);
            return mapper.readValue(json, AnalysisResponse.class);
        } catch (IOException e) {
            log.error("Failed to load stored precomputed result for {}: {}", mode, e.getMessage());
            return null;
        }
    }

    private static boolean isMarketCloseExpired(Instant updatedAt, Instant now) {
        ZonedDateTime nowKst = now.atZone(KST);
        return nowKst.toLocalTime().isAfter(MARKET_CLOSE_KST)
            && updatedAt.atZone(KST).toLocalDate().equals(nowKst.toLocalDate());
    }

    private static String cacheValidationError(String mode, AnalysisResponse data, Instant now) {
        if (data == null || data.updatedAt() == null) return "INVALID_TIMESTAMP";
        long ttlMinutes = MODE_TTL_MINUTES.getOrDefault(mode, 30L);
        try {
            Instant updatedAt = Instant.parse(data.updatedAt());
            Duration age = Duration.between(updatedAt, now);
            if (age.isNegative()) return "FUTURE_TIMESTAMP";
            if (age.toMinutes() > ttlMinutes) return "EXPIRED";
            if (MODE_TTL_MINUTES.containsKey(mode) && isMarketCloseExpired(updatedAt, now)) return "MARKET_CLOSED";
        } catch (RuntimeException e) { return "INVALID_TIMESTAMP"; }
        if (AnalysisOutcome.classify(data) == AnalysisOutcome.FAILED || data.stockPicks() == null || data.stockPicks().isEmpty()
            || data.stockPicks().stream().anyMatch(pick -> !AnalysisOutcome.isVerifiedPick(pick, now, ttlMinutes))) return "LEGACY_UNVERIFIED";
        return null;
    }

    /** 캐시된 라이브 분석 결과 조회 (컨트롤러에서 호출) */
    public AnalysisResponse getCachedLive(String query, String mode) {
        String key = mode + ":" + query;
        CachedResult cached = liveCache.get(key);
        if (cached != null && !cached.isExpired() && cacheValidationError(mode, cached.response(), clock.instant()) == null) {
            log.info("[Cache] 라이브 캐시 적중: {} ({}분 전)", key,
                (System.currentTimeMillis() - cached.timestamp) / 60000);
            return cached.response;
        }
        return null;
    }

    public AnalysisResponse runLiveAnalysis(String query, String mode) {
        AnalysisResponse result = runAnalysisForPublication(query, mode);
        if (AnalysisOutcome.classify(result) == AnalysisOutcome.EMPTY) return result;
        saveAnalysis(mode, result);
        recordPublishedAnalysis(result);
        liveCache.put(mode + ":" + query, new CachedResult(result, System.currentTimeMillis()));
        liveCache.entrySet().removeIf(e -> e.getValue().isExpired());
        return result;
    }

    /** Computes a validated response without publishing before periodic exclusions. */
    public AnalysisResponse runAnalysisForPublication(String query, String mode) {
        String key = mode + ":" + query;
        if (!runningAnalyses.add(key)) throw new DuplicateAnalysisException("동일한 분석이 이미 진행 중입니다.");
        if (!executionSlots.tryAcquire()) {
            runningAnalyses.remove(key);
            throw new IllegalStateException("Analysis capacity is busy; retry after the current runs finish");
        }
        var task = new FutureTask<AnalysisResponse>(() -> keywordConditionAnalysisService != null
            && keywordConditionAnalysisService.supports(query, mode)
            ? keywordConditionAnalysisService.analyze(query, mode) : orchestrator.runAnalysis(query, mode));
        Thread.startVirtualThread(() -> {
            try { task.run(); }
            finally {
                runningAnalyses.remove(key);
                executionSlots.release();
            }
        });
        try {
            AnalysisResponse result = task.get(Math.max(1, runTimeoutMs), TimeUnit.MILLISECONDS);
            AnalysisOutcome.requireSuccessfulAgents(result);
            return AnalysisOutcome.describe(result);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new IllegalStateException("ANALYSIS_TIMEOUT: execution budget exceeded", e);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Analysis interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Analysis execution failed", e.getCause());
        }
    }

    /** Only callers which have already persisted the final published set use this. */
    public void recordPublishedAnalysis(AnalysisResponse response) {
        if (trackRecordService != null && response.stockPicks() != null && !response.stockPicks().isEmpty()) {
            trackRecordService.recordAnalysis(response);
        }
    }

    public void saveAnalysis(String mode, AnalysisResponse result) {
        Path temporary = null;
        try {
            Files.createDirectories(dataDir);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            Path target = dataDir.resolve(mode + ".json").normalize();
            if (!target.getParent().equals(dataDir.normalize())) throw new IOException("Invalid analysis storage mode");
            temporary = Files.createTempFile(dataDir, ".analysis-", ".tmp");
            Files.writeString(temporary, json);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("[Storage] Saved analysis for mode: {}", mode);
        } catch (IOException e) {
            log.error("Failed to save analysis for {}: {}", mode, e.getMessage());
            throw new AnalysisPersistenceException("Analysis persistence failed for mode " + mode, e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { log.warn("Failed to remove analysis temporary file: {}", e.getMessage()); }
            }
        }
    }

    /**
     * 각 모드별 캐시 파일 상태를 반환합니다.
     * exists, valid, updatedAt, elapsedMinutes, ttlMinutes 정보를 포함합니다.
     */
    public Map<String, Map<String, Object>> getCacheStatus() {
        Map<String, Map<String, Object>> status = new LinkedHashMap<>();
        Instant now = clock.instant();

        for (var entry : MODE_TTL_MINUTES.entrySet()) {
            String mode = entry.getKey();
            long ttlMinutes = entry.getValue();
            Map<String, Object> modeStatus = new LinkedHashMap<>();

            try {
                Path file = dataDir.resolve(mode + ".json");
                boolean exists = Files.exists(file);
                modeStatus.put("exists", exists);

                if (exists) {
                    try {
                        String json = Files.readString(file);
                        AnalysisResponse data = mapper.readValue(json, AnalysisResponse.class);

                        if (data.updatedAt() != null) {
                            Instant updatedAt = Instant.parse(data.updatedAt());
                            long elapsed = Duration.between(updatedAt, now).toMinutes();
                            String invalidReason = cacheValidationError(mode, data, now);
                            boolean valid = invalidReason == null;

                            modeStatus.put("valid", valid);
                            modeStatus.put("updatedAt", data.updatedAt());
                            modeStatus.put("elapsedMinutes", elapsed);
                            if (invalidReason != null) modeStatus.put("unavailableReason", invalidReason);
                        } else {
                            modeStatus.put("valid", false);
                            modeStatus.put("updatedAt", "N/A");
                            modeStatus.put("elapsedMinutes", -1L);
                        }
                    } catch (Exception e) {
                        log.warn("[CacheStatus] {} 파일 읽기 실패: {}", mode, e.getMessage());
                        modeStatus.put("valid", false);
                        modeStatus.put("updatedAt", "error");
                        modeStatus.put("elapsedMinutes", -1L);
                    }
                } else {
                    modeStatus.put("valid", false);
                    modeStatus.put("updatedAt", "N/A");
                    modeStatus.put("elapsedMinutes", -1L);
                }
            } catch (Exception e) {
                log.error("[CacheStatus] {} 상태 확인 실패: {}", mode, e.getMessage());
                modeStatus.put("exists", false);
                modeStatus.put("valid", false);
                modeStatus.put("updatedAt", "error");
                modeStatus.put("elapsedMinutes", -1L);
            }

            modeStatus.put("ttlMinutes", ttlMinutes);
            status.put(mode, modeStatus);
        }

        return status;
    }

    public static class DuplicateAnalysisException extends RuntimeException {
        public DuplicateAnalysisException(String message) {
            super(message);
        }
    }

    public static class AnalysisPersistenceException extends IllegalStateException {
        public AnalysisPersistenceException(String message, IOException cause) { super(message, cause); }
    }
}
