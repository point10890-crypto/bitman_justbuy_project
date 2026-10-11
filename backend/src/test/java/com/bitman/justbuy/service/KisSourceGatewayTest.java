package com.bitman.justbuy.service;
import com.bitman.justbuy.config.KisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KisSourceGatewayTest {
    private KisApiService client(RestTemplate http) {
        var kis=new KisApiService(new KisProperties("fixture","fixture"),http,new ObjectMapper());kis.configureExtendedClient(http);
        when(http.exchange(contains("tokenP"),eq(HttpMethod.POST),any(),eq(String.class))).thenReturn(ResponseEntity.ok("{\"access_token\":\"fixture\",\"expires_in\":86400}"));
        return kis;
    }
    @Test void providerFailureCannotPublishSeeminglyValidRows() {
        RestTemplate http=mock(RestTemplate.class);var kis=client(http);
        when(http.exchange(contains("psearch-title"),eq(HttpMethod.GET),any(),eq(String.class))).thenReturn(ResponseEntity.ok("{\"rt_cd\":\"1\",\"output2\":[{\"seq\":\"1\"}]}"));
        var result=new KisSourceGateway(kis,true,true,"").fetch("/uapi/domestic-stock/v1/quotations/psearch-title","HHKST03900300",Map.of(),Long.MAX_VALUE);
        assertThat(result.status()).isEqualTo("PROVIDER_ERROR");assertThat(result.body().path("output2").isMissingNode()).isTrue();
    }
    @Test void documentedNoMatchIsOnlyNormalEmptyForConditionResult() {
        RestTemplate http=mock(RestTemplate.class);var kis=client(http);
        when(http.exchange(contains("psearch-result"),eq(HttpMethod.GET),any(),eq(String.class))).thenReturn(ResponseEntity.ok("{\"rt_cd\":\"1\",\"msg_cd\":\"MCA05918\"}"));
        when(http.exchange(contains("psearch-title"),eq(HttpMethod.GET),any(),eq(String.class))).thenReturn(ResponseEntity.ok("{\"rt_cd\":\"1\",\"msg_cd\":\"MCA05918\"}"));
        var gateway=new KisSourceGateway(kis,true,true,"");
        var result=gateway.fetch("/uapi/domestic-stock/v1/quotations/psearch-result","HHKST03900400",Map.of(),Long.MAX_VALUE);
        assertThat(result.status()).isEqualTo("EMPTY");assertThat(result.body().path("output2").isEmpty()).isTrue();
        assertThat(gateway.fetch("/uapi/domestic-stock/v1/quotations/psearch-title","HHKST03900300",Map.of(),Long.MAX_VALUE).status()).isEqualTo("PROVIDER_ERROR");
    }
    @Test void wrongTrAndInsufficientDeadlineNeverAcquireToken() {
        RestTemplate http=mock(RestTemplate.class);var kis=new KisApiService(new KisProperties("fixture","fixture"),http,new ObjectMapper());kis.configureExtendedClient(http);
        assertThat(kis.fetchVerifiedReadOnly("/uapi/domestic-stock/v1/quotations/psearch-title","WRONG",Map.of(),Long.MAX_VALUE).status()).isEqualTo("BLOCKED_ENDPOINT");
        assertThat(kis.fetchVerifiedReadOnly("/uapi/domestic-stock/v1/quotations/psearch-title","HHKST03900300",Map.of(),System.nanoTime()).status()).isEqualTo("BUDGET_EXCEEDED");
        verifyNoInteractions(http);
    }
    @Test void rightsGateDoesNotAcquireTokenOrReadData() {
        KisApiService kis=mock(KisApiService.class);
        var gateway=new KisSourceGateway(kis,true,false,"private-id");
        assertThat(gateway.fetch("/uapi/domestic-stock/v1/quotations/psearch-title","HHKST03900300",Map.of(),Long.MAX_VALUE).status()).isEqualTo("RIGHTS_UNCONFIRMED");
        assertThat(gateway.wsApproval(Long.MAX_VALUE)).isNull();
        assertThat(gateway.readiness()).doesNotContainValue("private-id");
    }
    @Test void successfulBoundedReadIsReturnedWithOriginalSourceBody() {
        RestTemplate http=mock(RestTemplate.class);
        var kis=new KisApiService(new KisProperties("fixture","fixture"),http,new ObjectMapper());
        kis.configureExtendedClient(http);
        when(http.exchange(contains("tokenP"),eq(HttpMethod.POST),any(),eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"access_token\":\"fixture\",\"expires_in\":86400}"));
        when(http.exchange(contains("psearch-title"),eq(HttpMethod.GET),any(),eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"rt_cd\":\"0\",\"output2\":[{\"seq\":\"1\"}]}"));
        var snapshot=new KisSourceGateway(kis,true,true,"fixture").fetch("/uapi/domestic-stock/v1/quotations/psearch-title","HHKST03900300",Map.of("user_id","fixture"),Long.MAX_VALUE);
        assertThat(snapshot.status()).isEqualTo("AVAILABLE");
        assertThat(snapshot.body().path("output2").get(0).path("seq").asText()).isEqualTo("1");
    }
    @Test void orderPathsAndUnknownTrAreRejectedBeforeTokenRequest() {
        RestTemplate http=mock(RestTemplate.class);
        var kis=new KisApiService(new KisProperties("fixture","fixture"),http,new ObjectMapper());
        kis.configureExtendedClient(http);
        var gateway=new KisSourceGateway(kis,true,true,"");
        assertThat(gateway.fetch("/uapi/domestic-stock/v1/trading/order-cash","TTTC0802U",Map.of(),Long.MAX_VALUE).status()).isEqualTo("BLOCKED_ENDPOINT");
        verifyNoInteractions(http);
    }
}
