package com.bitman.justbuy.controller;

import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.security.JwtAuthenticationFilter;
import com.bitman.justbuy.security.SecurityConfig;
import com.bitman.justbuy.service.JwtService;
import com.bitman.justbuy.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SubscriptionPlansSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean SubscriptionService subscriptionService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;

    @Test
    void anonymousVisitorCanReadCanonicalPlanCatalog() throws Exception {
        mvc.perform(get("/api/subscription/plans"))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                [{"id":"MONTHLY","label":"1개월","months":1,"price":30000},
                 {"id":"SIX_MONTHS","label":"6개월","months":6,"price":150000}]
                """, true));
    }

    @Test
    void catalogPermissionDoesNotExposeSubscriptionMutations() throws Exception {
        mvc.perform(post("/api/subscription/plans")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/subscription/apply")
                .contentType("application/json").content("{\"depositorName\":\"payer\",\"plan\":\"SIX_MONTHS\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/subscription/plans/private")).andExpect(status().isUnauthorized());
    }
}
