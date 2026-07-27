package com.bitman.justbuy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 기동 시 종가매매 추천의 미검증분을 소급 검증한다.
 *
 * <p>스케줄 잡({@link JonggaTrackRecordService#verifyPreviousDayJongga})은 평가일 당일
 * 15:40/16:10 에만 돈다. 그 창을 놓친 날(배포 직후, 서버 중단, KIS 장애)의 추천은 영구히
 * 미검증으로 남아 히스토리 화면이 비어 보인다. 기동할 때마다 최근 구간을 일봉으로 메워
 * 스스로 복구한다.
 *
 * <p>이미 검증된 레코드는 건너뛰므로 재기동해도 안전하다(멱등). 기동을 막지 않도록
 * 별도 스레드에서 돈다.
 */
@Component
@ConditionalOnProperty(
    name = "bitman.jongga.backfill-on-startup.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class JonggaBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(JonggaBackfillRunner.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JonggaTrackRecordService jonggaTrackRecordService;

    @Value("${bitman.jongga.backfill-on-startup.days:45}")
    private int days = 45;

    @Value("${bitman.jongga.backfill-on-startup.delay-ms:20000}")
    private long delayMs = 20_000L;

    public JonggaBackfillRunner(JonggaTrackRecordService jonggaTrackRecordService) {
        this.jonggaTrackRecordService = jonggaTrackRecordService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        Thread.ofVirtual().name("jongga-backfill").start(this::run);
    }

    void run() {
        try {
            // KIS 토큰 확보/초기 워밍업이 끝난 뒤 시작
            Thread.sleep(Math.max(delayMs, 0));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            LocalDate today = LocalDate.now(KST);
            LocalDate from = today.minusDays(Math.max(days, 1));
            log.info("[JonggaBackfill] 소급검증 시작: {} ~ {}", from, today);
            int verified = jonggaTrackRecordService.backfillVerification(from, today);
            log.info("[JonggaBackfill] 소급검증 종료: {}건 확정", verified);
        } catch (Exception e) {
            log.warn("[JonggaBackfill] 소급검증 실패 (서비스 영향 없음): {}", e.getMessage());
        }
    }
}
