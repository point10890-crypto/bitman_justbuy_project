package com.bitman.justbuy.service;

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

    private final KisApiService kisApiService;
    private final TrackRecordService trackRecordService;
    private final DeepSeekConditionVerifier verifier;
    private final Map<String, String> firstSeenByCode = new ConcurrentHashMap<>();
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
        this.kisApiService = kisApiService;
        this.trackRecordService = trackRecordService;
        this.verifier = verifier;
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
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!isTradingSession(now)) return;
        try {
            scanNow();
        } catch (Exception e) {
            log.warn("[ShortTermRealtime] scan failed: {}", e.getMessage());
        }
    }

    public synchronized ScanSnapshot scanNow() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        resetDailySeen(now.toLocalDate());

        if (!isTradingSession(now)) {
            latest = new ScanSnapshot(nowKst(now), now.toInstant(), "REALTIME_WAITING", false, latest.signals());
            return latest;
        }

        List<KisApiService.RankedStock> volume = kisApiService.fetchRealtimeVolumeRankingStocks(RAW_SCAN_LIMIT);
        List<KisApiService.RankedStock> gainers = kisApiService.fetchRealtimeGainersRankingStocks(RAW_SCAN_LIMIT);
        List<KisApiService.RankedStock> foreignNetBuy =
            kisApiService.fetchForeignNetBuyRankingStocks(FOREIGN_SCAN_LIMIT);
        List<ConditionSignalDto> signals = buildSignals(volume, gainers, foreignNetBuy, now);
        signals = verifyAndEnrich(signals, now);
        latest = new ScanSnapshot(
            nowKst(now),
            now.toInstant(),
            signals.isEmpty() ? "REALTIME_EMPTY" : "REALTIME_SCAN",
            true,
            signals
        );
        recordTrackableSignals(signals, now);
        log.info("[ShortTermRealtime] scan completed: picks={}, volumeRank={}, gainerRank={}, foreignRank={}",
            signals.size(), volume.size(), gainers.size(), foreignNetBuy.size());
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
        if (snapshot.signals().isEmpty()) return Optional.empty();
        if (!"REALTIME_SCAN".equals(snapshot.sourceStatus())) return Optional.empty();
        if (Duration.between(snapshot.scannedAt(), Instant.now()).compareTo(freshTtl()) > 0) {
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

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int rank = 1;
        for (KisApiService.RankedStock stock : safeList(volume)) {
            merge(candidates, stock).volumeRank = rank++;
        }

        rank = 1;
        for (KisApiService.RankedStock stock : safeList(gainers)) {
            merge(candidates, stock).gainerRank = rank++;
        }

        rank = 1;
        for (KisApiService.RankedStock stock : safeList(foreignNetBuy)) {
            Candidate candidate = merge(candidates, stock);
            candidate.foreignRank = rank++;
            candidate.foreignNetBuy = Math.max(candidate.foreignNetBuy, stock.foreignNetBuy());
        }

        List<Candidate> sorted = candidates.values().stream()
            .filter(Candidate::hasUsableCode)
            .filter(candidate -> StockUniverseFilters.isKoreanSpotEquity(candidate.name, candidate.code))
            .filter(candidate -> candidate.changeRate > 0)
            .peek(Candidate::calculateScore)
            .filter(candidate -> candidate.score >= 58)
            .sorted(Comparator.comparingInt((Candidate candidate) -> candidate.score).reversed())
            .limit(3)
            .toList();

        List<ConditionSignalDto> signals = new ArrayList<>();
        int signalRank = 1;
        for (Candidate candidate : sorted) {
            String capturedAt = firstSeenByCode.computeIfAbsent(candidate.code, ignored -> nowKst(now));
            signals.add(candidate.toSignal(signalRank++, capturedAt));
        }
        return signals;
    }

    /**
     * 룰 기반 단타 신호에 DeepSeek AI 검증/보강을 선택적으로 덧붙인다 (fail-open).
     *
     * <p>verifier 가 없거나 비활성이면 입력 신호를 그대로 반환한다. 활성 시 신호별 증거 패킷을 만들어
     * 한 번에 검증하고, REJECT 판정은 제외하며, 나머지는 룰/AI 점수를 0.6/0.4 로 블렌딩하고
     * 증거·리스크·요약·무효화 조건을 병합해 새 immutable DTO 로 재구성한다. 어떤 예외든 원본 신호로 폴백한다.
     */
    List<ConditionSignalDto> verifyAndEnrich(List<ConditionSignalDto> signals, ZonedDateTime now) {
        if (signals == null || signals.isEmpty()) return signals;
        if (verifier == null || !verifier.isAvailable()) return signals;

        try {
            List<EvidencePacket> packets = new ArrayList<>();
            for (ConditionSignalDto signal : signals) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("rank", signal.rank());
                metrics.put("maxReturnPct", signal.maxReturnPct());
                metrics.put("currentPrice", signal.currentPrice());
                packets.add(new EvidencePacket(
                    ConditionSection.SHORT_TERM.name(),
                    ConditionSection.SHORT_TERM.legacyMode(),
                    nowKst(now),
                    signal.stockCode(),
                    signal.stockName(),
                    signal.currentPrice(),
                    signal.maxReturnPct(),
                    signal.ruleScore(),
                    signal.evidence(),
                    metrics
                ));
            }

            Map<String, EvidenceVerdict> verdicts = verifier.verify(packets);
            if (verdicts == null || verdicts.isEmpty()) return signals;

            List<ConditionSignalDto> enriched = new ArrayList<>();
            int rank = 1;
            for (ConditionSignalDto signal : signals) {
                EvidenceVerdict verdict = verdicts.get(signal.stockCode());
                if (verdict == null) {
                    enriched.add(reRank(signal, rank++));
                    continue;
                }
                if (verdict.rejected()) continue;
                enriched.add(applyVerdict(signal, verdict, rank++));
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
            signal.capturedAt()
        );
    }

    private static ConditionSignalDto reRank(ConditionSignalDto signal, int rank) {
        if (signal.rank() == rank) return signal;
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
            signal.status(),
            signal.ruleScore(),
            signal.aiScore(),
            signal.finalScore(),
            signal.summary(),
            signal.evidence(),
            signal.riskFlags(),
            signal.invalidation(),
            signal.capturedAt()
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Candidate merge(Map<String, Candidate> candidates, KisApiService.RankedStock stock) {
        String code = normalize(stock.code());
        Candidate candidate = candidates.computeIfAbsent(code, ignored -> new Candidate());
        candidate.name = firstNonBlank(stock.name(), candidate.name);
        candidate.code = firstNonBlank(code, candidate.code);
        candidate.currentPrice = firstNonBlank(stock.currentPrice(), candidate.currentPrice);
        candidate.changeRateText = firstNonBlank(stock.changeRate(), candidate.changeRateText);
        candidate.changeRate = Math.max(candidate.changeRate, parseDouble(stock.changeRate()));
        candidate.volumeText = firstNonBlank(stock.volume(), candidate.volumeText);
        candidate.volume = Math.max(candidate.volume, parseLong(stock.volume()));
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
        if (value == null) return 0;
        String normalized = value.replace("%", "").replace("+", "").replace(",", "").trim();
        if (normalized.isBlank()) return 0;
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String formatPrice(String value) {
        long price = parseLong(value);
        if (price <= 0) return firstNonBlank(value, "-");
        return NumberFormat.getInstance(Locale.KOREA).format(price);
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
        long volume;
        long foreignNetBuy;
        int volumeRank;
        int gainerRank;
        int foreignRank;
        int score;
        ConditionSignalScoring.ScoreBreakdown scoreBreakdown;

        boolean hasUsableCode() {
            return code != null && code.matches("\\d{6}") && name != null && !name.isBlank();
        }

        void calculateScore() {
            scoreBreakdown = ConditionSignalScoring.shortTerm(
                volumeRank,
                gainerRank,
                foreignRank,
                changeRate,
                volume,
                foreignNetBuy
            );
            score = scoreBreakdown.finalScore();
        }

        ConditionSignalDto toSignal(int rank, String capturedAt) {
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
            int aiScore = scoreBreakdown != null ? scoreBreakdown.aiScore() : 0;
            int finalScore = scoreBreakdown != null ? scoreBreakdown.finalScore() : score;

            return new ConditionSignalDto(
                ConditionSection.SHORT_TERM.responseKey(),
                ConditionSection.SHORT_TERM.legacyMode(),
                rank,
                name,
                code,
                price,
                price,
                pct,
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
                capturedAt
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
