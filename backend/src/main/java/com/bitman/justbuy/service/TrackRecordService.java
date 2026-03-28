package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord.TrackStatus;
import com.bitman.justbuy.repository.TrackRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class TrackRecordService {

    private static final Logger log = LoggerFactory.getLogger(TrackRecordService.class);

    private final TrackRecordRepository repository;
    private final KisApiService kisApiService;

    public TrackRecordService(TrackRecordRepository repository, KisApiService kisApiService) {
        this.repository = repository;
        this.kisApiService = kisApiService;
    }

    /** 분석 완료 시 추천 종목을 DB에 기록 */
    public void recordAnalysis(AnalysisResponse response) {
        if (response == null || response.stockPicks() == null || response.stockPicks().isEmpty()) return;

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        for (StockPick pick : response.stockPicks()) {
            if (pick.code() == null || pick.code().length() != 6) continue;

            try {
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

                record.setPriceAtAnalysis(parsePrice(pick.currentPrice()));
                record.setTargetPrice(parsePrice(pick.targetPrice()));
                record.setStopLoss(parsePrice(pick.stopLoss()));

                repository.save(record);
            } catch (Exception e) {
                log.debug("[TrackRecord] Failed to record {}: {}", pick.code(), e.getMessage());
            }
        }

        log.info("[TrackRecord] Recorded {} picks for mode={}", response.stockPicks().size(), response.mode());
    }

    /** 매일 16:00 KST에 미완료 레코드의 현재가 업데이트 */
    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Seoul")
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

    private Long parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isBlank()) return null;
        try {
            return Long.parseLong(priceStr.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
