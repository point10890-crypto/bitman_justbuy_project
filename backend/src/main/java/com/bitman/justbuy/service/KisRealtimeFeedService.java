package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One bounded, read-only KRX quote connection. It never subscribes to account or order notifications. */
@Service
public class KisRealtimeFeedService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final URI ENDPOINT = URI.create("ws://ops.koreainvestment.com:21000");
    interface SocketConnector { CompletionStage<WebSocket> connect(WebSocket.Listener listener); }
    private final KisSourceGateway gateway;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SocketConnector connector;
    private final ScheduledExecutorService worker;
    private final KisRealtimeFrameParser parser = new KisRealtimeFrameParser();
    private Set<String> watched = Set.of();
    private KisRealtimeSupplementService supplemental;
    private Set<String> supplementalRequests = Set.of();
    private final Set<String> subscribed = new LinkedHashSet<>();
    private final Map<String, KisRealtimeFrameParser.Quote> quotes = new LinkedHashMap<>();
    private WebSocket socket;
    private boolean started, stopped, connecting;
    private long generation;
    private int failures;
    private Instant reconnectAt = Instant.EPOCH, lastFrame = Instant.EPOCH;

    @Autowired
    public KisRealtimeFeedService(KisSourceGateway gateway, ObjectMapper mapper) {
        this(gateway, mapper, Clock.systemUTC(), defaultConnector(), Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "kis-readonly-quotes"); thread.setDaemon(true); return thread;
        }));
    }
    KisRealtimeFeedService(KisSourceGateway gateway, ObjectMapper mapper, Clock clock,
                          SocketConnector connector, ScheduledExecutorService worker) {
        this.gateway = gateway; this.mapper = mapper; this.clock = clock; this.connector = connector; this.worker = worker;
    }

    private static SocketConnector defaultConnector() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return listener -> client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(ENDPOINT, listener);
    }

    @Autowired(required = false)
    public synchronized void configureSupplemental(KisRealtimeSupplementService supplemental) {
        this.supplemental = supplemental;
    }

    @PostConstruct
    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        worker.scheduleAtFixedRate(this::maintain, 5, 5, TimeUnit.SECONDS);
    }

    public synchronized void watch(List<String> codes) {
        if (stopped) return;
        var valid = new LinkedHashSet<String>();
        if (codes != null) for (String code : codes) {
            if (code != null && code.matches("[0-9]{6}")) valid.add(code);
            if (valid.size() == 20) break;
        }
        Set<String> nextSupplemental = supplemental == null ? Set.of() : supplemental.subscriptions().stream()
            .map(request -> request.trId() + "|" + request.code()).collect(java.util.stream.Collectors.toSet());
        if (!watched.equals(valid) || !supplementalRequests.equals(nextSupplemental)) {
            watched = Set.copyOf(valid); generation++; failures = 0; reconnectAt = Instant.EPOCH;
            supplementalRequests = Set.copyOf(nextSupplemental);
            quotes.keySet().retainAll(watched);
            closeSocket();
        }
        start();
        worker.execute(this::maintain);
    }

    private synchronized boolean marketOpen() {
        var now = clock.instant().atZone(KST);
        return KoreanMarketCalendar.isTradingDay(now.toLocalDate())
            && !now.toLocalTime().isBefore(LocalTime.of(9, 0)) && !now.toLocalTime().isAfter(LocalTime.of(15, 30));
    }

    private void maintain() {
        final long attempt;
        synchronized (this) {
            if (stopped) return;
            if (!gateway.isEnabled() || !marketOpen() || watched.isEmpty()) { closeSocket(); return; }
            if (socket != null && !socket.isInputClosed() && !socket.isOutputClosed()) {
                if (clock.instant().isAfter(lastFrame.plusSeconds(90))) {
                    closeSocket(); failConnection();
                }
                return;
            }
            if (connecting || failures >= 5 || clock.instant().isBefore(reconnectAt)) return;
            connecting = true; attempt = generation;
        }
        try {
            String approval = gateway.wsApproval(System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
            if (approval == null || !approval.matches("[A-Za-z0-9_-]{1,2048}")) {
                synchronized (this) { if (attempt == generation) failConnection(); }
                return;
            }
            connector.connect(new QuoteListener(attempt, approval)).toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS).whenComplete((ws, failure) -> {
                synchronized (KisRealtimeFeedService.this) {
                    if (attempt != generation || stopped) { if (ws != null) ws.abort(); return; }
                    if (failure != null) failConnection();
                }
            });
        } catch (RuntimeException ignored) {
            synchronized (this) { if (attempt == generation) failConnection(); }
        }
    }

    private synchronized void failConnection() {
        generation++;
        var previous = socket;
        connecting = false; subscribed.clear(); socket = null;
        if (supplemental != null) supplemental.reset();
        if (previous != null) previous.abort();
        failures = Math.min(5, failures + 1);
        reconnectAt = clock.instant().plusSeconds(Math.min(30, 1L << failures));
    }

    private synchronized void closeSocket() {
        var previous = socket;
        if (previous != null || connecting) generation++;
        socket = null; connecting = false; subscribed.clear();
        if (supplemental != null) supplemental.reset();
        if (previous != null) previous.abort();
    }

    public synchronized Map<String, Object> facts(String code) {
        if (!gateway.isEnabled()) return Map.of("status", "DISABLED", "source", "KIS KRX 실시간 체결");
        if (!marketOpen()) return Map.of("status", "MARKET_CLOSED", "source", "KIS KRX 실시간 체결");
        var quote = quotes.get(code);
        if (!watched.contains(code) || !subscribed.contains(code) || quote == null || socket == null)
            return Map.of("status", "UNAVAILABLE", "source", "KIS KRX 실시간 체결");
        Instant now = clock.instant();
        long age = Duration.between(quote.tradedAt(), now).toSeconds();
        if (quote.tradedAt().isAfter(now.plusSeconds(5)) || now.isAfter(quote.tradedAt().plusSeconds(30))
            || socket.isInputClosed() || socket.isOutputClosed())
            return Map.of("status", "STALE", "source", "KIS KRX 실시간 체결", "observedAt", quote.tradedAt().toString(),
                "fetchedAt", quote.receivedAt().toString());
        return Map.of("status", "AVAILABLE", "source", "KIS KRX 실시간 체결", "code", quote.code(),
            "currentPrice", quote.price(), "volume", quote.accumulatedVolume(), "tradingValue", quote.tradingValue(),
            "observedAt", quote.tradedAt().toString(), "fetchedAt", quote.receivedAt().toString(), "freshnessSeconds", Math.max(0, age));
    }

    public synchronized Map<String, Object> readiness() {
        int fresh = (int) watched.stream().filter(code -> "AVAILABLE".equals(facts(code).get("status"))).count();
        boolean connected = socket != null && !socket.isInputClosed() && !socket.isOutputClosed();
        String status = !gateway.isEnabled() ? "DISABLED" : !marketOpen() ? "MARKET_CLOSED"
            : fresh > 0 ? "AVAILABLE" : connecting ? "CONNECTING" : connected ? "WAITING_FOR_TRADES" : "DISCONNECTED";
        return Map.of("status", status, "source", "KIS KRX 실시간 체결", "connected", connected,
            "requestedCodes", watched.size(), "subscribedCodes", subscribed.size(), "freshQuotes", fresh,
            "reconnectFailures", failures, "maxAgeSeconds", 30, "totalSubscriptions", watched.size() + supplementalRequests.size());
    }

    synchronized void acceptFrame(String frame, WebSocket receivedSocket) {
        if (frame == null || frame.length() > 65_536 || receivedSocket != socket || stopped) return;
        lastFrame = clock.instant();
        if (frame.startsWith("{")) {
            try {
                var root = mapper.readTree(frame);
                String trId = root.path("header").path("tr_id").asText();
                if ("PINGPONG".equals(trId)) {
                    byte[] pong = frame.getBytes(StandardCharsets.UTF_8);
                    if (pong.length <= 125) receivedSocket.sendPong(ByteBuffer.wrap(pong));
                } else if ("H0STCNT0".equals(trId)) {
                    String code = root.path("header").path("tr_key").asText();
                    if (!watched.contains(code)) return;
                    if (root.path("body").path("rt_cd").isTextual()
                        && "0".equals(root.path("body").path("rt_cd").asText())
                        && "N".equals(root.path("header").path("encrypt").asText())
                        && root.path("body").path("msg1").isTextual()
                        && !root.path("body").path("msg1").asText().startsWith("UNSUB")) {
                        subscribed.add(code); failures = 0;
                    }
                    else subscribed.remove(code);
                } else if (supplemental != null) supplemental.acceptFrame(frame, receivedSocket);
            } catch (Exception ignored) { /* No raw control frame or secret-bearing output is logged. */ }
            return;
        }
        if (!frame.startsWith("0|H0STCNT0|")) {
            if (supplemental != null) supplemental.acceptFrame(frame, receivedSocket);
            return;
        }
        for (var quote : parser.parse(frame, watched, clock)) {
            if (!subscribed.contains(quote.code())) continue;
            var previous = quotes.get(quote.code());
            if (previous != null && (quote.tradedAt().isBefore(previous.tradedAt())
                || (quote.tradedAt().atZone(KST).toLocalDate().equals(previous.tradedAt().atZone(KST).toLocalDate())
                    && (quote.accumulatedVolume() <= previous.accumulatedVolume()
                        || quote.tradingValue().compareTo(previous.tradingValue()) <= 0)))) continue;
            quotes.put(quote.code(), quote);
        }
    }

    private final class QuoteListener implements WebSocket.Listener {
        private final long attempt;
        private final String approval;
        private final StringBuilder fragments = new StringBuilder();
        private QuoteListener(long attempt, String approval) { this.attempt = attempt; this.approval = approval; }
        @Override public void onOpen(WebSocket ws) {
            synchronized (KisRealtimeFeedService.this) {
                if (attempt != generation || stopped || !marketOpen() || !gateway.isEnabled()) { ws.abort(); return; }
                socket = ws; connecting = false; lastFrame = clock.instant();
                if (supplemental != null) supplemental.onConnected(ws);
                CompletionStage<WebSocket> sent = CompletableFuture.completedFuture(ws);
                var requests = new ArrayList<KisRealtimeSupplementService.Subscription>();
                for (String code : watched) requests.add(new KisRealtimeSupplementService.Subscription("H0STCNT0", code));
                if (supplemental != null) requests.addAll(supplemental.subscriptions());
                if (requests.size() > 31) { ws.abort(); failConnection(); return; }
                for (var subscription : requests) {
                    try {
                        String request = mapper.writeValueAsString(Map.of("header", Map.of("approval_key", approval,
                            "custtype", "P", "tr_type", "1", "content-type", "utf-8"), "body",
                            Map.of("input", Map.of("tr_id", subscription.trId(), "tr_key", subscription.code()))));
                        sent = sent.thenCompose(active -> active.sendText(request, true));
                    } catch (Exception ignored) { ws.abort(); failConnection(); return; }
                }
                sent.whenComplete((active, failure) -> { if (failure != null) onError(ws, failure); });
            }
            ws.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            synchronized (KisRealtimeFeedService.this) {
                if (attempt != generation || socket != ws || stopped) return null;
                if (fragments.length() + text.length() > 65_536) {
                    fragments.setLength(0); ws.abort(); failConnection(); return null;
                }
                fragments.append(text);
                if (last) { String frame = fragments.toString(); fragments.setLength(0); acceptFrame(frame, ws); }
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            synchronized (KisRealtimeFeedService.this) {
                if (attempt != generation || socket != ws || stopped) return null;
                lastFrame = clock.instant();
            }
            ws.request(1); return ws.sendPong(message);
        }
        @Override public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            synchronized (KisRealtimeFeedService.this) {
                if (attempt != generation || socket != ws || stopped) return null;
                lastFrame = clock.instant();
            }
            ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            synchronized (KisRealtimeFeedService.this) { if (attempt == generation && socket == ws) failConnection(); }
            return null;
        }
        @Override public void onError(WebSocket ws, Throwable error) {
            synchronized (KisRealtimeFeedService.this) { if (attempt == generation && socket == ws) { ws.abort(); failConnection(); } }
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopped = true; generation++; closeSocket(); watched = Set.of(); quotes.clear(); worker.shutdownNow();
    }
}
