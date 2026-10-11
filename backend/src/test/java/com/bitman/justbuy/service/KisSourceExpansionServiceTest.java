package com.bitman.justbuy.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KisSourceExpansionServiceTest {
    @Test void supplementalSourcesReachPromptAndValidatedOfficialClosureVetoesToday() {
        var gateway=mock(KisSourceGateway.class);var universe=mock(StockUniverseService.class);
        var candidates=mock(KisCandidateSourceService.class);var research=mock(KisResearchEvidenceService.class);
        var realtime=mock(KisRealtimeFeedService.class);var supplemental=mock(KisSupplementalEvidenceService.class);
        var extraRealtime=mock(KisRealtimeSupplementService.class);
        when(gateway.isEnabled()).thenReturn(true);
        when(universe.find(anyString())).thenReturn(Optional.empty());
        when(research.fetchEvidence(List.of("005930"))).thenReturn(new KisResearchEvidenceService.EvidenceBatch("UNAVAILABLE",Instant.now(),List.of()));
        when(realtime.facts("005930")).thenReturn(Map.of("status","UNAVAILABLE"));
        String today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
        when(supplemental.fetchEvidence(List.of("005930"))).thenReturn(Map.of("status","PARTIAL","candidates",Map.of("005930",Map.of("incomeStatement",Map.of("status","AVAILABLE","rawFields",Map.of("sale_account",123)))),"calendar",Map.of("status","AVAILABLE","requestDate",today,"calendar",List.of(Map.of("calendarDate",today,"opnd_yn","N")))));
        when(extraRealtime.historicalEvidence(List.of("005930"))).thenReturn(Map.of("status","PARTIAL","basis","ACTUAL_MINUTES"));
        when(extraRealtime.facts("005930")).thenReturn(Map.of("NXT",Map.of("status","UNAVAILABLE")));
        var service=new KisSourceExpansionService(gateway,universe,candidates,research,realtime,new ObjectMapper().findAndRegisterModules());
        service.configureSupplementalResearch(supplemental);service.configureSupplementalRealtime(extraRealtime);
        var result=service.enrich(List.of("005930"));
        assertThat(result.prompt()).contains("sale_account","ACTUAL_MINUTES","NXT");
        assertThat(result.metadata()).containsEntry("status","PARTIAL").containsEntry("enrichedCodes",1).containsEntry("availableFacts",1);
        assertThat(service.isOfficiallyClosedToday()).isTrue();
        verify(extraRealtime).watch(List.of("005930"));
    }
    @Test void disabledExpansionNeverStartsNetworkSources() {
        var gateway=mock(KisSourceGateway.class); var universe=mock(StockUniverseService.class);
        var candidates=mock(KisCandidateSourceService.class);var research=mock(KisResearchEvidenceService.class);
        var realtime=mock(KisRealtimeFeedService.class);
        when(gateway.readiness()).thenReturn(Map.of("status","RIGHTS_UNCONFIRMED"));
        var service=new KisSourceExpansionService(gateway,universe,candidates,research,realtime,new ObjectMapper().findAndRegisterModules());
        service.prepare();var result=service.enrich(List.of("005930"));
        assertThat(result.metadata().get("status")).isEqualTo("RIGHTS_UNCONFIRMED");
        assertThat(result.prompt()).isEmpty();
        verifyNoInteractions(universe,candidates,research,realtime);
    }
    @Test void verifiedCandidatesAndExplicitPartialEvidenceReachAnalysisInputs() {
        var gateway=mock(KisSourceGateway.class);var universe=mock(StockUniverseService.class);
        var candidates=mock(KisCandidateSourceService.class);var research=mock(KisResearchEvidenceService.class);
        var realtime=mock(KisRealtimeFeedService.class);
        when(gateway.isEnabled()).thenReturn(true);when(gateway.readiness()).thenReturn(Map.of("status","READY"));
        var now=Instant.now();var quote=new KeywordSearchDataService.Quote("005930","삼성전자","KOSPI",new BigDecimal("70000"),BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN,"KIS_OPENAPI",now,null);
        when(candidates.snapshot()).thenReturn(new KeywordSearchDataService.Snapshot("PARTIAL",List.of(quote),now,"bounded",List.of("시세 시각 미확인")));
        when(universe.find("005930")).thenReturn(Optional.of(new StockUniverseService.Stock("005930","삼성전자","KOSPI","전자",List.of("반도체"),false)));
        var evidence=new KisResearchEvidenceService.CandidateEvidence("005930",Map.of("status","AVAILABLE","observedDate","2026-10-08"),Map.of("status","AVAILABLE","per",BigDecimal.TEN),Map.of("status","UNAVAILABLE"),Map.of("status","ESTIMATE_UNDATED"),Map.of("status","UNAVAILABLE"),Map.of("status","UNAVAILABLE"));
        when(research.fetchEvidence(List.of("005930"))).thenReturn(new KisResearchEvidenceService.EvidenceBatch("PARTIAL",now,List.of(evidence)));
        when(realtime.facts("005930")).thenReturn(Map.of("status","STALE"));
        var service=new KisSourceExpansionService(gateway,universe,candidates,research,realtime,new ObjectMapper().findAndRegisterModules());
        assertThat(service.candidateSnapshot().quotes()).containsExactly(quote);
        var result=service.enrich(List.of("005930"));
        assertThat(result.prompt()).contains("ESTIMATE_UNDATED","반도체","2026-10-08");
        assertThat(result.metadata()).containsEntry("status","PARTIAL").containsEntry("requestedCodes",1).containsEntry("enrichedCodes",1).containsEntry("availableFacts",2).containsEntry("realtimeFreshQuotes",0);
        verify(realtime).watch(List.of("005930"));
    }
}
