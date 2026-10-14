package com.bitman.justbuy.service;

import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Public behavior of the expanded collection path, using only deterministic fixture responses. */
class KiwoomExpandedCollectionTest {
    private static final Instant NOW=Instant.parse("2026-10-12T01:00:00Z");
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final List<String> PARTS=List.of("quote","valuation","technical","investorDaily");
    private static final ObjectMapper MAPPER=new ObjectMapper();

    private record Call(String api,Map<String,String> params) {}
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;
        MutableClock(Instant now){this(new AtomicReference<>(now),ZoneOffset.UTC);}
        private MutableClock(AtomicReference<Instant> current,ZoneId zone){this.current=current;this.zone=zone;}
        void advance(Duration duration){current.updateAndGet(now->now.plus(duration));}
        @Override public ZoneId getZone(){return zone;}
        @Override public Clock withZone(ZoneId zone){return new MutableClock(current,zone);}
        @Override public Instant instant(){return current.get();}
    }

    private static final class Fixture implements KiwoomReadOnlyService.Transport {
        final MutableClock clock=new MutableClock(NOW);
        final List<Call> calls=new ArrayList<>();
        final Map<String,KiwoomReadOnlyService.Response> overrides=new HashMap<>();
        final KiwoomReadOnlyService service;
        BiConsumer<String,Map<String,String>> beforeReply=(api,params)->{};

        Fixture(){
            service=new KiwoomReadOnlyService(this,MAPPER,clock,true,false,"fixture-key","fixture-secret","MOCK");
            // Pacing is independently tested in KiwoomReadOnlyServiceTest. These tests have no real sleeps or HTTP.
            setField(service,"token","fixture-token");
            setField(service,"tokenExpiresAt",NOW.plus(Duration.ofHours(1)));
        }
        void deferForeground(){setField(service,"nextRequestNanos",System.nanoTime()+Duration.ofSeconds(60).toNanos());}
        void allowImmediateRequests(){setField(service,"nextRequestNanos",0L);}

        @Override public KiwoomReadOnlyService.Response send(String path,String api,String token,Map<String,String> params,long deadline){
            calls.add(new Call(api,Map.copyOf(params)));
            allowImmediateRequests();
            beforeReply.accept(api,params);
            var override=overrides.get(api);if(override!=null)return override;
            JsonNode body=switch(api){
                case "au10001"->ok().put("token","fixture-renewed-token").put("token_type","bearer")
                    .put("expires_dt",clock.instant().plus(Duration.ofHours(1)).atZone(KST).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
                case "ka10095"->quotes(Arrays.asList(params.get("stk_cd").split("\\|")),clock.instant().minusSeconds(1));
                case "ka10001"->basic(params.get("stk_cd"));
                case "ka10081"->chart(params.get("stk_cd"),LocalDate.parse(params.get("base_dt"),DateTimeFormatter.BASIC_ISO_DATE));
                case "ka10059"->investors(LocalDate.parse(params.get("dt"),DateTimeFormatter.BASIC_ISO_DATE));
                default->throw new AssertionError("Unexpected external call: "+api);
            };
            return new KiwoomReadOnlyService.Response(200,body,false);
        }
    }

    @Test void twentyQuotesUseOnePipeSeparatedRequestAndTheTwentyFirstAndInvalidCodesAreExcluded(){
        Fixture fixture=new Fixture();List<String> firstTwenty=codes(1,20);
        List<String> input=new ArrayList<>(List.of(firstTwenty.getFirst(),firstTwenty.getFirst(),"BAD","005930_NX","A005930"));
        input.addAll(firstTwenty.subList(1,20));input.add(code(21));

        var result=fixture.service.lookupAll(input);

        assertThat(result.keySet()).containsExactlyElementsOf(firstTwenty);
        assertThat(fixture.calls).hasSize(1);
        assertThat(fixture.calls.getFirst()).isEqualTo(new Call("ka10095",Map.of("stk_cd",String.join("|",firstTwenty))));
        assertThat(fixture.service.readiness()).containsEntry("candidateLimit",20).containsEntry("environment","MOCK");
        result.forEach((requested,quote)->{
            assertThat(quote.code()).isEqualTo(requested);
            assertThat(quote.source()).isEqualTo("KIWOOM_REST_MOCK");
            assertThat(quote.market()).isEqualTo("UNKNOWN");
            assertThat(quote.price()).isEqualByComparingTo("70000");
            assertThat(quote.fetchedAt()).isEqualTo(NOW);
            assertThat(quote.observedAt()).isEqualTo(NOW.minusSeconds(1));
        });
    }

    @Test void aDifferentSubsetUsesTheSharedPerCodeCacheAndKeepsTheOriginalCaptureTime(){
        Fixture fixture=new Fixture();var original=fixture.service.lookupAll(codes(1,20));
        fixture.clock.advance(Duration.ofSeconds(5));

        var subset=fixture.service.lookupAll(List.of(code(20),code(2),code(2),code(7)));

        assertThat(subset.keySet()).containsExactly(code(20),code(2),code(7));
        assertThat(fixture.calls).hasSize(1);
        subset.forEach((code,quote)->{
            assertThat(quote.fetchedAt()).isEqualTo(original.get(code).fetchedAt()).isEqualTo(NOW);
            assertThat(quote.observedAt()).isEqualTo(original.get(code).observedAt());
            assertThat(quote.price()).isEqualByComparingTo(original.get(code).price());
        });
    }

    @Test void overlappingConcurrentQuoteBatchesReuseTheFirstVerifiedCaptureInsteadOfQueryingTheirSharedCodeTwice() throws Exception {
        Fixture fixture=new Fixture();var firstEntered=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);
        fixture.beforeReply=(api,params)->{
            if(api.equals("ka10095")&&params.get("stk_cd").equals(code(1)+"|"+code(2))){
                firstEntered.countDown();
                try{if(!releaseFirst.await(2,TimeUnit.SECONDS))throw new AssertionError("Fixture quote response was not released");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
            }
        };
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            Future<Map<String,KeywordSearchDataService.Quote>> first=executor.submit(()->fixture.service.lookupAll(List.of(code(1),code(2))));
            assertThat(firstEntered.await(1,TimeUnit.SECONDS)).isTrue();
            Future<Map<String,KeywordSearchDataService.Quote>> second=executor.submit(()->fixture.service.lookupAll(List.of(code(2),code(3))));
            ReentrantLock requestLock=(ReentrantLock)getField(fixture.service,"requestLock");
            long bound=System.nanoTime()+Duration.ofSeconds(1).toNanos();
            while(!requestLock.hasQueuedThreads()&&System.nanoTime()<bound)Thread.yield();
            assertThat(requestLock.hasQueuedThreads()).isTrue();releaseFirst.countDown();

            var firstResult=first.get(2,TimeUnit.SECONDS);var secondResult=second.get(2,TimeUnit.SECONDS);
            assertThat(firstResult.keySet()).containsExactly(code(1),code(2));
            assertThat(secondResult.keySet()).containsExactly(code(2),code(3));
            assertThat(secondResult.get(code(2)).fetchedAt()).isEqualTo(firstResult.get(code(2)).fetchedAt());
            assertThat(fixture.calls).containsExactly(new Call("ka10095",Map.of("stk_cd",code(1)+"|"+code(2))),
                new Call("ka10095",Map.of("stk_cd",code(3))));
        }finally{releaseFirst.countDown();executor.shutdownNow();}
    }

    @Test void aVerifiedCachedReadIsStillReturnedWhenTheForegroundDeadlineHasPassed() throws Exception {
        Fixture fixture=new Fixture();Map<String,String> params=Map.of("stk_cd",code(1));
        Object original=read(fixture.service,"ka10001",params,System.nanoTime()+Duration.ofSeconds(8).toNanos());
        fixture.clock.advance(Duration.ofSeconds(2));

        Object reused=read(fixture.service,"ka10001",params,System.nanoTime()-1);

        assertThat(readProperty(reused,"status")).isEqualTo("AVAILABLE");
        assertThat(readProperty(reused,"fetchedAt")).isEqualTo(NOW);
        assertThat(reused).isSameAs(original);
        assertThat(fixture.calls).hasSize(1);
    }

    static Stream<KiwoomReadOnlyService.Response> throttledResponses(){
        return Stream.of(
            new KiwoomReadOnlyService.Response(429,MissingNode.getInstance(),false),
            new KiwoomReadOnlyService.Response(200,ok().put("return_code",1700),false),
            new KiwoomReadOnlyService.Response(200,ok().put("return_code",1701),false),
            new KiwoomReadOnlyService.Response(200,ok().put("return_code",1702),false),
            new KiwoomReadOnlyService.Response(200,ok().put("return_code",5).put("return_msg","[1700:허용된 API 요청 개수를 초과하였습니다]"),false)
        );
    }

    @ParameterizedTest @MethodSource("throttledResponses")
    void throttlingPausesOtherTrsForTheSameThirtySecondCooldown(KiwoomReadOnlyService.Response response){
        Fixture fixture=new Fixture();fixture.overrides.put("ka10095",response);
        assertThat(fixture.service.lookupAll(List.of(code(1)))).isEmpty();

        Map<String,Object> blocked=fixture.service.fetchEvidence(List.of(code(2)),Map.of(code(2),List.of("technical")));
        assertThat(part(blocked,code(2),"technical")).containsEntry("status","PROVIDER_COOLDOWN");
        assertThat(queueState(fixture.service)).containsEntry("status","COOLDOWN");
        fixture.clock.advance(Duration.ofMillis(29999));
        assertThat(fixture.service.lookupAll(List.of(code(3)))).isEmpty();
        assertThat(fixture.calls).hasSize(1);

        fixture.clock.advance(Duration.ofMillis(1));fixture.overrides.clear();
        Map<String,Object> resumed=fixture.service.fetchEvidence(List.of(code(2)),Map.of(code(2),List.of("technical")));
        assertThat(part(resumed,code(2),"technical")).containsEntry("status","AVAILABLE");
        assertThat(fixture.calls.stream().map(Call::api).toList()).containsExactly("ka10095","ka10081");
    }

    @Test void twentyColdCandidatesShareBoundedPendingJobsAndBecomeUsableAfterTheWorkerCollectsThem(){
        Fixture fixture=new Fixture();var executor=new BoundedMarketRefreshQueueTest.ManualExecutor();
        var queue=new BoundedMarketRefreshQueue(128,fixture.clock,executor);setField(fixture.service,"refreshQueue",queue);
        List<String> codes=codes(1,20);fixture.deferForeground();
        try{
            Map<String,Object> first=fixture.service.fetchEvidence(codes);
            assertThat(first).containsEntry("requestedCodes",20).containsEntry("enrichedCodes",0)
                .containsEntry("pendingCodes",20).containsEntry("pendingFacts",80).containsEntry("availableFacts",0L)
                .containsEntry("status","PENDING").containsEntry("backgroundRefreshEnabled",true);
            for(String code:codes)for(String part:PARTS)
                assertThat(part(first,code,part)).containsEntry("status","PENDING").doesNotContainKeys("currentPrice","per","ma20","foreignNetBuy5dShares");
            assertThat(queue.snapshot()).containsEntry("pendingJobs",61).containsEntry("capacity",128).containsEntry("enqueued",61L);
            assertThat(executor.tasks).hasSize(1);assertThat(fixture.calls).isEmpty();

            Map<String,Object> repeated=fixture.service.fetchEvidence(codes);
            assertThat(repeated).containsEntry("pendingCodes",20).containsEntry("pendingFacts",80);
            assertThat(queue.snapshot()).containsEntry("pendingJobs",61).containsEntry("enqueued",61L);
            fixture.allowImmediateRequests();executor.drain();

            assertThat(fixture.calls).hasSize(61);
            assertThat(fixture.calls.stream().filter(call->call.api().equals("ka10095")).count()).isEqualTo(1);
            Map<String,Object> warm=fixture.service.fetchEvidence(codes);
            assertThat(warm).containsEntry("requestedCodes",20).containsEntry("enrichedCodes",20)
                .containsEntry("pendingCodes",0).containsEntry("pendingFacts",0).containsEntry("availableFacts",80L);
            for(String code:codes)for(String part:PARTS)
                assertThat(part(warm,code,part)).containsEntry("status","AVAILABLE").containsEntry("source","KIWOOM_REST_MOCK")
                    .containsEntry("environment","MOCK").containsEntry("fetchedAt",NOW.toString());
            assertThat(queue.snapshot()).containsEntry("pendingJobs",0).containsEntry("completed",61L).containsEntry("failed",0L);
            assertThat(fixture.calls).hasSize(61);
        }finally{fixture.service.close();}
    }
    @Test void cachedInvestorProgressReadsOnlyLiveDemandAndValidatedSameSessionCacheWithoutRequests(){
        Fixture fixture=new Fixture();var executor=new BoundedMarketRefreshQueueTest.ManualExecutor();
        var queue=new BoundedMarketRefreshQueue(128,fixture.clock,executor);setField(fixture.service,"refreshQueue",queue);
        LocalDate expected=LocalDate.of(2026,10,8);String code=code(1);
        try {
            assertThat(part(fixture.service.cachedInvestorEvidence(List.of(code),expected),code,"investorDaily")).containsEntry("status","UNAVAILABLE");
            assertThat(fixture.calls).isEmpty();assertThat(queue.snapshot()).containsEntry("enqueued",0L);
            fixture.deferForeground();fixture.service.fetchEvidence(List.of(code),Map.of(code,List.of("investorDaily")));
            for(int i=0;i<3;i++)assertThat(part(fixture.service.cachedInvestorEvidence(List.of(code),expected),code,"investorDaily")).containsEntry("status","PENDING");
            assertThat(fixture.calls).isEmpty();assertThat(queue.snapshot()).containsEntry("enqueued",1L);
            fixture.allowImmediateRequests();executor.drain();int calls=fixture.calls.size();
            var verified=part(fixture.service.cachedInvestorEvidence(List.of(code),expected),code,"investorDaily");
            assertThat(verified).containsEntry("status","AVAILABLE").containsEntry("requestedStockCode",code).containsEntry("fetchedAt",NOW.toString());
            assertThat((List<?>)verified.get("observations")).hasSize(5);
            assertThat(fixture.calls).hasSize(calls);assertThat(queue.snapshot()).containsEntry("enqueued",1L);
            fixture.clock.advance(Duration.ofSeconds(600));
            assertThat(part(fixture.service.cachedInvestorEvidence(List.of(code),expected),code,"investorDaily")).containsEntry("status","UNAVAILABLE");
            assertThat(fixture.calls).hasSize(calls);assertThat(queue.snapshot()).containsEntry("enqueued",1L);
        }finally{fixture.service.close();}
    }
    @Test void twentyFlowCandidatesNeedOnlyOneSharedQuoteAndTwentyInvestorJobs(){
        Fixture fixture=new Fixture();var executor=new BoundedMarketRefreshQueueTest.ManualExecutor();
        var queue=new BoundedMarketRefreshQueue(128,fixture.clock,executor);setField(fixture.service,"refreshQueue",queue);
        List<String> selected=codes(1,20);Map<String,List<String>> required=new LinkedHashMap<>();
        selected.forEach(code->required.put(code,List.of("quote","investorDaily")));fixture.deferForeground();
        try {
            var first=fixture.service.fetchEvidence(selected,required);
            assertThat(first).containsEntry("pendingFacts",40);assertThat(queue.snapshot()).containsEntry("enqueued",21L);
            for(String code:selected)for(String unrequested:List.of("valuation","technical"))
                assertThat(part(first,code,unrequested)).containsEntry("status","NOT_REQUESTED");
            fixture.allowImmediateRequests();executor.drain();
            assertThat(fixture.calls).hasSize(21);assertThat(fixture.calls.stream().map(Call::api).toList()).containsOnly("ka10095","ka10059");
            assertThat(fixture.calls.stream().filter(call->call.api().equals("ka10059")).count()).isEqualTo(20);
            fixture.service.cachedInvestorEvidence(selected,LocalDate.of(2026,10,8));assertThat(fixture.calls).hasSize(21);
        }finally{fixture.service.close();}
    }
    @Test void cachePeekNeverConvertsExpiredDemandOrTheNextTradingSessionIntoAnotherPendingQuery(){
        Fixture fixture=new Fixture();var executor=new BoundedMarketRefreshQueueTest.ManualExecutor();
        var queue=new BoundedMarketRefreshQueue(128,fixture.clock,executor);setField(fixture.service,"refreshQueue",queue);
        String code=code(1);LocalDate expected=LocalDate.of(2026,10,8);fixture.deferForeground();
        try {
            fixture.service.fetchEvidence(List.of(code),Map.of(code,List.of("investorDaily")));fixture.clock.advance(Duration.ofSeconds(120));
            assertThat(part(fixture.service.cachedInvestorEvidence(List.of(code),expected),code,"investorDaily")).containsEntry("status","UNAVAILABLE");
            assertThat(queue.snapshot()).containsEntry("expired",1L).containsEntry("pendingJobs",0);
            fixture.clock.advance(Duration.ofDays(1));
            assertThat(part(fixture.service.cachedInvestorEvidence(List.of(code),expected),code,"investorDaily")).containsEntry("status","STALE_DATE");
            assertThat(fixture.calls).isEmpty();executor.drain();assertThat(fixture.calls).isEmpty();
        }finally{fixture.service.close();}
    }

    @Test void aFullQueueKeepsRejectedFactsUnconfirmedAndNeverExceedsItsCapacity(){
        Fixture fixture=new Fixture();var executor=new BoundedMarketRefreshQueueTest.ManualExecutor();
        var queue=new BoundedMarketRefreshQueue(10,fixture.clock,executor);setField(fixture.service,"refreshQueue",queue);
        fixture.deferForeground();
        try{
            Map<String,Object> result=fixture.service.fetchEvidence(codes(1,20));
            assertThat(queue.snapshot()).containsEntry("pendingJobs",10).containsEntry("enqueued",10L).containsEntry("rejected",51L);
            assertThat(result).containsEntry("pendingCodes",20).containsEntry("pendingFacts",29).containsEntry("availableFacts",0L);
            assertThat(part(result,code(20),"valuation")).containsEntry("status","BUDGET_EXCEEDED").doesNotContainKey("per");
            assertThat(fixture.calls).isEmpty();
        }finally{fixture.service.close();}
    }

    @Test void anActiveRefreshKeyDoesNotCountAnAlreadyVerifiedSharedQuoteAsPending(){
        Fixture fixture=new Fixture();fixture.service.lookupAll(List.of(code(1)));
        var executor=new BoundedMarketRefreshQueueTest.ManualExecutor();var queue=new BoundedMarketRefreshQueue(128,fixture.clock,executor);
        setField(fixture.service,"refreshQueue",queue);var result=new AtomicReference<Map<String,Object>>();
        try{
            queue.offer("quote/"+code(1),()->result.set(fixture.service.fetchEvidence(List.of(code(1)),Map.of(code(1),List.of("quote")))));
            executor.drain();

            assertThat(result.get()).containsEntry("enrichedCodes",1).containsEntry("availableFacts",1L)
                .containsEntry("pendingCodes",0).containsEntry("pendingFacts",0);
            assertThat(part(result.get(),code(1),"quote")).containsEntry("status","AVAILABLE").containsEntry("fetchedAt",NOW.toString());
            assertThat(fixture.calls).hasSize(1);
        }finally{fixture.service.close();}
    }

    static Stream<Arguments> dailyBoundaries(){
        return Stream.of(
            Arguments.of("technical",LocalDateTime.of(2026,10,12,15,34,50),"ka10081","base_dt"),
            Arguments.of("investorDaily",LocalDateTime.of(2026,10,12,23,59,50),"ka10059","dt")
        );
    }

    @ParameterizedTest @MethodSource("dailyBoundaries")
    void newCompletedDayHasItsOwnPendingDemandAndTheObsoleteDailyJobDoesNotQueryTheProvider(
            String part,LocalDateTime beforeBoundary,String api,String dateParameter){
        Fixture fixture=new Fixture();fixture.clock.advance(Duration.between(NOW,beforeBoundary.atZone(KST).toInstant()));
        setField(fixture.service,"tokenExpiresAt",fixture.clock.instant().plus(Duration.ofHours(1)));
        var executor=new BoundedMarketRefreshQueueTest.ManualExecutor();var queue=new BoundedMarketRefreshQueue(128,fixture.clock,executor);
        setField(fixture.service,"refreshQueue",queue);fixture.deferForeground();
        try{
            var oldDemand=fixture.service.fetchEvidence(List.of(code(1)),Map.of(code(1),List.of(part)));
            assertThat(oldDemand).containsEntry("pendingCodes",1).containsEntry("pendingFacts",1);
            assertThat(queue.keys()).containsExactly(part+"/"+code(1)+"/2026-10-08");
            fixture.clock.advance(Duration.ofSeconds(20));

            var currentDemand=fixture.service.fetchEvidence(List.of(code(1)),Map.of(code(1),List.of(part)));
            assertThat(currentDemand).containsEntry("pendingCodes",1).containsEntry("pendingFacts",1);
            assertThat(queue.keys()).containsExactly(part+"/"+code(1)+"/2026-10-08",part+"/"+code(1)+"/2026-10-12");
            fixture.allowImmediateRequests();executor.drain();

            assertThat(fixture.calls).containsExactly(new Call(api,
                part.equals("technical")?Map.of("stk_cd",code(1),"base_dt","20261012","upd_stkpc_tp","1")
                    :Map.of("dt","20261012","stk_cd",code(1),"amt_qty_tp","2","trde_tp","0","unit_tp","1")));
            assertThat(fixture.calls.getFirst().params()).containsEntry(dateParameter,"20261012");
            var warm=fixture.service.fetchEvidence(List.of(code(1)),Map.of(code(1),List.of(part)));
            assertThat(warm).containsEntry("pendingCodes",0).containsEntry("pendingFacts",0).containsEntry("availableFacts",1L);
            assertThat(part(warm,code(1),part)).containsEntry("status","AVAILABLE").containsEntry("observedDate","2026-10-12");
        }finally{fixture.service.close();}
    }

    @Test void exactQuoteCacheCapacityStillReclaimsExpiredCodesForNewSubsetReuse(){
        Fixture fixture=new Fixture();
        for(int start=1;start<=240;start+=20)fixture.service.lookupAll(codes(start,20));
        fixture.service.lookupAll(codes(241,16));
        assertThat(queueState(fixture.service)).containsEntry("cachedCodes",256);
        fixture.clock.advance(Duration.ofSeconds(21));

        var newlyCaptured=fixture.service.lookupAll(List.of(code(999)));
        assertThat(newlyCaptured).containsKey(code(999));
        assertThat(queueState(fixture.service).get("cachedCodes")).isEqualTo(1);
        fixture.service.lookupAll(List.of(code(999),code(998)));

        assertThat(fixture.calls.getLast().params()).containsEntry("stk_cd",code(998));
        assertThat(newlyCaptured.get(code(999)).fetchedAt()).isEqualTo(NOW.plusSeconds(21));
    }

    @Test void unauthorizedHttpWithoutAnObjectBodyInvalidatesTheLocalToken(){
        Fixture fixture=new Fixture();fixture.overrides.put("ka10095",new KiwoomReadOnlyService.Response(401,MissingNode.getInstance(),false));

        assertThat(fixture.service.lookupAll(List.of(code(1)))).isEmpty();

        assertThat(getField(fixture.service,"token")).isNull();
        assertThat(getField(fixture.service,"authCooldownUntil")).isEqualTo(NOW.plusSeconds(30));
        assertThat(fixture.service.readiness()).containsEntry("runtimeStatus","PROVIDER_UNAVAILABLE");
    }

    static Stream<Arguments> recoverableAuthErrors(){return Stream.of(
        Arguments.of(8005,""),Arguments.of(8031,""),Arguments.of(8103,""),
        Arguments.of(3,"[8005:Token invalid]"),Arguments.of(5,"CODE=8005"));}

    @ParameterizedTest @MethodSource("recoverableAuthErrors")
    void providerAuthErrorsInvalidateTheTokenAndRecoverOnceAfterCooldown(int code,String message){
        Fixture fixture=new Fixture();fixture.overrides.put("ka10095",new KiwoomReadOnlyService.Response(200,
            MAPPER.createObjectNode().put("return_code",code).put("return_msg",message),false));
        assertThat(fixture.service.lookupAll(List.of(code(1)))).isEmpty();
        assertThat(getField(fixture.service,"token")).isNull();
        assertThat(getField(fixture.service,"authCooldownUntil")).isEqualTo(NOW.plusSeconds(30));
        fixture.clock.advance(Duration.ofSeconds(6));
        assertThat(fixture.service.lookupAll(List.of(code(2)))).isEmpty();
        assertThat(fixture.calls).hasSize(1);
        fixture.clock.advance(Duration.ofSeconds(25));fixture.overrides.clear();
        assertThat(fixture.service.lookupAll(List.of(code(1)))).containsKey(code(1));
        assertThat(fixture.calls.stream().map(Call::api)).containsExactly("ka10095","au10001","ka10095");
        assertThat(getField(fixture.service,"token")).isEqualTo("fixture-renewed-token");
    }

    @Test void credentialModeAndIpErrorsDoNotStartAnAuthenticationRecoveryLoop(){
        for(int error:List.of(8030,8010)){
            Fixture fixture=new Fixture();fixture.overrides.put("ka10095",new KiwoomReadOnlyService.Response(200,
                MAPPER.createObjectNode().put("return_code",error),false));
            assertThat(fixture.service.lookupAll(List.of(code(1)))).isEmpty();
            assertThat(getField(fixture.service,"token")).isEqualTo("fixture-token");
            assertThat(fixture.calls.stream().map(Call::api)).containsExactly("ka10095");
        }
    }

    @Test void theSharedQuoteCacheExpiresAndDoesNotReplaceItsOriginalCaptureTime(){
        Fixture fixture=new Fixture();var first=fixture.service.lookupAll(List.of(code(1))).get(code(1));
        fixture.clock.advance(Duration.ofSeconds(19));
        var cached=fixture.service.lookupAll(List.of(code(1))).get(code(1));
        assertThat(cached.fetchedAt()).isEqualTo(first.fetchedAt()).isEqualTo(NOW);
        assertThat(fixture.calls).hasSize(1);

        fixture.clock.advance(Duration.ofSeconds(2));
        var refreshed=fixture.service.lookupAll(List.of(code(1))).get(code(1));
        assertThat(refreshed.fetchedAt()).isEqualTo(NOW.plusSeconds(21));
        assertThat(fixture.calls).hasSize(2);
    }

    @Test void cachedRawResponsesCannotResurrectStaleOrConflictingQuoteIdentity(){
        Fixture fixture=new Fixture();fixture.overrides.put("ka10095",new KiwoomReadOnlyService.Response(200,quotes(List.of(code(1)),NOW.minusSeconds(179)),false));
        assertThat(fixture.service.lookupAll(List.of(code(1)))).containsKey(code(1));
        fixture.clock.advance(Duration.ofSeconds(2));
        assertThat(fixture.service.lookupAll(List.of(code(1)))).isEmpty();
        assertThat(fixture.calls).hasSize(1);

        Fixture mismatched=new Fixture();mismatched.overrides.put("ka10095",new KiwoomReadOnlyService.Response(200,quotes(List.of(code(2)),NOW.minusSeconds(1)),false));
        assertThat(mismatched.service.lookupAll(List.of(code(1)))).isEmpty();
        assertThat(mismatched.service.lookupAll(List.of(code(1)))).isEmpty();
        assertThat(mismatched.calls).hasSize(1);
    }

    private static ObjectNode ok(){return MAPPER.createObjectNode().put("return_code",0);}
    private static ObjectNode basic(String code){
        return ok().put("stk_cd",code).put("stk_nm","검증용 종목 "+code).put("cur_prc","-70000")
            .put("flu_rt","-1.50").put("trde_qty","10000").put("per","12.34").put("pbr","1.23").put("roe","-3.40");
    }
    private static ObjectNode quotes(List<String> codes,Instant observed){
        ObjectNode body=ok();var rows=body.putArray("atn_stk_infr");var at=observed.atZone(KST);
        for(String code:codes)rows.addObject().put("stk_cd",code).put("stk_nm","검증용 종목 "+code)
            .put("cur_prc","-70000").put("flu_rt","-1.50").put("trde_qty","10000").put("trde_prica","123.50")
            .put("dt",at.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)).put("cntr_tm",at.format(DateTimeFormatter.ofPattern("HHmmss")));
        return body;
    }
    private static ObjectNode chart(String code,LocalDate latest){
        ObjectNode body=ok().put("stk_cd",code);var rows=body.putArray("stk_dt_pole_chart_qry");LocalDate day=latest;
        for(int index=0;index<60;index++){
            rows.addObject().put("dt",day.format(DateTimeFormatter.BASIC_ISO_DATE)).put("cur_prc",Integer.toString(200-index))
                .put("open_pric",Integer.toString(199-index)).put("high_pric",Integer.toString(201-index))
                .put("low_pric",Integer.toString(198-index)).put("trde_qty","100");
            day=KoreanMarketCalendar.previousTradingDay(day);
        }
        return body;
    }
    private static ObjectNode investors(LocalDate latest){
        ObjectNode body=ok();var rows=body.putArray("stk_invsr_orgn");LocalDate day=latest;
        for(int index=0;index<5;index++){
            rows.addObject().put("dt",day.format(DateTimeFormatter.BASIC_ISO_DATE)).put("frgnr_invsr","100").put("orgn","200");
            day=KoreanMarketCalendar.previousTradingDay(day);
        }
        return body;
    }
    private static List<String> codes(int start,int count){return IntStream.range(start,start+count).mapToObj(KiwoomExpandedCollectionTest::code).toList();}
    private static String code(int index){return String.format(Locale.ROOT,"%06d",index);}
    @SuppressWarnings("unchecked") private static Map<String,Object> part(Map<String,Object> batch,String code,String part){
        return (Map<String,Object>)((Map<String,Object>)((Map<String,Object>)batch.get("candidates")).get(code)).get(part);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> queueState(KiwoomReadOnlyService service){return (Map<String,Object>)service.readiness().get("queue");}
    private static Object read(KiwoomReadOnlyService service,String api,Map<String,String> params,long deadline) throws Exception {
        Method method=KiwoomReadOnlyService.class.getDeclaredMethod("read",String.class,Map.class,long.class);method.setAccessible(true);
        return method.invoke(service,api,params,deadline);
    }
    private static Object readProperty(Object value,String property) throws Exception {
        Method method=value.getClass().getDeclaredMethod(property);method.setAccessible(true);return method.invoke(value);
    }
    private static void setField(Object target,String name,Object value){
        try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    private static Object getField(Object target,String name){
        try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
}
