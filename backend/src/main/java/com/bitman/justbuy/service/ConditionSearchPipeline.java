package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 메인 조건검색식 엔드포인트 파이프라인.
 *
 * 기존 AnalysisService 실행 로직은 유지하고, 컨트롤러/관리자/스케줄러가
 * 조건검색식 단위로 같은 구조를 바라보도록 감싼다.
 */
@Service
public class ConditionSearchPipeline {

    private final ConditionSearchFormulaCatalog formulaCatalog;
    private final AnalysisService analysisService;

    public ConditionSearchPipeline(ConditionSearchFormulaCatalog formulaCatalog,
                                   AnalysisService analysisService) {
        this.formulaCatalog = formulaCatalog;
        this.analysisService = analysisService;
    }

    public boolean isValidMode(String mode) {
        return analysisService.isValidMode(mode);
    }

    public AnalysisResponse getPrecomputed(String mode) {
        return analysisService.getPrecomputed(mode);
    }

    public AnalysisResponse getStoredPrecomputed(String mode) {
        return analysisService.getStoredPrecomputed(mode);
    }

    public AnalysisResponse getCachedLive(String query, String mode) {
        return analysisService.getCachedLive(query, mode);
    }

    public AnalysisResponse runLiveAnalysis(String query, String mode) {
        return analysisService.runLiveAnalysis(query, mode);
    }

    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "BitMan condition-search pipeline");
        result.put("version", "v1");
        result.put("entrypoints", Map.of(
            "precomputed", "/api/analysis/{mode}",
            "live", "/api/analysis/live",
            "job", "/api/analysis/job/{jobId}",
            "adminRefresh", "/api/admin/system/refresh-all"
        ));

        Map<String, Map<String, Object>> cacheStatus = analysisService.getCacheStatus();
        List<Map<String, Object>> formulas = formulaCatalog.all().stream().map(spec -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", spec.id());
            item.put("mode", spec.mode());
            item.put("name", spec.name());
            item.put("shortName", spec.shortName());
            item.put("query", spec.query());
            item.put("userSection", spec.userSection());
            item.put("precomputedEndpoint", spec.precomputedEndpoint());
            item.put("liveEndpoint", spec.liveEndpoint());
            item.put("dataSources", spec.dataSources());
            item.put("pipelineSteps", spec.pipelineSteps());
            item.put("ttlMinutes", spec.ttlMinutes());
            item.put("cache", cacheStatus.getOrDefault(spec.mode(), Map.of()));
            return item;
        }).toList();

        result.put("formulas", formulas);
        return result;
    }
}
