package com.bitman.justbuy.ai.agent;

import com.bitman.justbuy.dto.AgentResult;

public interface AiAgent {
    String name();
    boolean isAvailable();
    AgentResult analyze(String systemPrompt, String userMessage);
}
