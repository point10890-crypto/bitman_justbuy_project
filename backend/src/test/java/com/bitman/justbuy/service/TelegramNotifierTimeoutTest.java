package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HIGH finding: TelegramNotifier instantiates `new RestTemplate()` directly,
 * bypassing the Spring-managed RestTemplate bean (AppConfig#restTemplate)
 * which has 10s connect / 120s read timeouts configured.
 *
 * A hung Telegram API can block scheduler threads indefinitely because the
 * default JDK HttpURLConnection has infinite timeouts.
 *
 * This test enforces that the notifier USES a RestTemplate provided by
 * the Spring context (by constructor injection), so the configured
 * timeouts apply.
 */
@ExtendWith(MockitoExtension.class)
class TelegramNotifierTimeoutTest {

    @Mock
    RestTemplate restTemplate;

    @Test
    void send_usesInjectedRestTemplate() {
        when(restTemplate.postForObject(contains("api.telegram.org"), any(HttpEntity.class), eq(String.class)))
            .thenReturn("{\"ok\":true}");

        TelegramNotifier notifier = new TelegramNotifier(
            "fake-bot-token",
            "123456",
            "",
            restTemplate
        );

        notifier.send("테스트 메시지");

        // 인젝션된 RestTemplate이 실제로 호출됐는지 검증
        // (현재 코드: new RestTemplate() 자체 생성 → 이 mock은 호출되지 않음 → 실패)
        verify(restTemplate, atLeastOnce())
            .postForObject(contains("api.telegram.org"), any(HttpEntity.class), eq(String.class));
    }
}
