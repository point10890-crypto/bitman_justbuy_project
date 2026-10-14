package com.bitman.justbuy.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConditionEngineControllerTest {
    final SubscriptionAccessGuard guard = mock(SubscriptionAccessGuard.class);
    final List<String> calls = new ArrayList<>();
    final UUID member = UUID.randomUUID();

    ConditionEngineController controller(ConditionEngineController.EngineTransport transport) {
        return new ConditionEngineController(guard, transport);
    }

    ConditionEngineController.EngineTransport replying(int status, String body) {
        return (method, path, json) -> {
            calls.add(method + " " + path + " " + json);
            return new ConditionEngineController.EngineReply(status, body);
        };
    }

    @Test void expiredMemberNeverReachesTheEngine() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "구독 만료", SubscriptionAccessGuard.CODE_EXPIRED)).when(guard).requirePro(member);
        var c = controller(replying(200, "{}"));
        assertThatThrownBy(() -> c.translate(member, "{\"text\":\"20일선 위\"}")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> c.health(member)).isInstanceOf(ApiException.class);
        assertThat(calls).isEmpty();
    }

    @Test void forwardsBodyAndEngineStatusToTheSameEnginePath() {
        var c = controller(replying(409, "{\"detail\":\"검증에서 탈락한 전략\"}"));
        var res = c.screen(member, "{\"strategy_id\":\"chase_gainers\"}");
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).contains("검증에서 탈락한 전략");
        assertThat(calls).containsExactly("POST /engine/screen {\"strategy_id\":\"chase_gainers\"}");
        verify(guard).requirePro(member);
    }

    @Test void engineDownOrBrokenIsA503WithoutInternalDetail() {
        var down = controller((m, p, b) -> { throw new ConnectException("refused"); });
        assertThat(down.regime(member).getStatusCode().value()).isEqualTo(503);
        var broken = controller(replying(500, "Traceback: secret path C:\\bitman"));
        var res = broken.backtest(member, "{}");
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).doesNotContain("Traceback");
        var loading = controller(replying(503, "{\"detail\":{\"message\":\"엔진이 데이터를 불러오는 중입니다\"}}"));
        assertThat(loading.strategies(member).getBody()).contains("불러오는 중");
    }

    @Test void oversizedBodyIsRejectedBeforeForwarding() {
        var c = controller(replying(200, "{}"));
        String huge = "{\"codes\":[\"" + "0".repeat(ConditionEngineController.MAX_BODY_CHARS) + "\"]}";
        assertThatThrownBy(() -> c.risk(member, huge)).isInstanceOf(ApiException.class);
        assertThat(calls).isEmpty();
    }

    @Test void interruptedCallRestoresTheFlag() throws Exception {
        var c = controller((m, p, b) -> { throw new InterruptedException(); });
        assertThat(c.health(member).getStatusCode().value()).isEqualTo(503);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test void transportFailsFastWhenNothingListens() {
        var t = ConditionEngineController.httpTransport("http://127.0.0.1:1/");
        assertThatThrownBy(() -> t.exchange("GET", "/engine/health", null)).isInstanceOf(IOException.class);
    }
}
