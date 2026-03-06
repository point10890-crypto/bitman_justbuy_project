package com.bitman.justbuy.dto;

public record AgentResult(
        String agent,
        String content,
        String model,
        int inputTokens,
        int outputTokens,
        String status,
        String error,
        long durationMs
) {
    public static AgentResult skipped(String agent, String reason) {
        return new AgentResult(agent, "", "", 0, 0, "skipped", reason, 0);
    }

    public static AgentResult error(String agent, String model, String error, long durationMs) {
        return new AgentResult(agent, "", model, 0, 0, "error", error, durationMs);
    }
}
