package com.bitman.justbuy.service;

import com.bitman.justbuy.config.KisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HIGH finding: KisApiService#fetchCurrentPrice catches all exceptions and
 * returns empty Map on KIS rate-limit (EGW00201 → HTTP 500 with JSON
 * `{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}`).
 *
 * Production logs on 2026-04-14 16:00 show dozens of these failures in a
 * single scheduling burst — all silently dropped, causing missing analysis
 * results.
 *
 * This test verifies that on a rate-limit response the service retries at
 * least once before giving up, so transient bursts recover automatically.
 */
@ExtendWith(MockitoExtension.class)
class KisApiRetryTest {

    @Mock RestTemplate restTemplate;
    KisProperties props;
    ObjectMapper mapper = new ObjectMapper();
    KisApiService service;

    @BeforeEach
    void setUp() {
        props = new KisProperties("test-app-key", "test-app-secret");
        service = new KisApiService(props, restTemplate, mapper);

        // 토큰 발급은 즉시 성공으로 스텁 (토큰 엔드포인트)
        String tokenJson = "{\"access_token\":\"t-123\",\"token_type\":\"Bearer\",\"expires_in\":86400}";
        when(restTemplate.exchange(contains("/oauth2/tokenP"), eq(HttpMethod.POST), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(tokenJson));
    }

    @Test
    void fetchCurrentPrice_retriesOnRateLimit() {
        String rateLimitBody =
            "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}";
        String successBody =
            "{\"output\":{\"stck_prpr\":\"70000\",\"prdy_vrss\":\"100\",\"prdy_ctrt\":\"0.14\","
            + "\"acml_vol\":\"1000\",\"acml_tr_pbmn\":\"70000000\",\"stck_oprc\":\"69900\","
            + "\"stck_hgpr\":\"70100\",\"stck_lwpr\":\"69800\",\"stck_mxpr\":\"80000\","
            + "\"stck_llam\":\"50000\",\"per\":\"15\",\"pbr\":\"1.2\",\"hts_avls\":\"100000\"}}";

        // 현재가 엔드포인트: 1차 rate-limit, 2차 성공
        when(restTemplate.exchange(contains("/uapi/domestic-stock/v1/quotations/inquire-price"),
                eq(HttpMethod.GET), any(), eq(String.class)))
            .thenThrow(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                org.springframework.http.HttpHeaders.EMPTY,
                rateLimitBody.getBytes(),
                null))
            .thenReturn(ResponseEntity.ok(successBody));

        Map<String, String> result = service.fetchCurrentPrice("005930");

        // 재시도로 최종 성공해야 함 (현재 코드는 1회 실패 후 즉시 empty 반환 → 실패 예상)
        assertThat(result).isNotEmpty();
        assertThat(result.get("현재가")).isEqualTo("70000");

        // 현재가 엔드포인트가 최소 2번 호출됐는지 (1차 실패 → 2차 재시도)
        verify(restTemplate, atLeast(2))
            .exchange(contains("/uapi/domestic-stock/v1/quotations/inquire-price"),
                eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    void fetchRealtimeVolumeRanking_usesDomesticStockMarketCode() {
        String rankingBody =
            "{\"output\":[{\"hts_kor_isnm\":\"삼성전자\",\"mksc_shrn_iscd\":\"005930\","
            + "\"stck_prpr\":\"70000\",\"prdy_ctrt\":\"1.23\",\"acml_vol\":\"1000000\"}]}";
        when(restTemplate.exchange(contains("/uapi/domestic-stock/v1/quotations/volume-rank"),
                eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(rankingBody));

        var result = service.fetchRealtimeVolumeRankingStocks(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).code()).isEqualTo("005930");
        verify(restTemplate, times(1))
            .exchange(contains("FID_COND_MRKT_DIV_CODE=J"),
                eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    void fetchForeignNetBuyRankingStocks_usesForeignInstitutionTotalEndpoint() {
        String rankingBody =
            "{\"output\":[{\"hts_kor_isnm\":\"삼성전자\",\"mksc_shrn_iscd\":\"005930\","
            + "\"stck_prpr\":\"70000\",\"prdy_ctrt\":\"1.23\",\"acml_vol\":\"1000000\","
            + "\"frgn_ntby_qty\":\"12345\",\"orgn_ntby_qty\":\"6789\"}]}";
        when(restTemplate.exchange(contains("/uapi/domestic-stock/v1/quotations/foreign-institution-total"),
                eq(HttpMethod.GET), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(rankingBody));

        var result = service.fetchForeignNetBuyRankingStocks(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).foreignNetBuy()).isEqualTo(12345L);
        verify(restTemplate, times(1))
            .exchange(contains("FID_COND_SCR_DIV_CODE=16449"),
                eq(HttpMethod.GET), any(), eq(String.class));
    }
}
