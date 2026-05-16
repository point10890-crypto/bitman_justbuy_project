package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HIGH finding: SubscriptionService#expireSubscriptions is @Scheduled but has
 * NO per-user try/catch. If userRepository.save() throws for any single user
 * (DB lock, transient constraint violation, connection loss), the entire
 * batch aborts and subsequent expired users remain PRO.
 *
 * This test forces a failure on the 2nd user and asserts that:
 *   1) the cron method does NOT propagate the exception (else Spring would
 *      suppress further scheduled fires or log noisily),
 *   2) the 3rd user is still processed.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionExpiryResilienceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    SubscriptionService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<TelegramNotifier> notifierProvider = mock(ObjectProvider.class);
        when(notifierProvider.getIfAvailable()).thenReturn(null);
        service = new SubscriptionService(userRepository, passwordEncoder, notifierProvider);
    }

    private User proUser(String email) {
        User u = new User(email, email.split("@")[0], "hash");
        u.setSubscription(SubscriptionStatus.PRO);
        u.setSubscriptionEndDate(LocalDate.now().minusDays(1));
        return u;
    }

    @Test
    void expireSubscriptions_continuesOnSingleUserFailure() {
        User u1 = proUser("a@test.com");
        User u2 = proUser("b@test.com");
        User u3 = proUser("c@test.com");
        when(userRepository.findExpiredProUsers(any(LocalDate.class)))
            .thenReturn(List.of(u1, u2, u3));

        // u2 저장 시 예외, u1·u3는 정상 저장되어야 함
        when(userRepository.save(u1)).thenReturn(u1);
        when(userRepository.save(u2)).thenThrow(new RuntimeException("simulated DB lock"));
        when(userRepository.save(u3)).thenReturn(u3);

        // 1) cron 메서드가 예외를 밖으로 전파하면 안 됨
        assertThatCode(() -> service.expireSubscriptions()).doesNotThrowAnyException();

        // 2) u3까지 save가 시도됐는지 (중간 실패에도 루프 지속)
        verify(userRepository, times(1)).save(u1);
        verify(userRepository, times(1)).save(u2);
        verify(userRepository, times(1)).save(u3);

        // 3) 성공한 유저는 FREE로 전환됐는지
        assertThat(u1.getSubscription()).isEqualTo(SubscriptionStatus.FREE);
        assertThat(u3.getSubscription()).isEqualTo(SubscriptionStatus.FREE);
    }
}
