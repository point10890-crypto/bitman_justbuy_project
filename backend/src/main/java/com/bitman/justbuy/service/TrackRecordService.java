package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.dto.performance.DailyClosePerformanceResponse;
import com.bitman.justbuy.dto.performance.SwingCumulativePerformanceResponse;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord.TrackStatus;
import com.bitman.justbuy.repository.TrackRecordRepository;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class TrackRecordService {

    private static final Logger log = LoggerFactory.getLogger(TrackRecordService.class);
    private static final String SHORT_TERM_MODE = "BREAKOUT";
    private static final String SWING_MODE = "REVERSAL_EDGE";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime SHORT_TERM_SESSION_START = LocalTime.of(9, 0);
    private static final LocalTime SHORT_TERM_CAPTURE_CUTOFF = LocalTime.of(15, 20);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter KST_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(KST);

    private final TrackRecordRepository repository;
    private final KisApiService kisApiService;

    public TrackRecordService(TrackRecordRepository repository, KisApiService kisApiService) {
        this.repository = repository;
        this.kisApiService = kisApiService;
    }

    /** 분석 완료 시 추천 종목을 DB에 기록 */
    @Transactional
    public void recordAnalysis(AnalysisResponse response) {
        if (response == null || response.stockPicks() == null || response.stockPicks().isEmpty()) return;

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        for (StockPick pick : response.stockPicks()) {
            if (pick.code() == null || pick.code().length() != 6) continue;

            try {
                Long priceAtAnalysis = parsePrice(pick.currentPrice());
                if (SHORT_TERM_MODE.equals(response.mode())
                    && !isShortTermRecordingWindow(Instant.now().atZone(KST))) {
                    continue;
                }
                boolean duplicate = repository.existsByModeAndAnalysisDateAndStockCode(
                    response.mode(), today, pick.code());
                if (duplicate) {
                    continue;
                }

                var record = new AnalysisTrackRecord();
                record.setMode(response.mode());
                record.setAnalysisDate(today);
                record.setStockCode(pick.code());
                record.setStockName(pick.name());
                record.setAction(pick.action());

                // 합의 점수
                if (response.consensus() != null) {
                    response.consensus().stocks().stream()
                        .filter(s -> pick.code().equals(s.code()))
                        .findFirst()
                        .ifPresent(cs -> record.setConsensusScore(cs.consensusScore()));
                }

                record.setPriceAtAnalysis(priceAtAnalysis);
                record.setTargetPrice(parsePrice(pick.targetPrice()));
                record.setStopLoss(parsePrice(pick.stopLoss()));

                repository.save(record);
            } catch (Exception e) {
                log.debug("[TrackRecord] Failed to record {}: {}", pick.code(), e.getMessage());
            }
        }

        log.info("[TrackRecord] Recorded {} picks for mode={}", response.stockPicks().size(), response.mode());
    }

    @Transactional
    public int recordShortTermRealtimeSignals(List<ConditionSignalDto> signals, ZonedDateTime capturedAt) {
        if (signals == null || signals.isEmpty()) return 0;
        ZonedDateTime captureTime = capturedAt != null ? capturedAt.withZoneSameInstant(KST) : ZonedDateTime.now(KST);
        if (!isShortTermRecordingWindow(captureTime)) return 0;

        LocalDate analysisDate = captureTime.toLocalDate();
        Set<String> seenInBatch = new HashSet<>();
        int recorded = 0;
        for (ConditionSignalDto signal : signals) {
            if (signal == null || signal.stockCode() == null || !signal.stockCode().matches("\\d{6}")) continue;
            if (!seenInBatch.add(signal.stockCode())) continue;
            if (repository.existsByModeAndAnalysisDateAndStockCode(
                SHORT_TERM_MODE, analysisDate, signal.stockCode())) {
                continue;
            }

            Long entryPrice = parsePrice(firstNonBlank(signal.capturePrice(), signal.currentPrice()));
            if (entryPrice == null || entryPrice <= 0) continue;

            var record = new AnalysisTrackRecord();
            record.setMode(SHORT_TERM_MODE);
            record.setAnalysisDate(analysisDate);
            record.setStockCode(signal.stockCode());
            record.setStockName(signal.stockName());
            record.setAction(firstNonBlank(signal.status(), "실시간 포착"));
            record.setConsensusScore(signal.finalScore());
            record.setPriceAtAnalysis(entryPrice);
            record.setTargetPrice(parsePrice(signal.highPrice()));

            repository.save(record);
            recorded++;
        }
        return recorded;
    }

    /** 단타는 당일 마감 후 바로 성과를 검증한다. */
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void verifyTodayShortTermClose() {
        LocalDate today = LocalDate.now(KST);
        if (!KoreanMarketCalendar.isTradingDay(today)) {
            log.info("[TrackRecord] 단타 마감 검증 건너뜀 (휴장일)");
            return;
        }
        verifyShortTermClose(today);
    }

    @Transactional
    public DailyClosePerformanceResponse getShortTermDailyClose(LocalDate date, boolean refresh) {
        LocalDate targetDate = date != null ? date : LocalDate.now(KST);
        LocalDate today = LocalDate.now(KST);
        boolean marketClosed = targetDate.isBefore(today)
            || (targetDate.equals(today) && LocalTime.now(KST).isAfter(MARKET_CLOSE));
        boolean canRefreshFromRealtimeClose = targetDate.equals(today) && marketClosed;

        if (canRefreshFromRealtimeClose && (refresh || hasUnverifiedShortTerm(targetDate))) {
            verifyShortTermClose(targetDate);
        }

        List<AnalysisTrackRecord> records = normalizedShortTermDailyRecords(
            repository.findByModeAndAnalysisDateOrderByCreatedAtDesc(SHORT_TERM_MODE, targetDate));
        return buildDailyCloseResponse(targetDate, marketClosed, records);
    }

    public SwingCumulativePerformanceResponse getSwingCumulative(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(KST);
        LocalDate start = from != null ? from : end.minusDays(30);
        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }

        List<AnalysisTrackRecord> records = repository
            .findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(SWING_MODE, start, end);
        return buildSwingCumulativeResponse(start, end, records);
    }

    /** 매일 16:00 KST에 미완료 레코드의 현재가 업데이트 */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    public void updatePrices() {
        List<AnalysisTrackRecord> tracking = repository.findByStatus(TrackStatus.TRACKING);
        if (tracking.isEmpty()) return;

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        int updated = 0;

        for (AnalysisTrackRecord record : tracking) {
            try {
                long daysSince = ChronoUnit.DAYS.between(record.getAnalysisDate(), today);
                if (daysSince < 1) continue;

                Long basePrice = record.getPriceAtAnalysis();
                if (basePrice == null || basePrice <= 0) continue;

                Map<String, String> priceData = kisApiService.fetchCurrentPrice(record.getStockCode());
                String currentPriceStr = priceData.get("현재가");
                if (currentPriceStr == null || currentPriceStr.isBlank()) continue;

                long currentPrice = Long.parseLong(currentPriceStr.replaceAll("[^0-9]", ""));
                double returnPct = ((double) (currentPrice - basePrice) / basePrice) * 100;
                returnPct = Math.round(returnPct * 100.0) / 100.0;

                if (daysSince >= 1 && record.getPrice1d() == null) {
                    record.setPrice1d(currentPrice);
                    record.setReturn1d(returnPct);
                }
                if (daysSince >= 3 && record.getPrice3d() == null) {
                    record.setPrice3d(currentPrice);
                    record.setReturn3d(returnPct);
                }
                if (daysSince >= 5 && record.getPrice5d() == null) {
                    record.setPrice5d(currentPrice);
                    record.setReturn5d(returnPct);
                    record.setStatus(TrackStatus.COMPLETED);
                }

                // 목표가/손절가 도달 체크
                if (record.getTargetPrice() != null && currentPrice >= record.getTargetPrice()) {
                    record.setHitTarget(true);
                }
                if (record.getStopLoss() != null && currentPrice <= record.getStopLoss()) {
                    record.setHitStop(true);
                }

                repository.save(record);
                updated++;
            } catch (Exception e) {
                log.debug("[TrackRecord] Price update failed for {}: {}", record.getStockCode(), e.getMessage());
            }
        }

        log.info("[TrackRecord] Updated {}/{} tracking records", updated, tracking.size());
    }

    /** 성과 통계 반환 */
    public Map<String, Object> getStats() {
        List<AnalysisTrackRecord> completed = repository.findByStatus(TrackStatus.COMPLETED);
        List<AnalysisTrackRecord> recent = repository.findTop50ByOrderByCreatedAtDesc();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRecommendations", recent.size());
        stats.put("completedTracking", completed.size());

        if (!completed.isEmpty()) {
            long wins = completed.stream()
                .filter(r -> r.getReturn5d() != null && r.getReturn5d() > 0).count();
            stats.put("winRate5d", Math.round((double) wins / completed.size() * 100));

            double avgReturn = completed.stream()
                .filter(r -> r.getReturn5d() != null)
                .mapToDouble(AnalysisTrackRecord::getReturn5d)
                .average().orElse(0);
            stats.put("avgReturn5d", Math.round(avgReturn * 100.0) / 100.0);

            long hits = completed.stream().filter(AnalysisTrackRecord::isHitTarget).count();
            stats.put("targetHitRate", Math.round((double) hits / completed.size() * 100));

            // 합의점수 구간별 승률
            Map<String, Object> byScore = new LinkedHashMap<>();
            int[][] ranges = {{80, 100}, {60, 79}, {40, 59}, {0, 39}};
            for (int[] range : ranges) {
                List<AnalysisTrackRecord> inRange = completed.stream()
                    .filter(r -> r.getConsensusScore() >= range[0] && r.getConsensusScore() <= range[1])
                    .toList();
                if (!inRange.isEmpty()) {
                    long w = inRange.stream()
                        .filter(r -> r.getReturn5d() != null && r.getReturn5d() > 0).count();
                    byScore.put(range[0] + "-" + range[1], Map.of(
                        "count", inRange.size(),
                        "winRate", Math.round((double) w / inRange.size() * 100)
                    ));
                }
            }
            stats.put("byConsensusScore", byScore);
        }

        // 최근 10건
        stats.put("recent", recent.stream().limit(10).toList());

        return stats;
    }

    private boolean hasUnverifiedShortTerm(LocalDate date) {
        return normalizedShortTermDailyRecords(
            repository.findByModeAndAnalysisDateOrderByCreatedAtDesc(SHORT_TERM_MODE, date)).stream()
            .anyMatch(record -> record.getClosePrice() == null);
    }

    private void verifyShortTermClose(LocalDate date) {
        List<AnalysisTrackRecord> records = normalizedShortTermDailyRecords(
            repository.findByModeAndAnalysisDateOrderByCreatedAtDesc(SHORT_TERM_MODE, date));
        if (records.isEmpty()) return;

        int verified = 0;
        for (AnalysisTrackRecord record : records) {
            try {
                Long entry = record.getPriceAtAnalysis();
                if (entry == null || entry <= 0 || record.getStockCode() == null) continue;

                Map<String, String> priceData = kisApiService.fetchCurrentPrice(record.getStockCode());
                Long close = parseCurrentPrice(priceData);
                if (close == null || close <= 0) continue;

                double returnPct = roundPct(((double) (close - entry) / entry) * 100);
                record.setClosePrice(close);
                record.setCloseReturn(returnPct);
                record.setCloseVerifiedAt(Instant.now());

                if (record.getTargetPrice() != null && close >= record.getTargetPrice()) {
                    record.setHitTarget(true);
                }
                if (record.getStopLoss() != null && close <= record.getStopLoss()) {
                    record.setHitStop(true);
                }

                repository.save(record);
                verified++;
            } catch (Exception e) {
                log.debug("[TrackRecord] 단타 마감 검증 실패 {}: {}", record.getStockCode(), e.getMessage());
            }
        }

        log.info("[TrackRecord] 단타 마감 검증 완료: date={}, verified={}/{}", date, verified, records.size());
    }

    private List<AnalysisTrackRecord> normalizedShortTermDailyRecords(List<AnalysisTrackRecord> records) {
        if (records == null || records.isEmpty()) return List.of();

        Map<String, AnalysisTrackRecord> firstValidByCode = new LinkedHashMap<>();
        records.stream()
            .filter(this::isShortTermPerformanceRecord)
            .sorted(Comparator.comparing(AnalysisTrackRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .forEach(record -> firstValidByCode.putIfAbsent(record.getStockCode(), record));

        return firstValidByCode.values().stream()
            .sorted(Comparator.comparing(AnalysisTrackRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    private boolean isShortTermPerformanceRecord(AnalysisTrackRecord record) {
        if (record == null || record.getStockCode() == null || record.getCreatedAt() == null) return false;
        return isShortTermRecordingWindow(record.getCreatedAt().atZone(KST));
    }

    private boolean isShortTermRecordingWindow(ZonedDateTime capturedAt) {
        if (capturedAt == null) return false;
        ZonedDateTime kst = capturedAt.withZoneSameInstant(KST);
        LocalDate date = kst.toLocalDate();
        LocalTime time = kst.toLocalTime();
        return KoreanMarketCalendar.isTradingDay(date)
            && !time.isBefore(SHORT_TERM_SESSION_START)
            && !time.isAfter(SHORT_TERM_CAPTURE_CUTOFF);
    }

    private DailyClosePerformanceResponse buildDailyCloseResponse(LocalDate date, boolean marketClosed,
                                                                 List<AnalysisTrackRecord> records) {
        List<DailyClosePerformanceResponse.DailyCloseRow> rows = new ArrayList<>();
        int rank = 1;
        for (AnalysisTrackRecord record : records) {
            Double closeReturn = record.getCloseReturn();
            rows.add(new DailyClosePerformanceResponse.DailyCloseRow(
                rank++,
                valueOrDash(record.getStockName()),
                valueOrDash(record.getStockCode()),
                valueOrDash(record.getAction()),
                formatPrice(record.getPriceAtAnalysis()),
                formatPrice(record.getClosePrice()),
                formatPercent(closeReturn),
                resultLabel(closeReturn),
                formatPrice(record.getTargetPrice()),
                formatPrice(record.getStopLoss()),
                record.isHitTarget(),
                record.isHitStop(),
                record.getCreatedAt() != null ? KST_FORMATTER.format(record.getCreatedAt()) : "",
                record.getCloseVerifiedAt() != null ? KST_FORMATTER.format(record.getCloseVerifiedAt()) : ""
            ));
        }

        List<Double> returns = records.stream()
            .map(AnalysisTrackRecord::getCloseReturn)
            .filter(Objects::nonNull)
            .toList();
        int wins = (int) returns.stream().filter(v -> v > 0).count();
        int losses = (int) returns.stream().filter(v -> v < 0).count();
        int flats = (int) returns.stream().filter(v -> v == 0).count();
        boolean verified = !records.isEmpty() && records.stream().allMatch(r -> r.getClosePrice() != null);

        String note;
        if (records.isEmpty()) {
            note = "해당 날짜에 단타 조건검색 기록이 없습니다.";
        } else if (!marketClosed) {
            note = "장마감 전입니다. 15:30 이후 마감 성과 검증이 가능합니다.";
        } else if (!verified) {
            note = "일부 종목은 KIS 현재가 확인 실패로 아직 미검증 상태입니다.";
        } else {
            note = "단타 후보의 당일 마감 기준 성과 검증 결과입니다.";
        }

        return new DailyClosePerformanceResponse(
            date.toString(),
            SHORT_TERM_MODE,
            "단타 당일 마감 성과",
            marketClosed,
            verified,
            KST_FORMATTER.format(Instant.now()),
            records.size(),
            wins,
            losses,
            flats,
            avgPercent(returns),
            extremePercent(returns, true),
            extremePercent(returns, false),
            rows,
            note
        );
    }

    private SwingCumulativePerformanceResponse buildSwingCumulativeResponse(LocalDate from, LocalDate to,
                                                                            List<AnalysisTrackRecord> records) {
        List<SwingCumulativePerformanceResponse.SwingRow> rows = new ArrayList<>();
        int rank = 1;
        for (AnalysisTrackRecord record : records) {
            rows.add(new SwingCumulativePerformanceResponse.SwingRow(
                rank++,
                valueOrDash(record.getStockName()),
                valueOrDash(record.getStockCode()),
                valueOrDash(record.getAction()),
                record.getAnalysisDate() != null ? record.getAnalysisDate().toString() : "",
                formatPrice(record.getPriceAtAnalysis()),
                record.getStatus() != null ? record.getStatus().name() : "",
                formatPercent(record.getReturn1d()),
                formatPercent(record.getReturn3d()),
                formatPercent(record.getReturn5d()),
                formatPrice(record.getTargetPrice()),
                formatPrice(record.getStopLoss()),
                record.isHitTarget(),
                record.isHitStop(),
                record.getCreatedAt() != null ? KST_FORMATTER.format(record.getCreatedAt()) : ""
            ));
        }

        List<Double> r1 = records.stream().map(AnalysisTrackRecord::getReturn1d).filter(Objects::nonNull).toList();
        List<Double> r3 = records.stream().map(AnalysisTrackRecord::getReturn3d).filter(Objects::nonNull).toList();
        List<Double> r5 = records.stream().map(AnalysisTrackRecord::getReturn5d).filter(Objects::nonNull).toList();
        int completed = (int) records.stream().filter(r -> r.getStatus() == TrackStatus.COMPLETED).count();
        int tracking = records.size() - completed;

        return new SwingCumulativePerformanceResponse(
            from.toString(),
            to.toString(),
            SWING_MODE,
            "스윙 누적 성과",
            records.size(),
            tracking,
            completed,
            avgPercent(r1),
            avgPercent(r3),
            avgPercent(r5),
            winRate(r1),
            winRate(r3),
            winRate(r5),
            ratioPercent((int) records.stream().filter(AnalysisTrackRecord::isHitTarget).count(), records.size()),
            ratioPercent((int) records.stream().filter(AnalysisTrackRecord::isHitStop).count(), records.size()),
            rows,
            "스윙 후보는 1/3/5거래일 추적 결과를 누적 성과로 표시합니다."
        );
    }

    private Long parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isBlank()) return null;
        try {
            return Long.parseLong(priceStr.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseCurrentPrice(Map<String, String> priceData) {
        if (priceData == null || priceData.isEmpty()) return null;
        String value = firstNonBlank(priceData.get("currentPrice"), priceData.get("현재가"));
        return parsePrice(value);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }

    private static double roundPct(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String formatPrice(Long value) {
        return value == null || value <= 0 ? "-" : String.format(Locale.KOREA, "%,d원", value);
    }

    private static String formatPercent(Double value) {
        if (value == null) return "-";
        return (value > 0 ? "+" : "") + String.format(Locale.KOREA, "%.2f%%", value);
    }

    private static String avgPercent(List<Double> values) {
        if (values == null || values.isEmpty()) return "-";
        return formatPercent(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static String extremePercent(List<Double> values, boolean max) {
        if (values == null || values.isEmpty()) return "-";
        OptionalDouble result = max
            ? values.stream().mapToDouble(Double::doubleValue).max()
            : values.stream().mapToDouble(Double::doubleValue).min();
        return result.isPresent() ? formatPercent(result.getAsDouble()) : "-";
    }

    private static String winRate(List<Double> values) {
        if (values == null || values.isEmpty()) return "-";
        int wins = (int) values.stream().filter(v -> v > 0).count();
        return ratioPercent(wins, values.size());
    }

    private static String ratioPercent(int numerator, int denominator) {
        if (denominator <= 0) return "-";
        return Math.round((double) numerator / denominator * 100) + "%";
    }

    private static String resultLabel(Double value) {
        if (value == null) return "미검증";
        if (value > 0) return "승";
        if (value < 0) return "패";
        return "보합";
    }
}
