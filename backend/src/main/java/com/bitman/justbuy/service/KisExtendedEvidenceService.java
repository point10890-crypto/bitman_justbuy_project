package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class KisExtendedEvidenceService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final String SOURCE = "KIS OpenAPI";
    private record MinuteBar(LocalDateTime time, long close, long volume) {}
    public record CandidateEvidence(String stockCode, Map<String, Object> minute, Map<String, Object> program) {}
    public record EvidenceBatch(String status, Instant fetchedAt, List<CandidateEvidence> candidates) {}

    private final KisApiService kis;
    private final boolean enabled;
    private final ObjectMapper mapper;
    private final Clock clock;
    @Value("${bitman.kis.extended-evidence.budget-ms:8000}")
    private long budgetMs = 8_000;

    @Autowired
    public KisExtendedEvidenceService(KisApiService kis,
            @Value("${bitman.kis.extended-evidence.enabled:false}") boolean enabled, ObjectMapper mapper) {
        this(kis, enabled, mapper, Clock.systemUTC());
    }

    KisExtendedEvidenceService(KisApiService kis, boolean enabled, ObjectMapper mapper, Clock clock) {
        this.kis = kis;
        this.enabled = enabled;
        this.mapper = mapper;
        this.clock = clock;
    }

    public EvidenceBatch fetchEvidence(List<String> candidateCodes) {
        Instant asOf = clock.instant();
        if (!enabled) return new EvidenceBatch("DISABLED", asOf, List.of());
        if (Thread.currentThread().isInterrupted()) return new EvidenceBatch("CANCELLED", asOf, List.of());
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (candidateCodes != null) for (String code : candidateCodes) {
            if (code != null && code.matches("[0-9]{6}")) codes.add(code);
            if (codes.size() == 5) break;
        }
        if (codes.isEmpty()) return new EvidenceBatch("NO_CANDIDATES", asOf, List.of());
        if (!kis.isAvailable()) return new EvidenceBatch("NOT_CONFIGURED", asOf, List.of());
        long deadline = System.nanoTime() + Duration.ofMillis(Math.max(1, Math.min(8_000, budgetMs))).toNanos();
        List<CandidateEvidence> evidence = new ArrayList<>();
        for (String code : codes) {
            String stop = stopStatus(deadline);
            if (stop != null) return new EvidenceBatch(stop, clock.instant(), List.copyOf(evidence));
            var minute = kis.fetchMinuteEvidence(code, asOf, deadline);
            stop = stopStatus(deadline);
            if (stop != null) return new EvidenceBatch(stop, clock.instant(), List.copyOf(evidence));
            var program = kis.fetchProgramEvidence(code, asOf, deadline);
            stop = stopStatus(deadline);
            if (stop != null) return new EvidenceBatch(stop, clock.instant(), List.copyOf(evidence));
            evidence.add(new CandidateEvidence(code, minuteFacts(minute, code, asOf), programFacts(program, code)));
        }
        // Program's official response does not supply a business date, so its freshness is explicitly partial.
        boolean available = evidence.stream().anyMatch(item -> "AVAILABLE".equals(item.minute().get("status"))
            || "DATE_UNKNOWN".equals(item.program().get("status")));
        return new EvidenceBatch(available ? "PARTIAL" : "UNAVAILABLE", clock.instant(), List.copyOf(evidence));
    }

    public String fetchFormattedExtendedEvidence(List<String> candidateCodes) {
        EvidenceBatch batch = fetchEvidence(candidateCodes);
        try {
            return "[KIS 확장 증거] " + batch.status() + "\n"
                + "분봉 지표는 최근 완료 분봉 조회 구간만 포함하며 전체 세션 VWAP 아님.\n"
                + "프로그램 응답은 영업일자·금액 단위가 미확인일 수 있으며 현재 세션 사실이나 룰 가점으로 사용하지 않는다.\n"
                + mapper.writeValueAsString(Map.of("status", batch.status(), "fetchedAt", batch.fetchedAt().toString(),
                    "candidates", batch.candidates()));
        } catch (Exception e) {
            return "[KIS 확장 증거] UNAVAILABLE: 표현 변환 실패";
        }
    }

    public boolean isEnabled() { return enabled; }

    private String stopStatus(long deadline) {
        if (Thread.currentThread().isInterrupted()) return "CANCELLED";
        return System.nanoTime() >= deadline ? "BUDGET_EXCEEDED" : null;
    }

    private Map<String, Object> minuteFacts(KisApiService.ExtendedSnapshot snapshot, String code, Instant asOf) {
        String error = snapshotError(snapshot, code);
        if (error != null) return unavailable(error);
        if (!snapshot.rows().isArray()) return unavailable("INVALID_RESPONSE");
        LocalDate today = asOf.atZone(KST).toLocalDate();
        LocalDateTime currentMinute = asOf.atZone(KST).toLocalDateTime().withSecond(0).withNano(0);
        Map<LocalDateTime, MinuteBar> unique = new LinkedHashMap<>();
        int inspected = 0;
        for (var row : snapshot.rows()) {
            // Bound processing even for an injected/malformed oversized response.
            if (inspected++ == 60) break;
            try {
                LocalDate day = LocalDate.parse(row.path("stck_bsop_date").asText(""), DAY);
                LocalTime time = LocalTime.parse(row.path("stck_cntg_hour").asText(""), TIME);
                LocalDateTime stamp = day.atTime(time);
                if (!today.equals(day) || !stamp.isBefore(currentMinute)) continue;
                Long close = integer(row.path("stck_prpr").asText(""), false);
                Long open = integer(row.path("stck_oprc").asText(""), false);
                Long high = integer(row.path("stck_hgpr").asText(""), false);
                Long low = integer(row.path("stck_lwpr").asText(""), false);
                Long volume = integer(row.path("cntg_vol").asText(""), false);
                if (close == null || open == null || high == null || low == null || volume == null
                    || low > high || close < low || close > high || open < low || open > high) continue;
                unique.putIfAbsent(stamp, new MinuteBar(stamp, close, volume));
            } catch (RuntimeException ignored) { /* invalid row cannot contribute to evidence */ }
        }
        List<MinuteBar> bars = unique.values().stream().sorted(Comparator.comparing(MinuteBar::time).reversed())
            .limit(30).sorted(Comparator.comparing(MinuteBar::time)).toList();
        if (bars.isEmpty()) return unavailable(snapshot.rows().isEmpty() ? "EMPTY" : "INVALID_OR_INCOMPLETE");
        MinuteBar latest = bars.getLast();
        if (Duration.between(latest.time().atZone(KST).toInstant(), asOf).toSeconds() > 300) return unavailable("STALE");
        BigInteger volume = BigInteger.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (MinuteBar bar : bars) {
            volume = volume.add(BigInteger.valueOf(bar.volume()));
            weighted = weighted.add(BigDecimal.valueOf(bar.close()).multiply(BigDecimal.valueOf(bar.volume())));
        }
        if (volume.bitLength() > 63) return unavailable("INVALID_VOLUME");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("status", "AVAILABLE");
        facts.put("source", SOURCE);
        facts.put("exchange", "KRX");
        facts.put("observationDate", today.toString());
        facts.put("coverageStart", bars.getFirst().time().format(TIME));
        facts.put("coverageEnd", latest.time().format(TIME));
        facts.put("barCount", bars.size());
        facts.put("volume", volume.longValueExact());
        facts.put("volumeUnit", "주");
        facts.put("recentWeightedClose", weighted.divide(new BigDecimal(volume), 2, RoundingMode.HALF_UP));
        facts.put("priceUnit", "원");
        facts.put("metricLabel", "최근 완료 분봉 종가·거래량 가중평균 (전체 세션 VWAP 아님)");
        facts.put("lastClose", latest.close());
        facts.put("fetchedAt", snapshot.fetchedAt().toString());
        return Map.copyOf(facts);
    }

    private Map<String, Object> programFacts(KisApiService.ExtendedSnapshot snapshot, String code) {
        String error = snapshotError(snapshot, code);
        if (error != null) return unavailable(error);
        if (!snapshot.rows().isArray()) return unavailable("INVALID_RESPONSE");
        Map<String, Object> latest = null;
        LocalTime latestTime = LocalTime.MIN;
        int inspected = 0;
        for (var row : snapshot.rows()) {
            if (inspected++ == 30) break;
            try {
                LocalTime time = LocalTime.parse(row.path("bsop_hour").asText(""), TIME);
                Long price = integer(row.path("stck_prpr").asText(""), false);
                Long cumulativeVolume = integer(row.path("acml_vol").asText(""), true);
                Long quantity = integer(row.path("whol_smtn_ntby_qty").asText(""), true);
                Long amount = integer(row.path("whol_smtn_ntby_tr_pbmn").asText(""), true);
                if (price == null || cumulativeVolume == null || cumulativeVolume < 0 || quantity == null || amount == null
                    || latest != null && time.isBefore(latestTime)) continue;
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("status", "DATE_UNKNOWN");
                facts.put("source", SOURCE);
                facts.put("exchange", "KRX");
                facts.put("providerTime", row.path("bsop_hour").asText());
                facts.put("observationDate", "UNKNOWN");
                facts.put("releaseTime", "UNKNOWN");
                facts.put("currentPrice", price);
                facts.put("netBuyQuantity", quantity);
                facts.put("quantityUnit", "주");
                facts.put("netBuyAmount", amount);
                facts.put("amountUnit", "UNKNOWN (official field check omits unit)");
                facts.put("freshnessNote", "영업일자가 없어 현재 세션과 시세 최신성을 검증할 수 없음");
                facts.put("fetchedAt", snapshot.fetchedAt().toString());
                latest = facts;
                latestTime = time;
            } catch (RuntimeException ignored) { /* absent or malformed facts stay unavailable */ }
        }
        return latest == null ? unavailable(snapshot.rows().isEmpty() ? "EMPTY" : "INVALID_RESPONSE") : Map.copyOf(latest);
    }

    private String snapshotError(KisApiService.ExtendedSnapshot snapshot, String code) {
        if (snapshot == null) return "UNAVAILABLE";
        if (!"AVAILABLE".equals(snapshot.status())) return snapshot.status() == null ? "UNAVAILABLE" : snapshot.status();
        if (!code.equals(snapshot.stockCode())) return "INVALID_IDENTITY";
        if (snapshot.fetchedAt() == null || snapshot.fetchedAt().isAfter(clock.instant())) return "INVALID_TIMESTAMP";
        if (Duration.between(snapshot.fetchedAt(), clock.instant()).toSeconds() > 120) return "STALE";
        return snapshot.rows() == null ? "INVALID_RESPONSE" : null;
    }

    private Map<String, Object> unavailable(String status) {
        return Map.of("status", status, "source", SOURCE);
    }

    private Long integer(String value, boolean signed) {
        if (value == null) return null;
        String numeric = value.trim();
        if (!numeric.matches(signed ? "[+-]?[0-9]+" : "[0-9]+")) return null;
        try {
            long number = Long.parseLong(numeric);
            return signed || number > 0 ? number : null;
        } catch (NumberFormatException e) { return null; }
    }
}
