package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KisRealtimeFeedServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:30:00Z"));
    private final KisSourceGateway gateway = mock(KisSourceGateway.class);
    private final WebSocket socket = mock(WebSocket.class);
    private KisRealtimeFeedService service() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.wsApproval(anyLong())).thenReturn("fixture-approval");
        when(socket.sendText(anyString(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
        var worker = mock(ScheduledExecutorService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(worker).execute(any(Runnable.class));
        return new KisRealtimeFeedService(gateway, new ObjectMapper(), clock,
            listener -> { listener.onOpen(socket); return CompletableFuture.completedFuture(socket); }, worker);
    }
    private void subscribed(KisRealtimeFeedService feed, String code) {
        feed.acceptFrame("{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"" + code
            + "\",\"encrypt\":\"N\"},\"body\":{\"rt_cd\":\"0\",\"msg1\":\"SUBSCRIBE SUCCESS\"}}", socket);
    }
    @Test void exposesOnlyConfirmedWatchedActualTradesAndExpiresAfterThirtySeconds() {
        var feed = service(); feed.watch(List.of("005930")); subscribed(feed, "005930");
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930", "093000", "20261012", "70000", "10", "100", "7000000"), socket);
        assertThat(feed.facts("005930")).containsEntry("status", "AVAILABLE").containsEntry("currentPrice", 70000L);
        clock.now = clock.now.plusSeconds(31);
        assertThat(feed.facts("005930")).containsEntry("status", "STALE").doesNotContainKey("currentPrice");
    }
    @Test void rejectsTradesBeforeSuccessfulSubscriptionAndFailedAcknowledgements() {
        var feed = service(); feed.watch(List.of("005930"));
        feed.acceptFrame("{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"005930\"},\"body\":{\"rt_cd\":\"1\"}}", socket);
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930", "093000", "20261012", "70000", "10", "100", "7000000"), socket);
        assertThat(feed.facts("005930")).doesNotContainKey("currentPrice");
        assertThat(feed.readiness()).containsEntry("subscribedCodes", 0);
    }
    @Test void outOfOrderAndDuplicateTradesNeverRefreshOriginalObservation() {
        var feed = service(); feed.watch(List.of("005930")); subscribed(feed, "005930");
        String frame = KisRealtimeFrameParserTest.frame("005930", "093000", "20261012", "70000", "10", "100", "7000000");
        feed.acceptFrame(frame, socket);
        clock.now = clock.now.plusSeconds(10); feed.acceptFrame(frame, socket);
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930", "092959", "20261012", "60000", "10", "90", "5400000"), socket);
        assertThat(feed.facts("005930")).containsEntry("currentPrice", 70000L).containsEntry("fetchedAt", "2026-10-12T00:30:00Z");
    }
    @Test void watchListIsBoundedAndRejectsInvalidCodes() {
        var feed = service();
        var codes = java.util.stream.IntStream.range(1, 31).mapToObj(i -> String.format("%06d", i)).toList();
        feed.watch(codes);
        assertThat(feed.readiness()).containsEntry("requestedCodes", 20);
        feed.watch(List.of("005930", "invalid", "005930", "000660"));
        assertThat(feed.readiness()).containsEntry("requestedCodes", 2);
    }
    @Test void disabledAndHolidayFactsCannotBePublishedAsRealtime() {
        var feed = service(); feed.watch(List.of("005930")); subscribed(feed, "005930");
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930", "093000", "20261012", "70000", "10", "100", "7000000"), socket);
        when(gateway.isEnabled()).thenReturn(false);
        assertThat(feed.facts("005930")).containsEntry("status", "DISABLED").doesNotContainKey("currentPrice");
        when(gateway.isEnabled()).thenReturn(true); clock.now = Instant.parse("2026-10-09T00:30:00Z");
        assertThat(feed.facts("005930")).containsEntry("status", "MARKET_CLOSED").doesNotContainKey("currentPrice");
    }
    @Test void pingPongRepliesDoNotCountAsQuotesOrLeakFields() {
        var feed = service(); feed.watch(List.of("005930"));
        feed.acceptFrame("{\"header\":{\"tr_id\":\"PINGPONG\"}}", socket);
        verify(socket).sendPong(any());
        assertThat(feed.readiness()).containsEntry("freshQuotes", 0).doesNotContainKeys("approvalKey", "appKey", "appSecret");
    }
    @Test void newTradingDayRestartsAccumulatedVolumeInsteadOfKeepingYesterdaysTrade() {
        var feed = service(); feed.watch(List.of("005930")); subscribed(feed, "005930");
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930", "093000", "20261012", "70000", "10", "100", "7000000"), socket);
        clock.now = Instant.parse("2026-10-13T00:30:00Z");
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930", "093000", "20261013", "71000", "10", "20", "1420000"), socket);
        assertThat(feed.facts("005930")).containsEntry("currentPrice", 71000L).containsEntry("volume", 20L);
    }
    @Test void malformedSuccessMarkersDoNotCreateAConfirmedSubscription() {
        var feed = service(); feed.watch(List.of("005930"));
        feed.acceptFrame("{\"header\":{\"tr_id\":\"H0STCNT0\",\"tr_key\":\"005930\",\"encrypt\":\"N\"},\"body\":{\"rt_cd\":0,\"msg1\":\"SUBSCRIBE SUCCESS\"}}", socket);
        assertThat(feed.readiness()).containsEntry("subscribedCodes", 0);
    }
    @Test void abandonedConnectionCannotOverwriteTheNewWatchSession() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.wsApproval(anyLong())).thenReturn("fixture-approval");
        var worker = mock(ScheduledExecutorService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(worker).execute(any(Runnable.class));
        var listeners = new ArrayList<WebSocket.Listener>();
        var feed = new KisRealtimeFeedService(gateway, new ObjectMapper(), clock,
            listener -> { listeners.add(listener); return new CompletableFuture<>(); }, worker);
        feed.watch(List.of("005930"));
        feed.watch(List.of("000660"));
        var oldSocket = mock(WebSocket.class);
        listeners.getFirst().onOpen(oldSocket);
        verify(oldSocket).abort();
        assertThat(feed.readiness()).containsEntry("connected", false).containsEntry("requestedCodes", 1);
    }
    @Test void failingConnectionRetriesAreSpacedAndStopAfterFiveAttempts() {
        when(gateway.isEnabled()).thenReturn(true);
        when(gateway.wsApproval(anyLong())).thenReturn("fixture-approval");
        var worker = mock(ScheduledExecutorService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(worker).execute(any(Runnable.class));
        var connections = new AtomicInteger();
        var feed = new KisRealtimeFeedService(gateway, new ObjectMapper(), clock, listener -> {
            connections.incrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("fixture transport"));
        }, worker);
        feed.watch(List.of("005930"));
        clock.now = clock.now.plusSeconds(1); ReflectionTestUtils.invokeMethod(feed, "maintain");
        assertThat(connections).hasValue(1);
        for (int i = 0; i < 8; i++) {
            clock.now = clock.now.plusSeconds(31); ReflectionTestUtils.invokeMethod(feed, "maintain");
        }
        assertThat(connections).hasValue(5);
        assertThat(feed.readiness()).containsEntry("reconnectFailures", 5).containsEntry("freshQuotes", 0);
    }
    @Test void increasingVolumeWithDecreasingTurnoverCannotRefreshAQuote() {
        var feed = service(); feed.watch(List.of("005930")); subscribed(feed, "005930");
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930", "093000", "20261012", "70000", "10", "100", "7000000"), socket);
        clock.now = clock.now.plusSeconds(1);
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930", "093001", "20261012", "80000", "10", "110", "6500000"), socket);
        assertThat(feed.facts("005930")).containsEntry("currentPrice", 70000L).containsEntry("volume", 100L);
    }
    @Test void supplementalSourcesUseTheSameBoundedSocketAndDoNotReplaceKrxQuotes() {
        var feed=service();var supplemental=new KisRealtimeSupplementService(gateway,new ObjectMapper(),clock);
        supplemental.watch(List.of("005930","000660"));feed.configureSupplemental(supplemental);
        var codes=new ArrayList<String>(List.of("005930","000660"));
        for(int i=1;i<=18;i++)codes.add(String.format("%06d",i));
        feed.watch(codes);
        verify(gateway,times(1)).wsApproval(anyLong());verify(socket,times(31)).sendText(anyString(),eq(true));
        assertThat(feed.readiness()).containsEntry("totalSubscriptions",31);
        feed.acceptFrame("{\"header\":{\"tr_id\":\"H0NXCNT0\",\"tr_key\":\"005930\",\"encrypt\":\"N\"},\"body\":{\"rt_cd\":\"0\",\"msg1\":\"SUBSCRIBE SUCCESS\"}}",socket);
        feed.acceptFrame(KisRealtimeFrameParserTest.frame("005930","093000","20261012","71000","10","100","7100000").replace("H0STCNT0","H0NXCNT0"),socket);
        assertThat(supplemental.facts("005930")).containsEntry("availableChannels",1);
        assertThat(feed.facts("005930")).doesNotContainKey("currentPrice");
        feed.shutdown();assertThat(supplemental.readiness()).containsEntry("connected",false);
        assertThat(supplemental.facts("005930")).containsEntry("availableChannels",0);
    }
    static class MutableClock extends Clock {
        Instant now; MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
}
