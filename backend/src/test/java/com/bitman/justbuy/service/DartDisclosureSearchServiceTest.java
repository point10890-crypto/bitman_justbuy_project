package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.bitman.justbuy.dto.condition.DisclosureEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DartDisclosureSearchServiceTest {

    @Test
    void tagReportMatchesSupplyContractKeyword() {
        assertThat(DartDisclosureSearchService.tagReport("단일판매ㆍ공급계약 체결"))
            .contains("공급계약");
    }

    @Test
    void tagReportMatchesRightsOffering() {
        assertThat(DartDisclosureSearchService.tagReport("주요사항보고서(유상증자결정)"))
            .contains("유상증자");
    }

    @Test
    void tagReportMatchesConvertibleBond() {
        assertThat(DartDisclosureSearchService.tagReport("전환사채권발행결정"))
            .contains("CB");
    }

    @Test
    void tagReportMatchesBondWithWarrant() {
        assertThat(DartDisclosureSearchService.tagReport("신주인수권부사채권발행결정"))
            .contains("BW");
    }

    @Test
    void tagReportSupportsMultipleTags() {
        // "투자" 키워드와 "공급계약/단일판매" 키워드가 동시에 매칭될 수 있음
        assertThat(DartDisclosureSearchService.tagReport("타법인 주식 및 출자증권 취득결정(투자)"))
            .contains("투자");
    }

    @Test
    void tagReportReturnsEmptyForGenericName() {
        assertThat(DartDisclosureSearchService.tagReport("사업보고서"))
            .isEmpty();
    }

    @Test
    void tagReportReturnsEmptyForNullOrBlank() {
        assertThat(DartDisclosureSearchService.tagReport(null)).isEmpty();
        assertThat(DartDisclosureSearchService.tagReport("   ")).isEmpty();
    }

    @Test
    void fetchReturnsEmptyWhenApiKeyMissing() {
        DartDisclosureSearchService service = new DartDisclosureSearchService(
            new DartProperties(null), new RestTemplate(), new ObjectMapper());

        assertThat(service.isAvailable()).isFalse();

        List<DisclosureEvent> events = service.fetchRecentDisclosureEvents(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 21));
        assertThat(events).isEmpty();
    }

    @Test
    void fetchReturnsEmptyWhenApiKeyBlank() {
        DartDisclosureSearchService service = new DartDisclosureSearchService(
            new DartProperties("   "), new RestTemplate(), new ObjectMapper());

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.fetchRecentDisclosureEvents(
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 21), "Y")).isEmpty();
    }
}
