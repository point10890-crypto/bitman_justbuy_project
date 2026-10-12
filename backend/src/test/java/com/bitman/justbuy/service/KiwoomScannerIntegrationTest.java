package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KiwoomScannerIntegrationTest {
    private static final ZonedDateTime NOW=ZonedDateTime.of(2026,10,12,10,0,0,0,ZoneId.of("Asia/Seoul"));
    private final KiwoomConditionEnricher enricher=mock(KiwoomConditionEnricher.class);
    private final DeepSeekConditionVerifier verifier=mock(DeepSeekConditionVerifier.class);
    private KiwoomConditionEnricher.Result independent() {
        return new KiwoomConditionEnricher.Result(Map.of("005930",Map.of("quote",Map.of("status","AVAILABLE",
            "source","KIWOOM_REST_MOCK","environment","MOCK","currentPrice",72000,"fetchedAt",NOW.toInstant().toString()),
            "investorDaily",Map.of("status","PARTIAL_COVERAGE","days",2,"observedDate","2026-10-08","fetchedAt",NOW.toInstant().toString()))),
            Map.of("source","KIWOOM_REST_MOCK","environment","MOCK","status","PARTIAL"));
    }
    private List<ConditionSignalDto> shortSignals(ShortTermRealtimeScanner scanner) {
        var stocks=List.of(new KisApiService.RankedStock("삼성전자","005930","71000","3.0","2500000",0));
        return scanner.buildSignals(stocks,stocks,NOW);
    }
    private List<KisApiService.ConditionCandidate> leaders() {
        return List.of(new KisApiService.ConditionCandidate("삼성전자","005930","71000","3.0",300,500,-800,800,"KIS","KIS 원자료"));
    }
    @Test void shortTermUsesKiwoomObservationsEvenWhenAiVerifierIsDisabled() {
        var scanner=new ShortTermRealtimeScanner(null);scanner.configureKiwoomEnricher(enricher);
        when(enricher.enrich(anyMap())).thenReturn(independent());var primary=shortSignals(scanner);
        var result=scanner.verifyAndEnrich(primary,NOW);
        verify(enricher).enrich(eq(Map.of("005930","삼성전자")));
        assertThat(result.getFirst().currentPrice()).isEqualTo("71,000");
        assertThat(result.getFirst().evidence()).anyMatch(line->line.contains("KIWOOM_REST_MOCK")&&line.contains("72000원"))
            .anyMatch(line->line.contains("5일 수급 기간 부족"));
        assertThat(result.getFirst().ruleScore()).isEqualTo(primary.getFirst().ruleScore());
    }
    @Test void shortTermVerifierReceivesBothSourcesWithoutReplacingPrimaryPrice() {
        var scanner=new ShortTermRealtimeScanner(null,(TrackRecordService)null,verifier);scanner.configureKiwoomEnricher(enricher);
        when(enricher.enrich(anyMap())).thenReturn(independent());when(verifier.isAvailable()).thenReturn(true);when(verifier.verify(anyList())).thenReturn(Map.of());
        var result=scanner.verifyAndEnrich(shortSignals(scanner),NOW);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EvidencePacket>> packets=ArgumentCaptor.forClass(List.class);
        verify(verifier).verify(packets.capture());var packet=packets.getValue().getFirst();
        assertThat(packet.currentPrice()).isEqualTo("71,000");assertThat(packet.metrics()).containsKey("kiwoomEvidence").containsKey("kiwoomCoverage");
        assertThat(packet.ruleEvidence()).containsAll(result.getFirst().evidence());
    }
    @Test void leadersUseIndependentKiwoomEvidenceWithoutAiAndPreserveKisScoresAndPrice() {
        var engine=new LeaderConditionEngine(null);engine.configureKiwoomEnricher(enricher);when(enricher.enrich(anyMap())).thenReturn(independent());
        var primary=new LeaderConditionEngine(null).buildSignals(leaders(),NOW);var result=engine.buildSignals(leaders(),NOW);
        assertThat(result.getFirst().currentPrice()).isEqualTo("71,000");assertThat(result.getFirst().ruleScore()).isEqualTo(primary.getFirst().ruleScore());
        assertThat(result.getFirst().evidence()).contains("KIS 쌍끌이 조건검색").anyMatch(line->line.contains("KIWOOM_REST_MOCK"));
    }
    @Test void leaderVerifierReceivesPrimaryFlowAndIndependentKiwoomPeriods() {
        var engine=new LeaderConditionEngine(null,verifier);engine.configureKiwoomEnricher(enricher);
        when(enricher.enrich(anyMap())).thenReturn(independent());when(verifier.isAvailable()).thenReturn(true);when(verifier.verify(anyList())).thenReturn(Map.of());
        engine.buildSignals(leaders(),NOW);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EvidencePacket>> packets=ArgumentCaptor.forClass(List.class);
        verify(verifier).verify(packets.capture());var metrics=packets.getValue().getFirst().metrics();
        assertThat(metrics).containsEntry("foreignNet",300L).containsEntry("institutionNet",500L).containsEntry("totalSmartNet",800L)
            .containsKey("kiwoomEvidence").containsKey("kiwoomCoverage");
    }
    @Test void auxiliaryFailurePreservesAllPrimarySignalsWithoutLeakingProviderMessages() {
        when(enricher.enrich(anyMap())).thenThrow(new IllegalStateException("PRIVATE_PROVIDER_ERROR"));
        var scanner=new ShortTermRealtimeScanner(null);scanner.configureKiwoomEnricher(enricher);var input=shortSignals(scanner);
        assertThat(scanner.verifyAndEnrich(input,NOW)).isSameAs(input);
        var engine=new LeaderConditionEngine(null);engine.configureKiwoomEnricher(enricher);
        assertThat(engine.buildSignals(leaders(),NOW)).hasSize(1).allSatisfy(signal->assertThat(signal.evidence()).noneMatch(line->line.contains("PRIVATE_PROVIDER_ERROR")));
    }
    @Test void shortTermQueriesTwentyOriginalCandidatesBeforeTopThreeAndReusesPreparedEvidence() {
        var scanner=new ShortTermRealtimeScanner(null);scanner.configureKiwoomEnricher(enricher);
        when(enricher.enrich(anyMap())).thenReturn(independent());
        var stocks=new ArrayList<KisApiService.RankedStock>();
        stocks.add(new KisApiService.RankedStock("삼성전자","005930","71000","3.0","2500000",0));
        for(int i=0;i<21;i++) stocks.add(new KisApiService.RankedStock("검증후보"+i,String.format("%06d",100000+i),"71000","3.0","2500000",0));
        stocks.add(new KisApiService.RankedStock("KODEX ETF","122630","71000","3.0","2500000",0));
        stocks.add(new KisApiService.RankedStock("무효코드","invalid","71000","3.0","2500000",0));
        var prepared=scanner.queryCandidateEvidence(stocks,stocks,List.of());
        var published=scanner.verifyAndEnrich(scanner.buildSignals(stocks,stocks,NOW),NOW,prepared);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,String>> requested=ArgumentCaptor.forClass(Map.class);
        verify(enricher,times(1)).enrich(requested.capture());
        assertThat(requested.getValue()).hasSize(20).containsKey("005930").containsKey("100018")
            .doesNotContainKey("100019").doesNotContainKey("122630").doesNotContainKey("invalid");
        assertThat(published).hasSize(3);
        assertThat(published.getFirst().evidence()).anyMatch(line->line.contains("KIWOOM_REST_MOCK"));
    }
    @Test void leaderQueriesTwentyOriginalCandidatesWhileAiRemainsSixAndPublishedResultsThree() {
        var engine=new LeaderConditionEngine(null,verifier);engine.configureKiwoomEnricher(enricher);
        when(enricher.enrich(anyMap())).thenReturn(independent());when(verifier.isAvailable()).thenReturn(true);when(verifier.verify(anyList())).thenReturn(Map.of());
        var candidates=new ArrayList<>(leaders());
        for(int i=0;i<21;i++) candidates.add(new KisApiService.ConditionCandidate("검증후보"+i,String.format("%06d",100000+i),"71000","3.0",300,500,-800,800,"KIS","KIS 원자료"));
        var published=engine.buildSignals(candidates,NOW);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,String>> requested=ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EvidencePacket>> packets=ArgumentCaptor.forClass(List.class);
        verify(enricher,times(1)).enrich(requested.capture());verify(verifier).verify(packets.capture());
        assertThat(requested.getValue()).hasSize(20).containsKey("005930").containsKey("100018").doesNotContainKey("100019");
        assertThat(packets.getValue()).hasSize(6);assertThat(published).hasSize(3);
        assertThat(published.getFirst().currentPrice()).isEqualTo("71,000");
    }
}
