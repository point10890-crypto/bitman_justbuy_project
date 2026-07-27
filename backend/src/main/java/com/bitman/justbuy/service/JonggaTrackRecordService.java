package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord.TrackStatus;
import com.bitman.justbuy.repository.TrackRecordRepository;
import com.bitman.justbuy.util.JonggaSignals;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 종가매매(JONGGA_V2) 추천종목의 익일 성과 추적.
 *
 * <p>추천일 아카이브(jongga_v2_results_YYYYMMDD.json)의 상위 3종목을 기록하고,
 * 익영업일 장마감 후 KIS 현재가 조회로 종가/고가/저가를 확보해 성과를 확정한다.
 * KIS 현재가 API는 과거 일자의 OHLC를 주지 못하므로 <b>검증은 반드시 평가일 당일</b>에
 * 이뤄져야 한다. 그날 검증에 실패한 레코드는 미검증으로 남는다 (다른 날 값으로 덮지 않음).
 */
@Service
@Transactional(readOnly = true)
public class JonggaTrackRecordService {

    public static final String MODE = "JONGGA_V2";

    private static final Logger log = LoggerFactory.getLogger(JonggaTrackRecordService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ARCHIVE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 장마감(15:30) 후 일봉이 확정되는 시각. 이 시각 이후에는 당일도 평가일로 쓸 수 있다. */
    private static final LocalTime DAILY_CANDLE_READY = LocalTime.of(15, 40);

    /** 스케줄 잡이 매 실행마다 되돌아보며 미검증분을 메우는 구간(일). */
    private static final int SWEEP_DAYS = 45;

    private final TrackRecordRepository repository;
    private final JonggaV2SearchService jonggaV2SearchService;
    private final KisApiService kisApiService;

    public JonggaTrackRecordService(TrackRecordRepository repository,
                                    JonggaV2SearchService jonggaV2SearchService,
                                    KisApiService kisApiService) {
        this.repository = repository;
        this.jonggaV2SearchService = jonggaV2SearchService;
        this.kisApiService = kisApiService;
    }

    /**
     * 장마감 직후 1차 실행, 16:10 에 2차 실행 (1차에서 KIS 조회에 실패한 종목 재시도).
     * 두 실행 모두 같은 평가일이므로 종가/고가 값이 유효하다.
     */
    @Schedules({
        @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul"),
        @Scheduled(cron = "0 10 16 * * MON-FRI", zone = "Asia/Seoul")
    })
    @Transactional
    public void verifyPreviousDayJongga() {
        LocalDate today = LocalDate.now(KST);
        if (!KoreanMarketCalendar.isTradingDay(today)) {
            log.info("[JonggaTrack] 휴장일이라 검증 건너뜀: {}", today);
            return;
        }

        LocalDate recommendedDate = KoreanMarketCalendar.previousTradingDay(today);
        if (recommendedDate == null) {
            log.warn("[JonggaTrack] 직전 거래일을 찾지 못함: {}", today);
            return;
        }

        int recorded = recordArchiveSignals(recommendedDate);
        int verified = verifyRecordedSignals(recommendedDate);
        log.info("[JonggaTrack] 검증 완료: 추천일={}, 신규기록={}, 검증={}", recommendedDate, recorded, verified);

        // 놓친 날(서버 중단, 배포, KIS 장애)을 일봉으로 되메운다. 이미 검증된 건은 건너뛴다.
        int swept = backfillVerification(today.minusDays(SWEEP_DAYS), today);
        if (swept > 0) {
            log.info("[JonggaTrack] 미검증분 소급 보정: {}건", swept);
        }
    }

    /**
     * 추천일 아카이브의 상위 종목({@link JonggaSignals#tracked})을 추적 레코드로 기록한다.
     * 이미 기록된 종목은 건너뛰므로 여러 번 실행해도 안전하다.
     *
     * @return 신규 기록 건수
     */
    @Transactional
    public int recordArchiveSignals(LocalDate recommendedDate) {
        JsonNode archive = readArchive(recommendedDate);
        if (archive == null) return 0;

        int recorded = 0;
        for (JsonNode signal : JonggaSignals.tracked(archive)) {
            String stockCode = signal.path("stock_code").asText("");
            if (repository.existsByModeAndAnalysisDateAndStockCode(MODE, recommendedDate, stockCode)) continue;

            AnalysisTrackRecord record = new AnalysisTrackRecord();
            record.setMode(MODE);
            record.setAnalysisDate(recommendedDate);
            record.setStockCode(stockCode);
            record.setStockName(signal.path("stock_name").asText(stockCode));
            record.setAction(signal.path("grade").asText("포착"));
            record.setConsensusScore(signal.path("score").path("total").asInt(0));
            record.setPriceAtAnalysis(JonggaSignals.entryPrice(signal));
            record.setTargetPrice(positiveOrNull(signal.path("target_price").asLong(0)));
            record.setStopLoss(positiveOrNull(signal.path("stop_price").asLong(0)));

            repository.save(record);
            recorded++;
        }
        return recorded;
    }

    /**
     * 아직 검증되지 않은 해당 추천일의 레코드를 현재 시세(= 평가일 종가/고가/저가)로 확정한다.
     *
     * @return 이번 실행에서 검증된 건수
     */
    @Transactional
    public int verifyRecordedSignals(LocalDate recommendedDate) {
        List<AnalysisTrackRecord> records =
            repository.findByModeAndAnalysisDateOrderByCreatedAtDesc(MODE, recommendedDate);

        int verified = 0;
        for (AnalysisTrackRecord record : records) {
            if (record.getClosePrice() != null) continue;

            Long entry = record.getPriceAtAnalysis();
            if (entry == null || entry <= 0 || record.getStockCode() == null) continue;

            try {
                Map<String, String> quote = kisApiService.fetchCurrentPrice(record.getStockCode());
                Long close = parsePrice(quote, "현재가", "currentPrice");
                if (close == null || close <= 0) continue;

                Long high = parsePrice(quote, "고가", null);
                Long low = parsePrice(quote, "저가", null);
                long highOrClose = high != null && high > 0 ? high : close;

                record.setClosePrice(close);
                record.setCloseReturn(returnPct(entry, close));
                record.setHighPrice1d(highOrClose);
                record.setMaxReturn1d(returnPct(entry, highOrClose));
                record.setCloseVerifiedAt(Instant.now());

                if (record.getTargetPrice() != null && highOrClose >= record.getTargetPrice()) {
                    record.setHitTarget(true);
                }
                long lowOrClose = low != null && low > 0 ? low : close;
                if (record.getStopLoss() != null && lowOrClose <= record.getStopLoss()) {
                    record.setHitStop(true);
                }
                record.setStatus(TrackStatus.COMPLETED);

                repository.save(record);
                verified++;
            } catch (Exception e) {
                log.debug("[JonggaTrack] 검증 실패 {}: {}", record.getStockCode(), e.getMessage());
            }
        }
        return verified;
    }

    /**
     * 과거 추천의 소급 성과검증.
     *
     * <p>{@link #verifyRecordedSignals}는 KIS 현재가를 쓰므로 평가일 당일에만 유효하다.
     * 이 메서드는 KIS 일봉({@link KisApiService#fetchDailyOhlc})으로 <b>평가일의 확정 OHLC</b>를
     * 직접 읽으므로 언제 실행해도 올바른 값을 기록한다. 이미 검증된 레코드는 건드리지 않는다.
     *
     * @param from 추천일 시작 (포함)
     * @param to   추천일 종료 (포함)
     * @return 이번 실행에서 검증된 건수
     */
    @Transactional
    public int backfillVerification(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) return 0;

        LocalDate today = LocalDate.now(KST);
        LocalTime nowKst = LocalTime.now(KST);
        int recorded = 0;
        int verified = 0;
        int skipped = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!KoreanMarketCalendar.isTradingDay(date)) continue;

            LocalDate evalDate = KoreanMarketCalendar.nextTradingDay(date);
            if (!evalDataAvailable(evalDate, today, nowKst)) {
                skipped++;
                continue;
            }

            recorded += recordArchiveSignals(date);
            verified += verifyWithDailyCandles(date, evalDate);
        }

        log.info("[JonggaTrack] 소급검증 완료: 기간={}~{}, 신규기록={}, 검증={}, 대기={}",
            from, to, recorded, verified, skipped);
        return verified;
    }

    /**
     * 추천일 레코드를 평가일 일봉으로 확정한다.
     *
     * @param recommendedDate 추천일
     * @param evalDate        평가일 (추천일의 다음 거래일)
     * @return 검증된 건수
     */
    @Transactional
    public int verifyWithDailyCandles(LocalDate recommendedDate, LocalDate evalDate) {
        List<AnalysisTrackRecord> records =
            repository.findByModeAndAnalysisDateOrderByCreatedAtDesc(MODE, recommendedDate);

        int verified = 0;
        for (AnalysisTrackRecord record : records) {
            if (record.getClosePrice() != null) continue;

            Long entry = record.getPriceAtAnalysis();
            if (entry == null || entry <= 0 || record.getStockCode() == null) continue;

            try {
                Map<LocalDate, KisApiService.DailyOhlc> candles =
                    kisApiService.fetchDailyOhlc(record.getStockCode(), evalDate, evalDate);
                KisApiService.DailyOhlc candle = candles.get(evalDate);
                if (candle == null || candle.close() <= 0) continue;

                applyOutcome(record, entry, candle.close(), candle.high(), candle.low());
                repository.save(record);
                verified++;
            } catch (Exception e) {
                log.debug("[JonggaTrack] 소급검증 실패 {} ({}): {}",
                    record.getStockCode(), evalDate, e.getMessage());
            }
        }
        return verified;
    }

    /**
     * 평가일의 확정 일봉을 지금 가져올 수 있는지 판단한다.
     *
     * <p>과거 평가일은 언제나 가능하다. 평가일이 오늘이면 장이 끝나고 일봉이 확정된 뒤
     * ({@link #DAILY_CANDLE_READY} 이후)에만 가능하다. 미래 평가일은 불가.
     *
     * <p>이 구분이 없으면 배포/재기동 시각에 따라 "장은 끝났는데 평가일이 오늘이라"
     * 검증이 영구히 빠지는 구멍이 생긴다.
     */
    static boolean evalDataAvailable(LocalDate evalDate, LocalDate today, LocalTime nowKst) {
        if (evalDate == null || today == null) return false;
        if (evalDate.isAfter(today)) return false;
        if (evalDate.isBefore(today)) return true;
        return nowKst != null && !nowKst.isBefore(DAILY_CANDLE_READY);
    }

    /** 종가/고가/저가로 수익률·목표가 도달·손절 도달을 확정한다. */
    private static void applyOutcome(AnalysisTrackRecord record, long entry, long close, long high, long low) {
        long highOrClose = high > 0 ? high : close;
        long lowOrClose = low > 0 ? low : close;

        record.setClosePrice(close);
        record.setCloseReturn(returnPct(entry, close));
        record.setHighPrice1d(highOrClose);
        record.setMaxReturn1d(returnPct(entry, highOrClose));
        record.setCloseVerifiedAt(Instant.now());

        if (record.getTargetPrice() != null && highOrClose >= record.getTargetPrice()) {
            record.setHitTarget(true);
        }
        if (record.getStopLoss() != null && lowOrClose <= record.getStopLoss()) {
            record.setHitStop(true);
        }
        record.setStatus(TrackStatus.COMPLETED);
    }

    private JsonNode readArchive(LocalDate recommendedDate) {
        try {
            return jonggaV2SearchService.history(recommendedDate.format(ARCHIVE_DATE));
        } catch (Exception e) {
            log.info("[JonggaTrack] 아카이브 없음: {} ({})", recommendedDate, e.getMessage());
            return null;
        }
    }

    private static Long positiveOrNull(long value) {
        return value > 0 ? value : null;
    }

    private static Long parsePrice(Map<String, String> quote, String key, String fallbackKey) {
        if (quote == null || quote.isEmpty()) return null;
        String value = quote.get(key);
        if ((value == null || value.isBlank()) && fallbackKey != null) {
            value = quote.get(fallbackKey);
        }
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return null;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double returnPct(long base, long value) {
        return Math.round(((double) (value - base) / base) * 100 * 100.0) / 100.0;
    }
}
