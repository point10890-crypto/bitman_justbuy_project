package com.bitman.justbuy.controller;

import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 구독 만료 워크플로우의 진입점 — 403 에 어떤 코드가 실리는지가
 * 프론트에서 "재구독 페이지로 보낼지"를 결정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionAccessGuardTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock UserRepository userRepository;
    @Mock SubscriptionService subscriptionService;

    private SubscriptionAccessGuard guard() {
        return new SubscriptionAccessGuard(userRepository, subscriptionService);
    }

    @Test
    void activeProPassesThrough() {
        UUID id = stub(user(SubscriptionStatus.PRO, LocalDate.now(KST).plusDays(10)), true);
        guard().requirePro(id);
    }

    @Test
    void expiredProGetsExpiredCodeSoClientCanOfferRenewal() {
        UUID id = stub(user(SubscriptionStatus.PRO, LocalDate.now(KST).minusDays(1)), false);

        assertThatThrownBy(() -> guard().requirePro(id))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(e.getCode()).isEqualTo(SubscriptionAccessGuard.CODE_EXPIRED);
                assertThat(e.getMessage()).contains("재구독");
            });
    }

    @Test
    void expiredMemberAlreadyDowngradedToFreeStillCountsAsRenewal() {
        // 자정 배치가 FREE 로 내린 뒤에도 종료일이 남아 있어 재구독 대상으로 인식된다
        UUID id = stub(user(SubscriptionStatus.FREE, LocalDate.now(KST).minusDays(3)), false);

        assertThatThrownBy(() -> guard().requirePro(id))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo(SubscriptionAccessGuard.CODE_EXPIRED));
    }

    @Test
    void neverSubscribedGetsRequiredCode() {
        UUID id = stub(user(SubscriptionStatus.FREE, null), false);

        assertThatThrownBy(() -> guard().requirePro(id))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getCode()).isEqualTo(SubscriptionAccessGuard.CODE_REQUIRED));
    }

    @Test
    void pendingApprovalGetsPendingCodeNotExpired() {
        UUID id = stub(user(SubscriptionStatus.PENDING, LocalDate.now(KST).minusDays(2)), false);

        assertThatThrownBy(() -> guard().requirePro(id))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getCode()).isEqualTo(SubscriptionAccessGuard.CODE_PENDING);
                assertThat(e.getMessage()).contains("승인");
            });
    }

    @Test
    void unknownUserIsUnauthorized() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard().requirePro(id))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private UUID stub(User user, boolean activePro) {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(subscriptionService.isActivePro(user)).thenReturn(activePro);
        return id;
    }

    private static User user(SubscriptionStatus status, LocalDate endDate) {
        User user = new User("member@example.com", "member", "hash");
        user.setSubscription(status);
        user.setSubscriptionEndDate(endDate);
        return user;
    }
}
