package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.RegisterRequest;
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

class RegistrationResponseTest {
    @Test
    void commitsAndReturnsTokenWithoutWaitingForSlowNotification() throws Exception {
        exercise(false, false);
    }

    @Test
    void rollbackDoesNotNotify() throws Exception {
        exercise(true, false);
    }

    @Test
    void saturatedNotificationQueueDoesNotFailCommittedSignup() throws Exception {
        exercise(false, true);
    }

    private void exercise(boolean rollback, boolean saturated) throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        ObjectProvider<TelegramNotifier> provider = mock(ObjectProvider.class);
        TelegramNotifier notifier = mock(TelegramNotifier.class);
        AuthService service = new AuthService(users, passwords, jwt, provider);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable block = () -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        when(users.count()).thenReturn(10L);
        when(passwords.encode(anyString())).thenReturn("hash");
        when(users.save(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            return user;
        });
        when(jwt.generateToken(any(), anyString(), anyString())).thenReturn("fixture-token");
        when(provider.getIfAvailable()).thenReturn(notifier);
        doAnswer(call -> { block.run(); return null; }).when(notifier).sendToAdmin(anyString());
        try {
            if (saturated) {
                ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "registrationNotifications");
                executor.execute(block);
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 32; i++) executor.execute(() -> {});
            }
            TransactionSynchronizationManager.initSynchronization();
            var response = service.register(new RegisterRequest("fixture", "fixture@example.invalid", "DummyPassword!123"));
            assertThat(response.token()).isEqualTo("fixture-token");
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
            service.stopRegistrationNotifications();
        }
    }
}
