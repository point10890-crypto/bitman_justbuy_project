package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class KiwoomReadOnlyServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-12T01:00:00Z");
    private record Call(String path, String api, Map<String,String> params, long nanos) {}
    private static class MutableClock extends Clock {
        final java.util.concurrent.atomic.AtomicReference<Instant> current;
        final ZoneId zone;
        MutableClock(Instant current) {this(new java.util.concurrent.atomic.AtomicReference<>(current),ZoneOffset.UTC);}
        private MutableClock(java.util.concurrent.atomic.AtomicReference<Instant> current,ZoneId zone) {this.current=current;this.zone=zone;}
        void advance(Duration duration) {current.updateAndGet(value->value.plus(duration));}
        @Override public ZoneId getZone() {return zone;}
        @Override public Clock withZone(ZoneId zone) {return new MutableClock(current,zone);}
        @Override public Instant instant() {return current.get();}
    }
    private class Fixture implements KiwoomReadOnlyService.Transport {
        final List<Call> calls = new ArrayList<>();
        final Map<String,KiwoomReadOnlyService.Response> responses = new HashMap<>();
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        boolean tokenTransportFails;
        @Override public KiwoomReadOnlyService.Response send(String path, String api, String token,
                Map<String,String> params, long deadlineNanos) throws Exception {
            calls.add(new Call(path, api, Map.copyOf(params), System.nanoTime()));
            if("/oauth2/token".equals(path)&&tokenTransportFails) throw new java.io.IOException("PRIVATE-TOKEN-FAILURE");
            if ("/oauth2/token".equals(path)) return new KiwoomReadOnlyService.Response(200,
                mapper.readTree("{\"return_code\":0,\"token_type\":\"bearer\",\"token\":\"fixture-token\",\"expires_dt\":\"20261013090000\"}"),false);
            return responses.getOrDefault(api, new KiwoomReadOnlyService.Response(200,
                mapper.readTree("{\"return_code\":17,\"return_msg\":\"PRIVATE-PROVIDER-ERROR\"}"),false));
        }
        void reply(String api, JsonNode body) { responses.put(api,new KiwoomReadOnlyService.Response(200,body,false)); }
        KiwoomReadOnlyService service(boolean enabled, boolean licensed) {
            return new KiwoomReadOnlyService(this, mapper, clock, enabled, licensed, "fixture-key", "fixture-secret");
        }
    }
    private ObjectNode ok() { return mapper.createObjectNode().put("return_code",0); }
    private ObjectNode basic(String code) {
        return ok().put("stk_cd",code).put("stk_nm","삼성전자").put("cur_prc","-70000")
            .put("flu_rt","-1.50").put("trde_qty","10000").put("per","12.34").put("pbr","1.23").put("roe","-3.40");
    }
    private ObjectNode quoteBody(String code, String date, String time) {
        ObjectNode body=ok(); body.putArray("atn_stk_infr").addObject().put("stk_cd",code).put("stk_nm","삼성전자")
            .put("cur_prc","-70000").put("flu_rt","-1.50").put("trde_qty","10000")
            .put("trde_prica","123.50").put("dt",date).put("cntr_tm",time);
        return body;
    }
    private ObjectNode chartBody(String code, int count) {
        ObjectNode body=ok().put("stk_cd",code);
        var rows=body.putArray("stk_dt_pole_chart_qry");
        LocalDate date=LocalDate.of(2026,10,8);
        for(int i=0;i<count;i++) {
            rows.addObject().put("dt",date.toString().replace("-",""))
                .put("cur_prc",Integer.toString(200-i)).put("open_pric",Integer.toString(199-i))
                .put("high_pric",Integer.toString(201-i)).put("low_pric",Integer.toString(198-i))
                .put("trde_qty","100").put("trde_prica","0.02");
            date=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(date);
        }
        return body;
    }
    private ObjectNode flows(int count) {
        ObjectNode body=ok(); var rows=body.putArray("stk_invsr_orgn");
        LocalDate date=LocalDate.of(2026,10,8);
        for(int i=0;i<count;i++) {
            rows.addObject().put("dt",date.toString().replace("-",""))
                .put("frgnr_invsr",Integer.toString(-100-i)).put("orgn",Integer.toString(200+i));
            date=com.bitman.justbuy.util.KoreanMarketCalendar.previousTradingDay(date);
        }
        return body;
    }
    @SuppressWarnings("unchecked") private Map<String,Object> part(Map<String,Object> evidence, String name) {
        return (Map<String,Object>)((Map<String,Object>)((Map<String,Object>)evidence.get("candidates")).get("005930")).get(name);
    }
    private Map<String,Object> only(Fixture fixture,String name) {
        return fixture.service(true,true).fetchEvidence(List.of("005930"), Map.of("005930",List.of(name)));
    }

    private java.net.URI transportUri(KiwoomReadOnlyService service,String path,String api) throws Exception {
        var field=KiwoomReadOnlyService.class.getDeclaredField("transport");field.setAccessible(true);
        Object transport=field.get(service);
        var target=transport.getClass().getDeclaredMethod("requestUri",String.class,String.class);target.setAccessible(true);
        return (java.net.URI)target.invoke(transport,path,api);
    }
    @Test void defaultRealEnvironmentKeepsProductionHostAndMetadata() throws Exception {
        Fixture fixture=new Fixture(); var service=fixture.service(true,true);
        assertThat(service.readiness()).containsEntry("environment","REAL").containsEntry("source","KIWOOM_REST");
        var httpService=new KiwoomReadOnlyService(mapper,true,true,"fixture-key","fixture-secret");
        assertThat(transportUri(httpService,"/oauth2/token","au10001").toString()).isEqualTo("https://api.kiwoom.com/oauth2/token");
        assertThat(transportUri(httpService,"/api/dostk/chart","ka10081").getHost()).isEqualTo("api.kiwoom.com");
        assertThat(fixture.calls).isEmpty();
    }
    @Test void mockEnvironmentSelectsOnlyOfficialMockHostForAuthenticationAndMarketRequests() throws Exception {
        var service=new KiwoomReadOnlyService(mapper,true,true,"fixture-key","fixture-secret","MOCK");
        assertThat(transportUri(service,"/oauth2/token","au10001").toString()).isEqualTo("https://mockapi.kiwoom.com/oauth2/token");
        assertThat(transportUri(service,"/api/dostk/stkinfo","ka10095").toString()).isEqualTo("https://mockapi.kiwoom.com/api/dostk/stkinfo");
    }
    @Test void unknownEnvironmentAndUrlsDisableAllRequestsRatherThanSelectingAnyHost() {
        for(String environment:List.of("PAPER","https://api.kiwoom.com","https://mockapi.kiwoom.com","https://attacker.invalid","")) {
            Fixture fixture=new Fixture();
            var service=new KiwoomReadOnlyService(fixture,mapper,fixture.clock,true,true,"fixture-key","fixture-secret",environment);
            assertThat(service.isEnabled()).isFalse();
            assertThat(service.readiness()).containsEntry("status","INVALID_ENVIRONMENT").containsEntry("environment","INVALID");
            assertThat(service.lookup("005930")).isNull();assertThat(service.lookupAll(List.of("005930"))).isEmpty();
            assertThat(service.fetchEvidence(List.of("005930")).get("status")).isEqualTo("INVALID_ENVIRONMENT");
            assertThat(fixture.calls).isEmpty();
        }
    }
    @Test void mockQuotesAndEveryEvidenceGroupAreMarkedAsSimulationAndCannotClaimProductionSource() {
        Fixture fixture=new Fixture();fixture.reply("ka10095",quoteBody("005930","20261012","095959"));
        fixture.reply("ka10001",basic("005930"));fixture.reply("ka10081",chartBody("005930",60));fixture.reply("ka10059",flows(5));
        var service=new KiwoomReadOnlyService(fixture,mapper,fixture.clock,true,true,"fixture-key","fixture-secret","MOCK");
        assertThat(service.lookupAll(List.of("005930")).get("005930").source()).isEqualTo("KIWOOM_REST_MOCK");
        assertThat(service.readiness()).containsEntry("environment","MOCK").containsEntry("source","KIWOOM_REST_MOCK");
        assertThat(service.readiness().get("scope").toString()).contains("모의","실전");
        Map<String,Object> result=service.fetchEvidence(List.of("005930"));
        assertThat(result).containsEntry("environment","MOCK").containsEntry("source","KIWOOM_REST_MOCK");
        assertThat(result.get("scope").toString()).contains("모의","실전");
        for(String name:List.of("quote","valuation","technical","investorDaily"))
            assertThat(part(result,name)).containsEntry("environment","MOCK").containsEntry("source","KIWOOM_REST_MOCK");
    }
    @Test void mockMarketQueriesNeedEnabledCredentialsButDoNotClaimRealRedistributionRights() {
        Fixture fixture=new Fixture();
        var disabled=new KiwoomReadOnlyService(fixture,mapper,fixture.clock,false,true,"fixture-key","fixture-secret","MOCK");
        var unlicensed=new KiwoomReadOnlyService(fixture,mapper,fixture.clock,true,false,"fixture-key","fixture-secret","MOCK");
        assertThat(disabled.fetchEvidence(List.of("005930")).get("status")).isEqualTo("DISABLED");
        assertThat(unlicensed.isEnabled()).isTrue();
        assertThat(unlicensed.readiness()).containsEntry("status","READY").containsEntry("licensed",false)
            .containsEntry("environment","MOCK");
        assertThat(disabled.lookupAll(List.of("005930"))).isEmpty();
        assertThat(fixture.calls).isEmpty();
    }
    @Test void technicalConditionsAreComputedFromOneConsecutiveProviderHistory() {
        Fixture fixture=new Fixture();fixture.reply("ka10081",chartBody("005930",60));
        Map<String,Object> facts=part(only(fixture,"technical"),"technical");
        assertThat(facts).containsEntry("maAligned",true).containsEntry("coverageEnd","2026-10-08");
        assertThat((java.math.BigDecimal)facts.get("volumeRatio20")).isEqualByComparingTo("1");
        assertThat(facts.get("coverageStart")).isNotNull();
        assertThat(facts.get("volumeRatioBasis")).isEqualTo("LATEST_COMPLETED_VOLUME_OVER_20_COMPLETED_AVERAGE");
    }
    @Test void mockRequestsRespectOnePerSecondWhileRealKeepsFivePerSecond() {
        Fixture fixture=new Fixture();fixture.reply("ka10001",basic("005930"));fixture.reply("ka10081",chartBody("005930",60));
        var service=new KiwoomReadOnlyService(fixture,mapper,fixture.clock,true,true,"fixture-key","fixture-secret","MOCK");
        service.fetchEvidence(List.of("005930"),Map.of("005930",List.of("valuation","technical")));
        assertThat(fixture.calls).hasSize(3);
        for(int index=1;index<fixture.calls.size();index++)
            assertThat(fixture.calls.get(index).nanos()-fixture.calls.get(index-1).nanos()).isGreaterThanOrEqualTo(Duration.ofMillis(995).toNanos());
        assertThat(service.readiness()).containsEntry("maxRequestsPerSecond",1);
        assertThat(fixture.service(true,true).readiness()).containsEntry("maxRequestsPerSecond",5);
    }

    @Test void disabledAndUnlicensedAdaptersNeverAuthenticate() {
        Fixture fixture=new Fixture();
        assertThat(fixture.service(false,true).lookup("005930")).isNull();
        assertThat(fixture.service(true,false).fetchEvidence(List.of("005930")).get("status")).isEqualTo("RIGHTS_UNCONFIRMED");
        assertThat(fixture.calls).isEmpty();
    }
    @Test void missingCredentialsAndInvalidCodesNeverAuthenticate() {
        Fixture fixture=new Fixture();
        var service=new KiwoomReadOnlyService(fixture,mapper,fixture.clock,true,true,"","");
        assertThat(service.lookup("005930")).isNull();
        assertThat(service.readiness().get("status")).isEqualTo("NOT_CONFIGURED");
        assertThat(fixture.service(true,true).lookupAll(List.of("005930_NX","005930_AL","ACCOUNT","A005930"))).isEmpty();
        assertThat(fixture.calls).isEmpty();
    }
    @Test void readinessExposesNoCredentialsOrTokenAndDoesNotMakeRequests() {
        Fixture fixture=new Fixture();
        String value=fixture.service(true,true).readiness().toString();
        assertThat(value).doesNotContain("fixture-key","fixture-secret","fixture-token");
        assertThat(fixture.calls).isEmpty();
    }
    @Test void quotePreservesSignedPercentAndConvertsMillionWonWithoutInventingMarket() {
        Fixture fixture=new Fixture(); fixture.reply("ka10095",quoteBody("005930","20261012","095959"));
        var quote=fixture.service(true,true).lookup("005930");
        assertThat(quote.price()).isEqualByComparingTo("70000");
        assertThat(quote.changePct()).isEqualByComparingTo("-1.50");
        assertThat(quote.tradingValueKrw()).isEqualByComparingTo("123500000");
        assertThat(quote.observedAt()).isEqualTo(Instant.parse("2026-10-12T00:59:59Z"));
        assertThat(quote.fetchedAt()).isEqualTo(now);
        assertThat(quote.market()).isEqualTo("UNKNOWN");
        assertThat(quote.source()).isEqualTo("KIWOOM_REST");
    }
    @Test void wrongIdentityAndOtherExchangeRowsAreNotReturned() {
        for(String code:List.of("000660","005930_NX","005930_AL","A005930")) {
            Fixture fixture=new Fixture(); fixture.reply("ka10095",quoteBody(code,"20261012","095959"));
            assertThat(fixture.service(true,true).lookup("005930")).isNull();
        }
    }
    @Test void duplicateQuoteRowsFailInsteadOfChoosingAnArbitraryPrice() {
        Fixture fixture=new Fixture(); ObjectNode body=quoteBody("005930","20261012","095959");
        body.withArray("atn_stk_infr").add(body.path("atn_stk_infr").get(0).deepCopy());
        fixture.reply("ka10095",body);
        assertThat(fixture.service(true,true).lookupAll(List.of("005930"))).isEmpty();
    }
    @Test void staleOrFutureIntradayQuoteDoesNotPassAsFreshMarketPrice() {
        for(String[] observed:List.of(new String[]{"20261008","153000"},new String[]{"20261012","090000"},new String[]{"20261012","100010"},new String[]{"20261013","090000"})) {
            Fixture fixture=new Fixture(); fixture.reply("ka10095",quoteBody("005930",observed[0],observed[1]));
            assertThat(fixture.service(true,true).lookupAll(List.of("005930"))).isEmpty();
        }
    }
    @Test void basicPriceFallbackKeepsUnknownObservationTime() {
        Fixture fixture=new Fixture(); fixture.reply("ka10001",basic("005930"));
        var quote=fixture.service(true,true).lookup("005930");
        assertThat(quote.price()).isEqualByComparingTo("70000");
        assertThat(quote.observedAt()).isNull();
        assertThat(quote.tradingValueKrw()).isNull();
    }
    @Test void missingOrMalformedNumbersAreNeverReplacedWithZero() {
        Fixture fixture=new Fixture(); ObjectNode body=quoteBody("005930","20261012","095959");
        ((ObjectNode)body.path("atn_stk_infr").get(0)).put("trde_qty","not-a-number");
        fixture.reply("ka10095",body);
        assertThat(fixture.service(true,true).lookupAll(List.of("005930"))).isEmpty();
    }
    @Test void batchQuotesAreBoundedDeduplicatedAndRequestedAsPipeSeparatedKrxCodes() {
        Fixture fixture=new Fixture(); fixture.reply("ka10095",quoteBody("005930","20261012","095959"));
        assertThat(fixture.service(true,true).lookupAll(List.of("005930","005930","000660","035420","051910","BAD"))).containsOnlyKeys("005930");
        assertThat(fixture.calls.stream().filter(c->c.api().equals("ka10095")).findFirst().orElseThrow().params())
            .containsEntry("stk_cd","005930|000660|035420");
    }
    @Test void verifiedQuotesKeepOriginalCaptureTimeWhenReadFromCache() {
        Fixture fixture=new Fixture(); fixture.reply("ka10095",quoteBody("005930","20261012","095959"));
        var service=fixture.service(true,true);
        var first=service.lookup("005930"); var second=service.lookup("005930");
        assertThat(second.fetchedAt()).isEqualTo(first.fetchedAt());
        assertThat(fixture.calls.stream().filter(c->c.api().equals("ka10095")).count()).isEqualTo(1);
    }
    @Test void previousSessionQuoteCachedBeforeOpenIsRejectedAfterTheMarketOpens() {
        Fixture fixture=new Fixture(); MutableClock clock=new MutableClock(Instant.parse("2026-10-11T23:59:50Z"));
        fixture.clock=clock;fixture.reply("ka10095",quoteBody("005930","20261008","153000"));
        var service=fixture.service(true,true);
        var beforeOpen=service.lookupAll(List.of("005930"));
        assertThat(beforeOpen).containsKey("005930");
        assertThat(beforeOpen.get("005930").fetchedAt()).isEqualTo(Instant.parse("2026-10-11T23:59:50Z"));
        clock.advance(Duration.ofSeconds(15));
        assertThat(service.lookupAll(List.of("005930"))).isEmpty();
    }
    @Test void cachedIntradayQuoteExpiresByActualObservationAgeAndKeepsOriginalFetchTime() {
        Fixture fixture=new Fixture(); MutableClock clock=new MutableClock(now);
        fixture.clock=clock;fixture.reply("ka10095",quoteBody("005930","20261012","095703"));
        var service=fixture.service(true,true);
        var first=service.lookupAll(List.of("005930"));
        assertThat(first).containsKey("005930");
        clock.advance(Duration.ofSeconds(2));
        var stillFresh=service.lookupAll(List.of("005930"));
        assertThat(stillFresh).containsKey("005930");
        assertThat(stillFresh.get("005930").fetchedAt()).isEqualTo(now);
        clock.advance(Duration.ofSeconds(2));
        assertThat(service.lookupAll(List.of("005930"))).isEmpty();
    }
    @Test void confirmedPrimaryPartsAreNotQueriedAndUnknownPartNamesCannotAddEndpoints() {
        Fixture fixture=new Fixture(); fixture.reply("ka10081",chartBody("005930",60));
        Map<String,Object> result=fixture.service(true,true).fetchEvidence(List.of("005930"),Map.of("005930",List.of("technical","orders","accounts")));
        assertThat(part(result,"technical").get("status")).isEqualTo("AVAILABLE");
        assertThat(part(result,"quote").get("status")).isEqualTo("NOT_REQUESTED");
        assertThat(part(result,"valuation").get("status")).isEqualTo("NOT_REQUESTED");
        assertThat(fixture.calls.stream().map(Call::api).filter(a->!a.equals("au10001")).toList()).containsExactly("ka10081");
    }
    @Test void anEmptyMissingPartsMapDoesNotAuthenticate() {
        Fixture fixture=new Fixture();
        assertThat(fixture.service(true,true).fetchEvidence(List.of("005930"),Map.of()).get("status")).isEqualTo("NOT_REQUESTED");
        assertThat(fixture.calls).isEmpty();
    }
    @Test void basicValuationKeepsVendorUpdateUncertaintyAndOmitsUnknownFields() {
        Fixture fixture=new Fixture(); fixture.reply("ka10001",basic("005930").put("private_note","PRIVATE").put("oyr_hgst","999999"));
        Map<String,Object> facts=part(only(fixture,"valuation"),"valuation");
        assertThat(facts.get("per")).isEqualTo(new BigDecimal("12.34"));
        assertThat(facts.get("roe")).isEqualTo(new BigDecimal("-3.40"));
        assertThat(facts.get("observationTimeBasis")).isEqualTo("NOT_PROVIDED");
        assertThat(facts.get("providerUpdateBasis")).isEqualTo("WEEKLY_OR_EARNINGS_SEASON");
        assertThat(facts).doesNotContainKeys("high52","observedAt","reportingPeriod","private_note","score");
    }
    @Test void chartCalculatesMovingAveragesAndBreakoutAgainstPreviousTwentyBars() {
        Fixture fixture=new Fixture(); fixture.reply("ka10081",chartBody("005930",60));
        Map<String,Object> facts=part(only(fixture,"technical"),"technical");
        assertThat(facts.get("ma5")).isEqualTo(new BigDecimal("198.00"));
        assertThat(facts.get("ma20")).isEqualTo(new BigDecimal("190.50"));
        assertThat(facts.get("ma60")).isEqualTo(new BigDecimal("170.50"));
        assertThat(facts.get("previous20High")).isEqualTo(new BigDecimal("200"));
        assertThat(facts.get("breakout20")).isEqualTo(true);
        assertThat(facts.get("observedDate")).isEqualTo("2026-10-08");
        assertThat(facts.get("observationTimeBasis")).isEqualTo("DATE_ONLY");
        assertThat(facts).doesNotContainKey("observedAt");
        assertThat(fixture.calls.stream().filter(c->c.api().equals("ka10081")).findFirst().orElseThrow().params())
            .containsEntry("base_dt","20261008").containsEntry("upd_stkpc_tp","1");
    }
    @Test void currentIncompleteBarIsExcludedAndDailyRetentionIsBoundedToOneHundred() {
        Fixture fixture=new Fixture(); ObjectNode body=chartBody("005930",105);
        body.withArray("stk_dt_pole_chart_qry").insert(0,mapper.createObjectNode().put("dt","20261012").put("cur_prc","999999")
            .put("open_pric","999999").put("high_pric","999999").put("low_pric","999999").put("trde_qty","1"));
        fixture.reply("ka10081",body);
        Map<String,Object> facts=part(only(fixture,"technical"),"technical");
        assertThat(facts.get("bars")).isEqualTo(100);
        assertThat(facts.get("ma5")).isEqualTo(new BigDecimal("198.00"));
    }
    @Test void shortHistoryDoesNotInventSixtyDayAverageOrTwentyDayBreakout() {
        Fixture fixture=new Fixture(); fixture.reply("ka10081",chartBody("005930",10));
        Map<String,Object> facts=part(only(fixture,"technical"),"technical");
        assertThat(facts.get("status")).isEqualTo("PARTIAL_COVERAGE");
        assertThat(facts).containsKey("ma5").doesNotContainKeys("ma20","ma60","previous20High","breakout20");
    }
    @Test void aMissingRecentTradingDayPreventsTwentyAndSixtyDayMetricsDespiteSixtyAvailableBars() {
        Fixture fixture=new Fixture(); ObjectNode body=chartBody("005930",61);
        body.withArray("stk_dt_pole_chart_qry").remove(10); fixture.reply("ka10081",body);
        Map<String,Object> facts=part(only(fixture,"technical"),"technical");
        assertThat(facts.get("status")).isEqualTo("PARTIAL_COVERAGE");
        assertThat(facts.get("bars")).isEqualTo(10);
        assertThat(facts.get("availableBars")).isEqualTo(60);
        assertThat(facts.get("ma5")).isEqualTo(new BigDecimal("198.00"));
        assertThat(facts).doesNotContainKeys("ma20","ma60","previous20High","breakout20");
        assertThat(facts.get("observedDate")).isEqualTo("2026-10-08");
    }
    @Test void malformedOhlcAndDuplicateDatesAndWrongChartIdentityFailClosed() {
        for(int variant=0;variant<3;variant++) {
            Fixture fixture=new Fixture(); ObjectNode body=chartBody("005930",60);
            if(variant==0) ((ObjectNode)body.path("stk_dt_pole_chart_qry").get(0)).put("low_pric","300");
            if(variant==1) body.withArray("stk_dt_pole_chart_qry").add(body.path("stk_dt_pole_chart_qry").get(0).deepCopy());
            if(variant==2) body.put("stk_cd","000660");
            fixture.reply("ka10081",body);
            assertThat(part(only(fixture,"technical"),"technical").get("status")).isEqualTo("INVALID_DATA");
        }
    }
    @Test void staleDailyHistoryCannotBePresentedAsLatestCompletedTechnicalEvidence() {
        Fixture fixture=new Fixture(); ObjectNode body=chartBody("005930",60);
        body.withArray("stk_dt_pole_chart_qry").remove(0); fixture.reply("ka10081",body);
        assertThat(part(only(fixture,"technical"),"technical").get("status")).isEqualTo("STALE_DATA");
    }
    @Test void dailyInvestorFlowPreservesSignedSharesAndUsesOnlyCompletedPreviousDays() {
        Fixture fixture=new Fixture(); fixture.reply("ka10059",flows(5));
        Map<String,Object> facts=part(only(fixture,"investorDaily"),"investorDaily");
        assertThat(facts.get("foreignNetBuy5dShares")).isEqualTo(-510L);
        assertThat(facts.get("institutionNetBuy5dShares")).isEqualTo(1010L);
        assertThat(facts.get("quantityUnit")).isEqualTo("shares");
        assertThat(facts.get("observedDate")).isEqualTo("2026-10-08");
        assertThat(facts.get("observationTimeBasis")).isEqualTo("DATE_ONLY");
        assertThat(facts).doesNotContainKeys("observedAt","score","estimated");
        assertThat(fixture.calls.stream().filter(c->c.api().equals("ka10059")).findFirst().orElseThrow().params())
            .containsEntry("dt","20261008").containsEntry("amt_qty_tp","2").containsEntry("trde_tp","0").containsEntry("unit_tp","1");
    }
    @Test void missingInvestorSharesAreUnknownRatherThanZero() {
        Fixture fixture=new Fixture(); ObjectNode body=flows(5);
        ((ObjectNode)body.path("stk_invsr_orgn").get(0)).remove("orgn"); fixture.reply("ka10059",body);
        assertThat(part(only(fixture,"investorDaily"),"investorDaily").get("status")).isEqualTo("INVALID_DATA");
    }
    @Test void providerErrorsNeverExposeErrorBodyAndDoNotBecomeAvailable() {
        Fixture fixture=new Fixture();
        Map<String,Object> evidence=fixture.service(true,true).fetchEvidence(List.of("005930"));
        assertThat(evidence.toString()).doesNotContain("PRIVATE-PROVIDER-ERROR","fixture-token","fixture-key","fixture-secret");
        assertThat(evidence.get("status")).isEqualTo("UNAVAILABLE");
    }
    @Test void tokenTransportFailureIsCooledDownAcrossAllSupplementParts() {
        Fixture fixture=new Fixture(); fixture.tokenTransportFails=true;
        Map<String,Object> evidence=fixture.service(true,true).fetchEvidence(List.of("005930"));
        assertThat(evidence.toString()).doesNotContain("PRIVATE-TOKEN-FAILURE");
        assertThat(evidence.get("status")).isEqualTo("UNAVAILABLE");
        assertThat(fixture.calls.stream().filter(c->c.path().equals("/oauth2/token")).count()).isEqualTo(1);
    }
    @Test void allReadOnlyRequestsShareTokenAndGlobalFivePerSecondThrottle() {
        Fixture fixture=new Fixture(); fixture.reply("ka10001",basic("005930"));
        fixture.reply("ka10081",chartBody("005930",60)); fixture.reply("ka10059",flows(5));
        fixture.service(true,true).fetchEvidence(List.of("005930"),Map.of("005930",List.of("valuation","technical","investorDaily")));
        assertThat(fixture.calls.stream().filter(c->c.path().equals("/oauth2/token")).count()).isEqualTo(1);
        for(int index=1;index<fixture.calls.size();index++)
            assertThat(fixture.calls.get(index).nanos()-fixture.calls.get(index-1).nanos()).isGreaterThanOrEqualTo(Duration.ofMillis(195).toNanos());
        assertThat(fixture.calls.stream().map(Call::path).toList()).allMatch(p->p.equals("/oauth2/token")||p.equals("/api/dostk/stkinfo")||p.equals("/api/dostk/chart"));
    }
}
