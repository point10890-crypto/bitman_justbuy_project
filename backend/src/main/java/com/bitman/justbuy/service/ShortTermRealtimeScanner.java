package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSection;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.bitman.justbuy.util.StockUniverseFilters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final KisApiService kisApiService;
    private final Map<String, String> firstSeenByCode = new ConcurrentHashMap<>();
    private volatile LocalDate firstSeenDate = LocalDate.MIN;
    private volatile ScanSnapshot latest = ScanSnapshot.empty();

    @Value("${bitman.short-term.realtime.fresh-ttl-ms:240000}")
    private long freshTtlMs = 240_000L;

    public ShortTermRealtimeScanner(KisApiService kisApiService) {
        this.kisApiService = kisApiService;
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
        List<ConditionSignalDto> signals = buildSignals(volume, gainers, now);
        latest = new ScanSnapshot(
            nowKst(now),
            now.toInstant(),
            signals.isEmpty() ? "REALTIME_EMPTY" : "REALTIME_SCAN",
            true,
            signals
        );
        log.info("[ShortTermRealtime] scan completed: picks={}, volumeRank={}, gainerRank={}",
            signals.size(), volume.size(), gainers.size());
        return latest;
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

    private static final class Candidate {
        String name = "";
        String code = "";
        String currentPrice = "";
        String changeRateText = "";
        String volumeText = "";
        double changeRate;
        long volume;
        int volumeRank;
        int gainerRank;
        int score;

        boolean hasUsableCode() {
            return code != null && code.matches("\\d{6}") && name != null && !name.isBlank();
        }

        void calculateScore() {
            int next = 48;
            if (volumeRank > 0) next += Math.max(0, 31 - volumeRank);
            if (gainerRank > 0) next += Math.max(0, 31 - gainerRank);
            if (volumeRank > 0 && gainerRank > 0) next += 10;
            next += (int) Math.min(16, Math.max(0, changeRate * 2.5));
            if (volume >= 1_000_000) next += 5;
            else if (volume >= 300_000) next += 3;
            score = Math.min(99, next);
        }

        ConditionSignalDto toSignal(int rank, String capturedAt) {
            String price = formatPrice(currentPrice);
            String pct = changeRateText == null || changeRateText.isBlank()
                ? formatPercent(changeRate)
                : normalizePercent(changeRateText);
            String volumeLabel = volume > 0 ? NumberFormat.getInstance(Locale.KOREA).format(volume) + "주" : firstNonBlank(volumeText, "-");

            List<String> evidence = new ArrayList<>();
            evidence.add("KIS 정규장 실시간 스캔");
            if (volumeRank > 0) evidence.add("거래량 상위 " + volumeRank + "위");
            if (gainerRank > 0) evidence.add("상승률 상위 " + gainerRank + "위");
            evidence.add("거래량 " + volumeLabel);

            String summary = "KIS 실시간 랭킹 기반 단타 포착";
            if (volumeRank > 0) summary += " · 거래량 " + volumeRank + "위";
            if (gainerRank > 0) summary += " · 상승률 " + gainerRank + "위";
            summary += " · 등락률 " + pct;

            return new ConditionSignalDto(
                ConditionSection.SHORT_TERM.responseKey(),
                ConditionSection.SHORT_TERM.legacyMode(),
                rank,
                name,
                code,
                price,
                price,
                pct,
                pct,
                "실시간 포착",
                score,
                0,
                score,
                summary,
                evidence,
                List.of("단타 변동성 확대", "거래량 급감 시 신호 약화", "추격 매수 주의"),
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
