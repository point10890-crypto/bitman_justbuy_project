package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SubscriptionWorkflowServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    SubscriptionService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<TelegramNotifier> notifierProvider = mock(ObjectProvider.class);
        lenient().when(notifierProvider.getIfAvailable()).thenReturn(null);
        service = new SubscriptionService(userRepository, passwordEncoder, notifierProvider);
    }

    @Test
    void applyForSubscription_trimsDepositorAndClearsExpiredEndDate() {
        UUID userId = UUID.randomUUID();
        User user = new User("member@example.com", "member", "hash");
        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(LocalDate.now().minusDays(1));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        var dto = service.applyForSubscription(userId, "  Hong Gil Dong  ");

        assertThat(dto.subscription()).isEqualTo("PENDING");
        assertThat(user.getDepositorName()).isEqualTo("Hong Gil Dong");
        assertThat(user.getSubscriptionEndDate()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void getPendingSubscriptionsReturnsNewestMembersFirst() {
        User older = new User("older@example.com", "older", "hash");
        older.setSubscription(SubscriptionStatus.PENDING);
        ReflectionTestUtils.setField(older, "createdAt", LocalDateTime.of(2026, 5, 1, 9, 0));

        User newest = new User("newest@example.com", "newest", "hash");
        newest.setSubscription(SubscriptionStatus.PENDING);
        ReflectionTestUtils.setField(newest, "createdAt", LocalDateTime.of(2026, 5, 26, 11, 30));

        when(userRepository.findBySubscription(SubscriptionStatus.PENDING))
            .thenReturn(List.of(older, newest));

        var pending = service.getPendingSubscriptions();

        assertThat(pending)
            .extracting(dto -> dto.email())
            .containsExactly("newest@example.com", "older@example.com");
    }

    @Test
    void applyForSubscription_allowsActiveProRenewalAndBlankDepositorFails() {
        UUID userId = UUID.randomUUID();
        User user = new User("pro@example.com", "pro", "hash");
        LocalDate currentEndDate = LocalDate.now().plusDays(7);
        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(currentEndDate);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var dto = service.applyForSubscription(userId, "payer");

        assertThat(dto.subscription()).isEqualTo("PENDING");
        assertThat(user.getSubscriptionEndDate()).isEqualTo(currentEndDate);
        assertThat(user.getDepositorName()).isEqualTo("payer");

        user.setSubscription(SubscriptionStatus.FREE);
        assertThatThrownBy(() -> service.applyForSubscription(userId, "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("입금자명");
    }

    @Test
    void adminUpdateUser_pendingClearsEndDateAndFreeClearsDepositor() {
        UUID userId = UUID.randomUUID();
        User user = new User("member@example.com", "member", "hash");
        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(LocalDate.now().plusDays(7));
        user.setDepositorName("payer");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var pending = service.adminUpdateUser(userId, null, null, "PENDING");
        assertThat(pending.subscription()).isEqualTo("PENDING");
        assertThat(user.getSubscriptionEndDate()).isNull();
        assertThat(user.getDepositorName()).isEqualTo("payer");

        var free = service.adminUpdateUser(userId, null, null, "FREE");
        assertThat(free.subscription()).isEqualTo("FREE");
        assertThat(user.getSubscriptionEndDate()).isNull();
        assertThat(user.getDepositorName()).isNull();
    }

    @Test
    void adminAccountsCannotBeDowngradedOrRevoked() {
        UUID userId = UUID.randomUUID();
        User admin = new User("admin@example.com", "admin", "hash");
        admin.setRole(Role.ADMIN);
        admin.setSubscription(SubscriptionStatus.PRO);

        when(userRepository.findById(userId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.revokeSubscription(userId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("관리자");

        assertThatThrownBy(() -> service.adminUpdateUser(userId, null, null, "FREE"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("관리자");
    }

    @Test
    void approvePendingRenewalExtendsFromCurrentEndDate() {
        UUID userId = UUID.randomUUID();
        User user = new User("renewal@example.com", "renewal", "hash");
        LocalDate currentEndDate = LocalDate.now().plusDays(10);
        user.setSubscription(SubscriptionStatus.PENDING);
        user.setSubscriptionEndDate(currentEndDate);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var dto = service.approveSubscription(userId);

        assertThat(dto.subscription()).isEqualTo("PRO");
        assertThat(user.getSubscriptionEndDate()).isEqualTo(currentEndDate.plusMonths(1));
        assertThat(user.getSubscriptionApprovedAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void rejectPendingRenewalRestoresExistingProAccess() {
        UUID userId = UUID.randomUUID();
        User user = new User("renewal-reject@example.com", "renewal", "hash");
        LocalDate currentEndDate = LocalDate.now().plusDays(10);
        user.setSubscription(SubscriptionStatus.PENDING);
        user.setSubscriptionEndDate(currentEndDate);
        user.setDepositorName("payer");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var dto = service.rejectSubscription(userId);

        assertThat(dto.subscription()).isEqualTo("PRO");
        assertThat(user.getSubscriptionEndDate()).isEqualTo(currentEndDate);
        assertThat(user.getDepositorName()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void revokeSubscriptionClearsProAccessWindow() {
        UUID userId = UUID.randomUUID();
        User user = new User("pro-revoke@example.com", "pro", "hash");
        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(LocalDate.now().plusDays(10));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var dto = service.revokeSubscription(userId);

        assertThat(dto.subscription()).isEqualTo("FREE");
        assertThat(user.getSubscriptionEndDate()).isNull();
        assertThat(user.getSubscriptionApprovedAt()).isNull();
        verify(userRepository).save(user);
    }
}
