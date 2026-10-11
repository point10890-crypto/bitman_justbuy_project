package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reflection permits the initial RED gate before the new source contract exists. */
class KeywordSearchDataServiceTest {
    RestTemplate http;
    KisApiService kis;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:10:00Z"), ZoneOffset.UTC);

    @BeforeEach void setup() {
        http = mock(RestTemplate.class);
        kis = mock(KisApiService.class);
    }

    Object service() throws Exception {
        Class<?> type = Class.forName("com.bitman.justbuy.service.KeywordSearchDataService");
        Constructor<?> constructor = type.getDeclaredConstructor(RestTemplate.class, ObjectMapper.class, KisApiService.class, Clock.class);
        constructor.setAccessible(true);
        return constructor.newInstance(http, mapper, kis, clock);
    }

    JsonNode call(Object service, String method, Class<?>[] types, Object... args) throws Exception {
        return mapper.valueToTree(service.getClass().getMethod(method, types).invoke(service, args));
    }

    String row(String code, String value, String raw, String date) {
        return "{\"itemCode\":\"" + code + "\",\"stockName\":\"실제조회종목\",\"closePrice\":\"12,000\","
            + "\"fluctuationsRatio\":\"2.50\",\"accumulatedTradingVolume\":\"1,000\","
            + "\"accumulatedTradingValue\":\"" + value + "\",\"accumulatedTradingValueRaw\":\"" + raw + "\","
            + "\"localTradedAt\":\"" + date + "\"}";
    }

    void pages(String row) {
        when(http.exchange(contains("/api/stocks/marketValue/"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"stocks\":[" + row + "]}"));
    }

    @Test void snapshotUsesActualRawKrwAndKeepsObservedAndFetchedTimesSeparate() throws Exception {
        pages(row("005930", "12", "12000000", "2026-10-08T15:30:00+09:00"));
        JsonNode result = call(service(), "snapshot", new Class<?>[]{});
        assertThat(result.path("quotes").size()).isEqualTo(1);
        JsonNode quote = result.path("quotes").get(0);
        assertThat(quote.path("price").decimalValue()).isEqualByComparingTo("12000");
        assertThat(quote.path("tradingValueKrw").decimalValue()).isEqualByComparingTo("12000000");
        assertThat(quote.path("observedAt").asText()).isEqualTo("2026-10-08T06:30:00Z");
        assertThat(quote.path("fetchedAt").asText()).isEqualTo("2026-10-12T00:10:00Z");
        assertThat(result.path("scope").asText()).contains("400");
        verify(http, times(4)).exchange(contains("/api/stocks/marketValue/"), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test void missingUnitsCannotPretendDisplayTradingValueIsKrw() throws Exception {
        pages(row("005930", "12", "", "2026-10-08T15:30:00+09:00"));
        JsonNode result = call(service(), "snapshot", new Class<?>[]{});
        assertThat(result.path("quotes").get(0).path("tradingValueKrw").isNull()).isTrue();
        assertThat(result.path("warnings").toString()).contains("거래대금");
    }

    @Test void percentagePricesAndInvalidCodesAreExcluded() throws Exception {
        pages(row("ABCDEF", "12", "12000000", "2026-10-08T15:30:00+09:00") + ","
            + row("005930", "12", "12000000", "2026-10-08T15:30:00+09:00").replace("12,000", "+5.21%"));
        assertThat(call(service(), "snapshot", new Class<?>[]{}).path("quotes")).isEmpty();
    }

    @Test void unavailableSourceHasNoDefaultStocksOrPrices() throws Exception {
        when(http.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenThrow(new IllegalStateException("fixture secret path must not appear"));
        JsonNode result = call(service(), "snapshot", new Class<?>[]{});
        assertThat(result.path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(result.path("quotes")).isEmpty();
        assertThat(result.toString()).doesNotContain("fixture secret", "삼성전자");
    }

    @Test void missingObservedTimeIsExplicitAndNeverInvented() throws Exception {
        pages(row("005930", "12", "12000000", ""));
        JsonNode result = call(service(), "snapshot", new Class<?>[]{});
        assertThat(result.path("quotes").get(0).path("observedAt").isNull()).isTrue();
        assertThat(result.path("warnings").toString()).contains("시각");
    }

    @Test void futureObservedTimeCannotBePublishedAsKnownObservation() throws Exception {
        pages(row("005930", "12", "12000000", "2026-10-13T15:30:00+09:00"));
        JsonNode result = call(service(), "snapshot", new Class<?>[]{});
        assertThat(result.path("quotes").get(0).path("observedAt").isNull()).isTrue();
        assertThat(result.path("warnings").toString()).contains("시각");
    }

    @Test void pageFailuresPreserveActualQuotesWithPartialStatus() throws Exception {
        when(http.exchange(contains("/api/stocks/marketValue/"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"stocks\":[" + row("005930", "12", "12000000", "2026-10-08T15:30:00+09:00") + "]}"))
            .thenThrow(new IllegalStateException("private URL"));
        JsonNode result = call(service(), "snapshot", new Class<?>[]{});
        assertThat(result.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(result.path("quotes").size()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("private URL");
    }

    @Test void successfulEmptyPagesAreCompleteZeroResults() throws Exception {
        pages("");
        JsonNode result = call(service(), "snapshot", new Class<?>[]{});
        assertThat(result.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("quotes")).isEmpty();
    }

    @Test void shortCachePreservesOriginalFetchTimeAndAvoidsRepeatedFourPageCalls() throws Exception {
        pages(row("005930", "12", "12000000", "2026-10-08T15:30:00+09:00"));
        Object service = service();
        JsonNode first = call(service, "snapshot", new Class<?>[]{});
        assertThat(call(service, "snapshot", new Class<?>[]{})).isEqualTo(first);
        verify(http, times(4)).exchange(contains("/api/stocks/marketValue/"), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test void directLookupIsActualIdentityValidatedAndCannotFetchUntrustedPath() throws Exception {
        when(http.exchange(contains("/stock/005930/basic"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(row("005930", "12", "12000000", "2026-10-08T15:30:00+09:00")));
        Object service = service();
        assertThat(call(service, "lookup", new Class<?>[]{String.class}, "005930").path("code").asText()).isEqualTo("005930");
        assertThat(call(service, "lookup", new Class<?>[]{String.class}, "../../../env").isNull()).isTrue();
        verify(http, times(1)).exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test void directLookupRejectsMismatchedProviderCode() throws Exception {
        when(http.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(row("000660", "12", "12000000", "2026-10-08T15:30:00+09:00")));
        assertThat(call(service(), "lookup", new Class<?>[]{String.class}, "005930").isNull()).isTrue();
    }

    @Test void interruptedRequestsStopBeforePublicHttp() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThat(call(service(), "snapshot", new Class<?>[]{}).path("status").asText()).isEqualTo("UNAVAILABLE");
            verifyNoInteractions(http);
        } finally { Thread.interrupted(); }
    }

    @Test void unavailableKisDoesNotProduceZeroOrInventedFlows() throws Exception {
        assertThat(call(service(), "flows", new Class<?>[]{List.class, int.class}, List.of("005930"), 3)).isEmpty();
        verify(kis).isAvailable();
        verifyNoInteractions(http);
    }

    @Test void invalidFlowInputsNeverCallSources() throws Exception {
        Object service = service();
        assertThat(call(service, "flows", new Class<?>[]{List.class, int.class}, List.of("../../env"), 3)).isEmpty();
        assertThat(call(service, "flows", new Class<?>[]{List.class, int.class}, List.of("005930"), 0)).isEmpty();
        assertThat(call(service, "flows", new Class<?>[]{List.class, int.class}, List.of("005930"), 31)).isEmpty();
        verifyNoInteractions(kis, http);
    }
}
