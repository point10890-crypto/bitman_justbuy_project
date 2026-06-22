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

/**
 * 주도주(FLOW_LEADER) 조건검색 엔진 — "룰 엔진 후보 + AI 검증" 레퍼런스 구현.
 *
 * 흐름: KIS 쌍끌이 후보(결정론적) → 룰 스코어/리스크필터 → 증거 패킷 → DeepSeek 검증 → TOP3 신호.
 * AI 비활성/실패 시 룰 점수만으로 신호를 만든다 (fail-open).
 *
 * {@link ShortTermRealtimeScanner} 의 스케줄·세션가드·캡처시각·TTL 스냅샷 패턴을 따른다.
 */
@Component
@ConditionalOnProperty(name = "bitman.leaders.engine.enabled", havingValue = "true", matchIfMissing = true)
public class LeaderConditionEngine {

    private static final Logger log = LoggerFactory.getLogger(LeaderConditionEngine.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(KST);
    private static final LocalTime SESSION_START = LocalTime.of(9, 0);
    private static final LocalTime SESSION_END = LocalTime.of(15, 30);
    private static final int CANDIDATE_LIMIT = 12;
    private static final int FLOW_DAYS = 5;
    private static final int VERIFY_LIMIT = 6;
    private static final int SIGNAL_LIMIT = 3;

    private final KisApiService kisApiService;
    private final DeepSeekConditionVerifier verifier;
    private final Map<String, String> firstSeenByCode = new ConcurrentHashMap<>();
    private volatile LocalDate firstSeenDate = LocalDate.MIN;
    private volatile LeaderSnapshot latest = LeaderSnapshot.empty();

    @Value("${bitman.leaders.engine.fresh-ttl-ms:1200000}")
    private long freshTtlMs = 1_200_000L;

    public LeaderConditionEngine(KisApiService kisApiService) {
        this(kisApiService, (DeepSeekConditionVerifier) null);
    }

    @Autowired
    public LeaderConditionEngine(KisApiService kisApiService,
                                 ObjectProvider<DeepSeekConditionVerifier> verifierProvider) {
        this(kisApiService, verifierProvider.getIfAvailable());
    }

    LeaderConditionEngine(KisApiService kisApiService, DeepSeekConditionVerifier verifier) {
        this.kisApiService = kisApiService;
        this.verifier = verifier;
    }

    public record LeaderSnapshot(
        String asOf,
        Instant scannedAt,
        String sourceStatus,
        boolean tradingSession,
        List<ConditionSignalDto> signals
    ) {
        static LeaderSnapshot empty() {
            return new LeaderSnapshot("", Instant.EPOCH, "LEADER_WAITING", false, List.of());
        }
    }

    @Scheduled(
        fixedDelayString = "${bitman.leaders.engine.interval-ms:600000}",
        initialDelayString = "${bitman.leaders.engine.initial-delay-ms:60000}"
    )
    public void scheduledScan() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        if (!isTradingSession(now)) return;
        try {
            scanNow();
        } catch (Exception e) {
            log.warn("[LeaderEngine] scan failed: {}", e.getMessage());
        }
    }

    public synchronized LeaderSnapshot scanNow() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        resetDailySeen(now.toLocalDate());

        if (!isTradingSession(now)) {
            latest = new LeaderSnapshot(nowKst(now), now.toInstant(), "LEADER_WAITING", false, latest.signals());
            return latest;
        }

        List<KisApiService.ConditionCandidate> candidates =
            kisApiService.fetchDoubleNetBuyCandidates(CANDIDATE_LIMIT, FLOW_DAYS);
        List<ConditionSignalDto> signals = buildSignals(candidates, now);
        latest = new LeaderSnapshot(
            nowKst(now),
            now.toInstant(),
            signals.isEmpty() ? "LEADER_EMPTY" : "LEADER_SCAN",
            true,
            signals
        );
        log.info("[LeaderEngine] scan completed: candidates={}, picks={}, aiVerify={}",
            candidates.size(), signals.size(), verifier != null && verifier.isAvailable());
        return latest;
    }

    List<ConditionSignalDto> buildSignals(List<KisApiService.ConditionCandidate> rawCandidates, ZonedDateTime now) {
        resetDailySeen(now.toLocalDate());
        if (rawCandidates == null || rawCandidates.isEmpty()) return List.of();

        List<Scored> scored = new ArrayList<>();
        int rank = 1;
        for (KisApiService.ConditionCandidate candidate : rawCandidates) {
            if (candidate.code() == null || !candidate.code().matches("\\d{6}")) continue;
            if (candidate.name() == null || candidate.name().isBlank()) continue;
            if (StockUniverseFilters.isExchangeTradedProductName(candidate.name())) continue;

            double changeRate = parseDouble(candidate.changeRate());
            ConditionSignalScoring.ScoreBreakdown breakdown = ConditionSignalScoring.leader(
                rank,
                changeRate,
                candidate.foreignNet(),
                candidate.institutionNet(),
                candidate.totalSmartNet()
            );
            scored.add(new Scored(candidate, breakdown, changeRate));
            rank++;
        }

        if (scored.isEmpty()) return List.of();

        scored.sort(Comparator.comparingInt((Scored s) -> s.breakdown.finalScore()).reversed());
        List<Scored> verifyPool = scored.size() > VERIFY_LIMIT ? scored.subList(0, VERIFY_LIMIT) : scored;

        Map<String, EvidenceVerdict> verdicts = verifyVerdicts(verifyPool, now);

        List<ConditionSignalDto> signals = new ArrayList<>();
        int signalRank = 1;
        for (Scored s : verifyPool) {
            EvidenceVerdict verdict = verdicts.get(s.candidate.code());
            if (verdict != null && verdict.rejected()) continue;
            String capturedAt = firstSeenByCode.computeIfAbsent(s.candidate.code(), ignored -> nowKst(now));
            signals.add(toSignal(s, verdict, signalRank++, capturedAt));
            if (signals.size() >= SIGNAL_LIMIT) break;
        }
        return signals;
    }

    private Map<String, EvidenceVerdict> verifyVerdicts(List<Scored> pool, ZonedDateTime now) {
        if (verifier == null || !verifier.isAvailable()) return Map.of();
        List<EvidencePacket> packets = new ArrayList<>();
        for (Scored s : pool) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("foreignNet", s.candidate.foreignNet());
            metrics.put("institutionNet", s.candidate.institutionNet());
            metrics.put("totalSmartNet", s.candidate.totalSmartNet());
            packets.add(new EvidencePacket(
                ConditionSection.LEADERS.name(),
                ConditionSection.LEADERS.legacyMode(),
                nowKst(now),
                s.candidate.code(),
                s.candidate.name(),
                s.candidate.currentPrice(),
                s.candidate.changeRate(),
                s.breakdown.ruleScore(),
                s.breakdown.evidence(),
                metrics
            ));
        }
        try {
            return verifier.verify(packets);
        } catch (Exception e) {
            log.warn("[LeaderEngine] verify 예외 — 룰 점수 폴백: {}", e.getMessage());
            return Map.of();
        }
    }

    private ConditionSignalDto toSignal(Scored s, EvidenceVerdict verdict, int rank, String capturedAt) {
        KisApiService.ConditionCandidate candidate = s.candidate;
        long current = parseLong(candidate.currentPrice());
        String price = formatPrice(candidate.currentPrice());
        String target = current > 0 ? formatLong(roundUpToTick(Math.round(current * 1.06))) : "-";
        String stop = current > 0 ? formatLong(roundDownToTick(Math.round(current * 0.96))) : "-";
        String maxReturnPct = calculateReturn(current, target);

        int ruleScore = s.breakdown.ruleScore();
        int aiScore = verdict != null ? verdict.aiScore() : s.breakdown.aiScore();
        int finalScore = verdict != null
            ? clamp((int) Math.round(0.6 * ruleScore + 0.4 * aiScore), 0, 99)
            : s.breakdown.finalScore();

        List<String> evidence = new ArrayList<>();
        evidence.add("KIS 쌍끌이 조건검색");
        evidence.addAll(s.breakdown.evidence());
        if (verdict != null && verdict.evidence() != null) {
            for (String e : verdict.evidence()) {
                if (!evidence.contains(e)) evidence.add(e);
            }
        }

        List<String> riskFlags = new ArrayList<>(s.breakdown.riskFlags());
        if (verdict != null && verdict.riskFlags() != null) {
            for (String r : verdict.riskFlags()) {
                if (!riskFlags.contains(r)) riskFlags.add(r);
            }
        }

        String status = verdict != null && verdict.displayStatus() != null && !verdict.displayStatus().isBlank()
            ? verdict.displayStatus()
            : gradeLabel(finalScore);

        String summary;
        if (verdict != null && verdict.summary() != null && !verdict.summary().isBlank()) {
            summary = verdict.summary();
        } else {
            summary = candidate.reason() != null && !candidate.reason().isBlank()
                ? candidate.reason()
                : "외국인·기관 동시 순매수가 확인된 주도주 후보입니다.";
        }

        String invalidation = verdict != null && verdict.invalidation() != null && !verdict.invalidation().isBlank()
            ? verdict.invalidation()
            : "외국인/기관 수급 이탈, 거래대금 감소, 섹터 강도 하락";

        return new ConditionSignalDto(
            ConditionSection.LEADERS.responseKey(),
            ConditionSection.LEADERS.legacyMode(),
            rank,
            candidate.name(),
            candidate.code(),
            price,
            price,
            target,
            stop,
            maxReturnPct,
            status,
            ruleScore,
            aiScore,
            finalScore,
            summary,
            List.copyOf(evidence),
            List.copyOf(riskFlags),
            invalidation,
            capturedAt
        );
    }

    public Optional<LeaderSnapshot> latestFresh() {
        LeaderSnapshot snapshot = latest;
        if (snapshot.signals().isEmpty()) return Optional.empty();
        if (!"LEADER_SCAN".equals(snapshot.sourceStatus())) return Optional.empty();
        if (Duration.between(snapshot.scannedAt(), Instant.now()).compareTo(freshTtl()) > 0) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    Duration freshTtl() {
        return Duration.ofMillis(Math.max(freshTtlMs, 600_000L));
    }

    public LeaderSnapshot latest() {
        return latest;
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

    private static String gradeLabel(int finalScore) {
        if (finalScore >= 80) return "강";
        if (finalScore >= 65) return "중";
        return "관찰";
    }

    private static String calculateReturn(long current, String target) {
        long targetPrice = parseLong(target);
        if (current <= 0 || targetPrice <= 0) return "-";
        return formatPercent(((double) targetPrice - current) / current * 100.0);
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

    private static long parseLong(String value) {
        if (value == null) return 0;
        String normalized = value.replaceAll("[^0-9]", "");
        if (normalized.isBlank()) return 0;
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String formatPrice(String value) {
        long price = parseLong(value);
        if (price <= 0) return value == null || value.isBlank() ? "-" : value;
        return formatLong(price);
    }

    private static String formatLong(long price) {
        return NumberFormat.getInstance(Locale.KOREA).format(price);
    }

    private static String formatPercent(double value) {
        return (value >= 0 ? "+" : "") + String.format(Locale.US, "%.2f", value) + "%";
    }

    private static long roundUpToTick(long price) {
        long tick = tickSize(price);
        return ((price + tick - 1) / tick) * tick;
    }

    private static long roundDownToTick(long price) {
        long tick = tickSize(price);
        return (price / tick) * tick;
    }

    private static long tickSize(long price) {
        if (price < 2_000) return 1;
        if (price < 5_000) return 5;
        if (price < 20_000) return 10;
        if (price < 50_000) return 50;
        if (price < 200_000) return 100;
        if (price < 500_000) return 500;
        return 1_000;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Scored {
        final KisApiService.ConditionCandidate candidate;
        final ConditionSignalScoring.ScoreBreakdown breakdown;
        final double changeRate;

        Scored(KisApiService.ConditionCandidate candidate,
               ConditionSignalScoring.ScoreBreakdown breakdown,
               double changeRate) {
            this.candidate = candidate;
            this.breakdown = breakdown;
            this.changeRate = changeRate;
        }
    }
}
