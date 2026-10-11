package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KisCandidateSourceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void disabledExpansionNeverCallsAnyExternalSource() {
        var gateway = mock(KisSourceGateway.class);
        var universe = mock(StockUniverseService.class);
        var service = new KisCandidateSourceService(gateway, universe, CLOCK);
        assertThat(service.snapshot().status()).isEqualTo("UNAVAILABLE");
        assertThat(service.lookup("005930")).isNull();
        verifyNoInteractions(universe);
        verify(gateway, never()).fetch(anyString(), anyString(), anyMap(), anyLong());
    }

    @Test void verifiedMultiplePriceUsesActualKrwAndDoesNotInventObservationTime() throws Exception {
        var gateway = mock(KisSourceGateway.class);
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.htsId()).thenReturn("");
        var universe = mock(StockUniverseService.class);
        when(universe.stocks()).thenReturn(List.of(new StockUniverseService.Stock("005930", "삼성전자", "KOSPI", "전기전자", List.of(), false)));
        when(gateway.fetch(anyString(), anyString(), anyMap(), anyLong())).thenAnswer(inv -> new KisSourceGateway.Snapshot("AVAILABLE", NOW,
            mapper.readTree(inv.<String>getArgument(0).contains("intstock-multprice") ? """
                {"rt_cd":"0","output":[{"inter_shrn_iscd":"005930","inter_kor_isnm":"삼성전자",
                "kospi_kosdaq_cls_name":"코스피","inter2_prpr":"70000","prdy_ctrt":"3.5","acml_vol":"1000000","acml_tr_pbmn":"100000000000"}]}
                """ : "{\"rt_cd\":\"0\",\"output\":[]}")));
        var result = new KisCandidateSourceService(gateway, universe, CLOCK).snapshot();
        assertThat(result.quotes()).hasSize(1);
        assertThat(result.quotes().getFirst().tradingValueKrw()).isEqualByComparingTo(new BigDecimal("100000000000"));
        assertThat(result.quotes().getFirst().observedAt()).isNull();
        assertThat(result.quotes().getFirst().fetchedAt()).isEqualTo(NOW);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.scope()).contains("전체 시장");
    }

    @Test void identityMismatchOrMalformedPriceCannotBecomeAQuote() throws Exception {
        var gateway = mock(KisSourceGateway.class);
        when(gateway.isEnabled()).thenReturn(true);
        var universe = mock(StockUniverseService.class);
        when(universe.stocks()).thenReturn(List.of(new StockUniverseService.Stock("005930", "삼성전자", "KOSPI", "", List.of(), false)));
        when(gateway.fetch(anyString(), anyString(), anyMap(), anyLong())).thenReturn(new KisSourceGateway.Snapshot("AVAILABLE", NOW,
            mapper.readTree("{\"rt_cd\":\"0\",\"output\":[{\"inter_shrn_iscd\":\"000001\",\"inter2_prpr\":\"70000\"}]}")));
        assertThat(new KisCandidateSourceService(gateway, universe, CLOCK).snapshot().quotes()).isEmpty();
    }

    @Test void normalZeroConditionResponseIsNotAnApiOutage() throws Exception {
        var gateway = mock(KisSourceGateway.class);
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.htsId()).thenReturn("fixture-hts");
        var universe = mock(StockUniverseService.class);
        when(universe.stocks()).thenReturn(List.of());
        when(gateway.fetch(anyString(), anyString(), anyMap(), anyLong())).thenAnswer(inv -> new KisSourceGateway.Snapshot("AVAILABLE", NOW,
            mapper.readTree(inv.<String>getArgument(0).contains("psearch-title") ? "{\"rt_cd\":\"0\",\"output2\":[{\"seq\":\"0\"}]}"
                : inv.<String>getArgument(0).contains("psearch-result") ? "{\"rt_cd\":\"1\",\"msg_cd\":\"MCA05918\"}" : "{\"rt_cd\":\"0\",\"output\":[]}")));
        var result = new KisCandidateSourceService(gateway, universe, CLOCK).snapshot();
        assertThat(result.quotes()).isEmpty();
        assertThat(result.status()).isNotEqualTo("UNAVAILABLE");
    }

    @Test void moreThanThirtyCandidatesUseSeparateBatchesAndOnlyMatchingRowsArePublished() throws Exception {
        var gateway = mock(KisSourceGateway.class);
        when(gateway.isEnabled()).thenReturn(true);
        var universe = mock(StockUniverseService.class);
        var stocks = java.util.stream.IntStream.rangeClosed(1, 31).mapToObj(i -> new StockUniverseService.Stock(
            String.format("%06d", i), "검증회사" + i, "KOSPI", "", List.of(), false)).toList();
        when(universe.stocks()).thenReturn(stocks);
        var sizes = new java.util.ArrayList<Integer>();
        when(gateway.fetch(anyString(), anyString(), anyMap(), anyLong())).thenAnswer(inv -> {
            var body = mapper.createObjectNode(); body.put("rt_cd", "0"); var rows = body.putArray("output");
            if (inv.<String>getArgument(0).contains("intstock-multprice")) {
                Map<String, String> params = inv.getArgument(2);
                var codes = params.entrySet().stream().filter(e -> e.getKey().startsWith("FID_INPUT_ISCD_")).map(Map.Entry::getValue).toList();
                sizes.add(codes.size());
                for (String code : codes) rows.addObject().put("inter_shrn_iscd", code).put("inter2_prpr", "1000")
                    .put("prdy_ctrt", "1").put("acml_vol", "100").put("acml_tr_pbmn", "100000");
            }
            return new KisSourceGateway.Snapshot("AVAILABLE", NOW, body);
        });
        var result = new KisCandidateSourceService(gateway, universe, CLOCK).snapshot();
        assertThat(result.quotes()).hasSize(31);
        assertThat(result.quotes()).extracting(KeywordSearchDataService.Quote::code).doesNotHaveDuplicates();
        assertThat(sizes).containsExactly(30, 1);
    }

    @Test void nearNewHighCandidatesUseOfficialParametersAndFreshQuoteInsteadOfRankingPrice() throws Exception {
        var gateway = mock(KisSourceGateway.class);
        when(gateway.isEnabled()).thenReturn(true);
        var universe = mock(StockUniverseService.class);
        when(universe.stocks()).thenReturn(java.util.stream.IntStream.rangeClosed(1, 601).mapToObj(i ->
            new StockUniverseService.Stock(String.format("%06d", i), "검증회사" + i, "KOSPI", "", List.of(), false)).toList());
        var calls = new java.util.ArrayList<Map<String, String>>();
        when(gateway.fetch(anyString(), anyString(), anyMap(), anyLong())).thenAnswer(inv -> {
            String path = inv.getArgument(0);
            var body = mapper.createObjectNode(); body.put("rt_cd", "0"); var rows = body.putArray("output");
            if (path.endsWith("near-new-highlow")) {
                assertThat(inv.<String>getArgument(1)).isEqualTo("FHPST01870000");
                calls.add(inv.getArgument(2));
                rows.addObject().put("mksc_shrn_iscd", "000601").put("stck_prpr", "999999").put("new_hgpr", "1000000");
                rows.addObject().put("mksc_shrn_iscd", "999999").put("stck_prpr", "1000");
            } else if (path.endsWith("intstock-multprice")) {
                Map<String, String> params = inv.getArgument(2);
                params.entrySet().stream().filter(e -> e.getKey().startsWith("FID_INPUT_ISCD_")).forEach(e ->
                    rows.addObject().put("inter_shrn_iscd", e.getValue()).put("inter2_prpr", "1000")
                        .put("prdy_ctrt", "1").put("acml_vol", "100").put("acml_tr_pbmn", "100000"));
            }
            return new KisSourceGateway.Snapshot("AVAILABLE", NOW, body);
        });
        var result = new KisCandidateSourceService(gateway, universe, CLOCK).snapshot();
        assertThat(calls).containsExactly(Map.ofEntries(
            Map.entry("fid_aply_rang_vol", "0"), Map.entry("fid_cond_mrkt_div_code", "J"),
            Map.entry("fid_cond_scr_div_code", "20187"), Map.entry("fid_div_cls_code", "0"),
            Map.entry("fid_input_cnt_1", "0"), Map.entry("fid_input_cnt_2", "10"),
            Map.entry("fid_prc_cls_code", "0"), Map.entry("fid_input_iscd", "0000"),
            Map.entry("fid_trgt_cls_code", "0"), Map.entry("fid_trgt_exls_cls_code", "0"),
            Map.entry("fid_aply_rang_prc_1", "0"), Map.entry("fid_aply_rang_prc_2", "1000000")));
        assertThat(result.quotes()).hasSize(600);
        assertThat(result.quotes().getFirst().code()).isEqualTo("000601");
        assertThat(result.quotes().getFirst().price()).isEqualByComparingTo("1000");
        assertThat(result.quotes()).extracting(KeywordSearchDataService.Quote::code).doesNotContain("999999");
        assertThat(result.scope()).contains("신고근접", "30종목");
    }
}
