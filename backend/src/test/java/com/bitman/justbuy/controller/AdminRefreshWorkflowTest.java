package com.bitman.justbuy.controller;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.service.AnalysisService;
import com.bitman.justbuy.service.ConditionSearchFormulaCatalog;
import com.bitman.justbuy.service.PrecomputeScheduler;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void omittedModesKeepTheExistingFullRefreshAndTimestampContract() throws Exception {
        successfulAnalysis();

        mvc.perform(post("/api/admin/system/refresh-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.results", aMapWithSize(4)))
            .andExpect(jsonPath("$.results.BREAKOUT").value("success"))
            .andExpect(jsonPath("$.startedAt").isNotEmpty())
            .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    void mixedModeFailuresAreReportedAsPartialWithTheOriginalError() throws Exception {
        successfulAnalysis();
        when(analysisService.runLiveAnalysis(anyString(), eq("BREAKOUT")))
            .thenThrow(new IllegalStateException("provider timed out"));

        mvc.perform(post("/api/admin/system/refresh-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("partial"))
            .andExpect(jsonPath("$.results.BREAKOUT").value("error: provider timed out"))
            .andExpect(jsonPath("$.results.CATALYST_BURST").value("success"));
    }

    @Test
    void allFailedModesAreReportedAsErrorWithoutLosingTheResultMap() throws Exception {
        when(analysisService.runLiveAnalysis(anyString(), anyString()))
            .thenThrow(new IllegalStateException("source unavailable"));

        mvc.perform(post("/api/admin/system/refresh-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.results", aMapWithSize(4)))
            .andExpect(jsonPath("$.results.FLOW_LEADER").value("error: source unavailable"));
    }

    @Test
    void selectedRetryNormalizesDuplicatesAndDoesNotRunSuccessfulModesAgain() throws Exception {
        successfulAnalysis();

        mvc.perform(post("/api/admin/system/refresh-all").contentType(MediaType.APPLICATION_JSON)
                .content("{\"modes\":[\" catalyst_burst \",\"CATALYST_BURST\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.results", aMapWithSize(1)))
            .andExpect(jsonPath("$.results.CATALYST_BURST").value("success"));

        verify(analysisService).runLiveAnalysis(anyString(), eq("CATALYST_BURST"));
        verify(analysisService, never()).runLiveAnalysis(anyString(), eq("BREAKOUT"));
        verify(analysisService, never()).runLiveAnalysis(anyString(), eq("FLOW_LEADER"));
        verify(analysisService, never()).runLiveAnalysis(anyString(), eq("REVERSAL_EDGE"));
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
        when(analysisService.runLiveAnalysis(anyString(), anyString()))
            .thenAnswer(invocation -> new AnalysisResponse(invocation.getArgument(1), "fixture query", List.of(), null,
                "fixture result", List.of(new StockPick("삼성전자", "005930", "100", "110", "90", "watch", "fixture")),
                null, Instant.now().toString(), true, new AnalysisResponse.Metadata(20, 2, 2)));
    }
}
