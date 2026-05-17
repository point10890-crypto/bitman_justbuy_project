package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.MultiAgentOrchestrator;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Set<String> runningAnalyses = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedResult> liveCache = new ConcurrentHashMap<>();

    private record CachedResult(AnalysisResponse response, long timestamp) {
        boolean isExpired() { return System.currentTimeMillis() - timestamp > LIVE_CACHE_TTL_MS; }
    }

    public AnalysisService(MultiAgentOrchestrator orchestrator,
                           ConditionSearchFormulaCatalog formulaCatalog,
                           KeywordConditionAnalysisService keywordConditionAnalysisService,
                           ObjectMapper mapper,
                           @Value("${bitman.storage.data-dir:./data}") String dataDir) {
        this.orchestrator = orchestrator;
        this.formulaCatalog = formulaCatalog;
        this.keywordConditionAnalysisService = keywordConditionAnalysisService;
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
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

            // TTL 만료 체크: 모드별 유효 시간 초과 시 null 반환
            Long ttlMinutes = MODE_TTL_MINUTES.get(mode);
            if (ttlMinutes != null && data.updatedAt() != null) {
                try {
                    Instant updatedAt = Instant.parse(data.updatedAt());
                    long elapsed = Duration.between(updatedAt, Instant.now()).toMinutes();
                    if (elapsed > ttlMinutes) {
                        log.info("[Storage] {} 프리컴퓨트 만료 (경과 {}분 > TTL {}분)", mode, elapsed, ttlMinutes);
                        return null;
                    }

                    // ★ v2.8.3: 장마감(15:30 KST) 이후 당일자 캐시는 강제 만료.
                    //    14:50 분석 결과가 TTL(175분)으로 17:45까지 남는 것을 차단.
                    //    "오후 2시 50분에 분석해서 장마감 까지 캐시" 사용자 요구사항 준수.
                    ZonedDateTime nowKst = ZonedDateTime.now(KST);
                    if (nowKst.toLocalTime().isAfter(MARKET_CLOSE_KST)) {
                        ZonedDateTime updatedKst = updatedAt.atZone(KST);
                        if (updatedKst.toLocalDate().equals(nowKst.toLocalDate())) {
                            log.info("[Storage] {} 당일 캐시 만료 (장마감 15:30 KST 경과)", mode);
                            return null;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[Storage] updatedAt 파싱 실패, TTL 검사 건너뜀: {}", e.getMessage());
                }
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

    /** 캐시된 라이브 분석 결과 조회 (컨트롤러에서 호출) */
    public AnalysisResponse getCachedLive(String query, String mode) {
        String key = mode + ":" + query;
        CachedResult cached = liveCache.get(key);
        if (cached != null && !cached.isExpired()) {
            log.info("[Cache] 라이브 캐시 적중: {} ({}분 전)", key,
                (System.currentTimeMillis() - cached.timestamp) / 60000);
            return cached.response;
        }
        return null;
    }

    public AnalysisResponse runLiveAnalysis(String query, String mode) {
        String key = mode + ":" + query;

        if (!runningAnalyses.add(key)) {
            throw new DuplicateAnalysisException("\ub3d9\uc77c\ud55c \ubd84\uc11d\uc774 \uc774\ubbf8 \uc9c4\ud589 \uc911\uc785\ub2c8\ub2e4.");
        }

        try {
            AnalysisResponse result = keywordConditionAnalysisService.supports(query, mode)
                ? keywordConditionAnalysisService.analyze(query, mode)
                : orchestrator.runAnalysis(query, mode);

            // 방어 로직: 빈 결과(에이전트 0/0 또는 모든 에이전트 실패)는 캐시하지 않는다.
            // 외부 데이터 소스 장애·API 키 만료 등으로 빈 결과가 생기면, 다음 요청에서 즉시 재시도하도록 fresh 캐시 저장을 막는다.
            int agentsUsed = result.metadata() != null ? result.metadata().agentsUsed() : 0;
            int agentsSucceeded = result.metadata() != null ? result.metadata().agentsSucceeded() : 0;
            int picks = result.stockPicks() != null ? result.stockPicks().size() : 0;
            if (agentsUsed == 0 || agentsSucceeded == 0 || picks == 0) {
                log.warn("[Cache] {} 빈 결과 — 캐시 저장 건너뜀 (agents={}/{}, picks={})",
                    mode, agentsSucceeded, agentsUsed, picks);
            } else {
                saveAnalysis(mode, result);
                liveCache.put(key, new CachedResult(result, System.currentTimeMillis()));
                // 만료된 캐시 정리
                liveCache.entrySet().removeIf(e -> e.getValue().isExpired());
            }
            return result;
        } finally {
            runningAnalyses.remove(key);
        }
    }

    public void saveAnalysis(String mode, AnalysisResponse result) {
        try {
            Files.createDirectories(dataDir);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            Files.writeString(dataDir.resolve(mode + ".json"), json);
            log.info("[Storage] Saved analysis for mode: {}", mode);
        } catch (IOException e) {
            log.error("Failed to save analysis for {}: {}", mode, e.getMessage());
        }
    }

    /**
     * 각 모드별 캐시 파일 상태를 반환합니다.
     * exists, valid, updatedAt, elapsedMinutes, ttlMinutes 정보를 포함합니다.
     */
    public Map<String, Map<String, Object>> getCacheStatus() {
        Map<String, Map<String, Object>> status = new LinkedHashMap<>();

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
                            long elapsed = Duration.between(updatedAt, Instant.now()).toMinutes();
                            boolean valid = elapsed <= ttlMinutes;

                            modeStatus.put("valid", valid);
                            modeStatus.put("updatedAt", data.updatedAt());
                            modeStatus.put("elapsedMinutes", elapsed);
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
}
