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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 회원 티어 분류 불변식.
 *
 * <p>"구독한 적 있는데 지금은 아닌 회원"과 "한 번도 구독한 적 없는 회원"은 반드시 갈라져야
 * 한다. 재구독 유도와 신규 구독 유도가 완전히 다른 흐름이고, 통계·관리자 목록·프론트
 * 마스킹 미리보기가 모두 이 구분 위에 서 있다.
 *
 * <p>만료 배치는 이 불변식을 지키는데 해제/강등 경로는 지키지 않아, 관리자가 해제한 회원이
 * 신규 회원으로 둔갑하던 것을 여기서 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class MemberTierClassificationTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    SubscriptionService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<TelegramNotifier> notifierProvider = mock(ObjectProvider.class);
        service = new SubscriptionService(userRepository, passwordEncoder, notifierProvider);
    }

    private static User proMember(UUID id) {
        User user = new User("member@example.com", "member", "hash");
        ReflectionTestUtils.setField(user, "id", id);
        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(LocalDate.now().plusDays(10));
        user.setSubscriptionApprovedAt(LocalDateTime.now().minusDays(20));
        return user;
    }

    @Test
    void revokedMemberStaysClassifiedAsExpiredNotAsNewcomer() {
        UUID userId = UUID.randomUUID();
        User user = proMember(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        service.revokeSubscription(userId);

        assertThat(user.getSubscription()).as("access must still be cut").isEqualTo(SubscriptionStatus.FREE);
        assertThat(service.tierOf(user))
            .as("a revoked member has subscribed before — they belong in the renewal funnel")
            .isEqualTo(MemberTier.EXPIRED);
    }

    @Test
    void adminDowngradeToFreeAlsoKeepsTheSubscriptionHistory() {
        UUID userId = UUID.randomUUID();
        User user = proMember(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        service.adminUpdateUser(userId, null, null, "FREE");

        assertThat(user.getSubscription()).isEqualTo(SubscriptionStatus.FREE);
        assertThat(user.getDepositorName()).as("payment detail is not history").isNull();
        assertThat(service.tierOf(user)).isEqualTo(MemberTier.EXPIRED);
    }

    @Test
    void revokedMemberAppearsInTheRenewalListAndNotInTheNewcomerList() {
        UUID userId = UUID.randomUUID();
        User user = proMember(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        service.revokeSubscription(userId);

        when(userRepository.findAll()).thenReturn(List.of(user));

        assertThat(service.getExpiredUsers()).hasSize(1);
        assertThat(service.getNeverSubscribedUsers()).isEmpty();
    }

    @Test
    void memberWhoNeverSubscribedIsStillClassifiedAsNone() {
        User newcomer = new User("new@example.com", "new", "hash");

        assertThat(service.tierOf(newcomer)).isEqualTo(MemberTier.NONE);
    }

    @Test
    void freshApplicantIsPendingNotExpired() {
        User applicant = new User("applicant@example.com", "applicant", "hash");
        applicant.setSubscription(SubscriptionStatus.PENDING);
        applicant.setSubscriptionRequestedAt(LocalDateTime.now());

        assertThat(service.tierOf(applicant)).isEqualTo(MemberTier.PENDING);
    }
}
