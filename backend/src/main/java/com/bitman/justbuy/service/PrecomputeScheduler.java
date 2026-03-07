package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "bitman.scheduler.enabled", havingValue = "true")
public class PrecomputeScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrecomputeScheduler.class);
    private final AnalysisService analysisService;
    private final TelegramNotifier telegramNotifier;
    private final Map<String, String> lastRunTimes = new ConcurrentHashMap<>();

    /** 서버 시작 시 캐시에 데이터 없는 모드를 자동 실행 */
    private static final Map<String, String> STARTUP_MODES = Map.of(
        "오늘뭐사", "오늘 뭐 살까? 당일 매매 추천",
        "스윙매매", "스윙매매 후보 종목 분석",
        "종가매매", "종가매매 후보 종목 분석",
        "수급분석", "오늘 수급 현황 분석"
    );

    public PrecomputeScheduler(AnalysisService analysisService,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               TelegramNotifier telegramNotifier) {
        this.analysisService = analysisService;
        this.telegramNotifier = telegramNotifier;
        log.info("[Scheduler] Initialized - precompute schedules active (KST), telegram={}", telegramNotifier != null);
    }

    /** 서버 시작 후 캐시 없는 모드 자동 분석 */
    @PostConstruct
    public void onStartup() {
        new Thread(this::runStartupPrecompute, "startup-precompute").start();
    }

    private void runStartupPrecompute() {
        // 서버 완전 시작 후 30초 대기
        try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }

        log.info("[Scheduler] 🚀 서버 시작 - 캐시 확인 중...");
        int success = 0;
        int total = 0;
        for (var entry : STARTUP_MODES.entrySet()) {
            String mode = entry.getKey();
            String query = entry.getValue();
            try {
                var cached = analysisService.getPrecomputed(mode);
                if (cached != null) {
                    log.info("[Scheduler] ✅ {} - 캐시 유효, 건너뜀", mode);
                    success++;
                    total++;
                    continue;
                }
                log.info("[Scheduler] ▶ {} - 캐시 없음, 자동 실행", mode);
                total++;
                execute(mode, query);
                success++;
            } catch (Exception e) {
                log.error("[Scheduler] ❌ {} 시작 시 실행 실패: {}", mode, e.getMessage());
            }
        }
        log.info("[Scheduler] 🏁 시작 시 프리컴퓨트 완료");

        if (telegramNotifier != null) {
            telegramNotifier.sendStartupComplete(success, total);
        }
    }

    /** 오늘뭐사: 평일 오전 8시, 휴장일 제외 (결과 유효: 다음날 07:50까지) */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Seoul")
    public void precomputeToday() {
        if (!isTradingDay()) {
            log.info("[Scheduler] \u23ED \uc624\ub298\ubb50\uc0ac \uac74\ub108\ub700 (\ud734\uc7a5\uc77c)");
            return;
        }
        execute("\uc624\ub298\ubb50\uc0ac", "\uc624\ub298 \ubb58 \uc0b4\uae4c? \ub2f9\uc77c \ub9e4\ub9e4 \ucd94\ucc9c");
    }

    /** 스윙매매: 3일마다 오전 7시, 휴장일 제외 (결과 유효: 71시간 50분) */
    @Scheduled(cron = "0 0 7 */3 * *", zone = "Asia/Seoul")
    public void precomputeSwing() {
        if (!isTradingDay()) {
            log.info("[Scheduler] \u23ED \uc2a4\uc719\ub9e4\ub9e4 \uac74\ub108\ub700 (\ud734\uc7a5\uc77c)");
            return;
        }
        execute("\uc2a4\uc719\ub9e4\ub9e4", "\uc2a4\uc719\ub9e4\ub9e4 \ud6c4\ubcf4 \uc885\ubaa9 \ubd84\uc11d");
    }

    /** 종가매매: 평일 오후 3시 10분, 휴장일 제외 (결과 유효: 23시간 50분) */
    @Scheduled(cron = "0 10 15 * * MON-FRI", zone = "Asia/Seoul")
    public void precomputeClosing() {
        if (!isTradingDay()) {
            log.info("[Scheduler] \u23ED \uc885\uac00\ub9e4\ub9e4 \uac74\ub108\ub700 (\ud734\uc7a5\uc77c)");
            return;
        }
        execute("\uc885\uac00\ub9e4\ub9e4", "\uc885\uac00\ub9e4\ub9e4 \ud6c4\ubcf4 \uc885\ubaa9 \ubd84\uc11d");
    }

    /** 수급분석: 평일 오전 10시 + 오후 2시, 휴장일 제외 (결과 유효: 3시간 50분) */
    @Scheduled(cron = "0 0 10,14 * * MON-FRI", zone = "Asia/Seoul")
    public void precomputeSupply() {
        if (!isTradingDay()) {
            log.info("[Scheduler] \u23ED \uc218\uae09\ubd84\uc11d \uac74\ub108\ub700 (\ud734\uc7a5\uc77c)");
            return;
        }
        execute("\uc218\uae09\ubd84\uc11d", "\uc624\ub298 \uc218\uae09 \ud604\ud669 \ubd84\uc11d");
    }

    /** 오늘이 한국 증시 거래일인지 확인 */
    private boolean isTradingDay() {
        return KoreanMarketCalendar.isTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }

    private void execute(String mode, String query) {
        String now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("[Scheduler] \u25B6 {} \uc2e4\ud589 \uc2dc\uc791 ({})", mode, now);

        try {
            AnalysisResponse result = analysisService.runLiveAnalysis(query, mode);
            lastRunTimes.put(mode, result.updatedAt());
            log.info("[Scheduler] \u2705 {} \uc644\ub8cc ({}ms, {}/{} agents)",
                mode, result.metadata().totalDurationMs(),
                result.metadata().agentsSucceeded(), result.metadata().agentsUsed());

            if (telegramNotifier != null) {
                telegramNotifier.sendAnalysisComplete(mode,
                    result.metadata().totalDurationMs(),
                    result.metadata().agentsSucceeded(),
                    result.metadata().agentsUsed());
            }
        } catch (Exception e) {
            log.error("[Scheduler] \u274C {} \uc2e4\ud328: {}", mode, e.getMessage());
            if (telegramNotifier != null) {
                telegramNotifier.sendAnalysisFailed(mode, e.getMessage());
            }
        }
    }

    /**
     * 모든 STARTUP_MODES를 순차 실행하고, 각 모드별 결과를 반환합니다.
     * 한 모드의 실패가 다른 모드 실행을 막지 않습니다.
     */
    public Map<String, String> refreshAll() {
        Map<String, String> results = new LinkedHashMap<>();
        log.info("[Scheduler] 전체 새로고침 시작");

        for (var entry : STARTUP_MODES.entrySet()) {
            String mode = entry.getKey();
            String query = entry.getValue();
            try {
                execute(mode, query);
                results.put(mode, "success");
            } catch (Exception e) {
                log.error("[Scheduler] {} 새로고침 실패: {}", mode, e.getMessage());
                results.put(mode, "error: " + e.getMessage());
            }
        }

        log.info("[Scheduler] 전체 새로고침 완료: {}", results);
        return results;
    }

    public Map<String, String> getLastRunTimes() {
        return Map.copyOf(lastRunTimes);
    }
}
