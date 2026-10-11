package com.bitman.justbuy.service;

import com.bitman.justbuy.config.KisProperties;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KisInvestorFlowIntegrityTest {
    RestTemplate http;
    KisApiService service;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    LocalDate tradeDay = latestTradingDay(today);
    LocalDate previous = KoreanMarketCalendar.previousTradingDay(tradeDay);
    LocalDate third = KoreanMarketCalendar.previousTradingDay(previous);

    private static LocalDate latestTradingDay(LocalDate day) {
        while (!KoreanMarketCalendar.isTradingDay(day)) day = day.minusDays(1);
        return day;
    }

    @BeforeEach void setup() {
        http = mock(RestTemplate.class);
        service = new KisApiService(new KisProperties("fixture-key", "fixture-secret"), http, mapper);
        when(http.exchange(contains("/oauth2/tokenP"), eq(HttpMethod.POST), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"access_token\":\"fixture-token\",\"expires_in\":86400}"));
    }

    String row(LocalDate date, String foreign, String institution, String individual) {
        return "{\"stck_bsop_date\":\"" + date.toString().replace("-", "") + "\",\"frgn_ntby_qty\":\""
            + foreign + "\",\"orgn_ntby_qty\":\"" + institution + "\",\"prsn_ntby_qty\":\"" + individual + "\"}";
    }

    void response(String prefix, String rows) {
        when(http.exchange(contains("/inquire-investor"), eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{" + prefix + "\"output\":[" + rows + "]}"));
    }

    @Test void providerErrorWithPlausibleRowsCannotVerifyFlow() {
        response("\"rt_cd\":\"1\",", row(tradeDay, "100", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
    }

    @Test void absentSuccessMarkerCannotVerifyFlow() {
        response("", row(tradeDay, "100", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
    }

    @Test void fewerObservedDaysThanRequestedCannotPretendToBeThreeDayFlow() {
        response("\"rt_cd\":\"0\",", row(tradeDay, "100", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 3)).isNull();
    }

    @Test void duplicatedDaysAreNotCountedTwice() {
        response("\"rt_cd\":\"0\",", row(tradeDay, "100", "200", "-300") + "," + row(tradeDay, "100", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 2)).isNull();
    }

    @Test void futureAndInvalidDatesCannotBecomeSupplyEvidence() {
        response("\"rt_cd\":\"0\",", row(today.plusDays(1), "100", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
        response("\"rt_cd\":\"0\",", row(tradeDay, "100", "200", "-300").replace(tradeDay.toString().replace("-", ""), "20260230"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
    }

    @Test void missingQuantityCannotBeSubstitutedWithZero() {
        response("\"rt_cd\":\"0\",", row(tradeDay, "100", "200", "-300").replace("\"orgn_ntby_qty\":\"200\",", ""));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
    }

    @Test void fractionalOrOverflowQuantitiesCannotBecomeEvidence() {
        response("\"rt_cd\":\"0\",", row(tradeDay, "100.5", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
        response("\"rt_cd\":\"0\",", row(tradeDay, "9223372036854775808", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
    }

    @Test void latestActualDaysAreSortedBeforeSummingAndProvenanceIsPreserved() {
        response("\"rt_cd\":\"0\",", row(third, "900", "900", "-1800") + ","
            + row(tradeDay, "10", "20", "-30") + "," + row(previous, "30", "40", "-70"));
        var result = service.fetchInvestorFlowSnapshot("005930", 2);
        assertThat(result.foreignNet()).isEqualTo(40);
        assertThat(result.institutionNet()).isEqualTo(60);
        var tree = mapper.valueToTree(result);
        assertThat(tree.path("fromDate").asText()).isEqualTo(previous.toString());
        assertThat(tree.path("toDate").asText()).isEqualTo(tradeDay.toString());
        assertThat(Instant.parse(tree.path("fetchedAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(mapper.valueToTree(service.fetchInvestorFlowSnapshot("005930", 2)).path("fetchedAt"))
            .isEqualTo(tree.path("fetchedAt"));
        verify(http, times(1)).exchange(contains("/inquire-investor"), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test void sumOverflowFailsClosed() {
        response("\"rt_cd\":\"0\",", row(tradeDay, "9223372036854775807", "1", "-1") + "," + row(previous, "1", "1", "-1"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 2)).isNull();
    }

    @Test void invalidCodesAndUnboundedDaysNeverCallProvider() {
        assertThat(service.fetchInvestorFlowSnapshot("bad/code", 1)).isNull();
        assertThat(service.fetchInvestorFlowSnapshot("005930", 0)).isNull();
        assertThat(service.fetchInvestorFlowSnapshot("005930", 31)).isNull();
        verifyNoInteractions(http);
    }

    @Test void weekendRowsCannotBeClaimedAsObservedTradingDays() {
        LocalDate saturday = today;
        while (saturday.getDayOfWeek() != DayOfWeek.SATURDAY) saturday = saturday.minusDays(1);
        response("\"rt_cd\":\"0\",", row(saturday, "100", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
    }

    @Test void registeredExchangeHolidayCannotBeClaimedAsAnObservedTradingDay() {
        LocalDate holiday = LocalDate.of(2025, 1, 1);
        assertThat(KoreanMarketCalendar.isTradingDay(holiday)).isFalse();
        response("\"rt_cd\":\"0\",", row(holiday, "100", "200", "-300"));
        assertThat(service.fetchInvestorFlowSnapshot("005930", 1)).isNull();
    }
}
