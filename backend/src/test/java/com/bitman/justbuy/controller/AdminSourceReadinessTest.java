package com.bitman.justbuy.controller;

import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.service.AnalysisService;
import com.bitman.justbuy.service.ConditionSearchPipeline;
import com.bitman.justbuy.service.KisApiService;
import com.bitman.justbuy.service.KiwoomReadOnlyService;
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

    @Test void explicitFinancialProbeIsolatesUnexpectedProviderFailureWithoutExposingItsMessage() throws Exception {
        var dart=mock(com.bitman.justbuy.service.DartApiService.class);
        when(dart.fetchFinancialEvidence(List.of("028670"))).thenThrow(new IllegalStateException("private-key-do-not-return"));
        var controller=new AdminController(null,analysis,pipeline,List.of(),kis,null,null,new ConditionRunService());
        ReflectionTestUtils.setField(controller,"dartFinancialSource",dart);
        var response=controller.getDartFinancialEvidence("028670");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status","UNAVAILABLE");
        assertThat(new ObjectMapper().writeValueAsString(response.getBody())).doesNotContain("private-key-do-not-return");
        verify(dart).fetchFinancialEvidence(List.of("028670"));
        org.mockito.Mockito.verifyNoInteractions(kis,analysis);
    }

    @Test void savedQualityReportDoesNotExposePrivateQuestionsOrStartAnalysis() throws Exception {
        var pick=new com.bitman.justbuy.dto.StockPick("팬오션","028670","6,510","6,750","6,420","주목","private rationale",50,"legacy score","MARKET_DATA","NAVER_FINANCE",java.time.Instant.now().toString());
        var response=new com.bitman.justbuy.dto.AnalysisResponse("BREAKOUT","private user question",List.of(),null,"private final content",List.of(pick),null,
            java.time.Instant.now().toString(),true,new com.bitman.justbuy.dto.AnalysisResponse.Metadata(1,2,2));
        when(analysis.getStoredPrecomputed("BREAKOUT")).thenReturn(response);
        var controller=new AdminController(null,analysis,pipeline,List.of(),kis,null,null,new ConditionRunService());
        ReflectionTestUtils.setField(controller,"detectedStockQualityService",new com.bitman.justbuy.service.DetectedStockQualityService());
        var body=controller.getDetectedStockQuality().getBody();
        assertThat(body).containsEntry("publishedSlots",1).containsEntry("uniqueStocks",1);
        String json=new ObjectMapper().writeValueAsString(body);
        assertThat(json).contains("028670","LIMITED_EVIDENCE").doesNotContain("private user question","private rationale","private final content");
        for(String mode:List.of("BREAKOUT","REVERSAL_EDGE","FLOW_LEADER","CATALYST_BURST"))verify(analysis).getStoredPrecomputed(mode);
        verifyNoMoreInteractions(analysis); org.mockito.Mockito.verifyNoInteractions(kis);
    }

    @Test
    void runtimeQueueStatusIsVisibleButUnexpectedPrivateFieldsAreNeverExposed() throws Exception {
        var adapter=mock(KiwoomReadOnlyService.class);
        when(adapter.readiness()).thenReturn(Map.of("source","KIWOOM_REST_MOCK","environment","MOCK","candidateLimit",20,
            "privateToken","fixture-private-token", "queue",Map.of("status","COLLECTING","pendingJobs",17,"workerActive",true,
                "capacity",128,"privateResponse","fixture-private-response")));
        var body=status(new MockEnvironment(),adapter);
        var ready=(Map<?,?>)body.get("kiwoomReadiness");
        assertThat(ready.get("queueStatus")).isEqualTo("COLLECTING");assertThat(ready.get("candidateLimit")).isEqualTo(20);
        assertThat(((Map<?,?>)ready.get("queue")).get("pendingJobs")).isEqualTo(17);
        var source=sources(body).stream().filter(value->"KIWOOM".equals(value.get("id"))).findFirst().orElseThrow();
        assertThat(((Map<?,?>)source.get("runtime")).get("workerActive")).isEqualTo(true);
        assertThat(new ObjectMapper().writeValueAsString(body)).doesNotContain("fixture-private","privateToken","privateResponse");
    }

    @Test
    void absentConfigurationKeepsNewSourcesDisabledAndCredentialsExplicitlyMissing() {
        var body = status(new MockEnvironment());

        var sources = sources(body);
        assertThat(sources).hasSize(4);
        assertThat(sources).allSatisfy(source -> {
            assertThat(source.get("enabled")).isEqualTo(false);
            assertThat(source.get("credentialsConfigured")).isEqualTo(false);
            assertThat(source.get("state")).isEqualTo("DISABLED");
        });
        assertThat(sources.get(0).get("message").toString()).contains("이용 권한");
        assertThat(sources.get(2).get("message").toString()).contains("인증키");
        assertThat(sources.get(3)).containsEntry("id","KIWOOM").containsEntry("name","키움 시장조회 (KIS 병행)");
        assertThat(sources.get(3).get("message").toString()).contains("키", "공인 출발 IP", "정보이용 범위");
    }

    @Test
    void configuredKeysNeverBecomeLiveConnectionClaimsOrAppearInTheResponse() throws Exception {
        var env = enabledEnvironment()
            .withProperty("bitman.kis.app-key", "fixture-kis-key-never-return")
            .withProperty("bitman.kis.app-secret", "fixture-kis-secret-never-return")
            .withProperty("bitman.dart.api-key", "fixture-dart-key-never-return")
            .withProperty("bitman.ecos.api-key", "fixture-ecos-key-never-return")
            .withProperty("bitman.kiwoom.app-key", "fixture-kiwoom-key-never-return")
            .withProperty("bitman.kiwoom.app-secret", "fixture-kiwoom-secret-never-return");
        var body = status(env);

        var sources = sources(body);
        assertThat(sources).allSatisfy(source -> {
            assertThat(source.get("enabled")).isEqualTo(true);
            assertThat(source.get("credentialsConfigured")).isEqualTo(true);
        });
        assertThat(sources.get(0).get("state")).isEqualTo("RIGHTS_CHECK_REQUIRED");
        assertThat(sources.get(1).get("state")).isEqualTo("CONFIGURED");
        assertThat(sources.get(2).get("state")).isEqualTo("CONFIGURED");
        assertThat(sources.get(3).get("state")).isEqualTo("RIGHTS_CHECK_REQUIRED");
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
    void licensedKiwoomConfigurationIsReportedWithoutClaimingAValidatedConnectionOrReturningKeys() throws Exception {
        var body=status(enabledEnvironment()
            .withProperty("bitman.kiwoom.app-key","fixture-kiwoom-key-never-return")
            .withProperty("bitman.kiwoom.app-secret","fixture-kiwoom-secret-never-return")
            .withProperty("bitman.kiwoom.licensed","true"));
        var kiwoom=sources(body).stream().filter(source->"KIWOOM".equals(source.get("id"))).findFirst().orElseThrow();
        assertThat(kiwoom).containsEntry("enabled",true).containsEntry("credentialsConfigured",true).containsEntry("state","CONFIGURED");
        assertThat(kiwoom.get("message").toString()).contains("실제 연결", "미검증", "공인 출발 IP", "정보이용 범위");
        assertThat(new ObjectMapper().writeValueAsString(body)).doesNotContain("fixture-", "api-key", "app-secret");
        verify(kis).isAvailable(); verifyNoMoreInteractions(kis);
    }

    @Test
    void mockKiwoomEnvironmentIsVisibleWithoutEnablingSourcesOrExposingConfiguredKeys() throws Exception {
        var body = status(new MockEnvironment()
            .withProperty("bitman.kiwoom.environment", "MOCK")
            .withProperty("bitman.kiwoom.app-key", "fixture-kiwoom-mock-key-never-return")
            .withProperty("bitman.kiwoom.app-secret", "fixture-kiwoom-mock-secret-never-return")
            .withProperty("bitman.kiwoom.enabled", "false")
            .withProperty("bitman.kiwoom.licensed", "false"));
        var kiwoom = sources(body).stream().filter(source -> "KIWOOM".equals(source.get("id"))).findFirst().orElseThrow();
        assertThat(kiwoom).containsEntry("name", "키움 시장조회 (KIS 병행·모의)")
            .containsEntry("enabled", false).containsEntry("credentialsConfigured", true).containsEntry("state", "DISABLED");
        assertThat(kiwoom.get("message").toString()).contains("시장 조회", "KIS", "관측일", "주문·계좌는 사용하지 않습니다");
        assertThat(sources(body)).allSatisfy(source -> assertThat(source.get("enabled")).isEqualTo(false));
        assertThat(new ObjectMapper().writeValueAsString(body)).doesNotContain("fixture-", "api-key", "app-secret");
        verify(kis).isAvailable();
        verifyNoMoreInteractions(kis);
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
            .withProperty("bitman.ecos.enabled", "true")
            .withProperty("bitman.kiwoom.enabled", "true");
    }

    private Map<String, Object> status(MockEnvironment environment) {
        return status(environment,null);
    }
    private Map<String, Object> status(MockEnvironment environment,KiwoomReadOnlyService adapter) {
        when(analysis.getCacheStatus()).thenReturn(Map.of());
        when(pipeline.describe()).thenReturn(Map.of("status", "configured"));
        // Only this controller is registered: no startup hooks, DB, providers or application configuration.
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(AdminController.class, () -> {
                var controller=new AdminController(null,analysis,pipeline,List.of(),kis,null,null,new ConditionRunService());
                if(adapter!=null)ReflectionTestUtils.setField(controller,"kiwoomReadOnlyService",adapter);
                return controller;
            });
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
