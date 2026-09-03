package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 라이브 분석 쿼터 — 회원 1명이 LLM 비용을 무한히 밀어올리지 못하게 막는 상한.
 */
class LiveAnalysisQuotaServiceTest {

    @Test
    void allowsUpToDailyQuotaThenBlocks() {
        LiveAnalysisQuotaService service = new LiveAnalysisQuotaService();
        UUID user = UUID.randomUUID();
        int quota = service.quota();

        for (int i = 0; i < quota; i++) {
            assertThat(service.tryConsume(user)).as("%d번째 요청", i + 1).isTrue();
        }
        assertThat(service.tryConsume(user)).isFalse();
        assertThat(service.remaining(user)).isZero();
    }

    @Test
    void quotaIsPerUser() {
        LiveAnalysisQuotaService service = new LiveAnalysisQuotaService();
        UUID heavy = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        for (int i = 0; i < service.quota(); i++) service.tryConsume(heavy);

        assertThat(service.tryConsume(heavy)).isFalse();
        // 한 명이 다 써도 다른 회원은 영향받지 않아야 한다
        assertThat(service.tryConsume(other)).isTrue();
        assertThat(service.remaining(other)).isEqualTo(service.quota() - 1);
    }

    @Test
    void remainingDoesNotConsume() {
        LiveAnalysisQuotaService service = new LiveAnalysisQuotaService();
        UUID user = UUID.randomUUID();

        assertThat(service.remaining(user)).isEqualTo(service.quota());
        assertThat(service.remaining(user)).isEqualTo(service.quota());
        assertThat(service.tryConsume(user)).isTrue();
        assertThat(service.remaining(user)).isEqualTo(service.quota() - 1);
    }

    @Test
    void concurrentRequestsNeverExceedQuota() throws InterruptedException {
        LiveAnalysisQuotaService service = new LiveAnalysisQuotaService();
        UUID user = UUID.randomUUID();
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (service.tryConsume(user)) granted.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();

        // 경쟁 상태에서도 한도를 절대 넘지 않아야 한다 (비용 상한의 핵심)
        assertThat(granted.get()).isEqualTo(service.quota());
    }

    @Test
    void nullUserIsRejected() {
        LiveAnalysisQuotaService service = new LiveAnalysisQuotaService();
        assertThat(service.tryConsume(null)).isFalse();
        assertThat(service.remaining(null)).isZero();
    }
}
