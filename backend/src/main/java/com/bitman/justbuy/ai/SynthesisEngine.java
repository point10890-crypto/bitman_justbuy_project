package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.ChatGptAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.StockPick;

import java.util.List;
import java.util.Map;

public class SynthesisEngine {

    private final ChatGptAgent chatGptAgent;

    public SynthesisEngine(ChatGptAgent chatGptAgent) {
        this.chatGptAgent = chatGptAgent;
    }

    public boolean isAvailable() {
        return chatGptAgent.isAvailable();
    }

    public AgentResult synthesizeWithResult(List<AgentResult> round1,
                                            String query,
                                            String mode,
                                            String today) {
        return synthesizeWithResult(round1, query, mode, today, "", List.of(), Map.of());
    }

    public AgentResult synthesizeWithResult(List<AgentResult> round1,
                                            String query,
                                            String mode,
                                            String today,
                                            String marketContext,
                                            List<StockPick> picks,
                                            Map<String, String> realPrices) {
        String systemPrompt = """
            You are the final synthesis engine for JustBuy.
            Combine agent outputs, keep only grounded facts, and produce a concise Korean final answer.
            """;

        StringBuilder userMessage = new StringBuilder();
        userMessage.append("today=").append(today).append('\n');
        userMessage.append("mode=").append(mode).append('\n');
        userMessage.append("query=").append(query).append("\n\n");

        if (round1 != null) {
            for (AgentResult result : round1) {
                userMessage.append("## ").append(result.agent()).append(" / ").append(result.model()).append('\n');
                userMessage.append(result.content()).append("\n\n");
            }
        }

        boolean grokSucceeded = round1 != null && round1.stream()
            .anyMatch(r -> "grok".equalsIgnoreCase(r.agent()) && "success".equals(r.status()));
        if (grokSucceeded) {
            userMessage.append("GROK search data has priority for freshness when it is explicitly sourced.\n");
        }
        if ("수급분석".equals(mode)) {
            userMessage.append("수급분석 종합: GROK 검색 또는 제공된 수급 데이터를 우선 검증하세요.\n");
        }
        if (marketContext != null && !marketContext.isBlank()) {
            userMessage.append("marketContext:\n").append(marketContext).append("\n\n");
        }
        if (realPrices != null && !realPrices.isEmpty()) {
            userMessage.append("실시간 시세 참조:\n");
            if (picks != null) {
                for (StockPick pick : picks) {
                    String price = realPrices.get(pick.code());
                    if (price != null) {
                        userMessage.append("- ").append(pick.code()).append(" ")
                            .append(pick.name()).append(": ").append(price).append('\n');
                    }
                }
            }
            realPrices.forEach((code, price) -> userMessage.append("- ").append(code).append(": ").append(price).append('\n'));
        }

        return chatGptAgent.synthesize(systemPrompt, userMessage.toString());
    }
}
