package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.FinancialScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scores verified, period-specific DART facts; disclosure titles are not financial observations. */
@Service
public class FinancialScorer {
    private static final Logger log = LoggerFactory.getLogger(FinancialScorer.class);
    private final DartApiService dartApiService;

    public FinancialScorer(DartApiService dartApiService) { this.dartApiService = dartApiService; }

    public FinancialScore score(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9]{6}") || dartApiService == null) return FinancialScore.empty();
        try {
            var snapshot = dartApiService.fetchFinancialEvidence(List.of(stockCode));
            if (snapshot == null || snapshot.stocks() == null) return FinancialScore.empty();
            return snapshot.stocks().stream().filter(stock -> stockCode.equals(stock.stockCode()))
                .findFirst().map(this::scoreFromEvidence).orElseGet(FinancialScore::empty);
        } catch (RuntimeException error) {
            // Exception messages can contain credentials from an upstream request URI.
            log.debug("[FinancialScorer] {} 재무 근거 조회 실패: {}", stockCode, error.getClass().getSimpleName());
            return FinancialScore.empty();
        }
    }

    /** Kept for callers using the former API. Free text has no verifiable identity or accounting basis. */
    public FinancialScore scoreFromText(String ignoredText) { return FinancialScore.empty(); }

    /** Uses the already-collected batch result; never calls a provider. */
    public FinancialScore scoreFromEvidence(DartFinancialEvidenceService.StockEvidence evidence) {
        if (evidence == null || evidence.facts() == null || evidence.stockCode() == null
            || !evidence.stockCode().matches("[0-9]{6}") || evidence.corpCode() == null
            || !evidence.corpCode().matches("[0-9]{8}") || evidence.capturedAt() == null
            || evidence.status() == null || !List.of("COMPLETE", "PARTIAL").contains(evidence.status())) return FinancialScore.empty();
        var facts = evidence.facts();
        if (!"KRW".equals(facts.currency()) || facts.fsDiv() == null || !List.of("CFS", "OFS").contains(facts.fsDiv())
            || facts.reportCode() == null || !List.of("11011", "11012", "11013", "11014").contains(facts.reportCode())
            || facts.receiptNo() == null || !facts.receiptNo().matches("[0-9]{14}")
            || !("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + facts.receiptNo()).equals(facts.sourceUrl()))
            return FinancialScore.empty();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("source", "OPENDART_FULL_FINANCIAL"); raw.put("sourceUrl", facts.sourceUrl());
        raw.put("stockCode", evidence.stockCode()); raw.put("corpCode", evidence.corpCode());
        raw.put("status", evidence.status()); raw.put("capturedAt", evidence.capturedAt().toString());
        raw.put("businessYear", facts.businessYear()); raw.put("reportCode", facts.reportCode());
        raw.put("fsDiv", facts.fsDiv()); raw.put("currency", facts.currency()); raw.put("receiptNo", facts.receiptNo());
        raw.put("filingDate", facts.filingDate() == null ? "UNKNOWN" : facts.filingDate().toString());
        raw.put("period", facts.period()); raw.put("cashFlowPeriod", facts.cashFlowPeriod());
        raw.put("balancePeriod", facts.balancePeriod()); raw.put("selectionBasis", facts.selectionBasis());
        raw.put("latestReport", "UNKNOWN");
        raw.put("industryComparability", "UNKNOWN_GENERIC_THRESHOLDS");
        raw.put("industryLimitations", "금융·보험 등 산업별 기준 미확인. 일반 부채비율만으로 기업의 품질을 단정하지 않는다.");
        List<String> risks = new ArrayList<>(facts.risks() == null ? List.of() : facts.risks());
        put(raw, "revenue", facts.revenue()); put(raw, "previousRevenue", facts.previousRevenue());
        put(raw, "operatingProfit", facts.operatingProfit()); put(raw, "netIncome", facts.netIncome());
        put(raw, "operatingCashFlow", facts.operatingCashFlow()); put(raw, "equity", facts.equity());
        put(raw, "debt", facts.debt());
        boolean nonPositiveEquity = facts.equity() != null && facts.equity().signum() <= 0;
        if (nonPositiveEquity && !risks.contains("NON_POSITIVE_EQUITY")) risks.add("NON_POSITIVE_EQUITY");
        raw.put("risks", List.copyOf(risks));
        BigDecimal margin = facts.operatingMarginPct();
        BigDecimal yoy = facts.revenueYoYPct();
        BigDecimal debtRatio = facts.debtRatioPct();
        // Ratios remain unusable if their original accounting denominators are absent.
        if (facts.revenue() == null || facts.revenue().signum() <= 0 || facts.operatingProfit() == null) margin = null;
        if (facts.revenue() == null || facts.previousRevenue() == null || facts.previousRevenue().signum() <= 0) yoy = null;
        if (facts.equity() == null || nonPositiveEquity || facts.debt() == null || facts.debt().signum() < 0
            || debtRatio != null && debtRatio.signum() < 0) debtRatio = null;
        put(raw, "opMarginPct", margin); put(raw, "revenueYoYPct", yoy); put(raw, "debtRatioPct", debtRatio);
        if (nonPositiveEquity) return new FinancialScore(0, "자본총계가 0 이하: 재무 위험 확인", raw);
        boolean usable = margin != null || yoy != null || debtRatio != null || facts.operatingCashFlow() != null;
        if (!usable) return new FinancialScore(0, "재무 비율·현금흐름 미확인", raw);
        int score = 30;
        if (margin != null) {
            if (margin.compareTo(BigDecimal.TEN) > 0) score += 20;
            else if (margin.compareTo(BigDecimal.valueOf(5)) > 0) score += 10;
            else if (margin.signum() < 0) score -= 10;
        }
        if (debtRatio != null && debtRatio.compareTo(BigDecimal.valueOf(100)) < 0) score += 15;
        // No generic high-debt penalty: industry-specific accounting comparability is unknown.
        if (yoy != null) {
            if (yoy.compareTo(BigDecimal.TEN) > 0) score += 20;
            else if (yoy.signum() < 0) score -= 10;
        }
        if (facts.operatingCashFlow() != null) score += facts.operatingCashFlow().signum() > 0 ? 10
            : facts.operatingCashFlow().signum() < 0 ? -10 : 0;
        List<String> summary = new ArrayList<>();
        if (margin != null) summary.add("영업이익률 " + margin.toPlainString() + "%");
        if (yoy != null) summary.add("전년동기 매출 " + yoy.toPlainString() + "%");
        if (facts.operatingCashFlow() != null) summary.add("영업현금흐름 " + (facts.operatingCashFlow().signum() < 0 ? "음수" : "0 이상"));
        if (summary.isEmpty() && debtRatio != null)
            summary.add("부채비율 " + debtRatio.toPlainString() + "%·부분 근거·산업 비교 미확인");
        return new FinancialScore(Math.max(0, Math.min(100, score)), String.join("·", summary), raw);
    }

    private static void put(Map<String, Object> raw, String key, BigDecimal value) {
        if (value != null) raw.put(key, value);
    }
}
