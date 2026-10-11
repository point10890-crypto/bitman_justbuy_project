package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.ConsensusResult;
import com.bitman.justbuy.dto.StockPick;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Keeps every public result representation aligned with periodic final exclusions. */
final class AnalysisPublicationView {
    private static final Pattern PUBLIC_ROW = Pattern.compile("(?m)^\\s*📌 [^\\n]*?\\(([0-9]{6})\\)[^\\n]*");

    static AnalysisResponse finalizePicks(AnalysisResponse response, List<StockPick> picks) {
        var byCode = picks.stream().collect(Collectors.toMap(StockPick::code, Function.identity(), (a, b) -> a));
        Set<String> codes = byCode.keySet();
        var round1 = response.round1() == null ? List.<AgentResult>of() : response.round1().stream().map(agent ->
            new AgentResult(agent.agent(), retainAgentRows(agent.content(), byCode), agent.model(), agent.inputTokens(),
                agent.outputTokens(), agent.status(), agent.error(), agent.durationMs(), agent.thoughtTokens(), agent.totalTokens())).toList();
        var original = response.consensus();
        ConsensusResult consensus = original == null ? null : new ConsensusResult(
            original.stocks() == null ? List.of() : original.stocks().stream().filter(s -> codes.contains(s.code())).toList(),
            original.overallSentiment(), original.agreementScore(), original.divergences() == null ? List.of()
                : original.divergences().stream().filter(d -> codes.contains(d.stockCode())).toList(), original.agentCount());
        return AnalysisOutcome.describe(new AnalysisResponse(response.mode(), response.query(), round1, null, render(picks), picks,
            consensus, response.updatedAt(), response.isFresh(), response.metadata()));
    }

    private static String retainAgentRows(String content, Map<String, StockPick> byCode) {
        if (content == null || content.isBlank()) return "";
        var matcher = PUBLIC_ROW.matcher(content);
        StringBuilder result = new StringBuilder();
        int rowStart = -1;
        String rowCode = null;
        while (matcher.find()) {
            if (rowStart >= 0 && byCode.containsKey(rowCode)) result.append(content, rowStart, matcher.start());
            rowStart = matcher.start();
            rowCode = matcher.group(1);
        }
        if (rowStart >= 0 && byCode.containsKey(rowCode)) result.append(content.substring(rowStart));
        return result.toString().trim();
    }

    private static String render(List<StockPick> picks) {
        if (picks.isEmpty()) return "최종 발행 조건을 통과한 종목이 없습니다.";
        StringBuilder result = new StringBuilder("최종 발행된 조건 종목\n");
        for (var pick : picks) {
            result.append("\n📌 ").append(pick.name()).append(" (").append(pick.code()).append(")\n")
                .append("현재가: ").append(pick.currentPrice()).append("\n판단: ").append(pick.action())
                .append("\n근거: ").append(pick.reason()).append("\n목표가: ").append(pick.targetPrice())
                .append(" / 손절가: ").append(pick.stopLoss()).append("\n후보 출처: ").append(pick.candidateSource())
                .append(" / 시세 출처: ").append(pick.quoteSource()).append(" / 시세 확인 시각: ").append(pick.quoteCheckedAt()).append("\n");
        }
        return result.toString();
    }
}
