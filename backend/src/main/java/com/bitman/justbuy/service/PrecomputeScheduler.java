package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 홈 카드 4개 컨셉 모드 프리컴퓨트 스케줄러.
 *
 * 스케줄 (평일 거래일만, v2.8.3 토큰 절약 — 사용자 요청 2026-04-25):
 *   08:50 → 1차 분석 (→ 09:00 ~ 11:45 캐시 서빙)
 *   11:50 → 2차 분석 (→ 11:50 ~ 14:45 캐시 서빙)
 *   14:50 → 3차 분석 (→ 14:50 ~ 15:30 장마감 캐시 서빙)
 *
 * v2.8.2(2회/일) → v2.8.3(3회/일)로 변경.
 * + GrokAgent SearchPolicy 적용 — BREAKOUT 모드는 검색 OFF + 저렴 모델 우회.
 * + Round 2 Synthesis(ChatGPT gpt-4o) 단계 제거 — 두 AI 모두 analyze 만 수행.
 *
 * TTL: AnalysisService.MODE_TTL_MINUTES = 175분 (2h55m) — 스케줄 간격 3h - 5m 여유.
 *      14:50 결과는 15:30 KST 장마감 이후 자동 컷오프.
 *
 * 텔레그램 발송: 기존과 동일 (장 시간 09:00~15:59 KST 게이트 유지).
 * 주말·KRX 공휴일 자동 건너뜀.
 */
@Component
@ConditionalOnProperty(name = "bitman.scheduler.enabled", havingValue = "true")
public class PrecomputeScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrecomputeScheduler.class);
    private final AnalysisService analysisService;
    private final ConditionSearchFormulaCatalog formulaCatalog;
    private final TelegramNotifier telegramNotifier;
    private final KisApiService kisApiService;
    private final MarketDataService marketDataService;
    private final Map<String, String> lastRunTimes = new ConcurrentHashMap<>();

    public PrecomputeScheduler(AnalysisService analysisService,
                               ConditionSearchFormulaCatalog formulaCatalog,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               TelegramNotifier telegramNotifier,
                               KisApiService kisApiService,
                               MarketDataService marketDataService) {
        this.analysisService = analysisService;
        this.formulaCatalog = formulaCatalog;
        this.telegramNotifier = telegramNotifier;
        this.kisApiService = kisApiService;
        this.marketDataService = marketDataService;
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
        Set<String> seenCodes = new HashSet<>();  // ★ cross-mode dedup (cached 도 포함)
        Map<String, String> scheduledModes = formulaCatalog.scheduledQueries();
        for (var entry : scheduledModes.entrySet()) {
            String mode = entry.getKey();
            total++;
            try {
                var cached = analysisService.getPrecomputed(mode);
                if (cached != null) {
                    log.info("[Scheduler] ✅ {} — 캐시 유효, 건너뜀", mode);
                    success++;
                    // ★ v2.8.7 (2026-04-29): 캐시된 결과도 dedup + name disambiguation 적용
                    //   — 옛 캐시(중복 포함) 그대로 재발송 방지
                    cached = postProcessPicks(mode, cached, seenCodes);
                    // 장 시간(8~15시) 재시작이면 캐시 결과 즉시 발송 — 누락 방지
                    // v2.8.5 (2026-04-27): 1차 cron(08:50) 결과 텔레그램 발송 허용 (08시 포함)
                    int kstHour = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).getHour();
                    if (telegramNotifier != null && kstHour >= 8 && kstHour < 16) {
                        var picks = cached.stockPicks();
                        var meta  = cached.metadata();
                        int succeeded = meta != null ? meta.agentsSucceeded() : 0;
                        if (picks != null && !picks.isEmpty() && succeeded > 0) {
                            log.info("[Scheduler] 📨 {} — 장중 재시작 감지, 캐시 결과 즉시 발송 (dedup 적용)", mode);
                            telegramNotifier.sendAnalysisResult(mode, cached);
                        }
                    }
                    continue;
                }
                log.info("[Scheduler] ▶ {} — 캐시 없음, 자동 실행", mode);
                execute(mode, entry.getValue(), seenCodes);
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
     * 08:50 (1차) / 11:50 (2차) / 14:50 (3차) — 평일 KST.
     * 4개 컨셉 모드 순차 실행 → 각 분석 결과는 다음 회차 직전까지 캐시 서빙.
     *
     * Grok/ChatGPT API 토큰 소모 완화를 위해 v2.8.3 (2026-04-25)에 1일 3회로 조정.
     *  - 1차: 09:00 장 시작 직전 분석
     *  - 2차: 점심 직전 (11:45 캐시 만료 직전) 갱신
     *  - 3차: 오후장 마감 분석 (장마감 15:30까지 유효)
     */
    @Scheduled(cron = "0 50 8,11,14 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledPrecompute() {
        if (!isTradingDay()) {
            log.info("[Scheduler] ⏭ 분석 건너뜀 (휴장일)");
            return;
        }

        int hour = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).getHour();
        String round = switch (hour) {
            case 8 -> "1차";
            case 11 -> "2차";
            case 14 -> "3차";
            default -> "수동";
        };
        log.info("[Scheduler] ⏰ {}:50 {} 컨셉 모드 4종 분석 시작", hour, round);

        int success = 0;
        Set<String> seenCodes = new HashSet<>();  // ★ cross-mode dedup
        Map<String, String> scheduledModes = formulaCatalog.scheduledQueries();
        for (var entry : scheduledModes.entrySet()) {
            try {
                execute(entry.getKey(), entry.getValue(), seenCodes);
                success++;
            } catch (Exception e) {
                log.error("[Scheduler] ❌ {}:50 {} 실패: {}", hour, entry.getKey(), e.getMessage());
            }
        }

        log.info("[Scheduler] ✅ {}:50 {} 완료 ({}/{}) — 누적 추천 {}종목",
            hour, round, success, scheduledModes.size(), seenCodes.size());
    }

    private boolean isTradingDay() {
        return KoreanMarketCalendar.isTradingDay(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }

    /**
     * picks 후처리 — cross-mode dedup + 같은 모드 내 동일 이름 disambiguation.
     *
     * @return 후처리된 새 AnalysisResponse (picks 변경 없으면 원본 그대로 반환).
     *         picks 변경된 경우 캐시 파일도 다시 저장.
     */
    private AnalysisResponse postProcessPicks(String mode, AnalysisResponse result, Set<String> seenCodes) {
        List<StockPick> picks = result.stockPicks();
        if (picks == null || picks.isEmpty()) return result;

        // ★ v2.8.11 (2026-04-29): Naver Finance 로 ticker 검증 + 종목명 강제 교정
        //   - AI가 hallucinated 한 잘못된 코드(예: 0011T0) → 픽 제거 (6자리 숫자 아닌 것)
        //   - 6자리 숫자지만 Naver 에 없는 코드 → 픽 제거
        //   - Naver 에 있는 코드 → 종목명을 Naver 정식명(stockName)으로 강제 교체
        //     예: 229640 → "LS에코에너지", 006260 → "LS", 417200 → "LS머트리얼즈"
        //   KIS current-price 응답에는 종목명 필드(hts_kor_isnm)가 없음 — Naver 사용.
        List<StockPick> nameFixed = new java.util.ArrayList<>(picks.size());
        int nameFixedCount = 0;
        int invalidCodeDropped = 0;
        int notFoundDropped = 0;
        for (StockPick p : picks) {
            String code = p.code() != null ? p.code().trim() : "";
            // 1) ticker 형식 검증: 6자리 숫자만 valid (KRX 규격)
            if (!code.matches("\\d{6}")) {
                log.warn("[Scheduler] {} 잘못된 ticker → 픽 제거: code='{}', name='{}'", mode, code, p.name());
                invalidCodeDropped++;
                continue;
            }
            // 2) Naver 미가용 시 fallback (AI 그대로 유지)
            if (marketDataService == null) {
                nameFixed.add(p);
                continue;
            }
            try {
                String canonName = marketDataService.fetchStockName(code);
                if (canonName.isBlank()) {
                    // Naver 응답 무 → 미상장/잘못된 ticker
                    log.warn("[Scheduler] {} Naver 미존재 ticker → 픽 제거: code={}, AI name='{}'",
                        mode, code, p.name());
                    notFoundDropped++;
                    continue;
                }
                // Naver 정식명으로 강제 교체
                if (!canonName.equals(p.name())) {
                    nameFixed.add(new StockPick(
                        canonName, code, p.currentPrice(), p.targetPrice(),
                        p.stopLoss(), p.action(), p.reason(),
                        p.financialScore(), p.financialSummary()
                    ));
                    nameFixedCount++;
                } else {
                    nameFixed.add(p);
                }
            } catch (Exception e) {
                log.debug("[Scheduler] {} {} Naver 조회 실패: {}", mode, code, e.getMessage());
                nameFixed.add(p);
            }
        }
        if (nameFixedCount > 0 || invalidCodeDropped > 0 || notFoundDropped > 0) {
            log.info("[Scheduler] {} Naver 검증: 이름교정 {}건, 잘못된형식 {}건 제거, 미존재 {}건 제거",
                mode, nameFixedCount, invalidCodeDropped, notFoundDropped);
        }
        picks = nameFixed;
        // 모든 픽이 제거됐으면 dedup 진행 X — 원본 result 그대로 반환 방지를 위해 빈 result 저장
        if (picks.isEmpty()) {
            AnalysisResponse emptied = new AnalysisResponse(
                result.mode(), result.query(), result.round1(), result.synthesis(),
                result.finalContent(), java.util.List.of(), result.consensus(),
                result.updatedAt(), result.isFresh(), result.metadata()
            );
            analysisService.saveAnalysis(mode, emptied);
            log.warn("[Scheduler] {} 모든 픽이 검증 실패로 제거됨 — 빈 결과 저장", mode);
            return emptied;
        }

        // ★ v2.8.8 (2026-04-29): 같은 모드 내 동일 이름 dedup (코드 다르더라도 1개만 유지)
        //   KIS 이름 교정 후엔 LS네트웍스 / LS Corp / LS머트리얼즈 가 다 다른 이름이라 중복 안 됨.
        //   여전히 같은 이름 (예: 동일한 종목) 등장하면 첫 것만 유지.
        Set<String> seenNamesInMode = new HashSet<>();
        List<StockPick> intraDedup = new java.util.ArrayList<>();
        int sameNameDropped = 0;
        for (StockPick p : picks) {
            String key = p.name() == null ? "" : p.name().trim();
            if (key.isEmpty() || seenNamesInMode.add(key)) {
                intraDedup.add(p);
            } else {
                sameNameDropped++;
            }
        }
        if (sameNameDropped > 0) {
            log.info("[Scheduler] {} 같은 이름 dedup: {} 종목 제거", mode, sameNameDropped);
        }

        // 1) Cross-mode dedup (code 매치)
        //    ★ v2.8.9 (2026-04-29): dedup 결과 picks=0 이면 dedup 스킵 (앱에서 빈 모드 방지)
        List<StockPick> filtered = intraDedup;
        if (seenCodes != null && !seenCodes.isEmpty()) {
            List<StockPick> candidate = intraDedup.stream()
                .filter(p -> p.code() == null || !seenCodes.contains(p.code()))
                .toList();
            int removed = intraDedup.size() - candidate.size();
            if (candidate.isEmpty() && !intraDedup.isEmpty()) {
                // 전부 중복 → dedup 적용 안 함 (UX 우선 — 모드별 빈 화면 방지)
                log.warn("[Scheduler] {} cross-mode dedup 후 picks=0 → dedup 스킵 (앱 UX 보호, {}종목 유지)",
                    mode, intraDedup.size());
            } else if (removed > 0) {
                filtered = candidate;
                log.info("[Scheduler] {} cross-mode dedup: {} 종목 제거 (이전 모드 중복)", mode, removed);
            }
        }

        // ★ v2.8.12 (2026-04-29): changed 검사 — 개수 OR 내용(이름/코드) 중 하나라도 다르면 재저장
        //   기존: filtered.size() != picks.size() 만 체크 → 이름만 바뀐 경우 재저장 안 됨 → 캐시에 옛 이름 남음
        boolean changed = (filtered.size() != result.stockPicks().size());
        if (!changed) {
            // 같은 size 라도 내용 다른지 비교 (Naver 이름 교정 등)
            List<StockPick> orig = result.stockPicks();
            for (int i = 0; i < orig.size(); i++) {
                StockPick a = orig.get(i);
                StockPick b = filtered.get(i);
                if (!java.util.Objects.equals(a.name(), b.name())
                        || !java.util.Objects.equals(a.code(), b.code())) {
                    changed = true;
                    break;
                }
            }
        }

        // 변경 없으면 원본 반환
        if (!changed) {
            updateSeen(seenCodes, filtered);
            return result;
        }

        // 변경 있음 — 새 AnalysisResponse + 캐시 재저장
        AnalysisResponse updated = new AnalysisResponse(
            result.mode(), result.query(), result.round1(), result.synthesis(),
            result.finalContent(), filtered, result.consensus(),
            result.updatedAt(), result.isFresh(), result.metadata()
        );
        analysisService.saveAnalysis(mode, updated);
        updateSeen(seenCodes, filtered);
        return updated;
    }

    private void updateSeen(Set<String> seenCodes, List<StockPick> picks) {
        if (seenCodes == null || picks == null) return;
        for (StockPick p : picks) {
            if (p.code() != null && !p.code().isBlank()) {
                seenCodes.add(p.code());
            }
        }
    }

    private void execute(String mode, String query) {
        execute(mode, query, null);
    }

    /**
     * 모드 분석 실행 + cross-mode dedup + name disambiguation.
     *
     * @param seenCodes — 이전 모드들에서 이미 추천된 종목 코드 집합. null 이면 dedup 안함.
     *                   분석 후 이 set 에 본 모드의 코드들이 추가됨 (다음 모드용).
     */
    private void execute(String mode, String query, Set<String> seenCodes) {
        String now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.info("[Scheduler] ▶ {} 실행 시작 ({})", mode, now);

        long startMs = System.currentTimeMillis();
        try {
            AnalysisResponse result = analysisService.runLiveAnalysis(query, mode);
            if (result.updatedAt() != null) lastRunTimes.put(mode, result.updatedAt());

            // ★ v2.8.7 (2026-04-29): Cross-mode dedup + name disambiguation
            //   1) seenCodes 에 있는 코드는 이번 모드 picks 에서 제거 (이전 모드 중복 방지)
            //   2) 같은 모드 내 동일 이름 + 다른 코드는 "이름(code)" 로 disambiguate
            result = postProcessPicks(mode, result, seenCodes);

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
            // 3) 장 시간(08:00~15:59 KST)  — 서버 재시작/장외 자동 분석 발송 방지
            //    v2.8.5 (2026-04-27): 08:50 1차 cron 결과 발송 허용 위해 08시 포함
            int kstHour = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).getHour();
            boolean isMarketHours = kstHour >= 8 && kstHour < 16;
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

        Set<String> seenCodes = new HashSet<>();  // ★ cross-mode dedup
        for (var entry : formulaCatalog.scheduledQueries().entrySet()) {
            try {
                execute(entry.getKey(), entry.getValue(), seenCodes);
                results.put(entry.getKey(), "success");
            } catch (Exception e) {
                log.error("[Scheduler] {} 새로고침 실패: {}", entry.getKey(), e.getMessage());
                results.put(entry.getKey(), "error: " + e.getMessage());
            }
        }

        log.info("[Scheduler] 전체 새로고침 완료: {} — 누적 추천 {}종목", results, seenCodes.size());
        return results;
    }

    public Map<String, String> getLastRunTimes() {
        return Map.copyOf(lastRunTimes);
    }
}
