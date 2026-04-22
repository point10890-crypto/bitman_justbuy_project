package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 홈 카드 4개 컨셉 모드 프리컴퓨트 스케줄러.
 *
 * 스케줄 (평일 거래일만):
 *   08:50 → BREAKOUT, FLOW_LEADER, CATALYST_BURST, REVERSAL_EDGE 분석
 *   09:50 → 갱신 ... 14:50까지 매시 50분 반복
 *   → 정각에 최신 데이터 서빙
 *
 * 주말·KRX 공휴일 자동 건너뜀.
 */
@Component
@ConditionalOnProperty(name = "bitman.scheduler.enabled", havingValue = "true")
public class PrecomputeScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrecomputeScheduler.class);
    private final AnalysisService analysisService;
    private final TelegramNotifier telegramNotifier;
    private final Map<String, String> lastRunTimes = new ConcurrentHashMap<>();

    /** 스케줄 대상 4개 컨셉 모드 + 쿼리 */
    private static final Map<String, String> SCHEDULED_MODES = Map.of(
        "BREAKOUT",       "기술적 돌파 매수 후보 분석",
        "FLOW_LEADER",    "외국인·기관 수급 주도 종목 분석",
        "CATALYST_BURST", "재료/이벤트 드리븐 급등 후보 분석",
        "REVERSAL_EDGE",  "역발상 반전 매수 후보 분석"
    );

    public PrecomputeScheduler(AnalysisService analysisService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               TelegramNotifier telegramNotifier) {
        this.analysisService = analysisService;
        this.telegramNotifier = telegramNotifier;
        log.info("[Scheduler] 컨셉 모드 4종 스케줄러 초기화 (매시 50분, KST), telegram={}", telegramNotifier != null);
    }

    /** 서버 시작 후 캐시 없는 모드 자동 분석 */
    @PostConstruct
    public void onStartup() {
        new Thread(this::runStartupPrecompute, "startup-precompute").start();
    }

    private void runStartupPrecompute() {
        try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }

        log.info("[Scheduler] 🚀 서버 시작 — 캐시 확인 중...");
        int success = 0, total = 0;
        for (var entry : SCHEDULED_MODES.entrySet()) {
            String mode = entry.getKey();
            total++;
            try {
                var cached = analysisService.getPrecomputed(mode);
                if (cached != null) {
                    log.info("[Scheduler] ✅ {} — 캐시 유효, 건너뜀", mode);
                    success++;
                    continue;
                }
                log.info("[Scheduler] ▶ {} — 캐시 없음, 자동 실행", mode);
                execute(mode, entry.getValue());
                success++;
            } catch (Exception e) {
                log.error("[Scheduler] ❌ {} 시작 시 실행 실패: {}", mode, e.getMessage());
            }
        }
        log.info("[Scheduler] 🏁 시작 시 프리컴퓨트 완료 ({}/{})", success, total);

        if (telegramNotifier != null) {
            telegramNotifier.sendStartupComplete(success, total);
        }
    }

    /**
     * 매시 50분 — 평일 08:50~14:50 (KST)
     * 4개 컨셉 모드 순차 실행 → 정각에 최신 데이터 서빙
     */
    @Scheduled(cron = "0 50 8-14 * * MON-FRI", zone = "Asia/Seoul")
    public void hourlyPrecompute() {
        if (!isTradingDay()) {
            log.info("[Scheduler] ⏭ 매시 분석 건너뜀 (휴장일)");
            return;
        }

        int hour = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).getHour();
        log.info("[Scheduler] ⏰ {}:50 컨셉 모드 4종 분석 시작", hour);

        int success = 0;
        for (var entry : SCHEDULED_MODES.entrySet()) {
            try {
                execute(entry.getKey(), entry.getValue());
                success++;
            } catch (Exception e) {
                log.error("[Scheduler] ❌ {}:50 {} 실패: {}", hour, entry.getKey(), e.getMessage());
            }
        }

        log.info("[Scheduler] ✅ {}:50 완료 ({}/{})", hour, success, SCHEDULED_MODES.size());
    }

    private boolean isTradingDay() {
        return KoreanMarketCalendar.isTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }

    private void execute(String mode, String query) {
        String now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.info("[Scheduler] ▶ {} 실행 시작 ({})", mode, now);

        long startMs = System.currentTimeMillis();
        try {
            AnalysisResponse result = analysisService.runLiveAnalysis(query, mode);
            if (result.updatedAt() != null) lastRunTimes.put(mode, result.updatedAt());

            // metadata 가 null 일 수 있는 경로(폴백 응답 등)를 NPE 없이 처리
            var meta = result.metadata();
            long durationMs = meta != null ? meta.totalDurationMs() : (System.currentTimeMillis() - startMs);
            int agentsUsed = meta != null ? meta.agentsUsed() : 0;
            int agentsSucceeded = meta != null ? meta.agentsSucceeded() : 0;
            int picks = result.stockPicks() != null ? result.stockPicks().size() : 0;

            log.info("[Scheduler] ✅ {} 완료 ({}ms, {}/{} agents)",
                mode, durationMs, agentsSucceeded, agentsUsed);

            com.bitman.justbuy.controller.MonitorController.recordAnalysis(
                mode, true, durationMs, agentsUsed, agentsSucceeded, picks, null);

            // 텔레그램 발송 조건 3가지 동시 충족 시에만 발송:
            // 1) picks > 0  — 빈 분석 결과 발송 방지
            // 2) agentsSucceeded > 0  — 모든 AI 실패 시 발송 방지
            // 3) 장 시간(09:00~15:59 KST)  — 서버 재시작/장외 자동 분석 발송 방지
            int kstHour = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).getHour();
            boolean isMarketHours = kstHour >= 9 && kstHour < 16;
            if (telegramNotifier != null && picks > 0 && agentsSucceeded > 0 && isMarketHours) {
                telegramNotifier.sendAnalysisResult(mode, result);
            } else {
                log.info("[Scheduler] {} 텔레그램 Skip — picks={}, agents={}/{}, marketHours={}({}시)",
                    mode, picks, agentsSucceeded, agentsUsed, isMarketHours, kstHour);
            }
        } catch (Exception e) {
            log.error("[Scheduler] ❌ {} 실패: {}", mode, e.getMessage());

            com.bitman.justbuy.controller.MonitorController.recordAnalysis(
                mode, false, System.currentTimeMillis() - startMs, 0, 0, 0, e.getMessage());

            if (telegramNotifier != null) {
                telegramNotifier.sendAnalysisFailed(mode, e.getMessage());
            }
        }
    }

    /** 전체 모드 수동 새로고침 (관리자용) */
    public Map<String, String> refreshAll() {
        Map<String, String> results = new LinkedHashMap<>();
        log.info("[Scheduler] 전체 새로고침 시작");

        for (var entry : SCHEDULED_MODES.entrySet()) {
            try {
                execute(entry.getKey(), entry.getValue());
                results.put(entry.getKey(), "success");
            } catch (Exception e) {
                log.error("[Scheduler] {} 새로고침 실패: {}", entry.getKey(), e.getMessage());
                results.put(entry.getKey(), "error: " + e.getMessage());
            }
        }

        log.info("[Scheduler] 전체 새로고침 완료: {}", results);
        return results;
    }

    public Map<String, String> getLastRunTimes() {
        return Map.copyOf(lastRunTimes);
    }
}
