package com.bitman.justbuy.controller;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.service.AnalysisService;
import com.bitman.justbuy.service.ConditionSearchPipeline;
import com.bitman.justbuy.service.KisApiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AdminSourceReadinessTest {
    private final AnalysisService analysis = mock(AnalysisService.class);
    private final ConditionSearchPipeline pipeline = mock(ConditionSearchPipeline.class);
    private final KisApiService kis = mock(KisApiService.class);

    @Test
    void absentConfigurationKeepsNewSourcesDisabledAndCredentialsExplicitlyMissing() {
        var body = status(new MockEnvironment());

        var sources = sources(body);
        assertThat(sources).hasSize(3);
        assertThat(sources).allSatisfy(source -> {
            assertThat(source.get("enabled")).isEqualTo(false);
            assertThat(source.get("credentialsConfigured")).isEqualTo(false);
            assertThat(source.get("state")).isEqualTo("DISABLED");
        });
        assertThat(sources.get(0).get("message").toString()).contains("이용 권한");
        assertThat(sources.get(2).get("message").toString()).contains("인증키");
    }

    @Test
    void configuredKeysNeverBecomeLiveConnectionClaimsOrAppearInTheResponse() throws Exception {
        var env = enabledEnvironment()
            .withProperty("bitman.kis.app-key", "fixture-kis-key-never-return")
            .withProperty("bitman.kis.app-secret", "fixture-kis-secret-never-return")
            .withProperty("bitman.dart.api-key", "fixture-dart-key-never-return")
            .withProperty("bitman.ecos.api-key", "fixture-ecos-key-never-return");
        var body = status(env);

        var sources = sources(body);
        assertThat(sources).allSatisfy(source -> {
            assertThat(source.get("enabled")).isEqualTo(true);
            assertThat(source.get("credentialsConfigured")).isEqualTo(true);
        });
        assertThat(sources.get(0).get("state")).isEqualTo("RIGHTS_CHECK_REQUIRED");
        assertThat(sources.get(1).get("state")).isEqualTo("CONFIGURED");
        assertThat(sources.get(2).get("state")).isEqualTo("CONFIGURED");
        assertThat(new ObjectMapper().writeValueAsString(body)).doesNotContain("fixture-", "api-key", "app-secret");
        verify(kis).isAvailable();
        verifyNoMoreInteractions(kis);
    }

    @Test
    void enablingFlagsWithoutCredentialsReportsMissingCredentials() {
        var body = status(enabledEnvironment().withProperty("bitman.ecos.api-key", "   "));

        assertThat(sources(body)).allSatisfy(source -> {
            assertThat(source.get("enabled")).isEqualTo(true);
            assertThat(source.get("credentialsConfigured")).isEqualTo(false);
            assertThat(source.get("state")).isEqualTo("CREDENTIALS_MISSING");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedOutcomesKeepEmptyAndPartialSeparateFromFailuresAndLabelCompletionRate() {
        var logs = (Deque<Map<String, Object>>) ReflectionTestUtils.getField(MonitorController.class, "analysisLogs");
        var previous = new ArrayList<>(logs);
        logs.clear();
        try {
            MonitorController.recordAnalysis("COMPLETE", true, 100, 2, 2, 1, null);
            MonitorController.recordAnalysis("PARTIAL", false, 100, 2, 1, 1, "PARTIAL");
            MonitorController.recordAnalysis("EMPTY", false, 100, 2, 2, 0, "EMPTY");
            MonitorController.recordAnalysis("FAILED", false, 100, 2, 0, 0,
                "https://provider.invalid/fixture-secret?key=fixture-key raw-body");
            var body = status(new MockEnvironment());

            assertThat(body.get("analysisStats")).isInstanceOf(Map.class);
            var stats = (Map<String, Object>) body.get("analysisStats");
            assertThat(stats.get("totalAnalyses")).isEqualTo(4L);
            assertThat(stats.get("completionRate")).isEqualTo(25L);
            assertThat((Map<String, Long>) stats.get("outcomeCounts")).containsExactlyInAnyOrderEntriesOf(
                Map.of("COMPLETE", 1L, "PARTIAL", 1L, "EMPTY", 1L, "FAILED", 1L));
            assertThat((List<Map<String, Object>>) body.get("recentAnalyses")).extracting(log -> log.get("outcome"))
                .containsExactly("FAILED", "EMPTY", "PARTIAL", "COMPLETE");
            var recent = (List<Map<String, Object>>) body.get("recentAnalyses");
            assertThat(recent.getFirst()).containsEntry("error", "EXECUTION_FAILED");
            assertThat(recent.toString()).doesNotContain("fixture-secret", "fixture-key", "raw-body", "https://");
        } finally {
            logs.clear();
            logs.addAll(previous);
        }
    }

    private MockEnvironment enabledEnvironment() {
        return new MockEnvironment()
            .withProperty("bitman.kis.extended-evidence.enabled", "true")
            .withProperty("bitman.dart.event-evidence.enabled", "true")
            .withProperty("bitman.ecos.enabled", "true");
    }

    private Map<String, Object> status(MockEnvironment environment) {
        when(analysis.getCacheStatus()).thenReturn(Map.of());
        when(pipeline.describe()).thenReturn(Map.of("status", "configured"));
        // Only this controller is registered: no startup hooks, DB, providers or application configuration.
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(AdminController.class, () -> new AdminController(null, analysis, pipeline,
                List.of(), kis, null, null, new ConditionRunService()));
            context.refresh();
            return context.getBean(AdminController.class).getSystemStatus().getBody();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sources(Map<String, Object> body) {
        assertThat(body.get("dataSources")).as("additive source-readiness status").isInstanceOf(List.class);
        return (List<Map<String, Object>>) body.get("dataSources");
    }
}
