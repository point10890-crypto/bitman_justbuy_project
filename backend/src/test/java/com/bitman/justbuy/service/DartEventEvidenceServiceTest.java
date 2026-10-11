package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

/** Synthetic fixtures follow the official Korean OpenDART guides; no provider requests. */
class DartEventEvidenceServiceTest {
    private static final String EMPTY = "{\"status\":\"013\",\"message\":\"no data\"}";
    private final DartCorpCodeMapper corpMapper = mock(DartCorpCodeMapper.class);
    private final RestTemplate client = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nanos = new AtomicLong();
    private Clock clock = Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledAndMissingKeyDoNotLoadMapperOrCallProvider() {
        assertThat(service(false, "fixture").fetchEvidence(List.of("005930")).status()).isEqualTo("DISABLED");
        assertThat(service(true, "").fetchEvidence(List.of("005930")).status()).isEqualTo("MISSING_KEY");
        verifyNoInteractions(corpMapper);
        server.verify();
    }

    @Test
    void structuredBuybackBondAndRightsFactsKeepReceiptsUnitsAndActualFilingDate() throws Exception {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        expect("tsstkAqDecsn.json", structured("20261011000001", Map.of("aqpln_prc_ostk", "1,000,000", "aqpln_stk_ostk", "100")));
        expect("cvbdIsDecsn.json", structured("20261011000002", Map.of("bd_fta", "2,000,000", "cv_prc", "10,000", "cvisstk_tisstk_vs", "2.50")));
        expect("piicDecsn.json", structured("20261011000003", Map.of("nstk_ostk_cnt", "200", "fdpp_op", "3,000,000", "ic_mthn", "주주배정")));
        expect("list.json", listing(List.of(
            row("20261011000001", "자기주식 취득 결정", ""),
            row("20261011000002", "전환사채권 발행결정", "정"),
            row("20261011000003", "[기재정정] 유상증자 결정", ""))));

        var snapshot = service(true, "fixture").fetchEvidence(List.of("005930"));

        assertThat(snapshot.status()).isEqualTo("COMPLETE");
        assertThat(snapshot.capturedAt()).isEqualTo("2026-10-11T03:00:00Z");
        var stock = snapshot.stocks().getFirst();
        assertThat(stock.stockCode()).isEqualTo("005930");
        assertThat(stock.corpCode()).isEqualTo("00126380");
        assertThat(stock.events()).hasSize(3);
        assertThat(stock.events().getFirst().facts()).containsEntry("aqpln_prc_ostk", "1,000,000");
        assertThat(stock.events().getFirst().filingDate()).isEqualTo("2026-10-11");
        assertThat(stock.events().getFirst().sourceUrl()).endsWith("rcpNo=20261011000001");
        assertThat(stock.events().get(1).amendmentStatus()).isEqualTo("AMENDED");
        assertThat(stock.events().get(2).amendmentStatus()).isEqualTo("AMENDED");
        server.verify();
    }

    @Test
    void healthyEmptyIsCachedForFifteenMinutes() {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        emptyCalls(4);
        var service = service(true, "fixture");
        assertThat(service.fetchEvidence(List.of("005930")).status()).isEqualTo("EMPTY");
        assertThat(service.fetchEvidence(List.of("005930")).status()).isEqualTo("EMPTY");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"010", "011", "012", "020"})
    void authenticationAndQuotaErrorsRemainErrorsAndAreNotCached(String status) {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        for (int i = 0; i < 2; i++) expect("tsstkAqDecsn.json", "{\"status\":\"" + status + "\"}");
        var service = service(true, "fixture");
        assertThat(service.fetchEvidence(List.of("005930")).status()).isEqualTo("ERROR");
        assertThat(service.fetchEvidence(List.of("005930")).status()).isEqualTo("ERROR");
        server.verify();
    }

    @Test
    void malformedCorpCodeDoesNotReachTheProvider() {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("0012BAD");
        var snapshot = service(true, "fixture").fetchEvidence(List.of("005930"));
        assertThat(snapshot.status()).isEqualTo("ERROR");
        assertThat(snapshot.stocks().getFirst().issues()).contains("INVALID_CORP_CODE");
        server.verify();
    }

    @Test
    void noMoreThanFiveDistinctSelectedStocksAreRequested() {
        when(corpMapper.getCachedCorpCode(anyString())).thenReturn("00126380");
        emptyCalls(20);
        var snapshot = service(true, "fixture").fetchEvidence(List.of(
            "005930", "005930", "000660", "035420", "051910", "005380", "068270"));
        assertThat(snapshot.stocks()).extracting(DartEventEvidenceService.StockEvidence::stockCode)
            .containsExactly("005930", "000660", "035420", "051910", "005380");
        verify(corpMapper, never()).getCachedCorpCode("068270");
        server.verify();
    }

    @Test
    void monotonicDeadlineStopsFurtherRequestsWithoutCachingHealthyEmpty() {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        server.expect(ExpectedCount.times(2), requestTo(containsString("opendart.fss.or.kr/api/")))
            .andRespond(request -> {
                nanos.addAndGet(6_000_000_000L);
                return withSuccess(EMPTY, MediaType.APPLICATION_JSON).createResponse(request);
            });
        var snapshot = service(true, "fixture").fetchEvidence(List.of("005930"));
        assertThat(snapshot.status()).isEqualTo("ERROR");
        assertThat(snapshot.stocks().getFirst().issues()).contains("BUDGET_EXHAUSTED");
        server.verify();
    }

    @Test
    void interruptedBatchDoesNotStartProviderWork() {
        try {
            Thread.currentThread().interrupt();
            assertThat(service(true, "fixture").fetchEvidence(List.of("005930")).status()).isEqualTo("ERROR");
            verifyNoInteractions(corpMapper);
            server.verify();
        } finally { Thread.interrupted(); }
    }

    @Test
    void foreignReceiptIdentityAndMalformedAmountsNeverBecomeVerifiedFacts() throws Exception {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        expect("tsstkAqDecsn.json", structured("20261011000001", Map.of("corp_code", "00999999", "aqpln_prc_ostk", "1,000,000")));
        expect("cvbdIsDecsn.json", structured("bad", Map.of("bd_fta", "2,000,000")));
        expect("piicDecsn.json", structured("20261011000003", Map.of("fdpp_op", "NaN")));
        expect("list.json", EMPTY);
        var snapshot = service(true, "fixture").fetchEvidence(List.of("005930"));
        assertThat(snapshot.status()).isIn("PARTIAL", "ERROR");
        assertThat(snapshot.stocks().getFirst().events()).allSatisfy(event -> {
            assertThat(event.receiptNo()).matches("[0-9]{14}");
            assertThat(event.facts().values()).doesNotContain("NaN");
        });
        assertThat(snapshot.stocks().getFirst().issues()).isNotEmpty();
        server.verify();
    }

    @Test
    void futureFilingDatesCannotBecomeObservedEvents() throws Exception {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        expect("tsstkAqDecsn.json", structured("20261011000001", Map.of("aqpln_prc_ostk", "1,000,000")));
        expect("cvbdIsDecsn.json", EMPTY);
        expect("piicDecsn.json", EMPTY);
        var future = new java.util.LinkedHashMap<>(row("20261011000001", "자기주식 취득 결정", ""));
        future.put("rcept_dt", "20261012");
        expect("list.json", listing(List.of(future)));
        var snapshot = service(true, "fixture").fetchEvidence(List.of("005930"));
        assertThat(snapshot.status()).isIn("PARTIAL", "ERROR");
        assertThat(snapshot.stocks().getFirst().events()).isEmpty();
        server.verify();
    }

    @Test
    void selectedOriginalDocumentsAreBoundedAndTitlesDoNotProveNumericFacts() throws Exception {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        for (String endpoint : List.of("tsstkAqDecsn.json", "cvbdIsDecsn.json", "piicDecsn.json")) expect(endpoint, EMPTY);
        expect("list.json", listing(List.of(
            row("20261011000004", "[기재정정] 단일판매ㆍ공급계약 100억원", "정"),
            row("20261011000005", "주식등의대량보유상황보고서 5.21%", ""),
            row("20261011000006", "단일판매ㆍ공급계약", ""))));
        server.expect(ExpectedCount.times(2), requestTo(containsString("/document.xml?")))
            .andRespond(withSuccess(DartOriginalDocumentParserTest.zip("report.xml", "<DOCUMENT/>"), MediaType.APPLICATION_OCTET_STREAM));

        var snapshot = service(true, "fixture").fetchEvidence(List.of("005930"));

        assertThat(snapshot.stocks().getFirst().events()).hasSize(2).allSatisfy(event -> {
            assertThat(event.facts()).doesNotContainKeys("contractAmountWon", "ownershipPct");
            assertThat(event.unknownFields()).isNotEmpty();
            assertThat(event.sourceUrl()).contains("rcpNo=");
        });
        assertThat(snapshot.stocks().getFirst().events().getFirst().amendmentStatus()).isEqualTo("AMENDED");
        server.verify();
    }

    @Test
    void formattedFactsIncludeUnitsSourceAndAmendmentWarning() throws Exception {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        expect("tsstkAqDecsn.json", structured("20261011000001", Map.of("aqpln_prc_ostk", "1,000,000")));
        expect("cvbdIsDecsn.json", EMPTY); expect("piicDecsn.json", EMPTY);
        expect("list.json", listing(List.of(row("20261011000001", "[기재정정] 자기주식 취득 결정", ""))));

        String formatted = service(true, "fixture").fetchFormattedEvidence(List.of("005930"));

        assertThat(formatted).contains("1,000,000", "원", "2026-10-11", "2026-10-11T03:00:00Z",
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261011000001", "AMENDED", "정정", "점수");
        server.verify();
    }

    @Test
    void formattedEmptyStatesTheActualBoundedInspectedCoverage() {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        emptyCalls(4);
        String formatted = service(true, "fixture").fetchFormattedEvidence(List.of("005930"));
        assertThat(formatted).contains("30일", "목록 1페이지", "최대 20건", "원문 최대 2건", "EMPTY", "조회 범위");
        server.verify();
    }

    @Test
    void additionalUninspectedDisclosurePagesRemainPartialAndAreNotCached() {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        for (int attempt = 0; attempt < 2; attempt++) {
            for (String endpoint : List.of("tsstkAqDecsn.json", "cvbdIsDecsn.json", "piicDecsn.json")) expect(endpoint, EMPTY);
            expect("list.json", "{\"status\":\"000\",\"total_page\":2,\"total_count\":21,\"list\":[]}");
        }
        var adapter = service(true, "fixture");
        for (int attempt = 0; attempt < 2; attempt++) {
            var snapshot = adapter.fetchEvidence(List.of("005930"));
            assertThat(snapshot.status()).isEqualTo("PARTIAL");
            assertThat(snapshot.stocks().getFirst().issues()).contains("DISCLOSURE_PAGE_LIMIT");
        }
        server.verify();
    }

    @Test
    void healthyCacheExpiresAndDoesNotRefreshItsOriginalCaptureTime() {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        emptyCalls(8);
        var instant = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return instant.get(); }
        };
        var service = service(true, "fixture");
        var first = service.fetchEvidence(List.of("005930"));
        instant.set(instant.get().plusSeconds(60));
        assertThat(service.fetchEvidence(List.of("005930")).stocks().getFirst().capturedAt())
            .isEqualTo(first.stocks().getFirst().capturedAt());
        instant.set(instant.get().plusSeconds(15 * 60));
        assertThat(service.fetchEvidence(List.of("005930")).stocks().getFirst().capturedAt())
            .isNotEqualTo(first.stocks().getFirst().capturedAt());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "{\"status\":\"000\"}"})
    void malformedSuccessfulProviderPayloadIsAnError(String body) {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        expect("tsstkAqDecsn.json", body);
        assertThat(service(true, "fixture").fetchEvidence(List.of("005930")).status()).isEqualTo("ERROR");
        server.verify();
    }

    @Test
    void oversizedJsonBodyIsRejectedBeforeParsing() {
        when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
        expect("tsstkAqDecsn.json", "x".repeat(65_537));
        assertThat(service(true, "fixture").fetchEvidence(List.of("005930")).status()).isEqualTo("ERROR");
        server.verify();
    }

    private DartEventEvidenceService service(boolean enabled, String key) {
        return new DartEventEvidenceService(new DartProperties(key), corpMapper, client, mapper,
            new DartEventEvidenceService.Options(enabled), clock, nanos::get);
    }
    private void expect(String endpoint, String body) {
        server.expect(requestTo(containsString("/" + endpoint + "?")))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
    private void emptyCalls(int times) {
        server.expect(ExpectedCount.times(times), requestTo(containsString("opendart.fss.or.kr/api/")))
            .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
    }
    private String structured(String receipt, Map<String, String> facts) throws Exception {
        var row = new java.util.LinkedHashMap<String, String>();
        row.put("rcept_no", receipt); row.put("corp_code", "00126380"); row.put("corp_name", "Fixture");
        row.putAll(facts);
        return mapper.writeValueAsString(Map.of("status", "000", "list", List.of(row)));
    }
    private String listing(List<Map<String, String>> rows) throws Exception {
        return mapper.writeValueAsString(Map.of("status", "000", "list", rows));
    }
    private static Map<String, String> row(String receipt, String title, String remarks) {
        return Map.of("corp_code", "00126380", "stock_code", "005930", "corp_name", "Fixture",
            "rcept_no", receipt, "rcept_dt", "20261011", "report_nm", title, "rm", remarks);
    }
}
