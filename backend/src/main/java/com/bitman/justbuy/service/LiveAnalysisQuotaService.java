package com.bitman.justbuy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 라이브 분석(/api/analysis/live) 사용자별 일일 쿼터.
 *
 * <p>라이브 요청 1건은 LLM 에이전트 2개를 호출한다. 지금까지 상한이 30분 서버 캐시뿐이어서
 * 쿼리 문구만 바꾸면 캐시를 비껴가 무제한 호출이 가능했다. 회원 1명이 비용을 끝없이
 * 밀어올릴 수 있는 구조라 사용자 단위 상한을 둔다.
 *
 * <p>KST 자정에 리셋된다. 프로세스 메모리에만 두므로 재기동 시 초기화되는데,
 * 비용 폭주를 막는 것이 목적이고 재기동은 드물어 DB 를 쓰지 않는다.
 * 관리자는 제한하지 않는다(운영·점검 필요).
 */
@Service
public class LiveAnalysisQuotaService {

    private static final Logger log = LoggerFactory.getLogger(LiveAnalysisQuotaService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final Map<UUID, DailyCount> counters = new ConcurrentHashMap<>();

    @Value("${bitman.analysis.live.daily-quota:5}")
    private int dailyQuota = 5;

    private record DailyCount(LocalDate date, AtomicInteger used) {}

    /** 남은 횟수. 쿼터를 소비하지 않는다. */
    public int remaining(UUID userId) {
        if (userId == null) return 0;
        DailyCount current = counters.get(userId);
        LocalDate today = LocalDate.now(KST);
        int used = current == null || !current.date().equals(today) ? 0 : current.used().get();
        return Math.max(0, quota() - used);
    }

    /**
     * 1회 소비를 시도한다.
     *
     * @return 성공하면 true. 이미 한도를 다 쓴 경우 false (이때는 소비하지 않는다).
     */
    public boolean tryConsume(UUID userId) {
        if (userId == null) return false;
        LocalDate today = LocalDate.now(KST);

        DailyCount counter = counters.compute(userId, (key, existing) ->
            existing == null || !existing.date().equals(today)
                ? new DailyCount(today, new AtomicInteger(0))
                : existing);

        int limit = quota();
        // 경쟁 상태에서 한도를 넘기지 않도록 CAS 루프로 증가시킨다.
        while (true) {
            int used = counter.used().get();
            if (used >= limit) {
                log.info("[LiveQuota] 한도 초과: userId={}, used={}/{}", userId, used, limit);
                return false;
            }
            if (counter.used().compareAndSet(used, used + 1)) {
                return true;
            }
        }
    }

    public int quota() {
        return Math.max(1, dailyQuota);
    }

    /** 오래된 날짜의 카운터를 정리한다. */
    public void evictStale() {
        LocalDate today = LocalDate.now(KST);
        counters.entrySet().removeIf(entry -> !entry.getValue().date().equals(today));
    }
}
