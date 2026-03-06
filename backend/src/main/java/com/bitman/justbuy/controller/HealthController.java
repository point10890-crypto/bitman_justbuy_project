package com.bitman.justbuy.controller;

import com.bitman.justbuy.ai.agent.AiAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final List<AiAgent> agents;

    public HealthController(List<AiAgent> agents) {
        this.agents = agents;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("service", "justbuy-api");
        result.put("timestamp", Instant.now().toString());

        Map<String, Object> agentStatus = new LinkedHashMap<>();
        for (AiAgent agent : agents) {
            agentStatus.put(agent.name(), Map.of(
                "available", agent.isAvailable()
            ));
        }
        result.put("agents", agentStatus);
        result.put("totalAgents", agents.size());
        result.put("availableAgents", agents.stream().filter(AiAgent::isAvailable).count());

        return result;
    }
}
