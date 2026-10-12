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
}
