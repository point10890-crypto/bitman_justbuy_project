package com.bitman.justbuy.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 조건검색식 모드 카탈로그.
 *
 * 프론트/관리자/스케줄러가 같은 모드 정의를 보도록 하는 단일 출처다.
 * 실제 분석 실행은 ConditionSearchPipeline -> AnalysisService -> MultiAgentOrchestrator 로 위임한다.
 */
@Service
public class ConditionSearchFormulaCatalog {

    public record FormulaSpec(
        String id,
        String mode,
        String name,
        String shortName,
        String query,
        String userSection,
        String precomputedEndpoint,
        String liveEndpoint,
        List<String> dataSources,
        List<String> pipelineSteps,
        long ttlMinutes
    ) {}

    private final Map<String, FormulaSpec> formulas = new LinkedHashMap<>();

    public ConditionSearchFormulaCatalog() {
        register(new FormulaSpec(
            "breakout",
            "BREAKOUT",
            "단타",
            "단타",
            "단타 조건검색: 거래량 급증 + 돌파 + 상승률 + 장중 강도 + 실시간조회 순위 + 거래량 상위 + 거래대금 상위 + 거래원 매수 상위",
            "short-term",
            "/api/analysis/BREAKOUT",
            "/api/analysis/live",
            List.of("KIS 현재가", "KIS 실시간조회 순위", "KIS 상승률 순위", "KIS 거래량 상위", "KIS 거래대금 상위", "거래원 매수 상위", "AI 돌파 검증"),
            List.of("실시간조회/상승률/거래량/거래대금 후보 풀 수집", "거래량 급증·돌파·장중 강도 점수화", "거래원 매수 우위 여부 보강", "AI가 추격 리스크와 돌파 지속성을 검증", "TOP3 캐시 저장"),
            175L
        ));
        register(new FormulaSpec(
            "reversal",
            "REVERSAL_EDGE",
            "스윙",
            "스윙",
            "스윙 조건검색: 눌림목 + 추세 유지 + 목표구간 + 리스크 + 주도주 섹터 + 대장주",
            "swing",
            "/api/analysis/REVERSAL_EDGE",
            "/api/analysis/live",
            List.of("KIS 현재가", "눌림목/추세 유지 조건", "목표구간/손절구간", "주도 섹터", "섹터 대장주", "DART 리스크"),
            List.of("주도 섹터와 대장주 후보 수집", "눌림목·추세 유지·목표구간 조건 점수화", "DART/공시 리스크 점검", "AI가 보유 기간과 무효화 조건 검증", "TOP3 캐시 저장"),
            175L
        ));
        register(new FormulaSpec(
            "flow-leader",
            "FLOW_LEADER",
            "주도주",
            "주도주",
            "주도주 조건검색: 거래대금 + 외국인/기관 수급 + 섹터 강도 + 거래량 상위 + 거래대금 상위",
            "leaders",
            "/api/analysis/FLOW_LEADER",
            "/api/analysis/live",
            List.of("KIS 거래대금", "KIS 외국인/기관 수급", "KIS 거래량 상위", "KIS 거래대금 상위", "섹터 상대강도", "AI 수급 해석"),
            List.of("거래량/거래대금 상위 후보 수집", "외국인·기관 수급과 섹터 강도 교차 점검", "거래대금 지속성·대장주 여부 검증", "AI가 주도주 지속성과 이탈 조건 평가", "TOP3 캐시 저장"),
            175L
        ));
        register(new FormulaSpec(
            "catalyst",
            "CATALYST_BURST",
            "테마주",
            "테마주",
            "테마주 조건검색: DART/뉴스 재료 + 관련 종목 가격 반응 + 대장주 판별 + 대장주",
            "themes",
            "/api/analysis/CATALYST_BURST",
            "/api/analysis/live",
            List.of("DART 공시", "뉴스/이벤트", "KIS 가격 반응", "관련 종목 동반 반응", "대장주 판별", "DeepSeek 재료 요약"),
            List.of("DART/뉴스 재료 수집", "관련 종목 가격 반응과 확산성 확인", "거래대금·상승률 기준 대장주 판별", "AI가 재료 지속성과 소멸 리스크 평가", "TOP3 캐시 저장"),
            175L
        ));
    }

    private void register(FormulaSpec spec) {
        formulas.put(spec.mode(), spec);
    }

    public List<FormulaSpec> all() {
        return List.copyOf(formulas.values());
    }

    public Optional<FormulaSpec> findByMode(String mode) {
        return Optional.ofNullable(formulas.get(mode));
    }

    public boolean isConditionMode(String mode) {
        return formulas.containsKey(mode);
    }

    public Map<String, String> scheduledQueries() {
        Map<String, String> result = new LinkedHashMap<>();
        formulas.values().forEach(spec -> result.put(spec.mode(), spec.query()));
        return result;
    }
}
