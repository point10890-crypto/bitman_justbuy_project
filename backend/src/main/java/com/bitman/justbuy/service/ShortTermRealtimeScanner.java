package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.dto.condition.ConditionSection;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.dto.condition.EvidenceVerdict;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.bitman.justbuy.util.StockUniverseFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "bitman.short-term.realtime.enabled", havingValue = "true", matchIfMissing = true)
public class ShortTermRealtimeScanner {

    private static final Logger log = LoggerFactory.getLogger(ShortTermRealtimeScanner.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(KST);
    private static final LocalTime SESSION_START = LocalTime.of(9, 0);
    private static final LocalTime SESSION_END = LocalTime.of(15, 20);
    private static final int RAW_SCAN_LIMIT = 80;
    private static final int FOREIGN_SCAN_LIMIT = 40;
    private static final int VERIFICATION_POOL_SIZE = 6;
    private static final String KIS_SOURCE = ShortTermBreakoutEvidence.QUOTE_SOURCE;

    private final KisApiService kisApiService;
    private final TrackRecordService trackRecordService;
    private final DeepSeekConditionVerifier verifier;
    private final Clock clock;
    private KiwoomConditionEnricher kiwoomEnricher;
    private ConditionLiquidityService liquidity;
    private ConditionCaptureStore captures;
    @Autowired(required=false) public void configureCaptures(ConditionCaptureStore captures) { this.captures = captures; }
    @Autowired
    public void configureLiquidity(ConditionLiquidityService liquidity) { this.liquidity = liquidity; }
    @Autowired(required=false)
    public void configureKiwoomEnricher(KiwoomConditionEnricher enricher) { this.kiwoomEnricher=enricher; }
    private record Capture(String capturedAt, String price) {}
    private final Map<String, Capture> firstSeenByCode = new ConcurrentHashMap<>();
    private volatile LocalDate firstSeenDate = LocalDate.MIN;
    private volatile ScanSnapshot latest = ScanSnapshot.empty();

    @Value("${bitman.short-term.realtime.fresh-ttl-ms:240000}")
    private long freshTtlMs = 240_000L;

    public ShortTermRealtimeScanner(KisApiService kisApiService) {
        this(kisApiService, (TrackRecordService) null, (DeepSeekConditionVerifier) null);
    }

    public ShortTermRealtimeScanner(KisApiService kisApiService,
                                    ObjectProvider<TrackRecordService> trackRecordServiceProvider) {
        this(kisApiService, trackRecordServiceProvider.getIfAvailable(), (DeepSeekConditionVerifier) null);
    }

    @Autowired
    public ShortTermRealtimeScanner(KisApiService kisApiService,
                                    ObjectProvider<TrackRecordService> trackRecordServiceProvider,
                                    ObjectProvider<DeepSeekConditionVerifier> verifierProvider) {
        this(kisApiService, trackRecordServiceProvider.getIfAvailable(), verifierProvider.getIfAvailable());
    }

    ShortTermRealtimeScanner(KisApiService kisApiService, TrackRecordService trackRecordService) {
        this(kisApiService, trackRecordService, (DeepSeekConditionVerifier) null);
    }

    ShortTermRealtimeScanner(KisApiService kisApiService,
                             TrackRecordService trackRecordService,
                             DeepSeekConditionVerifier verifier) {
        this(kisApiService, trackRecordService, verifier, Clock.system(KST));
    }

    ShortTermRealtimeScanner(KisApiService kisApiService, TrackRecordService trackRecordService,
                             DeepSeekConditionVerifier verifier, Clock clock) {
        this.kisApiService = kisApiService;
        this.trackRecordService = trackRecordService;
        this.verifier = verifier;
        this.clock = clock;
    }

    public record ScanSnapshot(
        String asOf,
        Instant scannedAt,
        String sourceStatus,
        boolean tradingSession,
        List<ConditionSignalDto> signals
    ) {
        static ScanSnapshot empty() {
            return new ScanSnapshot("", Instant.EPOCH, "REALTIME_WAITING", false, List.of());
        }
    }

    @Scheduled(
        fixedDelayString = "${bitman.short-term.realtime.interval-ms:180000}",
        initialDelayString = "${bitman.short-term.realtime.initial-delay-ms:45000}"
    )
    public void scheduledScan() {
        ZonedDateTime now = clock.instant().atZone(KST);
        if (!isTradingSession(now)) return;
        try {
            scanNow();
        } catch (Exception e) {
            log.warn("[ShortTermRealtime] scan failed: {}", e.getMessage());
        }
    }

    public synchronized ScanSnapshot scanNow() {
        ZonedDateTime now = clock.instant().atZone(KST);
        resetDailySeen(now.toLocalDate());

        if (!isTradingSession(now)) {
            latest = new ScanSnapshot(nowKst(now), now.toInstant(), "REALTIME_WAITING", false, latest.signals());
            return latest;
        }

        var rankings = new Rankings(
            kisApiService.fetchRealtimeVolumeRankingSnapshot(RAW_SCAN_LIMIT),
            kisApiService.fetchRealtimeGainersRankingSnapshot(RAW_SCAN_LIMIT),
            kisApiService.fetchForeignNetBuyRankingSnapshot(FOREIGN_SCAN_LIMIT),
            kisApiService.fetchRealtimeTurnoverRankingSnapshot(30));
        ZonedDateTime checkedAt = clock.instant().atZone(KST);
        if (!isTradingSession(checkedAt)) {
            latest = new ScanSnapshot(nowKst(checkedAt), checkedAt.toInstant(), "REALTIME_WAITING", false, latest.signals());
            return latest;
        }
        Rankings received = rankings;
        rankings = received.recentAt(checkedAt);
        List<Candidate> ranked = rankCandidates(rankings);
        captureRankings(received, rankings, checkedAt);
        List<Candidate> inBand = ranked.stream().filter(Candidate::hasBreakoutChange).toList();
        var candidates = toSignals(inBand, checkedAt);
        var selection = liquidity == null
            ? new ConditionLiquidityService.SignalSelection(List.of(), Map.of(), candidates.isEmpty())
            : liquidity.selectShortTermSignals(candidates, VERIFICATION_POOL_SIZE);
        List<ConditionSignalDto> verified = List.of();
        if (!selection.signals().isEmpty()) {
            var independent = queryCandidateEvidence(inBand);
            verified = verifyAndEnrich(selection.signals(), clock.instant().atZone(KST), independent,
                selection.orderingBonuses());
        }
        ZonedDateTime publishedAt = clock.instant().atZone(KST);
        if (!isTradingSession(publishedAt) || !checkedAt.toLocalDate().equals(publishedAt.toLocalDate())) {
            latest = new ScanSnapshot(nowKst(publishedAt), publishedAt.toInstant(), "REALTIME_WAITING", false, latest.signals());
            return latest;
        }
        List<ConditionSignalDto> signals = selection.signals().isEmpty() ? List.of()
            : publish(selection.signals(), verified, publishedAt);
        boolean confirmedEmpty = rankings.complete() && selection.complete() && signals.isEmpty();
        latest = new ScanSnapshot(
            nowKst(publishedAt),
            publishedAt.toInstant(),
            !signals.isEmpty() ? "REALTIME_SCAN" : confirmedEmpty ? "REALTIME_EMPTY" : "REALTIME_UNAVAILABLE",
            true,
            signals
        );
        if (captures != null) captures.record("realtime_snapshot", "BREAKOUT", "pool", publishedAt.toInstant(), Map.of(
            "scanInputAt", checkedAt.toInstant().toString(), "sourceStatus", latest.sourceStatus(),
            "asOf", latest.asOf(), "sourcesComplete", rankings.complete(), "selectionComplete", selection.complete(),
            "signals", signals));
        recordTrackableSignals(signals, publishedAt);
        log.info("[ShortTermRealtime] scan completed: picks={}, volumeRank={}, gainerRank={}, foreignRank={}",
            signals.size(), rankings.volume().rows().size(), rankings.gainers().rows().size(), rankings.foreign().rows().size());
        return latest;
    }

    private void recordTrackableSignals(List<ConditionSignalDto> signals, ZonedDateTime now) {
        if (trackRecordService == null || signals == null || signals.isEmpty()) return;
        try {
            int recorded = trackRecordService.recordShortTermRealtimeSignals(signals, now);
            if (recorded > 0) {
                log.info("[ShortTermRealtime] recorded {} realtime picks for daily performance", recorded);
            }
        } catch (Exception e) {
            log.warn("[ShortTermRealtime] track record failed: {}", e.getMessage());
        }
    }

    public Optional<ScanSnapshot> latestFresh() {
        ScanSnapshot snapshot = latest;
        Instant now = clock.instant();
        if (!"REALTIME_SCAN".equals(snapshot.sourceStatus()) && !"REALTIME_EMPTY".equals(snapshot.sourceStatus())) return Optional.empty();
        if (!isTradingSession(now.atZone(KST)) || snapshot.scannedAt().isAfter(now)
            || Duration.between(snapshot.scannedAt(), now).compareTo(freshTtl()) > 0) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    Duration freshTtl() {
        return Duration.ofMillis(Math.max(freshTtlMs, 180_000L));
    }

    public ScanSnapshot latest() {
        return latest;
    }

    List<ConditionSignalDto> buildSignals(List<KisApiService.RankedStock> volume,
                                          List<KisApiService.RankedStock> gainers,
                                          ZonedDateTime now) {
        return buildSignals(volume, gainers, List.of(), now);
    }

    List<ConditionSignalDto> buildSignals(List<KisApiService.RankedStock> volume,
                                          List<KisApiService.RankedStock> gainers,
                                          List<KisApiService.RankedStock> foreignNetBuy,
                                          ZonedDateTime now) {
        resetDailySeen(now.toLocalDate());

        return buildCandidates(volume, gainers, foreignNetBuy, now, 3, true, List.of());
    }

    List<ConditionSignalDto> buildCandidates(List<KisApiService.RankedStock> volume,
            List<KisApiService.RankedStock> gainers, List<KisApiService.RankedStock> foreignNetBuy,
            ZonedDateTime now, int limit, boolean remember, List<KisApiService.RankedStock> turnover) {
        var rankings = rankingsAt(volume, gainers, foreignNetBuy, turnover, now.toInstant());
        var ranked = rankCandidates(rankings).stream()
            .filter(candidate -> candidate.score >= 58 || (candidate.turnoverRank > 0 && candidate.hasBreakoutChange()))
            .toList();
        var signals = toSignals(ranked, now);
        var accepted = liquidity == null ? signals.stream().limit(limit).toList() : liquidity.filterSignals("BREAKOUT", signals, limit);
        if (remember) for (ConditionSignalDto signal : accepted) {
            firstSeenByCode.putIfAbsent(signal.stockCode(), new Capture(signal.capturedAt(), signal.capturePrice()));
        }
        return accepted;
    }

    private record Rankings(KisApiService.SourceBatch<KisApiService.RankedStock> volume,
            KisApiService.SourceBatch<KisApiService.RankedStock> gainers,
            KisApiService.SourceBatch<KisApiService.RankedStock> foreign,
            KisApiService.SourceBatch<KisApiService.RankedStock> turnover) {
        boolean complete() { return volume.complete() && gainers.complete() && foreign.complete() && turnover.complete(); }

        Rankings recentAt(ZonedDateTime checkedAt) {
            return new Rankings(recent(volume, checkedAt), recent(gainers, checkedAt),
                recent(foreign, checkedAt), recent(turnover, checkedAt));
        }

        private static KisApiService.SourceBatch<KisApiService.RankedStock> recent(
                KisApiService.SourceBatch<KisApiService.RankedStock> batch, ZonedDateTime checkedAt) {
            if (batch == null || batch.fetchedAt() == null) return KisApiService.SourceBatch.unavailable();
            Instant now = checkedAt.toInstant();
            Instant open = checkedAt.toLocalDate().atTime(SESSION_START).atZone(KST).toInstant();
            if (batch.fetchedAt().isBefore(open) || batch.fetchedAt().isBefore(now.minusSeconds(120))
                || batch.fetchedAt().isAfter(now)) return KisApiService.SourceBatch.unavailable();
            return batch;
        }
    }

    private static Rankings rankingsAt(List<KisApiService.RankedStock> volume, List<KisApiService.RankedStock> gainers,
            List<KisApiService.RankedStock> foreign, List<KisApiService.RankedStock> turnover, Instant observedAt) {
        return new Rankings(new KisApiService.SourceBatch<>(safeList(volume), observedAt, true),
            new KisApiService.SourceBatch<>(safeList(gainers), observedAt, true),
            new KisApiService.SourceBatch<>(safeList(foreign), observedAt, true),
            new KisApiService.SourceBatch<>(safeList(turnover), observedAt, true));
    }

    private void captureRankings(Rankings received, Rankings accepted, ZonedDateTime now) {
        if (captures != null) captures.record("source", "BREAKOUT", "pool", now.toInstant(), Map.of(
            "volume", accepted.volume().rows(), "gainers", accepted.gainers().rows(),
            "foreignNetBuy", accepted.foreign().rows(), "turnover", accepted.turnover().rows(),
            "sourceCoverage", Map.of(
                "volume", sourceCoverage(received.volume(), accepted.volume()),
                "gainers", sourceCoverage(received.gainers(), accepted.gainers()),
                "foreignNetBuy", sourceCoverage(received.foreign(), accepted.foreign()),
                "turnover", sourceCoverage(received.turnover(), accepted.turnover()))));
    }

    private static Map<String, Object> sourceCoverage(KisApiService.SourceBatch<?> received, KisApiService.SourceBatch<?> accepted) {
        return Map.of(
            "fetchedAt", received == null || received.fetchedAt() == null ? "" : received.fetchedAt().toString(),
            "complete", accepted.complete(),
            "rowsReceived", received == null ? 0 : received.rows().size(),
            "rowsUsed", accepted.rows().size());
    }

    private List<ConditionSignalDto> toSignals(List<Candidate> ranked, ZonedDateTime now) {
        List<ConditionSignalDto> signals = new ArrayList<>();
        int signalRank = 1;
        String checkedAt = nowKst(now);
        for (Candidate candidate : ranked) {
            Capture capture = firstSeenByCode.getOrDefault(candidate.code,
                new Capture(checkedAt, formatPrice(candidate.currentPrice)));
            signals.add(candidate.toSignal(signalRank++, capture));
        }
        return signals;
    }

    List<ConditionSignalDto> publishVerified(List<ConditionSignalDto> candidates, ZonedDateTime now,
            KiwoomConditionEnricher.Result independent) {
        return publish(candidates, verifyAndEnrich(candidates, now, independent), now);
    }

    private List<ConditionSignalDto> publish(List<ConditionSignalDto> candidates,
            List<ConditionSignalDto> verified, ZonedDateTime now) {
        List<ConditionSignalDto> published = new ArrayList<>();
        for (var signal : verified) {
            if (published.size() == 3) break;
            Capture capture = firstSeenByCode.computeIfAbsent(signal.stockCode(),
                ignored -> new Capture(nowKst(now), signal.currentPrice()));
            published.add(reRank(signal, published.size() + 1, capture));
        }
        if (captures != null) captures.record("verified_publication", "BREAKOUT", "pool", now.toInstant(), Map.of(
            "verificationPool", candidates, "verified", verified, "published", published));
        return List.copyOf(published);
    }
    /** Queue cross-provider facts for the ranked original pool, before the published TOP3 reduction. */
    KiwoomConditionEnricher.Result queryCandidateEvidence(List<KisApiService.RankedStock> volume,
            List<KisApiService.RankedStock> gainers,List<KisApiService.RankedStock> foreignNetBuy) {
        return queryCandidateEvidence(rankCandidates(rankingsAt(volume, gainers, foreignNetBuy, List.of(), clock.instant())));
    }

    private KiwoomConditionEnricher.Result queryCandidateEvidence(List<Candidate> ranked) {
        if (kiwoomEnricher == null) return KiwoomConditionEnricher.empty("NOT_CONFIGURED");
        Map<String,String> names = new LinkedHashMap<>();
        ranked.stream().limit(KiwoomSupplementPolicy.CANDIDATE_LIMIT)
            .forEach(candidate->names.put(candidate.code,candidate.name));
        try {return java.util.Objects.requireNonNullElse(kiwoomEnricher.enrich(names),KiwoomConditionEnricher.empty("UNAVAILABLE"));}
        catch(RuntimeException ignored) {return KiwoomConditionEnricher.empty("UNAVAILABLE");}
    }

    private List<Candidate> rankCandidates(Rankings rankings) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int rank = 1;
        for (KisApiService.RankedStock stock : rankings.volume().rows()) {
            merge(candidates, stock, rankings.volume().fetchedAt()).volumeRank = rank++;
        }

        rank = 1;
        for (KisApiService.RankedStock stock : rankings.gainers().rows()) {
            merge(candidates, stock, rankings.gainers().fetchedAt()).gainerRank = rank++;
        }

        rank = 1;
        for (KisApiService.RankedStock stock : rankings.foreign().rows()) {
            Candidate candidate = merge(candidates, stock, rankings.foreign().fetchedAt());
            candidate.foreignRank = rank++;
            candidate.foreignNetBuy = Math.max(candidate.foreignNetBuy, stock.foreignNetBuy());
        }

        int moneyRank = 1;
        for (var stock : rankings.turnover().rows()) {
            merge(candidates, stock, rankings.turnover().fetchedAt()).turnoverRank = moneyRank++;
        }
        return candidates.values().stream()
            .filter(Candidate::hasUsableCode)
            .filter(candidate -> StockUniverseFilters.isKoreanSpotEquity(candidate.name, candidate.code))
            .filter(candidate -> parsePrice(candidate.currentPrice) > 0)
            .filter(candidate -> candidate.changeRate > 0)
            .peek(Candidate::calculateScore)
            .sorted(Comparator.comparingInt((Candidate candidate) -> candidate.score).reversed())
            .toList();
    }

    /**
     * 룰 기반 단타 신호에 DeepSeek AI 검증/보강을 선택적으로 덧붙인다 (fail-open).
     *
     * <p>키움 원자료를 독립적으로 붙인 뒤 verifier 가 없거나 비활성이면 측정 신호를 반환한다. 활성 시 증거 패킷을 만들어
     * 한 번에 검증하고, REJECT 판정은 제외하며, 나머지는 룰/AI 점수를 0.6/0.4 로 블렌딩하고
     * 증거·리스크·요약·무효화 조건을 병합해 새 immutable DTO 로 재구성한다. 어떤 예외든 원본 신호로 폴백한다.
     */
    List<ConditionSignalDto> verifyAndEnrich(List<ConditionSignalDto> signals, ZonedDateTime now) {
        if (signals == null || signals.isEmpty()) return signals;
        var independent=KiwoomConditionEnricher.empty("NOT_CONFIGURED");
        if(kiwoomEnricher!=null) {
            Map<String,String> names=new LinkedHashMap<>();
            signals.stream().limit(KiwoomSupplementPolicy.CANDIDATE_LIMIT).forEach(signal->names.put(signal.stockCode(),signal.stockName()));
            try {independent=java.util.Objects.requireNonNullElse(kiwoomEnricher.enrich(names),KiwoomConditionEnricher.empty("UNAVAILABLE"));}
            catch(RuntimeException ignored) { /* Preserve measured KIS signals. */ }
        }
        return verifyAndEnrich(signals,now,independent);
    }

    List<ConditionSignalDto> verifyAndEnrich(List<ConditionSignalDto> signals,ZonedDateTime now,KiwoomConditionEnricher.Result independent) {
        return verifyAndEnrich(signals, now, independent, Map.of());
    }

    private List<ConditionSignalDto> verifyAndEnrich(List<ConditionSignalDto> signals, ZonedDateTime now,
            KiwoomConditionEnricher.Result independent, Map<String, Integer> orderingBonuses) {
        if(signals==null || signals.isEmpty()) return signals;
        independent=java.util.Objects.requireNonNullElse(independent,KiwoomConditionEnricher.empty("UNAVAILABLE"));
        if(!independent.facts().isEmpty()) {
            List<ConditionSignalDto> observed=new ArrayList<>();
            for(ConditionSignalDto signal:signals) observed.add(independent.attach(signal));
            signals=List.copyOf(observed);
        }
        if (verifier == null || !verifier.isAvailable()) return signals;

        try {
            List<EvidencePacket> packets = new ArrayList<>();
            for (ConditionSignalDto signal : signals) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("rank", signal.rank());
                signal.conditionChecks().stream()
                    .filter(check -> ConditionCheck.BREAKOUT_DAILY_CHANGE_CHECK.equals(check.id())
                        && KIS_SOURCE.equals(check.source()) && check.observedAt() != null && check.observedValue() != null
                        && ("PASS".equals(check.status()) || "FAIL".equals(check.status())))
                    .findFirst()
                    .ifPresent(check -> metrics.put("dailyChange", Map.of(
                        "changeRate", check.observedValue(),
                        "source", check.source(),
                        "fetchedAt", check.observedAt(),
                        "unit", "%"
                    )));
                signal.conditionChecks().stream()
                    .filter(check -> ShortTermBreakoutEvidence.BREAKOUT_CHECK.equals(check.id())
                        && ShortTermBreakoutEvidence.SOURCE.equals(check.source()) && "PASS".equals(check.status())
                        && check.observedAt() != null && check.observedValue() != null)
                    .findFirst()
                    .ifPresent(check -> metrics.put("breakout", Map.of(
                        "observation", check.observedValue(),
                        "source", check.source(),
                        "fetchedAt", check.observedAt()
                    )));
                if(!independent.forCode(signal.stockCode()).isEmpty()) {
                    metrics.put("kiwoomEvidence",independent.forCode(signal.stockCode()));
                    metrics.put("kiwoomCoverage",independent.coverage());
                }
                packets.add(new EvidencePacket(
                    ConditionSection.SHORT_TERM.name(),
                    ConditionSection.SHORT_TERM.legacyMode(),
                    nowKst(now),
                    signal.stockCode(),
                    signal.stockName(),
                    signal.currentPrice(),
                    null,
                    signal.ruleScore(),
                    signal.evidence(),
                    metrics
                ));
            }

            Map<String, EvidenceVerdict> verdicts = verifier.verify(packets);
            if (verdicts == null || verdicts.isEmpty()) return signals;

            List<ConditionSignalDto> enriched = new ArrayList<>();
            for (ConditionSignalDto signal : signals) {
                EvidenceVerdict verdict = verdicts.get(signal.stockCode());
                if (verdict == null) {
                    enriched.add(signal);
                    continue;
                }
                if (verdict.rejected()) continue;
                enriched.add(applyVerdict(signal, verdict, signal.rank()));
            }
            enriched.sort(Comparator.comparingInt((ConditionSignalDto signal) ->
                signal.finalScore() + orderingBonuses.getOrDefault(signal.stockCode(), 0)).reversed());
            for (int i = 0; i < enriched.size(); i++) {
                enriched.set(i, reRank(enriched.get(i), i + 1));
            }
            return enriched;
        } catch (Exception e) {
            log.warn("[ShortTermRealtime] verifyAndEnrich 예외 — 룰 신호 폴백: {}", e.getMessage());
            return signals;
        }
    }

    private static ConditionSignalDto applyVerdict(ConditionSignalDto signal, EvidenceVerdict verdict, int rank) {
        int aiScore = verdict.aiScore();
        int finalScore = clamp((int) Math.round(0.6 * signal.ruleScore() + 0.4 * aiScore), 0, 99);

        String status = verdict.displayStatus() != null && !verdict.displayStatus().isBlank()
            ? verdict.displayStatus()
            : signal.status();

        List<String> evidence = new ArrayList<>(signal.evidence());
        if (verdict.evidence() != null) {
            for (String e : verdict.evidence()) {
                if (e != null && !e.isBlank() && !evidence.contains(e)) evidence.add(e);
            }
        }

        List<String> riskFlags = new ArrayList<>(signal.riskFlags());
        if (verdict.riskFlags() != null) {
            for (String r : verdict.riskFlags()) {
                if (r != null && !r.isBlank() && !riskFlags.contains(r)) riskFlags.add(r);
            }
        }

        String summary = verdict.summary() != null && !verdict.summary().isBlank()
            ? verdict.summary()
            : signal.summary();

        String invalidation = verdict.invalidation() != null && !verdict.invalidation().isBlank()
            ? verdict.invalidation()
            : signal.invalidation();

        return new ConditionSignalDto(
            signal.section(),
            signal.mode(),
            rank,
            signal.stockName(),
            signal.stockCode(),
            signal.capturePrice(),
            signal.currentPrice(),
            signal.highPrice(),
            signal.stopLoss(),
            signal.maxReturnPct(),
            status,
            signal.ruleScore(),
            aiScore,
            finalScore,
            summary,
            List.copyOf(evidence),
            List.copyOf(riskFlags),
            invalidation,
            signal.capturedAt(), signal.conditionChecks()
        );
    }

    private static ConditionSignalDto reRank(ConditionSignalDto signal, int rank) {
        return reRank(signal, rank, new Capture(signal.capturedAt(), signal.capturePrice()));
    }

    private static ConditionSignalDto reRank(ConditionSignalDto signal, int rank, Capture capture) {
        if (signal.rank() == rank && Objects.equals(capture.capturedAt(), signal.capturedAt())
            && Objects.equals(capture.price(), signal.capturePrice())) return signal;
        return new ConditionSignalDto(
            signal.section(),
            signal.mode(),
            rank,
            signal.stockName(),
            signal.stockCode(),
            capture.price(),
            signal.currentPrice(),
            signal.highPrice(),
            signal.stopLoss(),
            signal.maxReturnPct(),
            signal.status(),
            signal.ruleScore(),
            signal.aiScore(),
            signal.finalScore(),
            signal.summary(),
            signal.evidence(),
            signal.riskFlags(),
            signal.invalidation(),
            capture.capturedAt(), signal.conditionChecks()
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Candidate merge(Map<String, Candidate> candidates, KisApiService.RankedStock stock, Instant observedAt) {
        String code = normalize(stock.code());
        Candidate candidate = candidates.computeIfAbsent(code, ignored -> new Candidate());
        candidate.name = firstNonBlank(stock.name(), candidate.name);
        candidate.code = firstNonBlank(code, candidate.code);
        double quotedChange = parseDouble(stock.changeRate());
        if (observedAt != null && (candidate.quoteObservedAt == null || !observedAt.isBefore(candidate.quoteObservedAt))
            && parsePrice(stock.currentPrice()) > 0 && Double.isFinite(quotedChange)) {
            candidate.currentPrice = stock.currentPrice();
            candidate.changeRateText = stock.changeRate();
            candidate.changeRate = quotedChange;
            candidate.volumeText = stock.volume();
            candidate.volume = parseLong(stock.volume());
            candidate.quoteObservedAt = observedAt;
        }
        candidate.foreignNetBuy = Math.max(candidate.foreignNetBuy, stock.foreignNetBuy());
        return candidate;
    }

    private static boolean isTradingSession(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        return KoreanMarketCalendar.isTradingDay(today)
            && !time.isBefore(SESSION_START)
            && !time.isAfter(SESSION_END);
    }

    private void resetDailySeen(LocalDate today) {
        if (!today.equals(firstSeenDate)) {
            firstSeenByCode.clear();
            firstSeenDate = today;
        }
    }

    private static String nowKst(ZonedDateTime now) {
        return KST_FORMATTER.format(now);
    }

    private static List<KisApiService.RankedStock> safeList(List<KisApiService.RankedStock> stocks) {
        return stocks == null ? List.of() : stocks;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private static long parseLong(String value) {
        if (value == null) return 0;
        String normalized = value.replaceAll("[^0-9-]", "");
        if (normalized.isBlank()) return 0;
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        if (value == null || !value.trim().matches("[+-]?[0-9]+(?:\\.[0-9]+)?%?")) return Double.NaN;
        try { return Double.parseDouble(value.trim().replace("%", "")); }
        catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private static String formatPrice(String value) {
        long price = parsePrice(value);
        if (price <= 0) return "-";
        return NumberFormat.getInstance(Locale.KOREA).format(price);
    }

    static long parsePrice(String value) {
        if (value == null || !value.trim().matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)\\s*원?")) return 0;
        try {
            long price = Long.parseLong(value.trim().replace(",", "").replace("원", "").trim());
            return Math.max(0, price);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String formatPercent(double value) {
        return (value >= 0 ? "+" : "") + String.format(Locale.US, "%.2f", value) + "%";
    }

    private static String formatShares(long value) {
        return NumberFormat.getInstance(Locale.KOREA).format(value) + "주";
    }

    private static final class Candidate {
        String name = "";
        String code = "";
        String currentPrice = "";
        String changeRateText = "";
        String volumeText = "";
        double changeRate;
        Instant quoteObservedAt;
        long volume;
        long foreignNetBuy;
        int turnoverRank;
        int volumeRank;
        int gainerRank;
        int foreignRank;
        int score;
        ConditionSignalScoring.ScoreBreakdown scoreBreakdown;

        boolean hasUsableCode() {
            return code != null && code.matches("\\d{6}") && name != null && !name.isBlank();
        }

        boolean hasBreakoutChange() {
            return changeRate >= 3 && changeRate <= 15;
        }

        void calculateScore() {
            scoreBreakdown = ConditionSignalScoring.shortTerm(
                volumeRank,
                gainerRank,
                foreignRank,
                changeRate,
                volume,
                foreignNetBuy,
                turnoverRank
            );
            score = scoreBreakdown.finalScore();
        }

        ConditionSignalDto toSignal(int rank, Capture capture) {
            String price = formatPrice(currentPrice);
            String pct = changeRateText == null || changeRateText.isBlank()
                ? formatPercent(changeRate)
                : normalizePercent(changeRateText);
            String volumeLabel = volume > 0
                ? NumberFormat.getInstance(Locale.KOREA).format(volume) + "주"
                : firstNonBlank(volumeText, "-");

            List<String> evidence = new ArrayList<>();
            evidence.add("KIS 정규장 실시간 스캔");
            evidence.add("거래량 " + volumeLabel);
            if (scoreBreakdown != null) {
                evidence.addAll(scoreBreakdown.evidence());
            }

            String summary = "KIS 실시간 랭킹 기반 단타 포착";
            if (volumeRank > 0) summary += " · 거래량 " + volumeRank + "위";
            if (gainerRank > 0) summary += " · 상승률 " + gainerRank + "위";
            if (foreignRank > 0) summary += " · 외국인 순매수 " + foreignRank + "위";
            if (foreignNetBuy > 0) summary += " · 외국인 " + formatShares(foreignNetBuy);
            summary += " · 등락률 " + pct;

            List<String> riskFlags = new ArrayList<>(List.of(
                "단타 변동성 관리",
                "거래량 급감 시 신호 약화",
                "추격 매수 주의"
            ));
            if (scoreBreakdown != null) {
                riskFlags.addAll(scoreBreakdown.riskFlags());
            }
            int ruleScore = scoreBreakdown != null ? scoreBreakdown.ruleScore() : score;
            int aiScore = 0; // AI verification has not run on this measured rule signal.
            int finalScore = scoreBreakdown != null ? scoreBreakdown.finalScore() : score;

            return new ConditionSignalDto(
                ConditionSection.SHORT_TERM.responseKey(),
                ConditionSection.SHORT_TERM.legacyMode(),
                rank,
                name,
                code,
                capture.price(),
                price,
                "-",
                "",
                pct,
                "실시간 포착",
                ruleScore,
                aiScore,
                finalScore,
                summary,
                List.copyOf(evidence),
                List.copyOf(riskFlags),
                "상승률 둔화, 거래량 순위 이탈, 고점 대비 급락",
                capture.capturedAt(),
                ConditionCheck.withDailyChange("BREAKOUT", changeRate, KIS_SOURCE,
                    quoteObservedAt == null ? null : KST_FORMATTER.format(quoteObservedAt))
            );
        }

        private static String normalizePercent(String value) {
            String normalized = value.trim();
            if (!normalized.endsWith("%")) normalized += "%";
            if (!normalized.startsWith("+") && !normalized.startsWith("-")) normalized = "+" + normalized;
            return normalized;
        }
    }
}
