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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 엔진의 실제 /engine/sections 응답(2026-10-14 장중 캡처)으로 검증한다. */
class ConditionEngineSectionServiceTest {
    final ObjectMapper mapper = new ObjectMapper();

    String sample() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/engine-sections-sample.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void realEngineResponseBecomesFiveCardsInTheExistingFormat() throws Exception {
        var service = new ConditionEngineSectionService(mapper, this::sampleUnchecked);
        ConditionSectionResponse leaders = service.section(ConditionSection.LEADERS).orElseThrow();
        assertThat(leaders.sourceStatus()).isEqualTo("ENGINE_SIGNAL");
        assertThat(leaders.slug()).isEqualTo("leaders");
        assertThat(leaders.signals()).isNotEmpty();
        ConditionSignalDto first = leaders.signals().get(0);
        assertThat(first.stockCode()).matches("\\d{6}");
        assertThat(first.capturePrice()).matches("[0-9,]+");                 // 기준가
        assertThat(first.maxReturnPct()).startsWith("상위 ");                   // 상대강도 칸
        assertThat(first.evidence()).isNotEmpty();
        assertThat(first.invalidation()).contains("20거래일");

        ConditionSectionResponse shortTerm = service.section(ConditionSection.SHORT_TERM).orElseThrow();
        assertThat(shortTerm.sourceStatus()).isEqualTo("ENGINE_TRIGGERED");
        assertThat(shortTerm.signals()).allSatisfy(s -> assertThat(s.maxReturnPct()).matches("[+-]\\d+\\.\\d%"));

        ConditionSectionResponse themes = service.section(ConditionSection.THEMES).orElseThrow();
        assertThat(themes.sourceStatus()).isEqualTo("ENGINE_NEAR_CLOSE");
        assertThat(themes.signals()).allSatisfy(s -> assertThat(s.status()).contains("추격 주의"));

        assertThat(service.section(ConditionSection.SWING)).isPresent();
        assertThat(service.section(ConditionSection.CLOSING_BET)).isPresent();
        assertThat(service.section(ConditionSection.ALERTS)).isEmpty();
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
    void onlyBuyTargetsAreRecordedAndShortTermOnlyOnceTriggered() throws Exception {
        TrackRecordService ledger = mock(TrackRecordService.class);
        var service = new ConditionEngineSectionService(mapper, this::sampleUnchecked);
        service.configureTrackRecords(ledger);
        service.section(ConditionSection.LEADERS);
        verify(ledger).recordEngineSignals(eq("ENGINE_LEADERS"), any(LocalDate.class), anyList());
        verify(ledger).recordEngineSignals(eq("ENGINE_SWING"), any(LocalDate.class), anyList());
        verify(ledger).recordEngineSignals(eq("ENGINE_SHORT_TERM"), any(LocalDate.class), anyList());
        verify(ledger, never()).recordEngineSignals(eq("CATALYST_BURST"), any(), anyList());   // 테마 대장주는 기록 안 함

        // the same sections before the open: the short-term watchlist is not a buy
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
        assertThat(main.getSection(ConditionSection.LEADERS).sourceStatus()).isEqualTo("ENGINE_SIGNAL");

        var legacy = new MainConditionService(pipeline);
        legacy.configureEngineSections(new ConditionEngineSectionService(mapper, () -> { throw new IllegalStateException("down"); }));
        assertThat(legacy.getSection(ConditionSection.LEADERS).sourceStatus()).doesNotStartWith("ENGINE_");
    }

    private String sampleUnchecked() {
        try { return sample(); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
