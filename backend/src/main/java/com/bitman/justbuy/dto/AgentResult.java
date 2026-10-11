package com.bitman.justbuy.dto;

public record AgentResult(
        String agent,
        String content,
        String model,
        int inputTokens,
        int outputTokens,
        String status,
        String error,
        long durationMs,
        int thoughtTokens,
        int totalTokens
) {
    public AgentResult(String agent, String content, String model, int inputTokens, int outputTokens,
                       String status, String error, long durationMs) {
        this(agent, content, model, inputTokens, outputTokens, status, error, durationMs,
            0, Math.max(0, inputTokens) + Math.max(0, outputTokens));
    }
    public static AgentResult skipped(String agent, String reason) {
        return new AgentResult(agent, "", "", 0, 0, "skipped", reason, 0);
    }

    public static AgentResult error(String agent, String model, String error, long durationMs) {
        return new AgentResult(agent, "", model, 0, 0, "error", error, durationMs);
    }

    public static AgentResult error(String agent, String model, String error, long durationMs,
                                    int inputTokens, int outputTokens) {
        return new AgentResult(agent, "", model, inputTokens, outputTokens, "error", error, durationMs);
    }
}
