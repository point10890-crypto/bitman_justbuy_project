package com.bitman.justbuy.research.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.service.DartFinancialEvidenceService;
import com.bitman.justbuy.service.KisResearchEvidenceService;
import com.bitman.justbuy.research.ResearchOutcomeEvaluator;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EventResearchPipelineTest {
    @TempDir Path directory;
    private final Instant now=Instant.parse("2026-10-14T08:00:00Z");
    private EventResearchPipeline pipeline(java.util.function.Supplier<DartSupplyContractSource.Snapshot> source) {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var ports=new EventResearchPipeline.Ports(source,()->new KindBuybackSource.Snapshot("UNAVAILABLE",now,List.of(),List.of("TEST_SOURCE_MISSING")),
            codes->null,codes->null,codes->Map.of(),(code,from,to)->List.of());
        return new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),new EventResearchStore(directory,mapper),mapper,Clock.fixed(now,ZoneOffset.UTC),true);
    }
    @Test void repeatedGetNeverCollectsAndConcurrentRefreshHasOneWorker() throws Exception {
        var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pipeline=pipeline(()->{calls.incrementAndGet();entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new DartSupplyContractSource.Snapshot("EMPTY",now,List.of(),List.of());})) {
            assertEquals("WAITING",pipeline.snapshot().path("status").asText());pipeline.snapshot();assertEquals(0,calls.get());
            assertEquals(true,pipeline.requestRefresh().get("accepted"));assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(false,pipeline.requestRefresh().get("accepted"));release.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(Boolean.TRUE.equals(pipeline.status().get("running"))&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals(1,calls.get());assertEquals("EMPTY",pipeline.snapshot().path("status").asText());
        }
    }
    @Test void failedSourceRetainsSuccessfulPersistedSnapshotAndReportsStale() {
        var calls=new AtomicInteger();
        try(var pipeline=pipeline(()->calls.incrementAndGet()==1?new DartSupplyContractSource.Snapshot("EMPTY",now,List.of(),List.of()):new DartSupplyContractSource.Snapshot("ERROR",now,List.of(),List.of("RATE_LIMIT")))) {
            pipeline.refreshNow();assertEquals("EMPTY",pipeline.snapshot().path("status").asText());pipeline.refreshNow();
            assertTrue(pipeline.snapshot().path("stale").asBoolean());assertEquals("EMPTY",pipeline.snapshot().path("status").asText());
            assertTrue(pipeline.status().get("lastError").toString().contains("SOURCE"));
        }
    }
    @Test void rateLimitedSourceCannotErasePreviouslySavedObservations() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);
        var saved=mapper.createObjectNode().put("status","PARTIAL").put("asOf",now.toString());saved.putArray("candidates").addObject().put("caseId","a".repeat(64)).put("publishedAt",now.toString());saved.putArray("issues");saved.putObject("coverage");saved.putObject("buybacks").putArray("rows");store.save(saved);
        try(var pipeline=pipeline(()->new DartSupplyContractSource.Snapshot("RATE_LIMITED",now,List.of(),List.of("020")))) {
            pipeline.refreshNow();assertEquals(1,pipeline.snapshot().path("candidates").size());assertTrue(pipeline.snapshot().path("stale").asBoolean());
        }
    }
    @Test void archivedCaseMaturesAfterItLeavesCurrentDiscoveryWindow() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf",now.toString());var archived=snapshot.putArray("candidates").addObject().put("caseId","b".repeat(64)).put("stockCode","000660").put("publishedAt","2026-10-12T08:00:00Z").put("evaluationPublishedAt","2026-10-12T08:00:00Z");archived.putArray("outcomes").addObject().put("state","PENDING");snapshot.putArray("issues");snapshot.putObject("coverage");snapshot.putObject("buybacks").putArray("rows");store.save(snapshot);
        var ports=new EventResearchPipeline.Ports(()->new DartSupplyContractSource.Snapshot("EMPTY",now,List.of(),List.of()),()->new KindBuybackSource.Snapshot("UNAVAILABLE",now,List.of(),List.of()),codes->null,codes->null,codes->Map.of(),(code,from,to)->List.of(new com.bitman.justbuy.research.ResearchOutcomeEvaluator.SessionBar(code,LocalDate.of(2026,10,13),now,new java.math.BigDecimal("100"),new java.math.BigDecimal("105"),new java.math.BigDecimal("99"),new java.math.BigDecimal("103"),true,false,"KRX","REGULAR",com.bitman.justbuy.research.ResearchOutcomeEvaluator.Provenance.REAL,com.bitman.justbuy.research.ResearchOutcomeEvaluator.Adjustment.ADJUSTED)));
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,Clock.fixed(now,ZoneOffset.UTC),true)) {pipeline.refreshNow();assertEquals("COMPLETE",store.caseById("b".repeat(64)).orElseThrow().path("outcomes").get(0).path("state").asText());}
    }
    @Test void laterCustomerFilingFindsPreviouslyObservedSupplierWithoutInventingAdditionalContractProfit() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf",now.minusSeconds(86400).toString());
        var relation=snapshot.putArray("candidates").addObject().put("caseId","c".repeat(64)).put("stockCode","000660").put("stockName","공급사").put("customerName","확인고객").put("product","제품").put("receiptNo","20261013000123").put("sourceUrl","https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261013000123").put("firstSeenAt",now.minusSeconds(86400).toString()).put("publishedAt",now.minusSeconds(86400).toString()).put("amendmentStatus","ORIGINAL").put("origin","SUPPLIER_CONTRACT").put("evidenceText","확인고객 제품");relation.putArray("outcomes");snapshot.putArray("issues");snapshot.putObject("coverage");snapshot.putObject("buybacks").putArray("rows");store.save(snapshot);
        var filing=new DartSupplyContractSource.Filing("005930","확인고객","20261014000124","영업(잠정)실적(공정공시)",LocalDate.of(2026,10,14),now,"https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000124");
        var ports=new EventResearchPipeline.Ports(()->new DartSupplyContractSource.Snapshot("EMPTY",now,List.of(),List.of()),()->new KindBuybackSource.Snapshot("UNAVAILABLE",now,List.of(),List.of()),codes->null,codes->null,codes->Map.of(),(code,from,to)->List.of(),()->List.of(filing));
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,Clock.fixed(now,ZoneOffset.UTC),true)) {
            pipeline.refreshNow();var candidate=pipeline.snapshot().path("candidates").get(0);assertNotNull(candidate);assertEquals("000660",candidate.path("stockCode").asText());
            assertEquals("CUSTOMER_EVENT_PROPAGATION",candidate.path("origin").asText());assertTrue(candidate.path("contractDeltaWon").isNull());assertTrue(candidate.path("scenario").path("operatingProfitWon").isNull());
            assertEquals("20261014000124",candidate.path("relatedCustomerEvent").path("receiptNo").asText());
        }
    }
    @Test void disclosedIdentityRemainsObservationWithUnknownFinanceAndFutureReturns() {
        var contract=new DartSupplyContractSource.Contract("000660","공급사","확인고객","확인제품",new java.math.BigDecimal("1000000"),null,null,
            LocalDate.of(2026,10,14),LocalDate.of(2026,12,31),LocalDate.of(2026,10,14),"20261014000123","https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000123",now,"ORIGINAL","확인고객 확인제품 계약금액(원) 1000000","a".repeat(64));
        try(var pipeline=pipeline(()->new DartSupplyContractSource.Snapshot("COMPLETE",now,List.of(contract),List.of()))) {
            pipeline.refreshNow();var candidate=pipeline.snapshot().path("candidates").get(0);
            assertEquals("000660",candidate.path("stockCode").asText());assertEquals("RULE_ONLY",candidate.path("verification").path("status").asText());
            assertTrue(candidate.path("scenario").path("cashFlowWon").isNull());assertEquals("PENDING",candidate.path("outcomes").get(0).path("state").asText());
            assertTrue(candidate.path("outcomes").get(0).path("netReturnPct").isNull());
        }
    }

    @Test void repeatedCaseRefreshesCurrentMarketWithoutChangingOriginalPublicationOrScenario() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var priceReady=new java.util.concurrent.atomic.AtomicBoolean();
        var ports=ports(clock,codes->null,codes->new KisResearchEvidenceService.EvidenceBatch("PARTIAL",clock.instant(),
            List.of(market(priceReady.get()?"AVAILABLE":"UNAVAILABLE",priceReady.get()?"12000":null,clock.instant()))),(code,from,to)->List.of());
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();var before=pipeline.snapshot().path("candidates").get(0).deepCopy();
            priceReady.set(true);clock.value=now.plusSeconds(1800);pipeline.refreshNow();var after=pipeline.snapshot().path("candidates").get(0);
            assertEquals("AVAILABLE",after.path("currentMarket").path("kis").path("valuation").path("status").asText());
            assertEquals("12000",after.path("currentMarket").path("kis").path("valuation").path("currentPrice").asText());
            assertEquals(before.path("market"),after.path("market"));assertEquals(before.path("publishedAt"),after.path("publishedAt"));
            assertEquals(before.path("scenario"),after.path("scenario"));
        }
    }

    @Test void failedAiStageRetriesStoredScenarioAtOriginalCutoffAndDoesNotRerunAfterSuccess() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var calls=new AtomicInteger();var packets=new ArrayList<JsonNode>();var financeChanged=new java.util.concurrent.atomic.AtomicBoolean();
        AiAgent agent=agent(()->true,(system,user)->{
            try{var packet=mapper.readTree(user);packets.add(packet);return calls.incrementAndGet()==1?AgentResult.error("deepseek","test","provider unavailable",1):success(mapper,packet);}
            catch(Exception e){throw new IllegalStateException(e);}
        });
        var ports=ports(clock,codes->finance(financeChanged.get()?"999":"100",financeChanged.get()?"99":"10",clock.instant()),codes->null,(code,from,to)->List.of());
        try(var verifier=new EventEvidenceVerifier(agent,mapper);var pipeline=new EventResearchPipeline(ports,verifier,store,mapper,clock,true)) {
            pipeline.refreshNow();var before=pipeline.snapshot().path("candidates").get(0).deepCopy();assertEquals("RULE_ONLY",before.path("verification").path("status").asText());
            financeChanged.set(true);clock.value=now.plusSeconds(1800);pipeline.refreshNow();var after=pipeline.snapshot().path("candidates").get(0);
            assertEquals("AI_CHECKED",after.path("verification").path("status").asText());assertEquals(2,calls.get());
            assertEquals(now.toString(),packets.get(1).path("cutoff").asText());
            assertEquals("100",fact(packets.get(1),"historical:revenue").path("value").asText());
            assertEquals(before.path("scenario"),after.path("scenario"));assertEquals(before.path("publishedAt"),after.path("publishedAt"));
            pipeline.refreshNow();assertEquals(2,calls.get());
        }
    }

    @Test void unavailableAiDoesNotSpendAttemptAndProviderRetriesStopAtThreeAcrossRestart() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var available=new java.util.concurrent.atomic.AtomicBoolean();var calls=new AtomicInteger();
        AiAgent agent=agent(available::get,(system,user)->{calls.incrementAndGet();return AgentResult.error("deepseek","test","provider unavailable",1);});
        var ports=ports(clock,codes->null,codes->null,(code,from,to)->List.of());
        try(var verifier=new EventEvidenceVerifier(agent,mapper)) {
            try(var pipeline=new EventResearchPipeline(ports,verifier,store,mapper,clock,true)) {
                pipeline.refreshNow();assertEquals(0,calls.get());
                assertEquals(0,pipeline.snapshot().path("candidates").get(0).path("verification").path("providerAttempts").asInt(-1));
                available.set(true);for(int i=0;i<2;i++){clock.value=clock.value.plusSeconds(1800);pipeline.refreshNow();}
            }
            try(var restarted=new EventResearchPipeline(ports,verifier,store,mapper,clock,true)) {
                for(int i=0;i<4;i++){clock.value=clock.value.plusSeconds(1800);restarted.refreshNow();}
                assertEquals(3,calls.get());assertEquals(3,restarted.snapshot().path("candidates").get(0).path("verification").path("providerAttempts").asInt(-1));
            }
        }
    }

    @Test void paidProviderCallsStayAtTwoPerRefreshAndBudgetDeferredCaseEventuallyRetries() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);var calls=new AtomicInteger();
        AiAgent agent=agent(()->true,(system,user)->{calls.incrementAndGet();return AgentResult.error("deepseek","test","provider unavailable",1);});
        var contracts=new ArrayList<DartSupplyContractSource.Contract>();for(int i=0;i<3;i++) {
            var c=contract(now);String receipt="2026101400012"+i;
            contracts.add(new DartSupplyContractSource.Contract(c.supplierCode(),c.supplierName(),c.customerName(),c.product(),c.amountWon(),null,null,c.startDate(),c.endDate(),c.filingDate(),receipt,
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt,now,"ORIGINAL",c.evidenceText(),c.documentHash()));
        }
        var ports=new EventResearchPipeline.Ports(()->new DartSupplyContractSource.Snapshot("COMPLETE",clock.instant(),contracts,List.of()),
            ()->new KindBuybackSource.Snapshot("EMPTY",clock.instant(),List.of(),List.of()),codes->null,codes->null,codes->Map.of(),(code,from,to)->List.of());
        try(var verifier=new EventEvidenceVerifier(agent,mapper);var pipeline=new EventResearchPipeline(ports,verifier,store,mapper,clock,true)) {
            for(int i=0;i<6;i++){int before=calls.get();pipeline.refreshNow();assertTrue(calls.get()-before<=2);clock.value=clock.value.plusSeconds(1800);}
            assertEquals(9,calls.get());for(JsonNode candidate:pipeline.snapshot().path("candidates"))assertEquals(3,candidate.path("verification").path("providerAttempts").asInt());
            pipeline.refreshNow();assertEquals(9,calls.get());
        }
    }

    @Test void archivedEvaluationCursorSurvivesNewDiscoverySnapshot() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(Instant.parse("2026-10-20T08:00:00Z"));
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf",clock.instant().toString());var candidates=snapshot.putArray("candidates");
        for(int i=0;i<6;i++)candidates.addObject().put("caseId",Integer.toHexString(i+1).repeat(64)).put("stockCode","000660").put("publishedAt","2026-10-01T08:00:00Z")
            .put("evaluationPublishedAt","2026-10-01T08:00:00Z").putArray("outcomes").addObject().put("state","PENDING");
        snapshot.putArray("issues");snapshot.putObject("coverage");snapshot.putObject("buybacks").putArray("rows");store.save(snapshot);
        try(var pipeline=new EventResearchPipeline(emptyPorts(clock,(code,from,to)->bars(code,from,to,clock.instant())),new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();assertEquals(4,pipeline.snapshot().path("evaluationCursor").asInt());
            pipeline.refreshNow();assertEquals("COMPLETE",outcome(store.caseById("6".repeat(64)).orElseThrow(),1).path("state").asText());
        }
    }

    @Test void completedIndividualOutcomeSurvivesLaterPriceTransportFailure() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(Instant.parse("2026-10-20T08:00:00Z"));
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf",clock.instant().toString());
        snapshot.putArray("candidates").addObject().put("caseId","b".repeat(64)).put("stockCode","000660").put("publishedAt","2026-10-01T08:00:00Z")
            .put("evaluationPublishedAt","2026-10-01T08:00:00Z").put("evaluationPolicy","AFTER_CLOSE_NEXT_SESSION_OPEN_V1").putArray("outcomes");
        snapshot.putArray("issues");snapshot.putObject("coverage");snapshot.putObject("buybacks").putArray("rows");store.save(snapshot);
        var failure=new java.util.concurrent.atomic.AtomicBoolean();
        var ports=emptyPorts(clock,(code,from,to)->{if(failure.get())throw new IllegalStateException("transport failure");return bars(code,from,to,clock.instant());});
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();var measured=outcome(store.caseById("b".repeat(64)).orElseThrow(),1).deepCopy();assertEquals("COMPLETE",measured.path("state").asText());
            failure.set(true);clock.value=clock.value.plusSeconds(1800);pipeline.refreshNow();
            assertEquals(measured,outcome(store.caseById("b".repeat(64)).orElseThrow(),1));
        }
    }

    @Test void comparisonCursorSurvivesRefreshAndRestartSoThirdCaptureIsNotStarved() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);
        var clock=new MutableClock(Instant.parse("2026-10-20T08:00:00Z"));
        for(String date:List.of("2026-10-01","2026-10-02","2026-10-06"))store.capturePortfolio(capture(mapper,date,false));
        var ports=emptyPorts(clock,(code,from,to)->bars(code,from,to,clock.instant()));
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();assertEquals(2,pipeline.snapshot().path("comparison").path("measuredDates").asInt());
            assertEquals(2,pipeline.snapshot().path("portfolioEvaluationCursor").asInt());
        }
        try(var restarted=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            restarted.refreshNow();var third=store.portfolios().get(2);
            assertEquals("GROSS_ONLY",outcome(third,5).path("state").asText());
            assertEquals(3,restarted.snapshot().path("comparison").path("measuredDates").asInt());
        }
    }

    @Test void completedStoredFiveDayComparisonsStillContributeWhenNoCaptureIsReprocessed() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);
        var clock=new MutableClock(Instant.parse("2026-10-20T08:00:00Z"));var captured=capture(mapper,"2026-09-01",true);
        var outcomes=captured.withArray("outcomes");for(int horizon:new int[]{1,3,5,20})outcomes.addObject().put("horizonSessions",horizon).put("state","COMPLETE")
            .put("baselineGross","5").put("challengerGross","8").put("baselineNet","4.8").put("challengerNet","7.8").put("netDelta","3").putArray("reasons");
        store.capturePortfolio(captured);var barCalls=new AtomicInteger();
        try(var pipeline=new EventResearchPipeline(emptyPorts(clock,(code,from,to)->{barCalls.incrementAndGet();return List.of();}),new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();var review=pipeline.snapshot().path("comparison");
            assertEquals(0,barCalls.get());assertEquals(1,review.path("measuredDates").asInt());assertEquals(1,review.path("independentDates").asInt());
            assertEquals(0,new BigDecimal("3").compareTo(new BigDecimal(review.path("meanPairedDeltaPct").asText())));assertFalse(review.path("productionActivationAllowed").asBoolean());
            pipeline.refreshNow();assertEquals(1,pipeline.snapshot().path("comparison").path("independentDates").asInt());
        }
    }

    @Test void yearEndComparisonMeasuresCoveredOneDayButRejectsHorizonEnteringUnconfirmedYear() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);
        var clock=new MutableClock(Instant.parse("2026-12-30T08:00:00Z"));store.capturePortfolio(capture(mapper,"2026-12-29",false));
        try(var pipeline=new EventResearchPipeline(emptyPorts(clock,(code,from,to)->bars(code,from,to,clock.instant())),new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();var comparison=store.portfolios().get(0);
            assertEquals("GROSS_ONLY",outcome(comparison,1).path("state").asText());
            assertEquals("UNMEASURABLE",outcome(comparison,3).path("state").asText());
            assertEquals("OFFICIAL_CALENDAR_COVERAGE_UNCONFIRMED",outcome(comparison,3).path("reasons").get(0).asText());
        }
    }

    @Test void earlierCalculationPolicyIsPreservedButExcludedFromCurrentLearning() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);
        var clock=new MutableClock(Instant.parse("2026-10-20T08:00:00Z"));
        var legacy=capture(mapper,"2026-09-01",true).put("challengerPolicyId","event-research-v1").put("inputVersion","event-research-v1");
        for(int horizon:new int[]{1,3,5,20})legacy.withArray("outcomes").addObject().put("horizonSessions",horizon).put("state","COMPLETE")
            .put("baselineGross","5").put("challengerGross","8").put("baselineNet","4.8").put("challengerNet","7.8").put("netDelta","3").putArray("reasons");
        store.capturePortfolio(legacy);
        try(var pipeline=new EventResearchPipeline(emptyPorts(clock,(code,from,to)->{throw new AssertionError("Historic policy must not be rewritten");}),new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();var comparison=pipeline.snapshot().path("comparison");
            assertEquals(0,comparison.path("capturedDates").asInt());assertEquals(0,comparison.path("measuredDates").asInt());
            assertEquals(0,comparison.path("independentDates").asInt());assertEquals(legacy,store.portfolios().getFirst());
        }
    }

    @Test void packagingHashAndCaptureTimeChangesCannotRepublishSameFactsAfterRestart() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var current=new java.util.concurrent.atomic.AtomicReference<>(contract(now));var price=new java.util.concurrent.atomic.AtomicReference<>("12000");
        var ports=changingContractPorts(clock,current,codes->finance("100","10",clock.instant()),codes->new KisResearchEvidenceService.EvidenceBatch("AVAILABLE",clock.instant(),List.of(market("AVAILABLE",price.get(),clock.instant()))));
        JsonNode original;
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();original=pipeline.snapshot().path("candidates").get(0).deepCopy();
        }
        clock.value=clock.value.plusSeconds(1800);var c=current.get();
        current.set(new DartSupplyContractSource.Contract(c.supplierCode(),c.supplierName(),c.customerName(),c.product(),new BigDecimal("1000000.000"),c.previousAmountWon(),c.recentSalesWon(),
            c.startDate(),c.endDate(),c.filingDate(),c.receiptNo(),c.sourceUrl(),clock.instant(),c.amendmentStatus(),"\n  원문   근거  ","b".repeat(64)));price.set("13000");
        try(var restarted=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            restarted.refreshNow();var currentCase=restarted.snapshot().path("candidates").get(0);
            assertEquals(original.path("caseId"),currentCase.path("caseId"));assertEquals(original.path("publishedAt"),currentCase.path("publishedAt"));
            assertEquals(original.path("firstSeenAt"),currentCase.path("firstSeenAt"));assertEquals(original.path("scenario"),currentCase.path("scenario"));
            assertEquals(original.path("evaluationPublishedAt"),currentCase.path("evaluationPublishedAt"));assertEquals(original.path("outcomes"),currentCase.path("outcomes"));
            assertEquals(original.path("financial"),currentCase.path("financial"));assertEquals(original.path("market"),currentCase.path("market"));
            assertEquals("a".repeat(64),currentCase.path("documentHash").asText());assertEquals("13000",currentCase.path("currentMarket").path("kis").path("valuation").path("currentPrice").asText());
            assertEquals("event-research-v3",currentCase.path("policyVersion").asText());assertTrue(currentCase.path("contractFingerprint").asText().matches("[a-f0-9]{64}"));
            assertEquals(1,store.cases().size());
        }
    }

    @Test void actualContractFactOrEvidenceChangeCreatesNewCaseEvenWhenTransportHashIsSame() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var current=new java.util.concurrent.atomic.AtomicReference<>(contract(now));var ports=changingContractPorts(clock,current,codes->null,codes->null);
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();var original=pipeline.snapshot().path("candidates").get(0).deepCopy();String originalId=original.path("caseId").asText();
            clock.value=clock.value.plusSeconds(1800);var c=current.get();
            current.set(new DartSupplyContractSource.Contract(c.supplierCode(),c.supplierName(),c.customerName(),c.product(),new BigDecimal("2000000"),c.previousAmountWon(),c.recentSalesWon(),
                c.startDate(),c.endDate(),c.filingDate(),c.receiptNo(),c.sourceUrl(),clock.instant(),c.amendmentStatus(),c.evidenceText(),c.documentHash()));
            pipeline.refreshNow();var revised=pipeline.snapshot().path("candidates").get(0).deepCopy();assertNotEquals(originalId,revised.path("caseId").asText());
            assertEquals("2000000",revised.path("contractAmountWon").asText());assertEquals(original.path("publishedAt"),store.caseById(originalId).orElseThrow().path("publishedAt"));
            clock.value=clock.value.plusSeconds(1800);c=current.get();
            current.set(new DartSupplyContractSource.Contract(c.supplierCode(),c.supplierName(),c.customerName(),c.product(),c.amountWon(),c.previousAmountWon(),c.recentSalesWon(),
                c.startDate(),c.endDate(),c.filingDate(),c.receiptNo(),c.sourceUrl(),clock.instant(),c.amendmentStatus(),"원문 근거. 계약 해제 조건 추가",c.documentHash()));
            pipeline.refreshNow();assertNotEquals(revised.path("caseId"),pipeline.snapshot().path("candidates").get(0).path("caseId"));assertEquals(3,store.cases().size());
        }
    }

    @Test void differentFinancialReceiptCreatesSeparateImmutableRevision() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var current=new java.util.concurrent.atomic.AtomicReference<>(contract(now));var receipt=new java.util.concurrent.atomic.AtomicReference<>("20260814000123");
        var ports=changingContractPorts(clock,current,codes->finance("100","10",clock.instant(),receipt.get()),codes->null);
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();var original=pipeline.snapshot().path("candidates").get(0).deepCopy();
            clock.value=clock.value.plusSeconds(1800);receipt.set("20260814000124");pipeline.refreshNow();var revised=pipeline.snapshot().path("candidates").get(0);
            assertNotEquals(original.path("caseId"),revised.path("caseId"));assertEquals("20260814000124",revised.path("financial").path("facts").path("receiptNo").asText());
            assertEquals(original.path("publishedAt"),store.caseById(original.path("caseId").asText()).orElseThrow().path("publishedAt"));
            assertEquals(original.path("financial"),store.caseById(original.path("caseId").asText()).orElseThrow().path("financial"));assertEquals(2,store.cases().size());
        }
    }

    @Test void v2CasesAndComparisonsRemainSeparateFromStableIdentityPolicy() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var legacy=capture(mapper,"2026-09-01",true).put("challengerPolicyId","event-research-v2").put("inputVersion","event-research-v2");store.capturePortfolio(legacy);
        var initial=mapper.createObjectNode().put("status","PARTIAL").put("asOf",now.toString());
        var previous=initial.putArray("candidates").addObject().put("caseId","f".repeat(64)).put("stockCode","000660").put("policyVersion","event-research-v2")
            .put("publishedAt",now.minusSeconds(3600).toString()).put("firstSeenAt",now.minusSeconds(3600).toString()).put("documentHash","a".repeat(64));previous.putObject("scenario").put("status","PARTIAL");
        initial.putArray("issues");initial.putObject("coverage");initial.putObject("buybacks").putArray("rows");store.save(initial);
        var frozen=previous.deepCopy();var current=new java.util.concurrent.atomic.AtomicReference<>(contract(now));
        try(var pipeline=new EventResearchPipeline(changingContractPorts(clock,current,codes->null,codes->null),new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();assertEquals("event-research-v3",pipeline.snapshot().path("candidates").get(0).path("policyVersion").asText());
            assertEquals(0,pipeline.snapshot().path("comparison").path("capturedDates").asInt());assertEquals(legacy,store.portfolios().getFirst());
            var old=store.caseById("f".repeat(64)).orElseThrow();for(String field:List.of("caseId","policyVersion","publishedAt","firstSeenAt","documentHash","scenario"))assertEquals(frozen.path(field),old.path(field));
        }
    }

    @Test void transientFinancialNullUnavailableAndExceptionPreserveCompletedEvidenceAcrossRestarts() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var current=new java.util.concurrent.atomic.AtomicReference<>(contract(now));var phase=new AtomicInteger();var price=new java.util.concurrent.atomic.AtomicReference<>("12000");
        var ports=changingContractPorts(clock,current,codes->{
            if(phase.get()==1)return null;
            if(phase.get()==2)return new DartFinancialEvidenceService.EvidenceSnapshot("UNAVAILABLE",clock.instant(),List.of(new DartFinancialEvidenceService.StockEvidence("000660","00000001","UNAVAILABLE",clock.instant(),null,List.of("TRANSPORT_OR_PARSE_ERROR"))));
            if(phase.get()==3)throw new IllegalStateException("transport failure");
            var result=finance("100","10",clock.instant());var stock=result.stocks().getFirst();
            return new DartFinancialEvidenceService.EvidenceSnapshot("COMPLETE",clock.instant(),List.of(new DartFinancialEvidenceService.StockEvidence(stock.stockCode(),stock.corpCode(),"COMPLETE",stock.capturedAt(),stock.facts(),stock.issues())));
        },codes->new KisResearchEvidenceService.EvidenceBatch("AVAILABLE",clock.instant(),List.of(market("AVAILABLE",price.get(),clock.instant()))));
        JsonNode original;
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {pipeline.refreshNow();original=pipeline.snapshot().path("candidates").get(0).deepCopy();}
        assertEquals("COMPLETE",original.path("financial").path("status").asText());assertEquals("PARTIAL",original.path("scenario").path("status").asText());
        for(int i=1;i<=3;i++) {
            phase.set(i);clock.value=clock.value.plusSeconds(1800);price.set(Integer.toString(12000+i*1000));
            try(var restarted=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
                restarted.refreshNow();var snapshot=restarted.snapshot();var preserved=snapshot.path("candidates").get(0);
                for(String field:List.of("caseId","publishedAt","financial","scenario","market","evaluationPublishedAt","outcomes"))assertEquals(original.path(field),preserved.path(field),"Failed financial phase "+i+" must preserve "+field);
                assertEquals(price.get(),preserved.path("currentMarket").path("kis").path("valuation").path("currentPrice").asText());
                assertEquals(0,snapshot.path("coverage").path("financialReferences").asInt());assertTrue(snapshot.path("issues").toString().contains("FINANCIAL_COVERAGE_PARTIAL"));assertEquals(1,store.cases().size());
            }
        }
    }

    @Test void firstUnknownFinanceThenActualReceiptCreatesNewResearchInsteadOfRewritingUnknownCase() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var current=new java.util.concurrent.atomic.AtomicReference<>(contract(now));var available=new java.util.concurrent.atomic.AtomicBoolean();
        var ports=changingContractPorts(clock,current,codes->available.get()?finance("100","10",clock.instant()):null,codes->null);
        try(var pipeline=new EventResearchPipeline(ports,new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();var unknown=pipeline.snapshot().path("candidates").get(0).deepCopy();
            assertTrue(unknown.path("financial").isNull());clock.value=clock.value.plusSeconds(1800);available.set(true);pipeline.refreshNow();var observed=pipeline.snapshot().path("candidates").get(0);
            assertNotEquals(unknown.path("caseId"),observed.path("caseId"));assertNotEquals(unknown.path("publishedAt"),observed.path("publishedAt"));
            assertEquals("20260814000123",observed.path("financial").path("facts").path("receiptNo").asText());
            assertTrue(store.caseById(unknown.path("caseId").asText()).orElseThrow().path("financial").isNull());assertEquals(2,store.cases().size());
        }
    }

    @Test void temporaryFinancialFailureCannotInheritCompleteEvidenceFromLegacyOrDifferentContractFacts() {
        var mapper=new ObjectMapper().findAndRegisterModules();var store=new EventResearchStore(directory,mapper);var clock=new MutableClock(now);
        var current=new java.util.concurrent.atomic.AtomicReference<>(contract(now));JsonNode valid;
        try(var pipeline=new EventResearchPipeline(changingContractPorts(clock,current,codes->finance("100","10",clock.instant()),codes->null),new EventEvidenceVerifier(null,mapper),store,mapper,clock,true)) {
            pipeline.refreshNow();valid=pipeline.snapshot().path("candidates").get(0).deepCopy();
        }
        for(boolean legacy:List.of(true,false)) {
            var isolated=new EventResearchStore(directory.resolve(legacy?"legacy-only":"other-fingerprint"),mapper);
            var previous=(ObjectNode)valid.deepCopy();previous.put("caseId","e".repeat(64));
            if(legacy)previous.put("policyVersion","event-research-v2");else previous.put("contractFingerprint","d".repeat(64));
            var initial=mapper.createObjectNode().put("status","PARTIAL").put("asOf",clock.instant().toString());initial.putArray("candidates").add(previous);initial.putArray("issues");initial.putObject("coverage");initial.putObject("buybacks").putArray("rows");isolated.save(initial);
            try(var pipeline=new EventResearchPipeline(changingContractPorts(clock,current,codes->null,codes->null),new EventEvidenceVerifier(null,mapper),isolated,mapper,clock,true)) {
                pipeline.refreshNow();var candidate=pipeline.snapshot().path("candidates").get(0);
                assertNotEquals("e".repeat(64),candidate.path("caseId").asText());assertTrue(candidate.path("financial").isNull());assertTrue(candidate.path("scenario").path("operatingProfitWon").isNull());
            }
        }
    }

    private DartSupplyContractSource.Contract contract(Instant first) {
        return new DartSupplyContractSource.Contract("000660","공급사","확인고객","제품",new BigDecimal("1000000"),null,null,
            LocalDate.of(2026,10,14),LocalDate.of(2026,12,31),LocalDate.of(2026,10,14),"20261014000123","https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261014000123",first,"ORIGINAL","원문 근거","a".repeat(64));
    }
    private EventResearchPipeline.Ports ports(MutableClock clock,java.util.function.Function<List<String>,DartFinancialEvidenceService.EvidenceSnapshot> finance,
        java.util.function.Function<List<String>,KisResearchEvidenceService.EvidenceBatch> kis,EventResearchPipeline.Bars bars) {
        return new EventResearchPipeline.Ports(()->new DartSupplyContractSource.Snapshot("COMPLETE",clock.instant(),List.of(contract(now)),List.of()),
            ()->new KindBuybackSource.Snapshot("EMPTY",clock.instant(),List.of(),List.of()),finance,kis,codes->Map.of(),bars);
    }
    private EventResearchPipeline.Ports emptyPorts(MutableClock clock,EventResearchPipeline.Bars bars) {
        return new EventResearchPipeline.Ports(()->new DartSupplyContractSource.Snapshot("EMPTY",clock.instant(),List.of(),List.of()),
            ()->new KindBuybackSource.Snapshot("EMPTY",clock.instant(),List.of(),List.of()),codes->null,codes->null,codes->Map.of(),bars);
    }
    private EventResearchPipeline.Ports changingContractPorts(MutableClock clock,java.util.concurrent.atomic.AtomicReference<DartSupplyContractSource.Contract> current,
        java.util.function.Function<List<String>,DartFinancialEvidenceService.EvidenceSnapshot> finance,java.util.function.Function<List<String>,KisResearchEvidenceService.EvidenceBatch> kis) {
        return new EventResearchPipeline.Ports(()->new DartSupplyContractSource.Snapshot("COMPLETE",clock.instant(),List.of(current.get()),List.of()),
            ()->new KindBuybackSource.Snapshot("EMPTY",clock.instant(),List.of(),List.of()),finance,kis,codes->Map.of(),(code,from,to)->List.of());
    }
    private static KisResearchEvidenceService.CandidateEvidence market(String status,String price,Instant time) {
        Map<String,Object> value=new LinkedHashMap<>();value.put("status",status);value.put("currentPrice",price);value.put("fetchedAt",time.toString());value.put("source","KIS OpenAPI");
        return new KisResearchEvidenceService.CandidateEvidence("000660",Map.of(),value,Map.of(),Map.of(),Map.of(),Map.of());
    }
    private static DartFinancialEvidenceService.EvidenceSnapshot finance(String revenue,String profit,Instant known) {
        return finance(revenue,profit,known,"20260814000123");
    }
    private static DartFinancialEvidenceService.EvidenceSnapshot finance(String revenue,String profit,Instant known,String receipt) {
        var facts=new DartFinancialEvidenceService.FinancialFacts(2026,"11012","CFS","KRW",receipt,LocalDate.of(2026,8,14),"2026-H1",
            new BigDecimal(revenue),new BigDecimal(profit),null,null,null,null,null,null,null,null,
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt,List.of(),null,null,"TEST_FIXTURE");
        return new DartFinancialEvidenceService.EvidenceSnapshot("AVAILABLE",known,List.of(new DartFinancialEvidenceService.StockEvidence("000660","00000001","AVAILABLE",known,facts,List.of())));
    }
    private static AiAgent agent(java.util.function.BooleanSupplier available,java.util.function.BiFunction<String,String,AgentResult> result) {
        return new AiAgent(){public String name(){return "deepseek";}public boolean isAvailable(){return available.getAsBoolean();}public AgentResult analyze(String system,String user){return result.apply(system,user);}};
    }
    private static AgentResult success(ObjectMapper mapper,JsonNode packet) throws Exception {
        ObjectNode reply=mapper.createObjectNode().put("subjectCode",packet.path("subjectCode").asText()).put("verdict","WATCH").put("invalidation","MISSING_SOURCE_EVIDENCE");
        var claims=reply.putArray("claims");boolean observed=false,scenario=false;
        for(JsonNode fact:packet.path("facts"))if((!observed&&"OBSERVED".equals(fact.path("kind").asText()))||(!scenario&&"SCENARIO".equals(fact.path("kind").asText()))) {
            claims.addObject().put("factId",fact.path("id").asText()).put("kind",fact.path("kind").asText()).set("value",fact.path("value"));
            if("OBSERVED".equals(fact.path("kind").asText()))observed=true;else scenario=true;
        }
        reply.putArray("riskFlags").add("CASH_FLOW_UNKNOWN");return new AgentResult("deepseek",mapper.writeValueAsString(reply),"test",1,1,"success",null,1);
    }
    private static JsonNode fact(JsonNode packet,String id){for(JsonNode f:packet.path("facts"))if(id.equals(f.path("id").asText()))return f;throw new AssertionError("Missing fact "+id);}
    private static ObjectNode capture(ObjectMapper mapper,String date,boolean verified) {
        var value=mapper.createObjectNode().put("captureId",date).put("decisionDate",date).put("decisionAt",date+"T08:00:00Z")
            .put("baselinePolicyId","existing-swing-v1").put("challengerPolicyId","event-research-v3").put("inputVersion","event-research-v3")
            .put("roundTripCostPct",verified?"0.2":null).put("costsVerified",verified);
        value.putObject("baselineWeights").put("005930","1");value.putObject("challengerWeights").put("000660","1");value.putArray("outcomes");return value;
    }
    private static JsonNode outcome(JsonNode capture,int horizon){for(JsonNode o:capture.path("outcomes"))if(o.path("horizonSessions").asInt()==horizon)return o;throw new AssertionError("Missing horizon "+horizon);}
    private static List<ResearchOutcomeEvaluator.SessionBar> bars(String code,LocalDate from,LocalDate to,Instant asOf) {
        List<ResearchOutcomeEvaluator.SessionBar> result=new ArrayList<>();for(LocalDate d=from;!d.isAfter(to);d=d.plusDays(1))if(KoreanMarketCalendar.isTradingDay(d)) {
            result.add(new ResearchOutcomeEvaluator.SessionBar(code,d,asOf,new BigDecimal("100"),new BigDecimal("115"),new BigDecimal("95"),new BigDecimal("110"),true,false,"KRX","REGULAR",ResearchOutcomeEvaluator.Provenance.REAL,ResearchOutcomeEvaluator.Adjustment.ADJUSTED));
        }return result;
    }
    private static final class MutableClock extends Clock {
        private Instant value;MutableClock(Instant value){this.value=value;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value;}
    }
}
