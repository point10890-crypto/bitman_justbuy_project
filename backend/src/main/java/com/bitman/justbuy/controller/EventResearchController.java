package com.bitman.justbuy.controller;

import com.bitman.justbuy.research.event.EventResearchPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class EventResearchController {
    private final EventResearchPipeline pipeline;
    private final SubscriptionAccessGuard guard;
    public EventResearchController(EventResearchPipeline pipeline,SubscriptionAccessGuard guard){this.pipeline=pipeline;this.guard=guard;}
    @GetMapping("/api/research/events")
    public JsonNode events(@AuthenticationPrincipal UUID userId){guard.requirePro(userId);return pipeline.snapshot();}
    @GetMapping("/api/research/events/status")
    public Map<String,Object> publicStatus(){var result=new LinkedHashMap<>(pipeline.status());Object raw=result.get("issues");
        if(raw instanceof Collection<?> issues)result.put("issues",issues.stream().map(i->i.toString().replaceAll("[0-9]{14}:","")).map(i->i.matches("[A-Z0-9_]+")?i:"SOURCE_DIAGNOSTIC_REDACTED").distinct().toList());return result;}
    @PreAuthorize("hasRole('ADMIN')") @GetMapping("/api/admin/research/events/status")
    public Map<String,Object> adminStatus(){return pipeline.status();}
    @PreAuthorize("hasRole('ADMIN')") @PostMapping("/api/admin/research/events/refresh")
    public ResponseEntity<Map<String,Object>> refresh(){Map<String,Object> result=pipeline.requestRefresh();return ResponseEntity.status(Boolean.TRUE.equals(result.get("accepted"))?202:200).body(result);}
}
