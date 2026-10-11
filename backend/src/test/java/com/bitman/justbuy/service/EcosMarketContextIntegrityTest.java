package com.bitman.justbuy.service;

import com.bitman.justbuy.config.EcosProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EcosMarketContextIntegrityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneOffset.UTC);
    private RestTemplate http;
    private EcosMarketContextService service;
    private String fxOverride;

    @BeforeEach void setUp() {
        http = mock(RestTemplate.class);
        service = new EcosMarketContextService(new EcosProperties(true, "fixture-key", null), http, mapper, clock);
        when(http.getForEntity(anyString(), eq(String.class))).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (url.contains("731Y001")) return ResponseEntity.ok(fxOverride == null ? fx() : fxOverride);
            if (url.contains("817Y002")) return ResponseEntity.ok(body("817Y002", "010200000", "연%", "20261008", "3.983"));
            return ResponseEntity.ok(body("722Y001", "0101000", "연%", "20261008", "3"));
        });
    }

    @Test void disabledAndMissingKeyMakeNoHttpCalls() {
        assertThat(new EcosMarketContextService(new EcosProperties(false, "", null), http, mapper, clock)
            .fetchContext().status()).isEqualTo("DISABLED");
        assertThat(new EcosMarketContextService(new EcosProperties(true, "", null), http, mapper, clock)
            .fetchContext().status()).isEqualTo("MISSING_KEY");
        verifyNoInteractions(http);
    }

    @Test void latestRealPeriodKeepsSourceUnitAndUnknownReleaseTime() {
        fxOverride = "{\"StatisticSearch\":{\"row\":["
            + row("731Y001", "0000001", "원", "20261008", "1339.2") + ","
            + row("731Y001", "0000001", "원", "20261007", "1343.4") + "]}}";
        var context = service.fetchContext();
        assertThat(context.status()).isEqualTo("AVAILABLE");
        assertThat(context.observations()).hasSize(3);
        var fx = context.observations().getFirst();
        assertThat(fx.observedDate()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(fx.value()).isEqualByComparingTo("1339.2");
        assertThat(fx.source()).isEqualTo("한국은행 ECOS");
        assertThat(fx.fetchedAt()).isEqualTo(clock.instant());
        assertThat(fx.releasedAt()).isNull();
        assertThat(service.fetchFormattedContext()).contains("공표 시각: 미제공", "실시간 시세가 아닙니다", "가공");
    }

    @ParameterizedTest @ValueSource(strings = {"WRONG_ID", "WRONG_UNIT", "EXTRA_DIMENSION", "OBJECT_DIMENSION", "ARRAY_DIMENSION"})
    void wrongSeriesUnitOrDimensionNeverEntersPrompt(String problem) {
        fxOverride = switch (problem) {
            case "WRONG_ID" -> body("731Y001", "9999999", "원", "20261008", "1339.2");
            case "WRONG_UNIT" -> body("731Y001", "0000001", "달러", "20261008", "1339.2");
            case "OBJECT_DIMENSION" -> fx().replace("\"ITEM_CODE2\":null", "\"ITEM_CODE2\":{}");
            case "ARRAY_DIMENSION" -> fx().replace("\"ITEM_CODE2\":null", "\"ITEM_CODE2\":[]");
            default -> fx().replace("\"ITEM_CODE2\":null", "\"ITEM_CODE2\":\"OTHER\"");
        };
        assertThat(service.fetchContext().status()).isEqualTo("PARTIAL");
        assertThat(service.fetchContext().observations()).extracting(EcosMarketContextService.Observation::id)
            .doesNotContain("USD_KRW").hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"NaN", "Infinity", "+5.21%", "-1"})
    void malformedNonfiniteOrNegativeExchangeRateIsUnavailable(String value) {
        fxOverride = body("731Y001", "0000001", "원", "20261008", value);
        assertThat(service.fetchContext().observations()).extracting(EcosMarketContextService.Observation::id)
            .doesNotContain("USD_KRW");
    }

    @ParameterizedTest @ValueSource(strings = {"20261012", "20260230", "20250901"})
    void futureImpossibleAndStaleDatesAreUnavailable(String period) {
        fxOverride = body("731Y001", "0000001", "원", period, "1339.2");
        assertThat(service.fetchContext().observations()).extracting(EcosMarketContextService.Observation::id)
            .doesNotContain("USD_KRW");
        assertThat(service.fetchContext().unavailable()).anyMatch(message -> message.contains("USD_KRW"));
    }

    @Test void providerErrorsStayExplicitAndNeverExposeCredentialUrlOrException() {
        when(http.getForEntity(anyString(), eq(String.class))).thenThrow(
            new ResourceAccessException("https://ecos.bok.or.kr/api/StatisticSearch/fixture-key/private-body"));
        var context = service.fetchContext();
        assertThat(context.status()).isEqualTo("UNAVAILABLE");
        assertThat(context.observations()).isEmpty();
        assertThat(context.toString()).doesNotContain("fixture-key", "private-body", "https://");
        assertThat(service.fetchFormattedContext()).contains("UNAVAILABLE").doesNotContain("fixture-key");
    }

    @Test void boundedSeriesAndCacheAvoidRepeatedProviderCost() {
        var defaults = new EcosProperties(true, "fixture-key", null).series();
        var four = new ArrayList<>(defaults);
        four.add(new EcosProperties.Series("IGNORED", "초과", "731Y001", "0000001", "D", "원", 7));
        service = new EcosMarketContextService(new EcosProperties(true, "fixture-key", four), http, mapper, clock);
        assertThat(service.fetchContext().observations()).hasSize(3);
        assertThat(service.fetchContext().observations()).hasSize(3);
        verify(http, times(3)).getForEntity(startsWith("https://ecos.bok.or.kr/api/StatisticSearch/"), eq(String.class));
        assertThat(new EcosProperties(true, "fixture-key", null).toString()).doesNotContain("fixture-key");
    }

    @Test void invalidConfiguredSeriesCannotMakeAnArbitraryRequest() {
        var bad = new EcosProperties.Series("BAD", "bad", "../../secret", "0000001", "D", "원", 7);
        service = new EcosMarketContextService(new EcosProperties(true, "fixture-key", List.of(bad)), http, mapper, clock);
        assertThat(service.fetchContext().status()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(http);
    }

    @Test void existingMarketPromptReceivesProcessedEcosContext() {
        var marketHttp = mock(RestTemplate.class);
        var market = new MarketDataService(marketHttp, mapper);
        market.configureEcosContext(service);
        assertThat(market.fetchFormattedMarketData()).contains("한국은행 ECOS", "1339.2", "2026-10-08");
    }

    @Test void monthlyPeriodDoesNotInventAnObservationDay() {
        var monthly = new EcosProperties.Series("MONTHLY", "월별 통계", "999Y001", "001", "M", "지수", 92);
        when(http.getForEntity(anyString(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(body("999Y001", "001", "지수", "202609", "100.4")));
        service = new EcosMarketContextService(new EcosProperties(true, "fixture-key", List.of(monthly)), http, mapper, clock);
        var observation = service.fetchContext().observations().getFirst();
        assertThat(observation.period()).isEqualTo("202609");
        assertThat(observation.observedDate()).isNull();
        assertThat(service.fetchFormattedContext()).contains("관측월: 202609", "관측일: 미제공").doesNotContain("2026-09-01");
    }

    private static String fx() { return body("731Y001", "0000001", "원", "20261008", "1339.2"); }
    private static String body(String table, String item, String unit, String period, String value) {
        return "{\"StatisticSearch\":{\"row\":[" + row(table, item, unit, period, value) + "]}}";
    }
    private static String row(String table, String item, String unit, String period, String value) {
        return "{\"STAT_CODE\":\"" + table + "\",\"ITEM_CODE1\":\"" + item + "\",\"UNIT_NAME\":\"" + unit
            + "\",\"ITEM_CODE2\":null,\"ITEM_CODE3\":null,\"ITEM_CODE4\":null,\"TIME\":\"" + period
            + "\",\"DATA_VALUE\":\"" + value + "\"}";
    }
}
