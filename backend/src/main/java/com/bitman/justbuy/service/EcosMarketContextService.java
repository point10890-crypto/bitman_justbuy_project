package com.bitman.justbuy.service;

import com.bitman.justbuy.config.EcosProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.ArrayList;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Service
public class EcosMarketContextService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final EcosProperties properties;
    private final RestTemplate http;
    private final ObjectMapper mapper;
    private final Clock clock;
    private Context cached;
    private Instant cacheExpiresAt = Instant.EPOCH;
    public record Observation(String id, String name, String period, LocalDate observedDate,
        BigDecimal value, String unit, String source, Instant fetchedAt, Instant releasedAt) {}
    public record Context(String status, List<Observation> observations, List<String> unavailable, Instant fetchedAt) {}

    @Autowired
    public EcosMarketContextService(EcosProperties properties, @Qualifier("ecos") RestTemplate http, ObjectMapper mapper) {
        this(properties, http, mapper, Clock.systemUTC());
    }
    EcosMarketContextService(EcosProperties properties, RestTemplate http, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
        this.clock = clock;
    }

    public synchronized Context fetchContext() {
        Instant now = clock.instant();
        if (!properties.enabled()) return new Context("DISABLED", List.of(), List.of(), now);
        if (properties.apiKey().isBlank()) return new Context("MISSING_KEY", List.of(), List.of("ECOS: 인증키 미설정"), now);
        if (!properties.apiKey().matches("[A-Za-z0-9_-]{1,128}"))
            return new Context("INVALID_CONFIG", List.of(), List.of("ECOS: 인증키 형식 확인 필요"), now);
        if (cached != null && !now.isBefore(cached.fetchedAt()) && now.isBefore(cacheExpiresAt)) return cached;
        LocalDate today = LocalDate.now(clock.withZone(KST));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var observations = new ArrayList<Observation>();
        var unavailable = new ArrayList<String>();
        for (var series : properties.series().stream().limit(3).toList()) {
            String safeId = series.id() != null && series.id().matches("[A-Z0-9_]{1,40}") ? series.id() : "INVALID_SERIES";
            if (!validConfiguration(series)) {
                unavailable.add(safeId + ": INVALID_CONFIG");
                continue;
            }
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                unavailable.add(safeId + ": BUDGET_EXHAUSTED");
                continue;
            }
            try {
                boolean daily = "D".equals(series.cycle());
                String start = daily ? today.minusDays(45).format(DateTimeFormatter.BASIC_ISO_DATE)
                    : YearMonth.from(today).minusMonths(12).format(DateTimeFormatter.ofPattern("uuuuMM"));
                String end = daily ? today.format(DateTimeFormatter.BASIC_ISO_DATE)
                    : YearMonth.from(today).format(DateTimeFormatter.ofPattern("uuuuMM"));
                String url = "https://ecos.bok.or.kr/api/StatisticSearch/" + properties.apiKey()
                    + "/json/kr/1/100/" + series.table() + "/" + series.cycle() + "/" + start + "/" + end + "/" + series.item();
                var response = http.getForEntity(url, String.class);
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
                    unavailable.add(safeId + ": BUDGET_EXHAUSTED");
                    continue;
                }
                String body = response.getBody();
                if (!response.getStatusCode().is2xxSuccessful() || body == null || body.length() > 1_048_576) {
                    unavailable.add(safeId + ": PROVIDER_ERROR");
                    continue;
                }
                JsonNode root = mapper.readTree(body);
                JsonNode rows = root.path("StatisticSearch").path("row");
                if (root.has("RESULT") || !rows.isArray() || rows.size() > 100) {
                    unavailable.add(safeId + ": PROVIDER_ERROR_OR_EMPTY");
                    continue;
                }
                Observation latest = null;
                for (JsonNode row : rows) {
                    Observation observation = validateObservation(series, row, today, now);
                    if (observation != null && (latest == null || observation.period().compareTo(latest.period()) > 0))
                        latest = observation;
                }
                if (latest == null) unavailable.add(safeId + ": NO_VALID_RECENT_OBSERVATION");
                else observations.add(latest);
            } catch (Exception ignored) {
                // Transport messages may contain the key-bearing URL. Never expose or log them.
                unavailable.add(safeId + ": PROVIDER_ERROR");
            }
        }
        String status = observations.isEmpty() ? "UNAVAILABLE" : unavailable.isEmpty() ? "AVAILABLE" : "PARTIAL";
        cached = new Context(status, List.copyOf(observations), List.copyOf(unavailable), now);
        Instant midnight = today.plusDays(1).atStartOfDay(KST).toInstant();
        Instant ttl = now.plusSeconds("AVAILABLE".equals(status) ? 7200 : 30);
        cacheExpiresAt = ttl.isBefore(midnight) ? ttl : midnight;
        return cached;
    }

    private static boolean validConfiguration(EcosProperties.Series series) {
        return series.id() != null && series.id().matches("[A-Z0-9_]{1,40}")
            && series.name() != null && !series.name().isBlank() && series.name().length() <= 80
            && !series.name().contains("\n") && !series.name().contains("\r")
            && series.table() != null && series.table().matches("[0-9]{3}[A-Z][0-9]{3}")
            && series.item() != null && series.item().matches("[A-Za-z0-9]{1,30}")
            && ("D".equals(series.cycle()) || "M".equals(series.cycle()))
            && series.unit() != null && !series.unit().isBlank() && series.unit().length() <= 20
            && series.maxAgeDays() >= 1 && series.maxAgeDays() <= 366;
    }

    private static Observation validateObservation(EcosProperties.Series series, JsonNode row, LocalDate today, Instant fetchedAt) {
        if (!series.table().equals(row.path("STAT_CODE").asText()) || !series.item().equals(row.path("ITEM_CODE1").asText())
            || !series.unit().equals(row.path("UNIT_NAME").asText())) return null;
        for (String dimension : List.of("ITEM_CODE2", "ITEM_CODE3", "ITEM_CODE4"))
            if (!row.path(dimension).isMissingNode() && !row.path(dimension).isNull()
                && (!row.path(dimension).isTextual() || !row.path(dimension).asText().isBlank())) return null;
        String period = row.path("TIME").asText();
        String number = row.path("DATA_VALUE").asText();
        if (!number.matches("-?[0-9]{1,9}(?:\\.[0-9]{1,10})?")) return null;
        try {
            BigDecimal value = new BigDecimal(number);
            if ("731Y001".equals(series.table()) && "0000001".equals(series.item()) && value.signum() <= 0) return null;
            LocalDate observed;
            if ("D".equals(series.cycle())) {
                if (!period.matches("[0-9]{8}")) return null;
                observed = LocalDate.parse(period, DateTimeFormatter.BASIC_ISO_DATE);
                if (observed.isAfter(today) || ChronoUnit.DAYS.between(observed, today) > series.maxAgeDays()) return null;
            } else {
                if (!period.matches("[0-9]{6}")) return null;
                YearMonth month = YearMonth.parse(period, DateTimeFormatter.ofPattern("uuuuMM"));
                if (month.isAfter(YearMonth.from(today))) return null;
                observed = month.atDay(1);
                if (ChronoUnit.DAYS.between(observed, today) > series.maxAgeDays()) return null;
            }
            return new Observation(series.id(), series.name(), period, "D".equals(series.cycle()) ? observed : null,
                value, series.unit(), "한국은행 ECOS", fetchedAt, null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public String fetchFormattedContext() {
        Context context = fetchContext();
        if ("DISABLED".equals(context.status())) return "";
        StringBuilder text = new StringBuilder("\n한국은행 ECOS 공표 통계 (선택·가공) — 상태: ")
            .append(context.status()).append("\n일별·월별 공표 통계이며 실시간 시세가 아닙니다. 공표 시각: 미제공.\n");
        for (Observation observation : context.observations()) {
            text.append(observation.name()).append(": ").append(observation.value().toPlainString()).append(" ")
                .append(observation.unit()).append(observation.observedDate() == null ? " | 관측월: " : " | 관측기간: ")
                .append(observation.period())
                .append(" | 관측일: ").append(observation.observedDate() == null ? "미제공" : observation.observedDate())
                .append(" | 조회 시각: ")
                .append(observation.fetchedAt()).append("\n");
        }
        if (!context.unavailable().isEmpty()) text.append("확인 불가: ").append(String.join("; ", context.unavailable())).append("\n");
        text.append("출처: 한국은행 ECOS https://ecos.bok.or.kr/ — 데이터 부족은 매수 근거로 사용하지 않습니다.\n");
        return text.toString();
    }
}
