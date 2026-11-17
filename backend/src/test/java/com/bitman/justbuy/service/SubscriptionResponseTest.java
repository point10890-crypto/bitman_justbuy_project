package com.bitman.justbuy.service;


import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubscriptionResponseTest {
    @Test
    void returnsPendingWithoutWaitingForSlowNotification() throws Exception {
        exercise(false, false);
    }

    @Test
    void rollbackDoesNotNotify() throws Exception {
        exercise(true, false);
    }

    @Test
    void saturatedNotificationQueueDoesNotFailCommittedApplication() throws Exception {
        exercise(false, true);
    }

    private void exercise(boolean rollback, boolean saturated) throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);

        ObjectProvider<TelegramNotifier> provider = mock(ObjectProvider.class);
        TelegramNotifier notifier = mock(TelegramNotifier.class);
        SubscriptionService service = new SubscriptionService(users, passwords, provider);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable block = () -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };




        when(provider.getIfAvailable()).thenReturn(notifier);
        doAnswer(call -> { block.run(); return null; }).when(notifier).sendToAdmin(anyString());
        try {
            if (saturated) {
                ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "applicationNotifications");
                executor.execute(block);
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 32; i++) executor.execute(() -> {});
            }
            TransactionSynchronizationManager.initSynchronization();
            User member = new User("fixture@example.invalid", "fixture", "hash");
            UUID id = UUID.randomUUID();
            ReflectionTestUtils.setField(member, "id", id);
            when(users.findByIdForUpdate(id)).thenReturn(java.util.Optional.of(member));
            var response = service.applyForSubscription(id, "fixture");
            assertThat(response.subscription()).isEqualTo("PENDING");
            verifyNoInteractions(notifier);
            var callbacks = TransactionSynchronizationManager.getSynchronizations();
            if (rollback) {
                callbacks.forEach(c -> c.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
                verifyNoInteractions(notifier);
            } else {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> callbacks.forEach(TransactionSynchronization::afterCommit));
                if (!saturated) assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            release.countDown();
            service.stopApplicationNotifications();
        }
    }
}
