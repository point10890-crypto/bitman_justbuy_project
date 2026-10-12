package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.bitman.justbuy.config.DartFinancialEvidenceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DartFinancialEvidenceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate client = new DartFinancialEvidenceConfig().dartFinancialEvidenceRestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
    private final DartCorpCodeMapper corpMapper = mock(DartCorpCodeMapper.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-12T04:00:00Z"));
    private final AtomicLong nanos = new AtomicLong();
    private final DartFinancialEvidenceService service = new DartFinancialEvidenceService(
        new DartProperties("unit-test-key"), corpMapper, client, mapper, clock, nanos::get);

    @AfterEach void stopWorker() { service.close(); }

    @Test
    void quarterRatiosUseQuarterAmountsWhileCashFlowKeepsItsYearToDatePeriod() {
        mapped(); expect("CFS", financial());
        var evidence = only(service.fetchEvidence(List.of("005930")));
        assertThat(evidence.status()).isEqualTo("COMPLETE");
        var facts = evidence.facts();
        assertThat(facts.revenue()).isEqualByComparingTo("1000");
        assertThat(facts.previousRevenue()).isEqualByComparingTo("800");
        assertThat(facts.revenueYoYPct()).isEqualByComparingTo("25");
        assertThat(facts.operatingMarginPct()).isEqualByComparingTo("10");
        assertThat(facts.debtRatioPct()).isEqualByComparingTo("50");
        assertThat(facts.operatingCashFlow()).isEqualByComparingTo("700");
        assertThat(facts.cashFlowPeriod()).contains("누적");
        assertThat(facts.period()).contains("3개월");
        assertThat(facts.fsDiv()).isEqualTo("CFS");
        assertThat(facts.filingDate()).isNull();
        assertThat(facts.selectionBasis()).contains("UNKNOWN");
        assertThat(service.formatEvidence(new DartFinancialEvidenceService.EvidenceSnapshot(
            "COMPLETE", clock.instant(), List.of(evidence))))
            .contains("700", "KRW", "2026", "3개월", "누적", "unknown", "rcpNo=20261010000123")
            .doesNotContain("ROE:");
        server.verify();
    }

    @Test
    void requestBoundOptionalIdentityIsAcceptedButNeverOverwritesAProvidedConflict() {
        mapped(); var body = financial();
        body.withArray("list").forEach(row -> ((ObjectNode) row).remove(List.of("stock_code", "fs_div")));
        expect("CFS", body);
        assertThat(only(service.fetchEvidence(List.of("005930"))).facts().fsDiv()).isEqualTo("CFS");
    }

    @ParameterizedTest
    @ValueSource(strings={"-"," - "})
    void providerTotalAccountDashIsAcceptedWhileDimensionalRowsCannotReplaceTotals(String detail) {
        mapped();var body=financial();
        body.withArray("list").forEach(row->{
            var item=(ObjectNode)row;item.put("account_detail",detail);
            item.remove(List.of("stock_code","fs_div"));
            if("IS".equals(item.path("sj_div").asText()))item.put("sj_div","CIS");
        });
        body.withArray("list").add(row("SCE","ifrs-full_Equity","999999","999999").put("account_detail","자본구성요소 [member]"));
        body.withArray("list").add(row("IS","ifrs-full_Revenue","999999","999999").put("account_detail","사업부문 [member]"));
        expect("CFS",body);
        var stock=only(service.fetchEvidence(List.of("005930")));
        assertThat(stock.status()).isEqualTo("COMPLETE");
        assertThat(stock.facts().revenue()).isEqualByComparingTo("1000");
        assertThat(stock.facts().equity()).isEqualByComparingTo("2000");
        assertThat(stock.facts().operatingCashFlow()).isEqualByComparingTo("700");
        assertThat(stock.facts().selectionBasis()).endsWith("INCOME_CIS");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"corp_code,99999999", "stock_code,000660", "bsns_year,2025", "reprt_code,11012", "fs_div,OFS", "currency,USD", "rcept_no,broken", "rcept_no,20271010000123"})
    void contradictoryIdentityOrCurrencyPreventsAnyNumbersFromBeingUsed(String field, String value) {
        mapped(); var body = financial(); ((ObjectNode) body.withArray("list").get(0)).put(field, value);
        expect("CFS", body);
        var evidence = only(service.fetchEvidence(List.of("005930")));
        assertThat(evidence.facts()).isNull();
        assertThat(evidence.status()).isEqualTo("UNAVAILABLE");
        assertThat(service.formatEvidence(new DartFinancialEvidenceService.EvidenceSnapshot(
            "UNAVAILABLE", clock.instant(), List.of(evidence)))).doesNotContain("매출액=1000");
        server.verify();
    }

    @Test
    void wrongStatementAndCustomNameCannotInventStandardFinancialAccounts() {
        mapped(); var body = financial();
        ((ObjectNode) body.withArray("list").get(0)).put("sj_div", "BS");
        ((ObjectNode) body.withArray("list").get(1)).put("account_id", "-표준계정코드 미사용-");
        expect("CFS", body);
        var facts = only(service.fetchEvidence(List.of("005930"))).facts();
        assertThat(facts.revenue()).isNull();
        assertThat(facts.operatingProfit()).isNull();
        assertThat(facts.operatingMarginPct()).isNull();
    }

    @Test
    void duplicateConflictingAccountIsUnknownRatherThanLastRowWins() {
        mapped(); var body = financial();
        var duplicate = body.withArray("list").get(0).deepCopy();
        ((ObjectNode) duplicate).put("thstrm_amount", "99000");
        body.withArray("list").add(duplicate); expect("CFS", body);
        var evidence = only(service.fetchEvidence(List.of("005930")));
        assertThat(evidence.facts().revenue()).isNull();
        assertThat(evidence.facts().revenueYoYPct()).isNull();
        assertThat(evidence.status()).isEqualTo("PARTIAL");
    }

    @Test
    void ofsfallbackNeverMixesItsNumbersWithUnavailableConsolidatedAccounts() {
        mapped(); expect("CFS", mapper.createObjectNode().put("status", "013"));
        var body = financial(); body.withArray("list").forEach(row -> ((ObjectNode) row).put("fs_div", "OFS"));
        expect("OFS", body);
        var facts = only(service.fetchEvidence(List.of("005930"))).facts();
        assertThat(facts.fsDiv()).isEqualTo("OFS");
        assertThat(facts.revenueYoYPct()).isEqualByComparingTo("25");
        server.verify();
    }

    @Test
    void missingQuarterComparatorsDoNotUseCumulativeValuesOrZeroDefaults() {
        mapped(); var body = financial();
        ((ObjectNode) body.withArray("list").get(0)).remove("frmtrm_q_amount");
        ((ObjectNode) body.withArray("list").get(1)).put("thstrm_amount", "-");
        expect("CFS", body);
        var facts = only(service.fetchEvidence(List.of("005930"))).facts();
        assertThat(facts.previousRevenue()).isNull();
        assertThat(facts.revenueYoYPct()).isNull();
        assertThat(facts.operatingProfit()).isNull();
        assertThat(facts.operatingMarginPct()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"0", "-100"})
    void zeroOrNegativeEquityHasRiskAndCannotProduceDebtRatio(String equity) {
        mapped(); var body = financial(); ((ObjectNode) body.withArray("list").get(4)).put("thstrm_amount", equity);
        expect("CFS", body); var evidence = only(service.fetchEvidence(List.of("005930")));
        assertThat(evidence.facts().debtRatioPct()).isNull();
        assertThat(evidence.facts().risks()).contains("NON_POSITIVE_EQUITY");
        var score = new FinancialScorer(null).scoreFromEvidence(evidence);
        assertThat(score.score()).isZero();
        assertThat(score.raw()).containsKey("risks").doesNotContainKey("debtRatioPct").doesNotContainKey("roePct");
    }

    @Test
    void positiveCachePreservesEvidenceTimeAndSurvivesARepeatedSubsetRead() {
        mapped(); expect("CFS", financial());
        var first = only(service.fetchEvidence(List.of("005930", "005930")));
        clock.now = clock.now.plusSeconds(3600);
        var second = only(service.fetchEvidence(List.of("005930")));
        assertThat(second).isEqualTo(first);
        assertThat(second.capturedAt()).isEqualTo(Instant.parse("2026-10-12T04:00:00Z"));
        server.verify();
    }

    @Test
    void expiredPositiveAndNegativeEntriesAreRetriedInsteadOfClaimedCurrent() {
        mapped(); expect("CFS", financial());
        var first = only(service.fetchEvidence(List.of("005930"))); server.verify(); server.reset();
        clock.now = clock.now.plusSeconds(24 * 3600 + 1);
        expect("CFS", financial());
        var refreshed = only(service.fetchEvidence(List.of("005930")));
        assertThat(refreshed.capturedAt()).isAfter(first.capturedAt()); server.verify(); server.reset();
        when(corpMapper.getCachedCorpCode("000660")).thenReturn("00164779");
        expect("CFS", mapper.createObjectNode().put("status", "900"));
        var unavailable = only(service.fetchEvidence(List.of("000660")));
        assertThat(unavailable.facts()).isNull(); server.verify(); server.reset();
        assertThat(only(service.fetchEvidence(List.of("000660")))).isEqualTo(unavailable);
        clock.now = clock.now.plusSeconds(31);
        var body = financial(); body.withArray("list").forEach(row -> ((ObjectNode) row).put("stock_code", "000660").put("corp_code", "00164779"));
        expect("CFS", body);
        assertThat(only(service.fetchEvidence(List.of("000660"))).facts()).isNotNull(); server.verify();
    }

    @Test
    void boundedInputRejectsMalformedCodesDeduplicatesAndNeverCollectsASixthStock() {
        var codes = List.of("005930", "000660", "035420", "035720", "005380");
        for (int i = 0; i < codes.size(); i++) {
            var corp = "0012638" + i; when(corpMapper.getCachedCorpCode(codes.get(i))).thenReturn(corp);
            var body = financial(); String code = codes.get(i);
            body.withArray("list").forEach(row -> ((ObjectNode) row).put("corp_code", corp).put("stock_code", code));
            expect("CFS", body);
        }
        var result = service.fetchEvidence(List.of("bad", "005930", "005930", "000660", "035420", "035720", "005380", "006400"));
        assertThat(result.stocks()).extracting(DartFinancialEvidenceService.StockEvidence::stockCode).containsExactlyElementsOf(codes);
        assertThat(result.stocks()).allSatisfy(stock -> assertThat(stock.facts()).isNotNull());
        server.verify();
    }

    @Test
    void missingCachedCorpMappingNeverDownloadsOriginalMapping() {
        var evidence = only(service.fetchEvidence(List.of("005930")));
        assertThat(evidence.facts()).isNull();
        assertThat(evidence.issues()).contains("MISSING_CORP_MAPPING");
        verify(corpMapper, never()).getCorpCode(anyString());
        verify(corpMapper, never()).isReady();
    }

    @Test
    void quotaFailureStopsTheBatchAndAppliesASharedCooldown() {
        mapped(); when(corpMapper.getCachedCorpCode("000660")).thenReturn("00164779");
        expect("CFS", mapper.createObjectNode().put("status", "020"));
        var first = service.fetchEvidence(List.of("005930", "000660"));
        assertThat(first.stocks()).hasSize(2).allSatisfy(stock -> assertThat(stock.facts()).isNull());
        assertThat(only(service.fetchEvidence(List.of("000660"))).issues()).contains("PROVIDER_COOLDOWN");
        server.verify();
    }

    @Test
    void batchDeadlineRejectsLateTransportDataAndPreventsFollowingRequests() {
        mapped(); when(corpMapper.getCachedCorpCode("000660")).thenReturn("00164779");
        server.expect(queryParam("fs_div", "CFS")).andRespond(request -> {
            nanos.set(11_000_000_000L);
            return withSuccess(financial().toString(), MediaType.APPLICATION_JSON).createResponse(request);
        });
        var result = service.fetchEvidence(List.of("005930", "000660"));
        assertThat(result.stocks()).hasSize(2).allSatisfy(stock -> assertThat(stock.facts()).isNull());
        assertThat(result.stocks()).allSatisfy(stock -> assertThat(stock.issues()).contains("BUDGET_EXCEEDED"));
        server.verify();
    }

    @Test
    void concurrentReadsShareOneBoundedRequestAndOneCacheEntry() throws Exception {
        mapped(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.expect(queryParam("fs_div", "CFS")).andRespond(request -> {
            entered.countDown();
            try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return withSuccess(financial().toString(), MediaType.APPLICATION_JSON).createResponse(request);
        });
        var first = CompletableFuture.supplyAsync(() -> service.fetchEvidence(List.of("005930")));
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> service.fetchEvidence(List.of("005930")));
        release.countDown();
        assertThat(only(first.get(3, TimeUnit.SECONDS)).facts()).isNotNull();
        assertThat(only(second.get(3, TimeUnit.SECONDS)).facts()).isNotNull();
        server.verify();
    }

    @Test
    void structuredScoringUsesValidatedFactsAndNeverRequeriesProvider() {
        mapped(); expect("CFS", financial()); var evidence = only(service.fetchEvidence(List.of("005930")));
        var score = new FinancialScorer(null).scoreFromEvidence(evidence);
        assertThat(score.score()).isEqualTo(85);
        assertThat(score.raw()).containsKeys("source", "reportCode", "fsDiv", "currency", "period", "risks", "industryComparability");
        assertThat(score.raw()).doesNotContainKeys("positiveDisclosure", "negativeDisclosure", "roePct");
        server.verify();
    }

    @Test
    void debtOnlyPartialEvidenceExplainsItsMetricAndIndustryComparisonLimit() {
        mapped(); var body = financial();
        body.withArray("list").removeAll()
            .add(row("BS", "ifrs-full_Equity", "2000", "1800"))
            .add(row("BS", "ifrs-full_Liabilities", "1000", "900"));
        expect("CFS", body); var evidence = only(service.fetchEvidence(List.of("005930")));
        assertThat(evidence.status()).isEqualTo("PARTIAL");
        var score = new FinancialScorer(null).scoreFromEvidence(evidence);
        assertThat(score.score()).isEqualTo(45);
        assertThat(score.summary()).contains("부채비율 50%", "부분 근거", "산업 비교 미확인");
        assertThat(score.raw()).containsEntry("status", "PARTIAL").containsEntry("industryComparability", "UNKNOWN_GENERIC_THRESHOLDS");
        assertThat(score.raw()).doesNotContainKeys("opMarginPct", "revenueYoYPct", "operatingCashFlow", "roePct");
        server.verify();
    }

    @Test
    void slowlyArrivingBodyChecksTheWholeBatchBudgetBetweenEveryChunk() {
        mapped(); AtomicInteger reads = new AtomicInteger();
        server.expect(queryParam("fs_div", "CFS")).andRespond(request ->
            new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
                @Override public InputStream getBody() {
                    return new InputStream() {
                        @Override public int read() { throw new AssertionError("must read bounded chunks"); }
                        @Override public int read(byte[] bytes, int offset, int length) {
                            assertThat(length).isLessThanOrEqualTo(8192);
                            reads.incrementAndGet(); nanos.addAndGet(4_000_000_000L);
                            bytes[offset] = ' '; return 1;
                        }
                    };
                }
            });
        var evidence = only(service.fetchEvidence(List.of("005930")));
        assertThat(evidence.facts()).isNull();
        assertThat(evidence.issues()).contains("BUDGET_EXCEEDED");
        assertThat(reads).hasValue(3);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"429", "401", "403"})
    void httpQuotaOrAuthFailureAppliesCooldownBeforeAccessingTheErrorBody(int status) {
        mapped(); when(corpMapper.getCachedCorpCode("000660")).thenReturn("00164779");
        AtomicInteger bodyAccesses = new AtomicInteger();
        server.expect(queryParam("fs_div", "CFS")).andRespond(request ->
            new MockClientHttpResponse(new byte[0], HttpStatus.valueOf(status)) {
                @Override public InputStream getBody() {
                    bodyAccesses.incrementAndGet();
                    return new java.io.ByteArrayInputStream("provider error".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                // Match production's disconnect-only close instead of MockClientHttpResponse's getBody().close().
                @Override public void close() {}
            });
        assertThat(only(service.fetchEvidence(List.of("005930"))).facts()).isNull();
        assertThat(only(service.fetchEvidence(List.of("000660"))).issues()).contains("PROVIDER_COOLDOWN");
        assertThat(bodyAccesses).hasValue(0);
        server.verify();
    }

    @Test
    void hardCallerBudgetCancelsTransportAndCannotGrowTheWorkerOrWaitingQueue() throws Exception {
        mapped(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1); var completed = new CountDownLatch(1);
        AtomicInteger transports = new AtomicInteger();
        var bounded = new DartFinancialEvidenceService(new DartProperties("unit-test-key"), corpMapper,
            client, mapper, clock, nanos::get, TimeUnit.MILLISECONDS.toNanos(150));
        try {
            server.expect(queryParam("fs_div", "CFS")).andRespond(request -> {
                transports.incrementAndGet(); entered.countDown();
                try {
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException error) {
                        interrupted.countDown();
                        // Model a transport that does not immediately honor cancellation.
                        release.await(2, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { completed.countDown(); }
                return withSuccess(financial().toString(), MediaType.APPLICATION_JSON).createResponse(request);
            });
            long started = System.nanoTime();
            var first = only(bounded.fetchEvidence(List.of("005930")));
            assertThat(entered.getCount()).isZero();
            assertThat(first.issues()).contains("BUDGET_EXCEEDED");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 3; i++) {
                assertThat(only(bounded.fetchEvidence(List.of("005930"))).facts()).isNull();
                var worker = (ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(bounded, "transportExecutor");
                assertThat(worker.getLargestPoolSize()).isEqualTo(1);
                assertThat(worker.getQueue()).isEmpty(); // cancelled queued work is removed immediately
                assertThat(worker.getQueue().remainingCapacity()).isEqualTo(1);
            }
            assertThat(transports).hasValue(1);
        } finally {
            release.countDown(); assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue(); bounded.close();
        }
        server.verify();
    }

    @Test
    void interruptingTheCallerCancelsTheOnlyTransportWithoutStartingAnotherRequest() throws Exception {
        mapped(); var entered = new CountDownLatch(1); var workerInterrupted = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<DartFinancialEvidenceService.EvidenceSnapshot>();
        server.expect(queryParam("fs_div", "CFS")).andRespond(request -> {
            entered.countDown();
            try { new CountDownLatch(1).await(2, TimeUnit.SECONDS); }
            catch (InterruptedException error) { workerInterrupted.countDown(); Thread.currentThread().interrupt(); }
            return withSuccess(financial().toString(), MediaType.APPLICATION_JSON).createResponse(request);
        });
        Thread caller = new Thread(() -> result.set(service.fetchEvidence(List.of("005930"))));
        caller.start(); assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); caller.interrupt(); caller.join(1000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(only(result.get()).issues()).contains("INTERRUPTED");
        assertThat(workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        server.verify();
    }

    private void mapped() { when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380"); }
    private void expect(String fsDiv, ObjectNode response) {
        server.expect(request -> assertThat(request.getURI().getPath()).endsWith("/fnlttSinglAcntAll.json"))
            .andExpect(queryParam("bsns_year", "2026")).andExpect(queryParam("reprt_code", "11014"))
            .andExpect(queryParam("fs_div", fsDiv)).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
    }
    private static DartFinancialEvidenceService.StockEvidence only(DartFinancialEvidenceService.EvidenceSnapshot result) {
        assertThat(result.stocks()).hasSize(1); return result.stocks().getFirst();
    }
    private ObjectNode financial() {
        var root = mapper.createObjectNode().put("status", "000"); ArrayNode rows = root.putArray("list");
        rows.add(row("IS", "ifrs-full_Revenue", "1000", "800").put("thstrm_add_amount", "9000").put("frmtrm_add_amount", "6000"));
        rows.add(row("IS", "dart_OperatingIncomeLoss", "100", "90").put("thstrm_add_amount", "800"));
        rows.add(row("IS", "ifrs-full_ProfitLoss", "80", "60"));
        rows.add(row("CF", "ifrs-full_CashFlowsFromUsedInOperatingActivities", "700", "500"));
        rows.add(row("BS", "ifrs-full_Equity", "2000", "1800"));
        rows.add(row("BS", "ifrs-full_Liabilities", "1000", "900"));
        return root;
    }
    private ObjectNode row(String statement, String account, String amount, String previous) {
        return mapper.createObjectNode().put("corp_code", "00126380").put("stock_code", "005930")
            .put("bsns_year", "2026").put("reprt_code", "11014").put("fs_div", "CFS")
            .put("rcept_no", "20261010000123").put("sj_div", statement).put("account_id", account)
            .put("account_nm", account).put("account_detail", "").put("currency", "KRW")
            .put("thstrm_nm", "제58기 3분기").put("thstrm_amount", amount)
            .put("frmtrm_q_nm", "제57기 3분기").put("frmtrm_q_amount", previous).put("frmtrm_amount", previous);
    }
    private static class MutableClock extends Clock {
        Instant now; MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
