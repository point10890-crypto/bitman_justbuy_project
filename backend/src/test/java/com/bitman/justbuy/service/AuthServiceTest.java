package com.bitman.justbuy.service;

import com.bitman.justbuy.controller.ApiException;
import com.bitman.justbuy.dto.AuthRequest;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;

    AuthService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<TelegramNotifier> notifierProvider = mock(ObjectProvider.class);
        service = new AuthService(userRepository, passwordEncoder, jwtService, notifierProvider);
        ReflectionTestUtils.setField(service, "adminEmail", "");
    }

    @Test
    void invalidLoginReturnsUnauthorizedWithoutChangingExistingSubscription() {
        LocalDate endDate = LocalDate.now().plusDays(11);
        User user = new User("member@example.com", "member", "hash");
        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(endDate);

        when(userRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtAsc("member@example.com"))
            .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new AuthRequest(" member@example.com ", "wrong-password", true)))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> {
                ApiException apiError = (ApiException) error;
                assertThat(apiError.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(apiError.getMessage()).contains("이메일 또는 비밀번호");
            });

        assertThat(user.getSubscription()).isEqualTo(SubscriptionStatus.PRO);
        assertThat(user.getSubscriptionEndDate()).isEqualTo(endDate);
    }

    @Test
    void validLoginPreservesProUserAndReturnsToken() {
        UUID userId = UUID.randomUUID();
        LocalDate endDate = LocalDate.now().plusDays(11);
        User user = new User("pro@example.com", "pro", "hash");
        ReflectionTestUtils.setField(user, "id", userId);
        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(endDate);

        when(userRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtAsc("pro@example.com"))
            .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "hash")).thenReturn(true);
        when(jwtService.generateToken(eq(userId), eq("pro@example.com"), eq("USER"), eq(true)))
            .thenReturn("token");

        var response = service.login(new AuthRequest("PRO@example.com", "correct-password", true));

        assertThat(response.token()).isEqualTo("token");
        assertThat(response.user().subscription()).isEqualTo("PRO");
        assertThat(response.user().subscriptionEndDate()).isEqualTo(endDate);
        verify(jwtService).generateToken(userId, "pro@example.com", "USER", true);
    }

    @Test
    void deletedMemberTokenReturnsUnauthorized() {
        UUID deletedUserId = UUID.randomUUID();
        when(userRepository.findById(deletedUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCurrentUser(deletedUserId))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> {
                ApiException apiError = (ApiException) error;
                assertThat(apiError.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(apiError.getMessage()).contains("회원정보를 찾을 수 없습니다");
            });
    }
}
