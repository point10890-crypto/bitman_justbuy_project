package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.performance.JonggaPerformanceResponse;
import com.bitman.justbuy.dto.performance.JonggaPerformanceResponse.DayGroup;
import com.bitman.justbuy.dto.performance.JonggaPerformanceResponse.PerformanceRow;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.repository.TrackRecordRepository;
import com.bitman.justbuy.util.JonggaSignals;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 종가매매 추천종목 히스토리 조회. 아카이브(목록)와 추적 DB(성과)를 병합한다.
 */
@Service
@Transactional(readOnly = true)
public class JonggaPerformanceService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ARCHIVE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 365;

    /**
     * 국내 증시 일일 가격제한폭(±30%). 반올림 여유 0.5%p 를 더해 판정한다.
     *
     * <p>이 폭을 넘는 "수익률"은 하루 만에 나올 수 없다. 액면분할·병합·합병 등
     * corporate action 으로 진입가와 익일 종가의 기준이 달라졌을 때 생긴다
     * (예: 가온전선 진입 385,500 -> 익일 종가 187,543 = -51.35%).
     * 이런 값을 성과로 세면 승률·평균수익률이 통째로 왜곡되므로 집계에서 제외한다.
     */
    private static final double DAILY_PRICE_LIMIT_PCT = 30.5;

    static final String RESULT_UNVERIFIED = "미검증";
    static final String RESULT_NOT_MEASURABLE = "검증불가";

    private final JonggaV2SearchService jonggaV2SearchService;
    private final TrackRecordRepository repository;

    public JonggaPerformanceService(JonggaV2SearchService jonggaV2SearchService,
                                    TrackRecordRepository repository) {
        this.jonggaV2SearchService = jonggaV2SearchService;
        this.repository = repository;
    }

    public JonggaPerformanceResponse getPerformance(String fromParam, String toParam) {
        LocalDate to = parseDate(toParam, LocalDate.now(KST));
        LocalDate from = parseDate(fromParam, to.minusDays(DEFAULT_RANGE_DAYS));
        if (from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        if (from.isBefore(to.minusDays(MAX_RANGE_DAYS))) {
            from = to.minusDays(MAX_RANGE_DAYS);
        }

        Map<String, AnalysisTrackRecord> recordsByKey = new LinkedHashMap<>();
        for (AnalysisTrackRecord record : repository
            .findByModeAndAnalysisDateBetweenOrderByAnalysisDateDescCreatedAtDesc(
                JonggaTrackRecordService.MODE, from, to)) {
            recordsByKey.putIfAbsent(key(record.getAnalysisDate(), record.getStockCode()), record);
        }

        List<DayGroup> days = new ArrayList<>();
        List<PerformanceRow> allRows = new ArrayList<>();
        for (LocalDate date : datesInRange(from, to)) {
            JsonNode archive = readArchive(date);
            List<JsonNode> signals = JonggaSignals.tracked(archive);
            if (signals.isEmpty()) continue;

            List<PerformanceRow> rows = new ArrayList<>();
            int rank = 1;
            for (JsonNode signal : signals) {
                String stockCode = signal.path("stock_code").asText("");
                rows.add(toRow(rank++, signal, recordsByKey.get(key(date, stockCode))));
            }
            allRows.addAll(rows);

            boolean verified = rows.stream().noneMatch(row -> "미검증".equals(row.result()));
            days.add(new DayGroup(date.toString(), verified, avgOf(closeReturns(rows)), rows));
        }

        return summarize(from, to, days, allRows);
    }

    private JonggaPerformanceResponse summarize(LocalDate from, LocalDate to,
                                                List<DayGroup> days, List<PerformanceRow> rows) {
        List<Double> closeReturns = closeReturns(rows);
        List<Double> maxReturns = rows.stream()
            .map(row -> parsePercent(row.maxReturnPct()))
            .filter(Objects::nonNull)
            .toList();

        int wins = (int) closeReturns.stream().filter(value -> value > 0).count();
        int losses = (int) closeReturns.stream().filter(value -> value < 0).count();
        int flats = closeReturns.size() - wins - losses;
        int verifiedCount = closeReturns.size();

        int notMeasurable = (int) rows.stream()
            .filter(row -> RESULT_NOT_MEASURABLE.equals(row.result()))
            .count();

        String note;
        if (rows.isEmpty()) {
            note = "해당 기간에 종가매매 조건검색 기록이 없습니다.";
        } else if (verifiedCount == 0) {
            note = "목록만 있고 익일 성과 검증 기록이 아직 없습니다. 검증은 추천 다음 영업일 장마감 후 채워집니다.";
        } else if (verifiedCount < rows.size()) {
            note = "일부 종목은 익일 성과 검증 기록이 없어 미검증으로 표시됩니다.";
        } else {
            note = "진입가 대비 익일 종가 수익률과 익일 고가 기준 최대수익률입니다.";
        }
        if (notMeasurable > 0) {
            note += " 액면분할·합병 등으로 진입가와 기준이 달라진 " + notMeasurable
                + "건은 검증불가로 분류해 통계에서 제외했습니다.";
        }

        return new JonggaPerformanceResponse(
            from.toString(),
            to.toString(),
            JonggaTrackRecordService.MODE,
            "종가매매 추천종목 히스토리",
            rows.size(),
            verifiedCount,
            wins,
            losses,
            flats,
            avgOf(closeReturns),
            avgOf(maxReturns),
            ratioPercent(wins, verifiedCount),
            ratioPercent((int) rows.stream().filter(PerformanceRow::hitTarget).count(), verifiedCount),
            ratioPercent((int) rows.stream().filter(PerformanceRow::hitStop).count(), verifiedCount),
            days,
            note
        );
    }

    private PerformanceRow toRow(int rank, JsonNode signal, AnalysisTrackRecord record) {
        long entry = JonggaSignals.entryPrice(signal);
        long target = signal.path("target_price").asLong(0);
        long stop = signal.path("stop_price").asLong(0);

        boolean verified = record != null && record.getClosePrice() != null;
        boolean measurable = verified
            && !isPriceLimitViolation(record.getCloseReturn(), record.getMaxReturn1d());

        return new PerformanceRow(
            rank,
            signal.path("stock_name").asText("-"),
            signal.path("stock_code").asText(""),
            signal.path("grade").asText("-"),
            signal.path("score").path("total").asInt(0),
            formatPrice(entry),
            formatPrice(target),
            formatPrice(stop),
            measurable ? formatPrice(record.getClosePrice()) : "-",
            measurable ? formatPercent(record.getCloseReturn()) : "-",
            measurable ? formatPercent(record.getMaxReturn1d()) : "-",
            measurable && record.isHitTarget(),
            measurable && record.isHitStop(),
            verified
                ? (measurable ? resultLabel(record.getCloseReturn()) : RESULT_NOT_MEASURABLE)
                : RESULT_UNVERIFIED
        );
    }

    /**
     * 하루에 나올 수 없는 수익률인지 판정한다.
     *
     * <p>가격제한폭 초과, 또는 고가가 종가보다 낮은 모순(고가 &gt;= 종가 는 항상 성립)이면
     * 진입가와 익일 시세의 기준이 어긋난 것이므로 성과로 세지 않는다.
     */
    static boolean isPriceLimitViolation(Double closeReturn, Double maxReturn) {
        if (closeReturn != null && Math.abs(closeReturn) > DAILY_PRICE_LIMIT_PCT) return true;
        if (maxReturn != null && Math.abs(maxReturn) > DAILY_PRICE_LIMIT_PCT) return true;
        if (closeReturn != null && maxReturn != null && maxReturn < closeReturn - 0.01) return true;
        return false;
    }

    /** 아카이브가 존재하는 날짜만, 최신순으로. */
    private List<LocalDate> datesInRange(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        for (JsonNode node : jonggaV2SearchService.dates().path("dates")) {
            LocalDate date = parseArchiveDate(node.asText(""));
            if (date == null) continue;
            if (date.isBefore(from) || date.isAfter(to)) continue;
            dates.add(date);
        }
        dates.sort((left, right) -> right.compareTo(left));
        return dates;
    }

    private JsonNode readArchive(LocalDate date) {
        try {
            return jonggaV2SearchService.history(date.format(ARCHIVE_DATE));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Double> closeReturns(List<PerformanceRow> rows) {
        return rows.stream()
            .map(row -> parsePercent(row.closeReturnPct()))
            .filter(Objects::nonNull)
            .toList();
    }

    private static String key(LocalDate date, String stockCode) {
        return date + "|" + (stockCode == null ? "" : stockCode);
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        try {
            if (normalized.matches("\\d{8}")) return LocalDate.parse(normalized, ARCHIVE_DATE);
            return LocalDate.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be YYYY-MM-DD or YYYYMMDD");
        }
    }

    private static LocalDate parseArchiveDate(String value) {
        if (value == null || !value.matches("\\d{8}")) return null;
        try {
            return LocalDate.parse(value, ARCHIVE_DATE);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String formatPrice(Long value) {
        if (value == null || value <= 0) return "-";
        return String.format(Locale.KOREA, "%,d", value);
    }

    private static String formatPercent(Double value) {
        if (value == null) return "-";
        return String.format(Locale.KOREA, "%+.2f%%", value);
    }

    private static Double parsePercent(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) return null;
        try {
            return Double.parseDouble(value.replace("%", "").replace("+", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String avgOf(List<Double> values) {
        if (values == null || values.isEmpty()) return "-";
        return formatPercent(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static String ratioPercent(int numerator, int denominator) {
        if (denominator <= 0) return "-";
        return Math.round((double) numerator / denominator * 100) + "%";
    }

    private static String resultLabel(Double closeReturn) {
        if (closeReturn == null) return "미검증";
        if (closeReturn > 0) return "승";
        if (closeReturn < 0) return "패";
        return "보합";
    }
}
