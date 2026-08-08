package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionStatsAndGuardTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    SubscriptionService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<TelegramNotifier> notifierProvider = mock(ObjectProvider.class);
        service = new SubscriptionService(userRepository, passwordEncoder, notifierProvider);
    }

    private static User member(String email, SubscriptionStatus status, LocalDate endDate) {
        User user = new User(email, email, "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.now().minusDays(1));
        user.setSubscription(status);
        user.setSubscriptionEndDate(endDate);
        return user;
    }

    @Test
    void renewalApplicantIsNotCountedAsBothProAndPending() {
        // 연장 신청한 기존 PRO: 상태는 PENDING 이지만 남은 기간 동안 접근은 유지된다.
        // 이 회원이 proCount 와 pendingCount 양쪽에 잡히면 합계가 회원 수를 넘는다.
        User renewing = member("renewing@example.com", SubscriptionStatus.PENDING, LocalDate.now().plusDays(5));
        User newcomer = member("newcomer@example.com", SubscriptionStatus.FREE, null);

        List<User> all = List.of(renewing, newcomer);
        when(userRepository.findAll()).thenReturn(all);
        when(userRepository.count()).thenReturn((long) all.size());
        when(userRepository.findBySubscription(SubscriptionStatus.PENDING)).thenReturn(List.of(renewing));
        when(userRepository.findBySubscription(SubscriptionStatus.FREE)).thenReturn(List.of(newcomer));
        when(userRepository.countExpiringBetween(any(), any())).thenReturn(0L);
        when(userRepository.countCreatedSince(any())).thenReturn(0L);

        Map<String, Object> stats = service.getSubscriptionStats();

        long pro = ((Number) stats.get("proCount")).longValue();
        long pending = ((Number) stats.get("pendingCount")).longValue();
        long free = ((Number) stats.get("freeCount")).longValue();
        long total = ((Number) stats.get("totalUsers")).longValue();

        assertThat(pro + pending + free)
            .as("every member must be counted in exactly one bucket")
            .isEqualTo(total);
    }

    @Test
    void rejectingAnAdminAccountIsBlockedLikeApproveAndRevoke() {
        UUID adminId = UUID.randomUUID();
        User admin = member("admin@example.com", SubscriptionStatus.PENDING, null);
        admin.setRole(Role.ADMIN);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.rejectSubscription(adminId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("관리자");
    }

    @Test
    void approvingAnAdminAccountExplainsApprovalNotRejection() {
        UUID adminId = UUID.randomUUID();
        User admin = member("admin@example.com", SubscriptionStatus.PENDING, null);
        admin.setRole(Role.ADMIN);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.approveSubscription(adminId))
            .isInstanceOf(IllegalStateException.class)
            .as("the approve path must not tell the admin it cannot 반려")
            .hasMessageNotContaining("반려");
    }
}
