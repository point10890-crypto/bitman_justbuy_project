package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSection;
import com.bitman.justbuy.dto.condition.ConditionSectionResponse;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 엔진의 실제 /engine/sections 응답(2026-10-14 14:57 실시간 조건검색기 캡처)으로 검증한다. */
class ConditionEngineSectionServiceTest {
    final ObjectMapper mapper = new ObjectMapper();

    String sample() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/engine-sections-sample.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void realScreenerResponseBecomesFiveScreenerCards() {
        var service = new ConditionEngineSectionService(mapper, this::sampleUnchecked);
        ConditionSectionResponse leaders = service.section(ConditionSection.LEADERS).orElseThrow();
        assertThat(leaders.sourceStatus()).isEqualTo("ENGINE_LIVE_SCAN");
        assertThat(leaders.slug()).isEqualTo("leaders");
        assertThat(leaders.signals()).isNotEmpty();
        ConditionSignalDto first = leaders.signals().get(0);
        assertThat(first.stockCode()).matches("\\d{6}");
        assertThat(first.capturePrice()).matches("[0-9,]+");                 // 포착가
        assertThat(first.currentPrice()).matches("[0-9,]+");                 // 현재가
        assertThat(first.maxReturnPct()).matches("[+-]\\d+\\.\\d%");          // 포착 후 등락률
        assertThat(first.capturedAt()).startsWith("2026-10-14T14:57");       // 편입 시각
        assertThat(first.evidence()).isNotEmpty();
        assertThat(first.invalidation()).contains("20거래일");

        assertThat(service.section(ConditionSection.SHORT_TERM).orElseThrow().signals())
            .isNotEmpty().allSatisfy(s -> assertThat(s.status()).contains("시가 갭"));
        assertThat(service.section(ConditionSection.THEMES).orElseThrow().signals())
            .allSatisfy(s -> assertThat(s.status()).contains("추격 주의"));
        assertThat(service.section(ConditionSection.SWING)).isPresent();
        assertThat(service.section(ConditionSection.CLOSING_BET)).isPresent();
        assertThat(service.section(ConditionSection.ALERTS)).isEmpty();
    }

    @Test
    void disabledByDefaultSoTheLegacySourcesServeTheHomeAndNothingIsRecorded() {
        AtomicInteger calls = new AtomicInteger();
        TrackRecordService ledger = mock(TrackRecordService.class);
        var off = new ConditionEngineSectionService(mapper, () -> { calls.incrementAndGet(); return sampleUnchecked(); }, false);
        off.configureTrackRecords(ledger);
        assertThat(off.section(ConditionSection.LEADERS)).isEmpty();
        off.refreshDuringSession();
        assertThat(calls.get()).isZero();
        verifyNoInteractions(ledger);
    }

    @Test
    void engineOutageYieldsNothingSoTheLegacySourceIsUsed() {
        var service = new ConditionEngineSectionService(mapper, () -> { throw new IllegalStateException("refused"); });
        assertThat(service.section(ConditionSection.LEADERS)).isEmpty();
    }

    @Test
    void responsesAreCachedForAMinute() {
        AtomicInteger calls = new AtomicInteger();
        var service = new ConditionEngineSectionService(mapper, () -> { calls.incrementAndGet(); return sampleUnchecked(); });
        service.section(ConditionSection.LEADERS);
        service.section(ConditionSection.SWING);
        service.section(ConditionSection.THEMES);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void capturedBuyTargetsAreRecordedButNotThemeLeadersOrAWatchlist() throws Exception {
        TrackRecordService ledger = mock(TrackRecordService.class);
        var service = new ConditionEngineSectionService(mapper, this::sampleUnchecked);
        service.configureTrackRecords(ledger);
        service.section(ConditionSection.LEADERS);
        verify(ledger).recordEngineSignals(eq("ENGINE_LEADERS"), eq(LocalDate.of(2026, 10, 14)), anyList());
        verify(ledger).recordEngineSignals(eq("ENGINE_SWING"), any(LocalDate.class), anyList());
        verify(ledger).recordEngineSignals(eq("ENGINE_SHORT_TERM"), any(LocalDate.class), anyList());
        verify(ledger, never()).recordEngineSignals(eq("CATALYST_BURST"), any(), anyList());   // 테마 대장주는 기록 안 함

        // the end-of-day fallback list: short-term names are only a watchlist, not a buy
        ObjectNode root = (ObjectNode) mapper.readTree(sample());
        ((ObjectNode) root.path("sections").path("short_term")).put("status", "WATCHLIST");
        TrackRecordService ledger2 = mock(TrackRecordService.class);
        var watch = new ConditionEngineSectionService(mapper, root::toString);
        watch.configureTrackRecords(ledger2);
        watch.section(ConditionSection.SHORT_TERM);
        verify(ledger2, never()).recordEngineSignals(eq("ENGINE_SHORT_TERM"), any(), anyList());
    }

    @Test
    void mainConditionServicePrefersTheEngineAndFallsBackWithoutIt() {
        ConditionSearchPipeline pipeline = mock(ConditionSearchPipeline.class);
        var main = new MainConditionService(pipeline);
        main.configureEngineSections(new ConditionEngineSectionService(mapper, this::sampleUnchecked));
        assertThat(main.getSection(ConditionSection.LEADERS).sourceStatus()).isEqualTo("ENGINE_LIVE_SCAN");

        var legacy = new MainConditionService(pipeline);
        legacy.configureEngineSections(new ConditionEngineSectionService(mapper, () -> { throw new IllegalStateException("down"); }));
        assertThat(legacy.getSection(ConditionSection.LEADERS).sourceStatus()).doesNotStartWith("ENGINE_");
    }

    private String sampleUnchecked() {
        try { return sample(); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
