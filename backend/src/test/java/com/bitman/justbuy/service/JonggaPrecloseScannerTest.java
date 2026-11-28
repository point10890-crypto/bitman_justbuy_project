package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JonggaPrecloseScannerTest {
    private static final Instant SCAN_AT = Instant.parse("2026-11-30T06:16:25Z");
    @TempDir Path data;
    private final KisApiService kis = mock(KisApiService.class);
    private final JonggaPrecloseKisSource source = mock(JonggaPrecloseKisSource.class);

    @Test
    void measuresCandidatesAndPreservesTheSelectionAcrossARestart() {
        when(kis.fetchRealtimeTurnoverRankingSnapshot(30)).thenReturn(new KisApiService.SourceBatch<>(
            List.of(row("상승 폭은 크지만 과열", "000660", "19"), row("측정된 후보", "005930", "6"),
                row("분봉 부족", "005380", "6")), SCAN_AT.minusSeconds(2), true));
        when(source.load(eq("005930"), eq(SCAN_AT), anyLong())).thenReturn(Optional.of(observation()));
        var scanner = scanner(SCAN_AT);

        scanner.scanNow();

        var live = scanner.section().orElseThrow();
        assertThat(live.sourceStatus()).isEqualTo("CLOSING_PRECLOSE_PARTIAL");
        assertThat(live.signals()).extracting(ConditionSignalDto::stockCode).containsExactly("005930");
        var pick = live.signals().getFirst();
        assertThat(pick.currentPrice()).isEqualTo("10,600");
        assertThat(pick.highPrice()).isEqualTo("-");
        assertThat(pick.conditionChecks()).anyMatch(check -> check.id().equals("technical.0") && check.status().equals("PASS"));

        var closingReview = scanner(Instant.parse("2026-11-30T06:31:00Z")).section().orElseThrow();
        assertThat(closingReview.signals()).isEqualTo(live.signals());
        assertThat(closingReview.asOf()).isEqualTo(live.asOf());
        assertThat(scanner(Instant.parse("2026-12-01T06:04:00Z")).section()).isEmpty();
    }

    @Test
    void separatesVerifiedZeroFromUnavailableAndDoesNotScanInTheAuction() {
        when(kis.fetchRealtimeTurnoverRankingSnapshot(30)).thenReturn(
            new KisApiService.SourceBatch<>(List.of(row("과열", "005930", "19")), SCAN_AT, true));
        var scanner = scanner(SCAN_AT);
        scanner.scanNow();
        assertThat(scanner.section().orElseThrow().sourceStatus()).isEqualTo("CLOSING_PRECLOSE_EMPTY");
        assertThat(scanner(Instant.parse("2026-11-30T06:21:00Z")).section().orElseThrow().sourceStatus())
            .isEqualTo("CLOSING_PRECLOSE_LAST_EMPTY");
        verifyNoInteractions(source);

        when(kis.fetchRealtimeTurnoverRankingSnapshot(30)).thenReturn(KisApiService.SourceBatch.unavailable());
        scanner.scanNow();
        assertThat(scanner.section().orElseThrow().sourceStatus()).isEqualTo("CLOSING_PRECLOSE_UNAVAILABLE");
        scanner(Instant.parse("2026-11-30T06:21:00Z")).scanNow();
        verifyNoInteractions(source);
    }

    @Test
    void putsStrongerClosingPositionFirstWhenTheConfirmedSetupsTie() {
        when(kis.fetchRealtimeTurnoverRankingSnapshot(30)).thenReturn(new KisApiService.SourceBatch<>(
            List.of(row("거래대금 우위", "005930", "6"), row("고가에 더 가까움", "000660", "6")), SCAN_AT, true));
        when(source.load(eq("005930"), eq(SCAN_AT), anyLong())).thenReturn(Optional.of(observation(10850, 25_000_000_000L)));
        when(source.load(eq("000660"), eq(SCAN_AT), anyLong())).thenReturn(Optional.of(observation()));
        var scanner = scanner(SCAN_AT);

        scanner.scanNow();

        assertThat(scanner.section().orElseThrow().signals()).extracting(ConditionSignalDto::stockCode)
            .containsExactly("000660", "005930");
    }

    @Test
    void leavesKnownNonStandardSessionsToTheExistingFeed() {
        var scanner = scanner(Instant.parse("2026-11-19T06:16:25Z"));
        scanner.scanNow();
        assertThat(scanner.section()).isEmpty();
        verifyNoInteractions(kis, source);
    }

    private JonggaPrecloseScanner scanner(Instant at) {
        return new JonggaPrecloseScanner(kis, source, new ObjectMapper().findAndRegisterModules(), data,
            Clock.fixed(at, ZoneOffset.UTC), "2026-11-19");
    }

    private static KisApiService.RankedStock row(String name, String code, String gain) {
        return new KisApiService.RankedStock(name, code, "10600", gain, "2000000", 0);
    }

    private static JonggaPrecloseKisSource.Observation observation() {
        return observation(10650, 15_000_000_000L);
    }

    private static JonggaPrecloseKisSource.Observation observation(long high, long turnover) {
        var first = Instant.parse("2026-11-30T05:56:00Z");
        var minutes = IntStream.range(0, 20).mapToObj(i -> new JonggaPrecloseStrategy.Minute(
            first.plusSeconds(i * 60), i == 19 ? 10600L : 10500L + 5 * i, i >= 15 ? 150L : 100L)).toList();
        var facts = new JonggaPrecloseStrategy.Facts(10200, high, turnover, 10540L, minutes);
        return new JonggaPrecloseKisSource.Observation(facts, SCAN_AT, SCAN_AT);
    }
}
