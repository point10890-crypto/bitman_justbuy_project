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
    private KisSourceExpansionService sourceExpansion;
    private KiwoomReadOnlyService kiwoomSupplement;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureSourceExpansion(KisSourceExpansionService expansion) { this.sourceExpansion=expansion; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureKiwoomSupplement(KiwoomReadOnlyService supplement) { this.kiwoomSupplement=supplement; }

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
        result.put("version", "v2");
        result.put("liquidityRules", ConditionLiquidityPolicy.RULES);
        result.put("liquidityAmountUnit", "100000000_KRW");
        result.put("liquidityMorningMinimumEok", Map.of("before10", Map.of("BREAKOUT", 30, "CATALYST_BURST", 30, "FLOW_LEADER", 150),
            "before11", Map.of("BREAKOUT", 50, "CATALYST_BURST", 50, "FLOW_LEADER", 250)));
        if (sourceExpansion != null) result.put("sourceExpansion", sourceExpansion.readiness());
        if (kiwoomSupplement != null) result.put("kiwoomSupplement", kiwoomSupplement.readiness());
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
