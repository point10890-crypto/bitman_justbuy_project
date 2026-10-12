package com.bitman.justbuy.service;

import com.bitman.justbuy.condition.run.ConditionRunEventType;
import com.bitman.justbuy.condition.run.ConditionRunService;
import com.bitman.justbuy.condition.run.ConditionRunTrigger;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.bitman.justbuy.util.StockUniverseFilters;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

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
    private final ConditionRunService conditionRunService;
    private final Map<String, String> lastRunTimes = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AtomicBoolean startupAttempted = new AtomicBoolean();
    private final AtomicBoolean scheduledRunning = new AtomicBoolean();
    private KisSourceExpansionService sourceExpansion;
    @Autowired(required=false)
    public void configureSourceExpansion(KisSourceExpansionService expansion) { this.sourceExpansion=expansion; }

    @Scheduled(cron="0 40 8,11,14 * * MON-FRI", zone="Asia/Seoul")
    public void prepareSourceExpansion() {
        if(!isTradingDay() || sourceExpansion==null) return;
        try { sourceExpansion.prepare(); }
        catch(Exception ignored) { log.warn("[Scheduler] New source preparation unavailable; verified existing sources retained"); }
    }
    @Value("${bitman.scheduler.startup-refresh-enabled:true}")
    private boolean startupRefreshEnabled = true;

    @Autowired
    public PrecomputeScheduler(AnalysisService analysisService,
                               ConditionSearchFormulaCatalog formulaCatalog,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               TelegramNotifier telegramNotifier,
                               KisApiService kisApiService,
                               MarketDataService marketDataService,
                               ConditionRunService conditionRunService) {
        this(analysisService, formulaCatalog, telegramNotifier, kisApiService, marketDataService, conditionRunService, Clock.systemUTC());
    }

    PrecomputeScheduler(AnalysisService analysisService, ConditionSearchFormulaCatalog formulaCatalog,
                        TelegramNotifier telegramNotifier, KisApiService kisApiService,
                        MarketDataService marketDataService, ConditionRunService conditionRunService, Clock clock) {
        this.analysisService = analysisService;
        this.formulaCatalog = formulaCatalog;
        this.telegramNotifier = telegramNotifier;
        this.kisApiService = kisApiService;
        this.marketDataService = marketDataService;
        this.conditionRunService = conditionRunService;
        this.clock = clock;
        log.info("[Scheduler] 컨셉 모드 4종 스케줄러 초기화 (매시 50분, KST), telegram={}", telegramNotifier != null);
    }

    /** 서버 시작 후 캐시 없는 모드 자동 분석 */
    @PostConstruct
    public void onStartup() {
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(30_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            runStartupPrecompute();
        });
    }

    private void runStartupPrecompute() {
        if (!startupAttempted.compareAndSet(false, true)) return;
        if (!isStartupRefreshAllowed()) {
            for (String mode : formulaCatalog.scheduledQueries().keySet()) {
                var stored = analysisService.getStoredPrecomputed(mode);
                if (stored != null && AnalysisOutcome.classify(stored) != AnalysisOutcome.FAILED
                    && stored.stockPicks() != null && !stored.stockPicks().isEmpty() && stored.updatedAt() != null) {
                    lastRunTimes.put(mode, stored.updatedAt());
                }
            }
            log.info("[Scheduler] Startup refresh skipped by trading-day/time/cost policy");
            return;
        }

        if(!scheduledRunning.compareAndSet(false,true)) {
            log.info("[Scheduler] Startup recovery skipped while scheduled analysis is active");return;
        }
        try {
        prepareSourceExpansion();
        if(sourceExpansion!=null && sourceExpansion.isOfficiallyClosedToday()) {
            log.info("[Scheduler] Startup analysis skipped: official KIS calendar reports market closed");return;
        }
        log.info("[Scheduler] 🚀 서버 시작 — 캐시 확인 중...");
        int success = 0, total = 0;
        Set<String> seenCodes = new HashSet<>();  // ★ cross-mode dedup (cached 도 포함)
        Map<String, String> scheduledModes = formulaCatalog.scheduledQueries();
        for (var entry : scheduledModes.entrySet()) {
            String mode = entry.getKey();
            String query = entry.getValue();
            total++;
            try {
                var cached = analysisService.getPrecomputed(mode);
                if (cached != null) {
                    var run = conditionRunService.create(ConditionRunTrigger.SCHEDULER_STARTUP, mode, query);
                    try {
                        log.info("[Scheduler] ✅ {} — 캐시 유효, 건너뜀", mode);
                        // ★ v2.8.7 (2026-04-29): 캐시된 결과도 dedup + name disambiguation 적용
                        //   — 옛 캐시(중복 포함) 그대로 재발송 방지
                        cached = postProcessPicks(mode, cached, seenCodes);
                        AnalysisOutcome.requireSuccessfulAgents(cached);
                        if (AnalysisOutcome.classify(cached) != AnalysisOutcome.EMPTY) {
                            analysisService.saveAnalysis(mode, cached);
                            updateSeen(seenCodes, cached.stockPicks());
                            if (cached.updatedAt() != null) lastRunTimes.put(mode, cached.updatedAt());
                        }
                        conditionRunService.recordEvent(run.runId(), ConditionRunEventType.CACHE_HIT,
                            "startup precompute cache hit", Map.of("mode", mode));
                        // 장 시간(8~15시) 재시작이면 캐시 결과 즉시 발송 — 누락 방지
                        // v2.8.5 (2026-04-27): 1차 cron(08:50) 결과 발송 허용 위해 08시 포함
                        int kstHour = nowKst().getHour();
                        boolean isMarketHours = kstHour >= 8 && kstHour < 16;
                        var picks = cached.stockPicks();
                        var meta  = cached.metadata();
                        int pickCount = picks != null ? picks.size() : 0;
                        int succeeded = meta != null ? meta.agentsSucceeded() : 0;
                        {
                            conditionRunService.recordEvent(run.runId(), ConditionRunEventType.NOTIFICATION_SKIPPED,
                                "startup cached notification skipped to avoid duplicate delivery", Map.of(
                                    "pickCount", pickCount,
                                    "agentsSucceeded", succeeded,
                                    "marketHours", isMarketHours,
                                    "notifierConfigured", telegramNotifier != null
                                ));
                        }
                        conditionRunService.markComplete(run.runId(), cached);
                        if (AnalysisOutcome.classify(cached) == AnalysisOutcome.COMPLETE) success++;
                    } catch (Exception e) {
                        conditionRunService.markFailed(run.runId(), e.getMessage());
                        throw e;
                    }
                    continue;
                }
                log.info("[Scheduler] ▶ {} — 캐시 없음, 자동 실행", mode);
                if ("success".equals(execute(mode, query, seenCodes, ConditionRunTrigger.SCHEDULER_STARTUP))) success++;
            } catch (Exception e) {
                log.error("[Scheduler] ❌ {} 시작 시 실행 실패: {}", mode, e.getMessage());
            }
        }
        log.info("[Scheduler] 🏁 시작 시 프리컴퓨트 완료 ({}/{})", success, total);

        if (telegramNotifier != null) {
            telegramNotifier.sendStartupComplete(success, total);
        }
        } finally { scheduledRunning.set(false); }
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
        if(!scheduledRunning.compareAndSet(false,true)) {
            log.warn("[Scheduler] Scheduled analysis already running; duplicate invocation skipped");return;
        }
        try {
        prepareSourceExpansion();
        if(sourceExpansion!=null && sourceExpansion.isOfficiallyClosedToday()) {
            log.info("[Scheduler] Scheduled analysis skipped: official KIS calendar reports market closed");return;
        }

        int hour = nowKst().getHour();
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
                if ("success".equals(execute(entry.getKey(), entry.getValue(), seenCodes, ConditionRunTrigger.SCHEDULER_CRON))) success++;
            } catch (Exception e) {
                log.error("[Scheduler] ❌ {}:50 {} 실패: {}", hour, entry.getKey(), e.getMessage());
            }
        }

        log.info("[Scheduler] ✅ {}:50 {} 완료 ({}/{}) — 누적 추천 {}종목",
            hour, round, success, scheduledModes.size(), seenCodes.size());
        } finally { scheduledRunning.set(false); }
    }

    private boolean isTradingDay() {
        return KoreanMarketCalendar.isTradingDay(nowKst().toLocalDate());
    }

    private ZonedDateTime nowKst() { return clock.instant().atZone(ZoneId.of("Asia/Seoul")); }

    boolean isStartupRefreshAllowed() {
        LocalTime time = nowKst().toLocalTime();
        return startupRefreshEnabled && isTradingDay()
            && !time.isBefore(LocalTime.of(8, 50)) && !time.isAfter(LocalTime.of(15, 30));
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
            // Published source identity is already verified; avoid serial network lookups.
            if (AnalysisOutcome.isVerifiedPick(p, clock.instant(), Long.MAX_VALUE) || marketDataService == null) {
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
                        p.financialScore(), p.financialSummary(), p.candidateSource(), p.quoteSource(), p.quoteCheckedAt()
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
        int exchangeTradedDropped = 0;
        List<StockPick> spotOnly = new java.util.ArrayList<>(picks.size());
        for (StockPick p : picks) {
            if (p.name() != null && StockUniverseFilters.isExchangeTradedProductName(p.name())) {
                exchangeTradedDropped++;
                continue;
            }
            spotOnly.add(p);
        }
        if (exchangeTradedDropped > 0) {
            log.info("[Scheduler] {} exchange-traded product filter: {} picks dropped",
                mode, exchangeTradedDropped);
        }
        picks = spotOnly;
        // 모든 픽이 제거됐으면 dedup 진행 X — 원본 result 그대로 반환 방지를 위해 빈 result 저장
        if (picks.isEmpty()) {
            AnalysisResponse emptied = new AnalysisResponse(
                result.mode(), result.query(), result.round1(), result.synthesis(),
                result.finalContent(), java.util.List.of(), result.consensus(),
                result.updatedAt(), result.isFresh(), result.metadata()
            );
            log.warn("[Scheduler] {} 모든 픽이 최종 조건으로 제거됨 — 정상 캐시 보존", mode);
            return AnalysisPublicationView.finalizePicks(emptied, List.of());
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
            if (removed > 0) {
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
            return AnalysisOutcome.describe(result);
        }

        // 변경 있음 — 새 AnalysisResponse + 캐시 재저장
        AnalysisResponse updated = new AnalysisResponse(
            result.mode(), result.query(), result.round1(), result.synthesis(),
            result.finalContent(), filtered, result.consensus(),
            result.updatedAt(), result.isFresh(), result.metadata()
        );
        return AnalysisPublicationView.finalizePicks(updated, filtered);
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
        execute(mode, query, null, ConditionRunTrigger.UNKNOWN);
    }

    /**
     * 모드 분석 실행 + cross-mode dedup + name disambiguation.
     *
     * @param seenCodes — 이전 모드들에서 이미 추천된 종목 코드 집합. null 이면 dedup 안함.
     *                   분석 후 이 set 에 본 모드의 코드들이 추가됨 (다음 모드용).
     */
    private void execute(String mode, String query, Set<String> seenCodes) {
        execute(mode, query, seenCodes, ConditionRunTrigger.UNKNOWN);
    }

    private String execute(String mode, String query, Set<String> seenCodes, ConditionRunTrigger trigger) {
        String now = nowKst()
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.info("[Scheduler] ▶ {} 실행 시작 ({})", mode, now);

        var run = conditionRunService.create(trigger, mode, query);
        long startMs = System.currentTimeMillis();
        try {
            conditionRunService.markStarted(run.runId(), "scheduler analysis started");
            conditionRunService.recordEvent(run.runId(), ConditionRunEventType.AI_STARTED,
                "scheduler pipeline run started", Map.of("mode", mode));
            AnalysisResponse result = analysisService.runAnalysisForPublication(query, mode);
            AnalysisOutcome.requireSuccessfulAgents(result);

            // ★ v2.8.7 (2026-04-29): Cross-mode dedup + name disambiguation
            //   1) seenCodes 에 있는 코드는 이번 모드 picks 에서 제거 (이전 모드 중복 방지)
            //   2) 같은 모드 내 동일 이름 + 다른 코드는 "이름(code)" 로 disambiguate
            result = postProcessPicks(mode, result, seenCodes);
            AnalysisOutcome outcome = AnalysisOutcome.classify(result);
            if (outcome == AnalysisOutcome.EMPTY) {
                log.info("[Scheduler] {} outcome=EMPTY agents={}/{} picks=0 cachePreserved=true", mode,
                    result.metadata().agentsSucceeded(), result.metadata().agentsUsed());
                conditionRunService.recordEvent(run.runId(), ConditionRunEventType.NOTIFICATION_SKIPPED,
                    "empty publication; last good cache preserved", Map.of("outcome", "EMPTY", "pickCount", 0));
                conditionRunService.markComplete(run.runId(), result);
                com.bitman.justbuy.controller.MonitorController.recordAnalysis(mode, false,
                    System.currentTimeMillis() - startMs, result.metadata().agentsUsed(), result.metadata().agentsSucceeded(), 0, "EMPTY");
                return "empty";
            }
            analysisService.saveAnalysis(mode, result);
            conditionRunService.recordEvent(run.runId(), ConditionRunEventType.SAVED,
                "final publication saved", Map.of("pickCount", result.stockPicks().size(), "outcome", outcome.name()));
            analysisService.recordPublishedAnalysis(result);
            updateSeen(seenCodes, result.stockPicks());
            if (result.updatedAt() != null) lastRunTimes.put(mode, result.updatedAt());

            // metadata 가 null 일 수 있는 경로(폴백 응답 등)를 NPE 없이 처리
            var meta = result.metadata();
            long durationMs = meta != null ? meta.totalDurationMs() : (System.currentTimeMillis() - startMs);
            int agentsUsed = meta != null ? meta.agentsUsed() : 0;
            int agentsSucceeded = meta != null ? meta.agentsSucceeded() : 0;
            int picks = result.stockPicks() != null ? result.stockPicks().size() : 0;
            conditionRunService.recordEvent(run.runId(), ConditionRunEventType.PICKS_PARSED,
                "scheduler analysis result parsed", Map.of("pickCount", picks));

            log.info("[Scheduler] ✅ {} 완료 ({}ms, {}/{} agents)",
                mode, durationMs, agentsSucceeded, agentsUsed);

            com.bitman.justbuy.controller.MonitorController.recordAnalysis(
                mode, outcome == AnalysisOutcome.COMPLETE, durationMs, agentsUsed, agentsSucceeded, picks,
                outcome == AnalysisOutcome.PARTIAL ? "PARTIAL" : null);

            // 에이전트 일부 실패는 지금까지 로그에만 남아 조용히 지나갔다.
            // 2개 중 1개만 성공해도 합의(consensus)가 비고 픽 품질이 떨어지므로 즉시 알린다.
            if (trigger != ConditionRunTrigger.ADMIN_REFRESH) notifyAgentDegradation(mode, agentsUsed, agentsSucceeded, picks);

            // 텔레그램 발송 조건 3가지 동시 충족 시에만 발송:
            // 1) picks > 0  — 빈 분석 결과 발송 방지
            // 2) agentsSucceeded > 0  — 모든 AI 실패 시 발송 방지
            // 3) 장 시간(08:00~15:59 KST)  — 서버 재시작/장외 자동 분석 발송 방지
            //    v2.8.5 (2026-04-27): 08:50 1차 cron 결과 발송 허용 위해 08시 포함
            int kstHour = nowKst().getHour();
            boolean isMarketHours = isTradingDay() && kstHour >= 8 && kstHour < 16;
            if (trigger != ConditionRunTrigger.ADMIN_REFRESH && telegramNotifier != null && picks > 0 && agentsSucceeded > 0 && isMarketHours) {
                recordDelivery(run.runId(), telegramNotifier.sendAnalysisResult(mode, result), picks);
            } else {
                log.info("[Scheduler] {} 텔레그램 Skip — picks={}, agents={}/{}, marketHours={}({}시), trigger={}",
                    mode, picks, agentsSucceeded, agentsUsed, isMarketHours, kstHour, trigger);
                conditionRunService.recordEvent(run.runId(), ConditionRunEventType.NOTIFICATION_SKIPPED,
                    "scheduler notification skipped", Map.of(
                        "pickCount", picks,
                        "agentsSucceeded", agentsSucceeded,
                        "marketHours", isMarketHours,
                        "trigger", trigger.name(),
                        "manualSilent", trigger == ConditionRunTrigger.ADMIN_REFRESH
                    ));
            }
            conditionRunService.markComplete(run.runId(), result);
            return outcome == AnalysisOutcome.COMPLETE ? "success" : "partial";
        } catch (Exception e) {
            log.error("[Scheduler] ❌ {} 실패: {}", mode, e.getMessage());

            conditionRunService.markFailed(run.runId(), e.getMessage());

            com.bitman.justbuy.controller.MonitorController.recordAnalysis(
                mode, false, System.currentTimeMillis() - startMs, 0, 0, 0, e.getMessage());

            if (trigger != ConditionRunTrigger.ADMIN_REFRESH && telegramNotifier != null) {
                try {
                    telegramNotifier.sendAnalysisFailed(mode, e.getMessage());
                } catch (Exception notificationError) {
                    log.warn("[Scheduler] {} 실패 통지 오류: {}", mode, notificationError.getMessage());
                }
            }
            if (e instanceof RuntimeException runtimeError) throw runtimeError;
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** 전체 모드 수동 새로고침 (관리자용) */
    public Map<String, String> refreshAll() {
        return refreshModes(null);
    }

    /** 선택 모드만 다시 실행한다. 모든 모드는 실행 전에 검증한다. */
    public Map<String, String> refreshModes(List<String> modes) {
        List<String> selectedModes = normalizeRefreshModes(modes);
        if (!scheduledRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("분석 갱신 작업이 이미 실행 중입니다. 완료 후 다시 시도해 주세요.");
        }
        try {
        Map<String, String> scheduledModes = formulaCatalog.scheduledQueries();
        Map<String, String> results = new LinkedHashMap<>();
        log.info("[Scheduler] 검색식 새로고침 시작: {}", selectedModes);

        Set<String> seenCodes = new HashSet<>();
        for (String mode : selectedModes) {
            try {
                results.put(mode, execute(mode, scheduledModes.get(mode), seenCodes, ConditionRunTrigger.ADMIN_REFRESH));
            } catch (Exception e) {
                log.error("[Scheduler] {} 새로고침 실패: {}", mode, e.getMessage());
                results.put(mode, "error: " + e.getMessage());
            }
        }
        log.info("[Scheduler] 전체 새로고침 완료: {} — 누적 추천 {}종목", results, seenCodes.size());
        return results;
        } finally {
            scheduledRunning.set(false);
        }
    }

    public List<String> normalizeRefreshModes(List<String> modes) {
        Map<String, String> scheduledModes = formulaCatalog.scheduledQueries();
        Set<String> selectedModes = new LinkedHashSet<>();
        if (modes == null) {
            selectedModes.addAll(scheduledModes.keySet());
        } else {
            if (modes.isEmpty()) throw new IllegalArgumentException("새로고침할 검색식 모드를 선택해 주세요.");
            for (String mode : modes) {
                String normalized = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
                if (!scheduledModes.containsKey(normalized)) {
                    throw new IllegalArgumentException("알 수 없는 검색식 모드: " + normalized);
                }
                selectedModes.add(normalized);
            }
        }
        return List.copyOf(selectedModes);
    }

    private void recordDelivery(java.util.UUID runId, TelegramNotifier.DeliveryReport report, int picks) {
        if (report == null || report.status() == TelegramNotifier.DeliveryStatus.SKIPPED) {
            conditionRunService.recordEvent(runId, ConditionRunEventType.NOTIFICATION_SKIPPED,
                "notification disabled or not acknowledged", Map.of("pickCount", picks));
            return;
        }
        Map<String, Object> metrics = Map.of("pickCount", picks, "sentCount", report.sentCount(),
            "failedCount", report.failedCount(), "deliveryStatus", report.status().name());
        if (report.sentCount() > 0) conditionRunService.recordEvent(runId, ConditionRunEventType.NOTIFIED,
            "notification delivery confirmed", metrics);
        if (report.failedCount() > 0) conditionRunService.recordEvent(runId, ConditionRunEventType.NOTIFICATION_FAILED,
            "notification delivery failed", metrics);
    }

    public Map<String, String> getLastRunTimes() {
        return Map.copyOf(lastRunTimes);
    }

    /**
     * 에이전트 부분 실패/픽 0건을 관리자에게 알린다.
     *
     * <p>스케줄 분석은 하루 12회 자동으로 돌아 아무도 보지 않는다. 에이전트가 절반만
     * 성공하면 합의(consensus)가 비어 픽 품질이 떨어지는데, 로그에만 남으면 며칠씩 방치된다.
     * 알림 실패가 분석 자체를 막지 않도록 예외는 삼킨다.
     */
    private void notifyAgentDegradation(String mode, int agentsUsed, int agentsSucceeded, int picks) {
        if (telegramNotifier == null) return;

        boolean partialFailure = agentsUsed > 0 && agentsSucceeded < agentsUsed;
        boolean noPicks = picks == 0;
        if (!partialFailure && !noPicks) return;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("[JUST BUY] 파이프라인 경고\n\n");
            sb.append("모드: ").append(mode).append("\n");
            sb.append("에이전트: ").append(agentsSucceeded).append("/").append(agentsUsed).append(" 성공\n");
            sb.append("픽: ").append(picks).append("건\n\n");
            if (partialFailure) {
                sb.append("- 에이전트 일부 실패 — 합의(consensus)가 비어 픽 품질이 낮아집니다.\n");
            }
            if (noPicks) {
                sb.append("- 픽 0건 — 해당 모드 화면이 비어 보입니다.\n");
            }
            sb.append("\n관리자 페이지 > 시스템 상태를 확인하세요.");
            telegramNotifier.sendToAdmin(sb.toString());
        } catch (Exception e) {
            log.warn("[Scheduler] 에이전트 경고 알림 실패: {}", e.getMessage());
        }
    }
}
