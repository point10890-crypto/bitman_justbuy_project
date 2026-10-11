package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.KeywordSearchResponse;
import com.bitman.justbuy.service.KeywordConditionSearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis/keyword-search")
public class KeywordSearchController {
    public record Request(@NotBlank @Size(max=200) String query) {}
    private final KeywordConditionSearchService search;
    private final SubscriptionAccessGuard guard;
    public KeywordSearchController(KeywordConditionSearchService search, SubscriptionAccessGuard guard) {
        this.search=search;
        this.guard=guard;
    }
    @PostMapping
    public KeywordSearchResponse search(@AuthenticationPrincipal UUID userId, @Valid @RequestBody Request request) {
        guard.requirePro(userId);
        return search.search(request.query());
    }
}
