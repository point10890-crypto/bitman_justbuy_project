package com.bitman.justbuy.controller;

import com.bitman.justbuy.research.event.EventResearchPipeline;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.security.*;
import com.bitman.justbuy.service.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventResearchController.class)
@Import({SecurityConfig.class,JwtAuthenticationFilter.class})
class EventResearchControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EventResearchPipeline pipeline;
    @MockitoBean SubscriptionAccessGuard guard;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository users;
    @Test void anonymousStatusIsMetadataOnlyAndPaidDataIsProtected() throws Exception {
        when(pipeline.status()).thenReturn(Map.of("status","WAITING","candidateCount",0,"issues",java.util.List.of("20261014000123:UNKNOWN_CUSTOMER")));
        mvc.perform(get("/api/research/events/status")).andExpect(status().isOk()).andExpect(jsonPath("$.candidates").doesNotExist()).andExpect(jsonPath("$.issues[0]").value("UNKNOWN_CUSTOMER"));
        mvc.perform(get("/api/research/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/research/events/refresh")).andExpect(status().isUnauthorized());
        verify(pipeline,never()).snapshot();verify(pipeline,never()).requestRefresh();
    }
    @Test @WithMockUser(roles="USER") void userCannotTriggerExternalResearchCalls() throws Exception {
        mvc.perform(post("/api/admin/research/events/refresh")).andExpect(status().isForbidden());verify(pipeline,never()).requestRefresh();
    }
    @Test @WithMockUser(roles="USER") void expiredSubscriptionCannotReadSavedCandidates() throws Exception {
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"구독 만료",SubscriptionAccessGuard.CODE_EXPIRED)).when(guard).requirePro(any());
        mvc.perform(get("/api/research/events")).andExpect(status().isForbidden());verify(pipeline,never()).snapshot();
    }
    @Test @WithMockUser(roles="ADMIN") void adminRefreshIsExplicitAndAcceptedOnlyOnce() throws Exception {
        when(pipeline.requestRefresh()).thenReturn(Map.of("accepted",true,"status","RUNNING","runId","run"));
        mvc.perform(post("/api/admin/research/events/refresh")).andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(true));verify(pipeline,times(1)).requestRefresh();
    }
}
