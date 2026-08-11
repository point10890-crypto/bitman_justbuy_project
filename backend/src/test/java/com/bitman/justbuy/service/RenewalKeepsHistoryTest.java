package com.bitman.justbuy.service;

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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재구독 신청은 구독 이력을 지우면 안 된다.
 *
 * <p>만료 회원이 재구독을 누르는 순간 종료일과 승인일이 지워지면, 그 회원은 시스템상
 * 신규 가입자와 구별할 수 없게 된다. 관리자 대기 목록에도 이력 없는 신규 신청으로 뜨고,
 * 반려라도 하면 NONE 으로 떨어져 그 뒤로는 영원히 "신규 구독" 흐름을 타게 된다.
 *
 * <p>revoke 와 adminUpdateUser 는 이 불변식을 지키도록 고쳤는데 신청 경로만 빠져 있었다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RenewalKeepsHistoryTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    SubscriptionService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<TelegramNotifier> notifierProvider = mock(ObjectProvider.class);
        service = new SubscriptionService(userRepository, passwordEncoder, notifierProvider);
    }

    /** 자정 배치가 FREE 로 내린 만료 회원. 종료일·승인일은 보존돼 있다. */
    private User lapsedMember(UUID id) {
        User user = new User("lapsed@example.com", "lapsed", "hash");
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.now().minusMonths(3));
        user.setSubscription(SubscriptionStatus.FREE);
        user.setSubscriptionEndDate(LocalDate.now().minusDays(5));
        user.setSubscriptionApprovedAt(LocalDateTime.now().minusMonths(2));
        return user;
    }

    @Test
    void renewalApplicationKeepsTheProofThatTheyWereAMemberBefore() {
        UUID userId = UUID.randomUUID();
        User user = lapsedMember(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        service.applyForSubscription(userId, "홍길동");

        assertThat(user.getSubscription()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(user.getSubscriptionApprovedAt())
            .as("wiping the approval date turns a renewal into a first-time signup")
            .isNotNull();
        assertThat(user.getSubscriptionEndDate())
            .as("the previous term is what proves this is a renewal")
            .isNotNull();
    }

    @Test
    void anAdminSeesARenewalApplicantAsAReturningMemberNotANewcomer() {
        UUID userId = UUID.randomUUID();
        User user = lapsedMember(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userRepository.findAll()).thenReturn(List.of(user));

        service.applyForSubscription(userId, "홍길동");

        assertThat(service.getNeverSubscribedUsers())
            .as("a returning member must never show up in the newcomer funnel")
            .isEmpty();
    }

    @Test
    void rejectingARenewalLeavesTheMemberInTheRenewalFunnel() {
        UUID userId = UUID.randomUUID();
        User user = lapsedMember(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        service.applyForSubscription(userId, "홍길동");
        service.rejectSubscription(userId);

        assertThat(service.tierOf(user))
            .as("a rejected renewal must not demote a former member to 'never subscribed'")
            .isEqualTo(MemberTier.EXPIRED);
    }

    @Test
    void approvingARenewalStartsAFreshMonthFromToday() {
        UUID userId = UUID.randomUUID();
        User user = lapsedMember(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        service.applyForSubscription(userId, "홍길동");
        service.approveSubscription(userId);

        assertThat(user.getSubscription()).isEqualTo(SubscriptionStatus.PRO);
        assertThat(user.getSubscriptionEndDate())
            .as("an expired term must not be extended from the old past date")
            .isEqualTo(LocalDate.now().plusMonths(1));
    }

    @Test
    void aGenuineNewcomerStillHasNoHistoryAfterApplying() {
        UUID userId = UUID.randomUUID();
        User newcomer = new User("new@example.com", "new", "hash");
        ReflectionTestUtils.setField(newcomer, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(newcomer));
        when(userRepository.save(newcomer)).thenReturn(newcomer);

        service.applyForSubscription(userId, "신규");

        assertThat(newcomer.getSubscriptionApprovedAt()).isNull();
        assertThat(newcomer.getSubscriptionEndDate()).isNull();
        assertThat(service.tierOf(newcomer)).isEqualTo(MemberTier.PENDING);
    }
}
