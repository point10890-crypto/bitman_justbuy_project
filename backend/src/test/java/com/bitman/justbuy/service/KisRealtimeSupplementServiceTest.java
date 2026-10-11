package com.bitman.justbuy.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.WebSocket;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class KisRealtimeSupplementServiceTest {
    private final KisSourceGateway gateway = mock(KisSourceGateway.class);
    private final WebSocket socket = mock(WebSocket.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:30:00Z"), ZoneOffset.UTC);
    private KisRealtimeSupplementService feed() {
        when(gateway.isEnabled()).thenReturn(true); when(gateway.wsApproval(anyLong())).thenReturn("fixture-approval");
        when(socket.sendText(anyString(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
        return new KisRealtimeSupplementService(gateway,new ObjectMapper(),clock);
    }
    private void ack(KisRealtimeSupplementService feed,String tr,String code) {
        feed.onConnected(socket);
        feed.acceptFrame("{\"header\":{\"tr_id\":\""+tr+"\",\"tr_key\":\""+code+"\",\"encrypt\":\"N\"},\"body\":{\"rt_cd\":\"0\",\"msg1\":\"SUBSCRIBE SUCCESS\"}}",socket);
    }
    @Test void subscriptionBudgetStaysBelowCombinedFortyAndRejectsBadSymbols() {
        var feed=feed(); feed.watch(List.of("005930","000660","035420","invalid"));
        assertThat(feed.readiness()).containsEntry("watchedCodes",2).containsEntry("requestedSubscriptions",11)
            .containsEntry("combinedSubscriptionUpperBound",31);
    }
    @Test void nxtAndIntegratedTradesStaySeparateAndNeverReplaceKrxCurrentPrice() {
        var feed=feed();feed.watch(List.of("005930"));ack(feed,"H0NXCNT0","005930");ack(feed,"H0UNCNT0","005930");
        String raw=KisRealtimeFrameParserTest.frame("005930","093000","20261012","70000","10","100","7000000");
        feed.acceptFrame(raw.replace("H0STCNT0","H0NXCNT0"),socket);
        feed.acceptFrame(raw.replace("H0STCNT0","H0UNCNT0").replace("70000^", "71000^"),socket);
        assertThat(feed.facts("005930")).containsKey("markets").doesNotContainKey("currentPrice");
        var markets=(Map<?,?>)feed.facts("005930").get("markets");
        assertThat(markets.containsKey("NXT")).isTrue();assertThat(markets.containsKey("INTEGRATED")).isTrue();
    }
    @Test void unapprovedBadDateAndWrongChannelFramesDoNotBecomeEvidence() {
        var feed=feed();feed.watch(List.of("005930"));
        String frame=KisRealtimeFrameParserTest.frame("005930","093000","20261012","70000","10","100","7000000");
        feed.acceptFrame(frame.replace("H0STCNT0","H0NXCNT0"),socket);
        assertThat(feed.facts("005930")).containsEntry("availableChannels",0);
        ack(feed,"H0NXCNT0","005930");feed.acceptFrame(frame.replace("H0STCNT0","H0NXCNT0").replace("20261012","20261008"),socket);
        feed.acceptFrame(frame.replace("H0STCNT0","H0STCNI0"),socket);
        assertThat(feed.facts("005930")).containsEntry("availableChannels",0);
    }
    @Test void bookRequiresRealLevelsAndPreservesMissingSourceDate() {
        var feed=feed();feed.watch(List.of("005930"));ack(feed,"H0STASP0","005930");
        String[] f=new String[63];Arrays.fill(f,"0");f[0]="005930";f[1]="093000";f[3]="70100";f[13]="70000";f[23]="10";f[33]="20";f[43]="10";f[44]="20";
        feed.acceptFrame("0|H0STASP0|001|"+String.join("^",f),socket);
        assertThat(feed.facts("005930")).containsEntry("availableChannels",1);
        assertThat(feed.facts("005930").toString()).contains("NOT_PROVIDED", "bestAsk", "70100");
    }
    @Test void marketStatusHasOneSubscriptionScopeAndDoesNotInventObservationTime() {
        var feed=feed();feed.watch(List.of("005930","000660"));ack(feed,"H0UNMKO0","005930");
        feed.acceptFrame("0|H0UNMKO0|001|N^^20^20^0^0^0^0^0^1",socket);
        assertThat(feed.facts("005930")).containsEntry("availableChannels",1);
        assertThat(feed.facts("000660")).containsEntry("availableChannels",0);
        assertThat(feed.facts("005930").toString()).contains("SUBSCRIPTION_SCOPE", "NOT_PROVIDED");
    }
    @Test void historicalCallIsReadOnlyBoundedAndRejectsWrongDates() throws Exception {
        var feed=feed();
        var mapper=new ObjectMapper();
        var wrong=mapper.readTree("{\"rt_cd\":\"0\",\"output1\":{\"hts_kor_isnm\":\"삼성전자\"},\"output2\":[{\"stck_bsop_date\":\"20261012\",\"stck_cntg_hour\":\"093000\",\"stck_prpr\":\"70000\",\"stck_oprc\":\"69900\",\"stck_hgpr\":\"70100\",\"stck_lwpr\":\"69800\",\"cntg_vol\":\"10\"}]}");
        when(gateway.fetch(anyString(),anyString(),anyMap(),anyLong())).thenReturn(new KisSourceGateway.Snapshot("AVAILABLE",clock.instant(),wrong));
        assertThat(feed.historicalEvidence(List.of("005930"))).containsEntry("status","UNAVAILABLE");
        verify(gateway).fetch(eq("/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice"),eq("FHKST03010230"),
            argThat(p -> "J".equals(p.get("FID_COND_MRKT_DIV_CODE")) && "20261008".equals(p.get("FID_INPUT_DATE_1"))),anyLong());
    }
    @Test void integratedOrderBookUsesAllSixtySixOfficialFields() {
        var feed=feed();feed.watch(List.of("005930"));ack(feed,"H0UNASP0","005930");
        String[] f=new String[66];Arrays.fill(f,"0");f[0]="005930";f[1]="093000";f[3]="70100";f[13]="70000";f[23]="10";f[33]="20";f[43]="10";f[44]="20";
        feed.acceptFrame("0|H0UNASP0|001|"+String.join("^",f),socket);
        assertThat(feed.facts("005930")).containsEntry("availableChannels",1);
    }
    private com.fasterxml.jackson.databind.JsonNode historicalRows(String volume) throws Exception {
        return new ObjectMapper().readTree("{\"rt_cd\":\"0\",\"output1\":{\"hts_kor_isnm\":\"삼성전자\",\"stck_shrn_iscd\":\"005930\"},\"output2\":[{\"stck_bsop_date\":\"20261008\",\"stck_cntg_hour\":\"153000\",\"stck_prpr\":\"70000\",\"stck_oprc\":\"69900\",\"stck_hgpr\":\"70100\",\"stck_lwpr\":\"69800\",\"cntg_vol\":\""+volume+"\"}]}");
    }
    @Test void validHistoricalBarsRetainTheirActualDateAndNeverBecomeCurrentPrice() throws Exception {
        var feed=feed();when(gateway.fetch(anyString(),anyString(),anyMap(),anyLong())).thenReturn(new KisSourceGateway.Snapshot("AVAILABLE",clock.instant(),historicalRows("10")));
        var result=feed.historicalEvidence(List.of("005930"));assertThat(result).containsEntry("status","AVAILABLE").doesNotContainKey("currentPrice");
        assertThat(result.toString()).contains("2026-10-08", "MAX_120_BARS_NOT_FULL_SESSION");
    }
    @Test void realZeroVolumeHistoricalBarsArePreserved() throws Exception {
        var feed=feed();when(gateway.fetch(anyString(),anyString(),anyMap(),anyLong())).thenReturn(new KisSourceGateway.Snapshot("AVAILABLE",clock.instant(),historicalRows("0")));
        var result=feed.historicalEvidence(List.of("005930"));assertThat(result).containsEntry("status","AVAILABLE");
        assertThat(result.toString()).contains("volume=0");
    }
    @Test void futureHistoricalFetchTimesDoNotBecomeEvidence() throws Exception {
        var feed=feed();when(gateway.fetch(anyString(),anyString(),anyMap(),anyLong())).thenReturn(new KisSourceGateway.Snapshot("AVAILABLE",clock.instant().plusSeconds(6),historicalRows("10")));
        assertThat(feed.historicalEvidence(List.of("005930"))).containsEntry("status","UNAVAILABLE");
    }
    @Test void replayedMarketStatusCannotRenewItsThirtySecondValidity() {
        var moving=new KisRealtimeFeedServiceTest.MutableClock(clock.instant());when(gateway.isEnabled()).thenReturn(true);
        var feed=new KisRealtimeSupplementService(gateway,new ObjectMapper(),moving);feed.watch(List.of("005930"));ack(feed,"H0UNMKO0","005930");
        String frame="0|H0UNMKO0|001|N^^20^20^0^0^0^0^0^1";feed.acceptFrame(frame,socket);
        moving.now=moving.now.plusSeconds(20);feed.acceptFrame(frame,socket);moving.now=moving.now.plusSeconds(11);
        assertThat(feed.facts("005930")).containsEntry("availableChannels",0);
    }
    @Test void regressingNxtCumulativeVolumeDoesNotReplaceAcceptedEvidence() {
        var moving=new KisRealtimeFeedServiceTest.MutableClock(clock.instant());when(gateway.isEnabled()).thenReturn(true);
        var feed=new KisRealtimeSupplementService(gateway,new ObjectMapper(),moving);feed.watch(List.of("005930"));ack(feed,"H0NXCNT0","005930");
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930","093000","20261012","70000","10","100","7000000").replace("H0STCNT0","H0NXCNT0"),socket);
        moving.now=moving.now.plusSeconds(1);
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930","093001","20261012","80000","10","90","7200000").replace("H0STCNT0","H0NXCNT0"),socket);
        var markets=(Map<?,?>)feed.facts("005930").get("markets");var nxt=(Map<?,?>)markets.get("NXT");var trade=(Map<?,?>)nxt.get("trade");
        assertThat(trade.get("tradePrice")).isEqualTo(70000L);assertThat(trade.get("volume")).isEqualTo(100L);
    }
}
