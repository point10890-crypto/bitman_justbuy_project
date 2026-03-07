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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final List<String> VALID_MODES = List.of(
        "\uc624\ub298\ubb50\uc0ac", "\uc2a4\uc719\ub9e4\ub9e4", "\uc885\uac00\ub9e4\ub9e4", "\uc218\uae09\ubd84\uc11d", "\ubd84\uc11d\ud574\uc918"
    );

    /** 모드별 프리컴퓨트 결과 유효 시간 (분 단위) */
    private static final Map<String, Long> MODE_TTL_MINUTES = Map.of(
        "\uc624\ub298\ubb50\uc0ac", 23L * 60 + 50,  // 23시간 50분 (08:00 → 다음날 07:50)
        "\uc2a4\uc719\ub9e4\ub9e4", 71L * 60 + 50,    // 71시간 50분 (07:00 → 3일 후 06:50)
        "\uc885\uac00\ub9e4\ub9e4", 23L * 60 + 50,   // 23시간 50분 (15:00 → 다음날 14:50)
        "\uc218\uae09\ubd84\uc11d", 3L * 60 + 50     // 3시간 50분 (10:00→13:50, 14:00→17:50)
    );

    private final MultiAgentOrchestrator orchestrator;
    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Set<String> runningAnalyses = ConcurrentHashMap.newKeySet();

    public AnalysisService(MultiAgentOrchestrator orchestrator, ObjectMapper mapper,
                           @Value("${bitman.storage.data-dir:./data}") String dataDir) {
        this.orchestrator = orchestrator;
        this.mapper = mapper;
        this.dataDir = Path.of(dataDir);
        try {
            Files.createDirectories(this.dataDir);
        } catch (IOException e) {
            log.warn("Failed to create data directory: {}", e.getMessage());
        }
    }

    public boolean isValidMode(String mode) {
        return VALID_MODES.contains(mode);
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

    public AnalysisResponse runLiveAnalysis(String query, String mode) {
        String key = mode + ":" + query;
        if (!runningAnalyses.add(key)) {
            throw new DuplicateAnalysisException("\ub3d9\uc77c\ud55c \ubd84\uc11d\uc774 \uc774\ubbf8 \uc9c4\ud589 \uc911\uc785\ub2c8\ub2e4.");
        }

        try {
            AnalysisResponse result = orchestrator.runAnalysis(query, mode);
            saveAnalysis(mode, result);
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
