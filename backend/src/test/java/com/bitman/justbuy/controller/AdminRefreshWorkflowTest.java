package com.bitman.justbuy.controller;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.service.AnalysisService;
import com.bitman.justbuy.service.ConditionSearchFormulaCatalog;
import com.bitman.justbuy.service.PrecomputeScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminRefreshWorkflowTest {
    @Mock AnalysisService analysisService;
    private AdminController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // No Spring context, startup hook, real provider, KIS, or Telegram sender.
        var scheduler = new PrecomputeScheduler(analysisService, new ConditionSearchFormulaCatalog(), null, null, null, new ConditionRunService());
        controller = new AdminController(null, analysisService, null, List.of(), null, null, null, new ConditionRunService());
        ReflectionTestUtils.setField(controller, "precomputeScheduler", scheduler);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void omittedModesKeepTheExistingFullRefreshAndTimestampContract() throws Exception {
        successfulAnalysis();

        var completed = refresh(null);
        assertThat(completed.get("status")).isEqualTo("ok");
        assertThat((Map<?, ?>) completed.get("results")).hasSize(4);
        assertThat(completed).containsKeys("startedAt", "completedAt", "jobId");
    }

    @Test
    void mixedModeFailuresAreReportedAsPartialWithTheOriginalError() throws Exception {
        successfulAnalysis();
        when(analysisService.runAnalysisForPublication(anyString(), eq("BREAKOUT")))
            .thenThrow(new IllegalStateException("provider timed out"));

        var completed = refresh(null);
        assertThat(completed.get("status")).isEqualTo("partial");
        assertThat((Map<String, String>) completed.get("results"))
            .containsEntry("BREAKOUT", "error: provider timed out").containsEntry("CATALYST_BURST", "success");
    }

    @Test
    void allFailedModesAreReportedAsErrorWithoutLosingTheResultMap() throws Exception {
        when(analysisService.runAnalysisForPublication(anyString(), anyString()))
            .thenThrow(new IllegalStateException("source unavailable"));

        var completed = refresh(null);
        assertThat(completed.get("status")).isEqualTo("error");
        assertThat((Map<String, String>) completed.get("results"))
            .hasSize(4).containsEntry("FLOW_LEADER", "error: source unavailable");
    }

    @Test
    void selectedRetryNormalizesDuplicatesAndDoesNotRunSuccessfulModesAgain() throws Exception {
        successfulAnalysis();

        var completed = refresh("{\"modes\":[\" catalyst_burst \",\"CATALYST_BURST\"]}");
        assertThat(completed.get("status")).isEqualTo("ok");
        assertThat((Map<String, String>) completed.get("results"))
            .hasSize(1).containsEntry("CATALYST_BURST", "success");

        verify(analysisService).runAnalysisForPublication(anyString(), eq("CATALYST_BURST"));
        verify(analysisService, never()).runAnalysisForPublication(anyString(), eq("BREAKOUT"));
        verify(analysisService, never()).runAnalysisForPublication(anyString(), eq("FLOW_LEADER"));
        verify(analysisService, never()).runAnalysisForPublication(anyString(), eq("REVERSAL_EDGE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"modes\":[]}",
        "{\"modes\":[\"UNKNOWN\"]}",
        "{\"modes\":[\"BREAKOUT\",\"UNKNOWN\"]}",
        "{\"modes\":[null]}"
    })
    void invalidSelectedModesAreRejectedBeforeAnyAnalysisRuns(String body) throws Exception {
        mvc.perform(post("/api/admin/system/refresh-all").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(analysisService);
    }

    @Test
    void disabledSchedulerKeepsItsExistingErrorResponse() throws Exception {
        ReflectionTestUtils.setField(controller, "precomputeScheduler", null);
        mvc.perform(post("/api/admin/system/refresh-all"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("error"));
        verifyNoInteractions(analysisService);
    }

    private void successfulAnalysis() {
        when(analysisService.runAnalysisForPublication(anyString(), anyString()))
            .thenAnswer(invocation -> {
                String mode = invocation.getArgument(1);
                String code = switch (mode) { case "BREAKOUT" -> "005930"; case "REVERSAL_EDGE" -> "000660";
                    case "FLOW_LEADER" -> "035420"; default -> "068270"; };
                return new AnalysisResponse(mode, "fixture query", List.of(), null,
                "fixture result", List.of(new StockPick("fixture " + code, code, "100", "110", "90", "watch", "fixture")),
                null, Instant.now().toString(), true, new AnalysisResponse.Metadata(20, 2, 2));
            });
    }

    private Map<String, Object> refresh(String body) throws Exception {
        var request = post("/api/admin/system/refresh-all");
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        var started = mvc.perform(request).andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty()).andReturn();
        var mapper = new ObjectMapper();
        Map<String, Object> response = mapper.readValue(started.getResponse().getContentAsString(), Map.class);
        String jobId = (String) response.get("jobId");
        for (int attempt = 0; attempt < 100; attempt++) {
            var poll = mvc.perform(get("/api/admin/system/refresh-all/" + jobId)).andExpect(status().isOk()).andReturn();
            response = mapper.readValue(poll.getResponse().getContentAsString(), Map.class);
            if (!"running".equals(response.get("status"))) return response;
            Thread.sleep(10);
        }
        throw new AssertionError("Refresh did not complete in fixture deadline");
    }
}
