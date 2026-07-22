package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.ConsensusResult;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.TradingResearchResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class TradingResearchViewService {

    public TradingResearchResponse build(String jobId, AnalysisResponse source) {
        List<TradingResearchResponse.AnalystReport> reports = analystReports(source);
        List<String> bullCase = bullCase(source);
        List<String> bearCase = bearCase(source);
        int agreement = source.consensus() != null ? clamp(source.consensus().agreementScore()) : 0;
        String rating = informationalRating(source, agreement);
        List<String> risks = riskFactors(source);
        List<String> invalidations = invalidationConditions(source);
        String riskLevel = risks.size() >= 3 || !source.isFresh() ? "HIGH" : risks.isEmpty() ? "LOW" : "MEDIUM";
        AnalysisResponse.Metadata metadata = source.metadata();

        return new TradingResearchResponse(
            jobId,
            rating,
            false,
            reports,
            new TradingResearchResponse.DebateReview(
                bullCase,
                bearCase,
                agreement,
                debateConclusion(bullCase, bearCase, agreement)
            ),
            new TradingResearchResponse.RiskReview(riskLevel, risks, invalidations),
            safe(source.finalContent()),
            new TradingResearchResponse.Freshness(source.isFresh(), safe(source.updatedAt())),
            new TradingResearchResponse.Usage(
                metadata != null ? metadata.totalDurationMs() : 0,
                metadata != null ? metadata.agentsUsed() : reports.size(),
                metadata != null ? metadata.agentsSucceeded() : successfulReports(reports)
            )
        );
    }

    private static List<TradingResearchResponse.AnalystReport> analystReports(AnalysisResponse source) {
        List<TradingResearchResponse.AnalystReport> reports = new ArrayList<>();
        if (source.round1() != null) {
            for (AgentResult result : source.round1()) {
                if (result == null) continue;
                reports.add(new TradingResearchResponse.AnalystReport(
                    safe(result.agent()), safe(result.status()), safe(result.model()), safe(result.content())
                ));
            }
        }
        if (source.synthesis() != null) {
            AgentResult synthesis = source.synthesis();
            reports.add(new TradingResearchResponse.AnalystReport(
                "research_manager", safe(synthesis.status()), safe(synthesis.model()), safe(synthesis.content())
            ));
        }
        return List.copyOf(reports);
    }

    private static List<String> bullCase(AnalysisResponse source) {
        if (source.stockPicks() == null) return List.of();
        return source.stockPicks().stream()
            .filter(pick -> pick != null && isPositive(pick.action()))
            .limit(5)
            .map(pick -> pick.name() + "(" + pick.code() + "): " + safe(pick.reason()))
            .toList();
    }

    private static List<String> bearCase(AnalysisResponse source) {
        List<String> result = new ArrayList<>();
        ConsensusResult consensus = source.consensus();
        if (consensus != null && consensus.divergences() != null) {
            consensus.divergences().stream()
                .filter(item -> item != null)
                .limit(5)
                .map(item -> item.stockName() + "(" + item.stockCode() + "): " + safe(item.details()))
                .forEach(result::add);
        }
        if (!source.isFresh()) result.add("Source data is stale; revalidation is required.");
        if (source.stockPicks() != null) {
            source.stockPicks().stream()
                .filter(pick -> pick != null && isNegative(pick.action()))
                .limit(Math.max(0, 5 - result.size()))
                .map(pick -> pick.name() + "(" + pick.code() + "): " + safe(pick.reason()))
                .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private static List<String> riskFactors(AnalysisResponse source) {
        List<String> risks = new ArrayList<>();
        if (!source.isFresh()) risks.add("STALE_SOURCE_DATA");
        AnalysisResponse.Metadata metadata = source.metadata();
        if (metadata != null && metadata.agentsSucceeded() < metadata.agentsUsed()) {
            risks.add("PARTIAL_AGENT_FAILURE");
        }
        if (source.consensus() == null) {
            risks.add("CONSENSUS_UNAVAILABLE");
        } else if (source.consensus().agreementScore() < 60) {
            risks.add("LOW_AGENT_AGREEMENT");
        }
        if (source.stockPicks() == null || source.stockPicks().isEmpty()) {
            risks.add("NO_VERIFIED_CANDIDATE");
        }
        return List.copyOf(risks);
    }

    private static List<String> invalidationConditions(AnalysisResponse source) {
        if (source.stockPicks() == null) return List.of();
        return source.stockPicks().stream()
            .filter(pick -> pick != null && pick.stopLoss() != null && !pick.stopLoss().isBlank())
            .limit(10)
            .map(pick -> pick.name() + "(" + pick.code() + ") stop reference: " + pick.stopLoss())
            .toList();
    }

    private static String informationalRating(AnalysisResponse source, int agreement) {
        if (!source.isFresh() || source.stockPicks() == null || source.stockPicks().isEmpty()) return "WATCH";
        long positive = source.stockPicks().stream().filter(p -> p != null && isPositive(p.action())).count();
        long negative = source.stockPicks().stream().filter(p -> p != null && isNegative(p.action())).count();
        if (negative > positive) return agreement >= 75 ? "STRONG_AVOID" : "AVOID";
        if (positive > negative) return agreement >= 75 ? "STRONG_INTEREST" : "INTEREST";
        return "WATCH";
    }

    private static String debateConclusion(List<String> bull, List<String> bear, int agreement) {
        if (bull.isEmpty() && bear.isEmpty()) return "WATCH: insufficient structured evidence.";
        if (bull.size() > bear.size()) return "Bull case leads with agent agreement " + agreement + "/100.";
        if (bear.size() > bull.size()) return "Bear case leads with agent agreement " + agreement + "/100.";
        return "Balanced evidence; retain watch status until conflicts are resolved.";
    }

    private static boolean isPositive(String action) {
        String normalized = safe(action).toLowerCase(Locale.ROOT);
        return normalized.equals("buy") || normalized.equals("overweight")
            || normalized.equals("\uB9E4\uC218") || normalized.equals("\uC8FC\uBAA9");
    }

    private static boolean isNegative(String action) {
        String normalized = safe(action).toLowerCase(Locale.ROOT);
        return normalized.equals("sell") || normalized.equals("underweight")
            || normalized.equals("\uB9E4\uB3C4");
    }

    private static int successfulReports(List<TradingResearchResponse.AnalystReport> reports) {
        return (int) reports.stream().filter(report -> "success".equalsIgnoreCase(report.status())).count();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
