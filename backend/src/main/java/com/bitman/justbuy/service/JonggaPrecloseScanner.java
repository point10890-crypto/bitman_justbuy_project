package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionCheck;
import com.bitman.justbuy.dto.condition.ConditionSection;
import com.bitman.justbuy.dto.condition.ConditionSectionResponse;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.bitman.justbuy.util.StockUniverseFilters;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Independent, bounded, read-only KRX scan completed before the closing call auction. */
@Component
@ConditionalOnProperty(name = "bitman.jongga.preclose.enabled", havingValue = "true", matchIfMissing = true)
public class JonggaPrecloseScanner {
    static final int SCHEMA_VERSION = 1;
    static final String STRATEGY_VERSION = "KRX_PRECLOSE_V1";
    static final String POSITIVE = "CLOSING_PRECLOSE";
    static final String EMPTY = "CLOSING_PRECLOSE_EMPTY";
    static final String PARTIAL = "CLOSING_PRECLOSE_PARTIAL";
    static final String UNAVAILABLE = "CLOSING_PRECLOSE_UNAVAILABLE";
    static final String LAST = "CLOSING_PRECLOSE_LAST";
    static final String LAST_EMPTY = "CLOSING_PRECLOSE_LAST_EMPTY";
    private static final Set<String> STATUSES = Set.of(POSITIVE, EMPTY, PARTIAL, UNAVAILABLE, LAST, LAST_EMPTY);
    private static final Logger log = LoggerFactory.getLogger(JonggaPrecloseScanner.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(KST);
    private static final LocalTime SCAN_START = LocalTime.of(15, 5);
    private static final LocalTime AUCTION_START = LocalTime.of(15, 20);
    private static final int TURNOVER_RANK_LIMIT = 30;
    private static final int EVIDENCE_LIMIT = 12;
    private static final int DISPLAY_LIMIT = 3;
    private static final long BUDGET_NANOS = Duration.ofSeconds(15).toNanos();

    record Assessment(String stockCode, String stockName, Double dayChangePct,
                      JonggaPrecloseKisSource.Observation observation, JonggaPrecloseStrategy.Judgement judgement) {}
    record Snapshot(int schemaVersion, String strategy, LocalDate date, Instant minuteCutoff, Instant rankingFetchedAt,
                    Instant publishedAt, String status, boolean rankingComplete, int rankingCount, int consideredCount,
                    List<Assessment> assessments, List<ConditionSignalDto> signals) {}

    private final KisApiService kis;
    private final JonggaPrecloseKisSource source;
    private final Clock clock;
    private final Set<LocalDate> excludedSessions;
    private final JonggaPrecloseSnapshotStore snapshots;
    private volatile Snapshot latest;
    private volatile LocalDate restoredDate;

    @Autowired
    public JonggaPrecloseScanner(KisApiService kis, JonggaPrecloseKisSource source, ObjectMapper mapper,
            @Value("${bitman.storage.data-dir:./data}") String dataDir,
            @Value("${bitman.jongga.preclose.excluded-sessions:2026-11-19}") String excludedSessions) {
        this(kis, source, mapper, Path.of(dataDir), Clock.systemUTC(), excludedSessions);
    }

    JonggaPrecloseScanner(KisApiService kis, JonggaPrecloseKisSource source, ObjectMapper mapper,
                         Path dataDir, Clock clock, String excludedSessions) {
        this.kis = kis;
        this.source = source;
        this.clock = clock;
        this.excludedSessions = Arrays.stream(excludedSessions.split(",")).map(String::trim)
            .filter(value -> !value.isEmpty()).map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
        this.snapshots = new JonggaPrecloseSnapshotStore(dataDir.resolve("jongga-preclose"), mapper);
    }

    @Scheduled(cron = "${bitman.jongga.preclose.cron:0 6,11,16 15 * * MON-FRI}", zone = "Asia/Seoul")
    public void scheduledScan() {
        try {
            scanNow();
        } catch (RuntimeException error) {
            log.warn("[JonggaPreclose] scan failed: {}", error.getClass().getSimpleName());
        }
    }

    synchronized void scanNow() {
        Instant started = clock.instant();
        LocalDate date = started.atZone(KST).toLocalDate();
        if (!inScanWindow(started, date)) return;

        var ranking = kis.fetchRealtimeTurnoverRankingSnapshot(TURNOVER_RANK_LIMIT);
        Instant cutoff = clock.instant();
        if (!inScanWindow(cutoff, date)) return;
        Instant rankingAt = ranking == null ? null : ranking.fetchedAt();
        if (rankingAt == null || rankingAt.isBefore(cutoff.minusSeconds(90)) || rankingAt.isAfter(cutoff)
            || !inScanWindow(rankingAt, date)) {
            publish(new Snapshot(SCHEMA_VERSION, STRATEGY_VERSION, date, cutoff, rankingAt, cutoff, UNAVAILABLE,
                false, 0, 0, List.of(), List.of()));
            return;
        }

        long deadline = System.nanoTime() + BUDGET_NANOS;
        List<Assessment> assessments = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean complete = ranking.complete();
        int considered = 0;
        for (var row : ranking.rows()) {
            if (!StockUniverseFilters.isKoreanSpotEquity(row.name(), row.code()) || !seen.add(row.code())) continue;
            Double change = changePct(row.changeRate());
            if (change == null) {
                complete = false;
                continue;
            }
            if (!JonggaPrecloseStrategy.inGainBand(change)) continue;
            if (considered == EVIDENCE_LIMIT) break;
            considered++;
            if (System.nanoTime() >= deadline || !inScanWindow(clock.instant(), date)) {
                complete = false;
                break;
            }
            var observation = source.load(row.code(), cutoff, deadline).orElse(null);
            if (!usable(observation, cutoff, clock.instant(), date)) {
                complete = false;
                assessments.add(new Assessment(row.code(), row.name(), change, null, null));
                continue;
            }
            var judgement = JonggaPrecloseStrategy.evaluate(observation.facts(), change, cutoff);
            complete &= judgement.complete();
            assessments.add(new Assessment(row.code(), row.name(), change, observation, judgement));
        }

        Instant publishedAt = clock.instant();
        if (!inScanWindow(publishedAt, date)) return;
        var selected = assessments.stream().filter(row -> row.judgement() != null && row.judgement().eligible())
            .sorted(Comparator.comparingInt((Assessment row) -> row.judgement().score()).reversed()
                .thenComparing(Comparator.comparingDouble((Assessment row) ->
                    (double) lastClose(row, cutoff) / row.observation().facts().todayHigh()).reversed())
                .thenComparing(Assessment::stockCode))
            .limit(DISPLAY_LIMIT).toList();
        List<ConditionSignalDto> signals = new ArrayList<>();
        for (var row : selected) signals.add(signal(row, signals.size() + 1, rankingAt, cutoff, publishedAt));
        boolean anyMeasured = assessments.stream().anyMatch(row -> row.judgement() != null);
        String status = complete ? (signals.isEmpty() ? EMPTY : POSITIVE)
            : (!signals.isEmpty() || anyMeasured ? PARTIAL : UNAVAILABLE);
        publish(new Snapshot(SCHEMA_VERSION, STRATEGY_VERSION, date, cutoff, rankingAt, publishedAt, status,
            ranking.complete(), ranking.rows().size(), considered, List.copyOf(assessments), List.copyOf(signals)));
    }

    Optional<ConditionSectionResponse> section() {
        Instant now = clock.instant();
        var local = now.atZone(KST);
        LocalDate date = local.toLocalDate();
        if (local.toLocalTime().isBefore(SCAN_START) || !regularSession(date)) return Optional.empty();
        Snapshot view = current(date);
        if (view == null || view.publishedAt().isAfter(now)) return Optional.empty();
        boolean auctionOrLater = !local.toLocalTime().isBefore(AUCTION_START);
        if (!auctionOrLater && view.publishedAt().isBefore(now.minus(Duration.ofMinutes(7)))) return Optional.empty();
        String status = view.status();
        if (auctionOrLater && POSITIVE.equals(status)) status = LAST;
        if (auctionOrLater && EMPTY.equals(status)) status = LAST_EMPTY;
        var section = ConditionSection.CLOSING_BET;
        return Optional.of(new ConditionSectionResponse(section.responseKey(), section.slug(), section.title(),
            section.legacyMode(), "/api/conditions/" + section.slug(), KST_TIME.format(view.publishedAt()), status,
            view.signals()));
    }

    static boolean isPrecloseStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    private Snapshot current(LocalDate date) {
        Snapshot snapshot = latest;
        if (snapshot != null && date.equals(snapshot.date())) return snapshot;
        if (date.equals(restoredDate)) return null;
        synchronized (snapshots) {
            if (latest != null && date.equals(latest.date())) return latest;
            if (!date.equals(restoredDate)) {
                latest = snapshots.read(date).orElse(null);
                restoredDate = date;
            }
            return latest;
        }
    }

    private void publish(Snapshot snapshot) {
        synchronized (snapshots) {
            snapshots.write(snapshot);
            latest = snapshot;
            restoredDate = snapshot.date();
        }
    }

    private ConditionSignalDto signal(Assessment row, int rank, Instant rankingAt, Instant cutoff, Instant publishedAt) {
        var observation = row.observation();
        var judgement = row.judgement();
        String price = String.format(Locale.KOREA, "%,d", lastClose(row, cutoff));
        var checks = judgement.checks().stream().map(check -> evidenceTime(check, observation, rankingAt)).toList();
        var setupLabels = judgement.setups().stream().map(id -> "CONFIRMED_20D_BREAKOUT".equals(id)
            ? "20거래일 고점 돌파" : "마감 전 강세 지속").toList();
        var evidence = new ArrayList<>(judgement.evidence());
        evidence.addAll(setupLabels);
        return new ConditionSignalDto(ConditionSection.CLOSING_BET.responseKey(), ConditionSection.CLOSING_BET.legacyMode(),
            rank, row.stockName(), row.stockCode(),
            price, price, "-", "-", String.format(Locale.ROOT, "%+.2f%%", row.dayChangePct()), "마감 전 포착",
            judgement.score(), 0, judgement.score(), String.join(" · ", setupLabels), List.copyOf(evidence),
            List.of("종가 단일가에서 가격 변동 가능", "익일 시가 갭 가능"), "마감 전 강세 또는 돌파 조건 이탈",
            KST_TIME.format(publishedAt), checks);
    }

    private static long lastClose(Assessment row, Instant cutoff) {
        return row.observation().facts().minutes().stream()
            .filter(minute -> !minute.startedAt().plusSeconds(60).isAfter(cutoff))
            .max(Comparator.comparing(JonggaPrecloseStrategy.Minute::startedAt)).orElseThrow().close();
    }

    private static ConditionCheck evidenceTime(ConditionCheck check, JonggaPrecloseKisSource.Observation observation,
                                                Instant rankingAt) {
        if ("UNKNOWN".equals(check.status())) return check;
        Instant observed = switch (check.id()) {
            case "liquidity.0" -> observation.dailyFetchedAt();
            case "technical.3" -> rankingAt;
            case "technical.0", "technical.2" -> observation.dailyFetchedAt().isAfter(observation.minuteFetchedAt())
                ? observation.dailyFetchedAt() : observation.minuteFetchedAt();
            default -> observation.minuteFetchedAt();
        };
        return new ConditionCheck(check.id(), check.category(), check.criterion(), check.status(),
            check.observedValue(), check.source(), observed.toString());
    }

    private boolean usable(JonggaPrecloseKisSource.Observation observation, Instant cutoff, Instant now, LocalDate date) {
        return observation != null && observation.facts() != null
            && recentBeforeAuction(observation.dailyFetchedAt(), cutoff, now, date)
            && recentBeforeAuction(observation.minuteFetchedAt(), cutoff, now, date);
    }

    private boolean recentBeforeAuction(Instant fetchedAt, Instant cutoff, Instant now, LocalDate date) {
        return fetchedAt != null && !fetchedAt.isAfter(now) && !fetchedAt.isBefore(cutoff.minusSeconds(90))
            && inScanWindow(fetchedAt, date);
    }

    private boolean inScanWindow(Instant at, LocalDate date) {
        var local = at.atZone(KST);
        return date.equals(local.toLocalDate()) && regularSession(date)
            && !local.toLocalTime().isBefore(SCAN_START) && local.toLocalTime().isBefore(AUCTION_START);
    }

    private boolean regularSession(LocalDate date) {
        return KoreanMarketCalendar.isTradingDay(date) && !excludedSessions.contains(date);
    }

    private static Double changePct(String value) {
        if (value == null || !value.trim().matches("[+-]?\\d+(?:\\.\\d+)?%?")) return null;
        double result = Double.parseDouble(value.trim().replace("%", ""));
        return Double.isFinite(result) ? result : null;
    }
}
