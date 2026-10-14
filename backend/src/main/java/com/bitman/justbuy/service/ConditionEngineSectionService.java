package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSection;
import com.bitman.justbuy.dto.condition.ConditionSectionResponse;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 홈 5개 섹션의 검출 원천 — 조건검색 엔진의 정석 검출기(`/engine/sections`).
 *
 * <p>설계와 검증: docs/SECTION_DETECTION_DESIGN_2026_10_14.md. 엔진이 응답하지 않으면 빈 결과를 돌려주고
 * {@link MainConditionService} 는 기존 원천을 그대로 쓴다 — 엔진 장애가 홈을 비우지 않는다.
 *
 * <p>카드는 기존 4칸(종목명 + 3칸)을 그대로 쓴다. 칸의 의미는 섹션마다 다르므로 값은 표시용 문자열로 채운다.
 * 카드에 보인 매수 대상은 새 모드 이름으로 원장에 기록해 기존 AI 섹션 성과와 섞이지 않게 한다.
 */
@Service
public class ConditionEngineSectionService {

    private static final Logger log = LoggerFactory.getLogger(ConditionEngineSectionService.class);
    static final long CACHE_MILLIS = 60_000;

    /** 원장 모드. 테마주(대장주)는 매수 대상이 아니라 기록하지 않는다. */
    static final Map<ConditionSection, String> LEDGER_MODE = Map.of(
        ConditionSection.SHORT_TERM, "ENGINE_SHORT_TERM",
        ConditionSection.SWING, "ENGINE_SWING",
        ConditionSection.LEADERS, "ENGINE_LEADERS",
        ConditionSection.CLOSING_BET, "ENGINE_CLOSING_BET");

    private static final Map<ConditionSection, String> ENGINE_KEY = Map.of(
        ConditionSection.SHORT_TERM, "short_term",
        ConditionSection.SWING, "swing",
        ConditionSection.LEADERS, "leaders",
        ConditionSection.THEMES, "themes",
        ConditionSection.CLOSING_BET, "closing_bet");

    private final ObjectMapper mapper;
    private final Supplier<String> fetcher;
    private TrackRecordService trackRecords;

    private volatile Map<ConditionSection, ConditionSectionResponse> cached;
    private volatile long cachedAt;

    @Autowired
    public ConditionEngineSectionService(ObjectMapper mapper,
                                         @Value("${bitman.engine.url:http://127.0.0.1:8090}") String engineUrl) {
        this(mapper, httpFetcher(engineUrl));
    }

    ConditionEngineSectionService(ObjectMapper mapper, Supplier<String> fetcher) {
        this.mapper = mapper;
        this.fetcher = fetcher;
    }

    @Autowired(required = false)
    public void configureTrackRecords(TrackRecordService trackRecords) {
        this.trackRecords = trackRecords;
    }

    public Optional<ConditionSectionResponse> section(ConditionSection section) {
        if (!ENGINE_KEY.containsKey(section)) return Optional.empty();
        Map<ConditionSection, ConditionSectionResponse> all = sections();
        return all == null ? Optional.empty() : Optional.ofNullable(all.get(section));
    }

    /** 장중에는 방문이 없어도 원장이 채워지도록 10분마다 갱신한다. */
    @Scheduled(cron = "0 */10 9-15 * * MON-FRI", zone = "Asia/Seoul")
    public void refreshDuringSession() {
        cachedAt = 0;
        sections();
    }

    synchronized Map<ConditionSection, ConditionSectionResponse> sections() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MILLIS) return cached;
        String body;
        try {
            body = fetcher.get();
        } catch (RuntimeException e) {
            log.warn("[EngineSections] engine unavailable: {}", e.getMessage());
            return cachedAt > 0 && now - cachedAt < 10 * CACHE_MILLIS ? cached : null;
        }
        try {
            Map<ConditionSection, ConditionSectionResponse> parsed = parse(mapper.readTree(body));
            cached = parsed;
            cachedAt = now;
            record(parsed);
            return parsed;
        } catch (Exception e) {
            log.warn("[EngineSections] unreadable engine response: {}", e.getMessage());
            return null;
        }
    }

    Map<ConditionSection, ConditionSectionResponse> parse(JsonNode root) {
        Map<ConditionSection, ConditionSectionResponse> out = new EnumMap<>(ConditionSection.class);
        JsonNode sections = root.path("sections");
        for (Map.Entry<ConditionSection, String> e : ENGINE_KEY.entrySet()) {
            JsonNode node = sections.path(e.getValue());
            if (node.isMissingNode()) continue;
            out.put(e.getKey(), toResponse(e.getKey(), node));
        }
        return out;
    }

    private ConditionSectionResponse toResponse(ConditionSection section, JsonNode node) {
        String status = node.path("status").asText("SIGNAL");
        // 카드의 기준 시각 = 마지막 검색 시각(실시간 검색기) 또는 신호 종가(종가 기준 목록)
        String asOf = node.hasNonNull("observed_at") ? node.path("observed_at").asText()
            : node.path("as_of").asText("") + "T15:30:00+09:00";
        List<ConditionSignalDto> signals = new ArrayList<>();
        int rank = 0;
        for (JsonNode row : node.path("rows")) {
            rank++;
            signals.add(toSignal(section, node, row, rank, asOf));
        }
        return new ConditionSectionResponse(
            section.responseKey(), section.slug(), section.title(), section.legacyMode(),
            "/api/conditions/" + section.slug(), asOf, "ENGINE_" + status, signals, null);
    }

    private ConditionSignalDto toSignal(ConditionSection section, JsonNode design, JsonNode row, int rank, String asOf) {
        String ref = price(row.path("ref_price"));
        String live = price(row.path("live_price"));
        List<String> reasons = new ArrayList<>();
        row.path("reasons").forEach(r -> reasons.add(r.asText()));
        List<String> flags = new ArrayList<>();
        row.path("risk").path("flags").forEach(f -> flags.add(f.path("label").asText()));
        String grade = row.path("risk").path("grade").asText("");
        String invalidation = design.path("exit").asText("") + (design.path("caution").asText("").isBlank()
            ? "" : " · " + design.path("caution").asText(""));

        // 조건검색기 카드: 종목명(포착 시각) | 포착가 | 현재가 | 포착 후 등락률
        String a = ref, b = live, c = pct(row.path("since_capture"));
        String status = switch (section) {
            case SHORT_TERM -> "시가 갭 " + pct(row.path("open_gap")) + " · 과매도 반등 자리";
            case SWING -> "주도주 눌림 " + pct(row.path("pullback"));
            case LEADERS -> row.hasNonNull("rs_top_pct") ? "상대강도 상위 " + row.path("rs_top_pct").asInt() + "%" : "주도주";
            case THEMES -> row.path("theme").asText("") + " 테마 대장주 · 추격 주의";
            case CLOSING_BET -> row.path("theme").asText("") + " 후발주 · 종가 매수";
            default -> "";
        };
        // 실시간 검색 결과면 행마다 편입 시각, 아니면(종가 기준 목록) 섹션 기준 시각
        String capturedAt = row.hasNonNull("captured_at") ? row.path("captured_at").asText() : asOf;
        return new ConditionSignalDto(
            section.responseKey(), LEDGER_MODE.getOrDefault(section, section.legacyMode()), rank,
            row.path("name").asText(row.path("code").asText()), row.path("code").asText(),
            a, b, b, c, c, grade.isBlank() ? status : status + " · 위험 " + grade,
            0, 0, 0, String.join(" · ", reasons), reasons, flags, invalidation, capturedAt);
    }

    /** 검색기에 포착된 매수 대상만 원장에 기록한다. 종가 기준 목록의 단타 관찰 종목은 아직 매수 대상이 아니다. */
    private void record(Map<ConditionSection, ConditionSectionResponse> parsed) {
        if (trackRecords == null) return;
        for (Map.Entry<ConditionSection, String> e : LEDGER_MODE.entrySet()) {
            ConditionSectionResponse response = parsed.get(e.getKey());
            if (response == null || response.signals().isEmpty()) continue;
            if (e.getKey() == ConditionSection.SHORT_TERM && "ENGINE_WATCHLIST".equals(response.sourceStatus())) continue;
            try {
                LocalDate signalDate = LocalDate.parse(response.asOf().substring(0, 10));
                trackRecords.recordEngineSignals(e.getValue(), signalDate, response.signals());
            } catch (RuntimeException ex) {
                log.warn("[EngineSections] ledger record failed for {}: {}", e.getValue(), ex.getMessage());
            }
        }
    }

    static String price(JsonNode node) {
        if (node == null || !node.isNumber()) return "-";
        return NumberFormat.getInstance(Locale.KOREA).format(Math.round(node.asDouble()));
    }

    static String pct(JsonNode node) {
        if (node == null || !node.isNumber()) return "-";
        double v = node.asDouble() * 100;
        return String.format(Locale.ROOT, "%+.1f%%", v);
    }

    static Supplier<String> httpFetcher(String engineUrl) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        String base = engineUrl.endsWith("/") ? engineUrl.substring(0, engineUrl.length() - 1) : engineUrl;
        return () -> {
            try {
                HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/engine/sections"))
                        .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) throw new IllegalStateException("engine HTTP " + res.statusCode());
                return res.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        };
    }
}
