package com.bitman.justbuy.service;

import com.bitman.justbuy.controller.ApiException;
import com.bitman.justbuy.dto.AuthRequest;
import com.bitman.justbuy.dto.RegisterRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 인증 표면의 남용 방어.
 *
 * <p>로그인은 permitAll 이고 시도 제한이 없어 온라인 무차별 대입을 막을 게 BCrypt 비용뿐이었다.
 * 가입은 이메일 중복을 그대로 알려줘 회원 열거가 가능했다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginThrottleTest {

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

    private void givenMemberWithWrongPassword() {
        User user = new User("victim@example.com", "victim", "hash");
        when(userRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtAsc("victim@example.com"))
            .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("guess", "hash")).thenReturn(false);
    }

    @Test
    void repeatedFailuresLockTheAccountOutInsteadOfAllowingUnlimitedGuesses() {
        givenMemberWithWrongPassword();
        AuthRequest attempt = new AuthRequest("victim@example.com", "guess", false);

        for (int i = 0; i < AuthService.MAX_LOGIN_ATTEMPTS; i++) {
            assertThatThrownBy(() -> service.login(attempt)).isInstanceOf(ApiException.class);
        }

        assertThatThrownBy(() -> service.login(attempt))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(e.getMessage()).contains("잠시 후");
            });
    }

    @Test
    void aSuccessfulLoginClearsTheFailureCounter() {
        User user = new User("member@example.com", "member", "hash");
        when(userRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtAsc("member@example.com"))
            .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        when(passwordEncoder.matches("right", "hash")).thenReturn(true);
        when(jwtService.generateToken(any(), any(), any(), anyBoolean())).thenReturn("token");

        assertThatThrownBy(() -> service.login(new AuthRequest("member@example.com", "wrong", false)))
            .isInstanceOf(ApiException.class);
        service.login(new AuthRequest("member@example.com", "right", false));

        // 카운터가 남아 있으면 정상 사용자가 곧 잠긴다.
        assertThatThrownBy(() -> service.login(new AuthRequest("member@example.com", "wrong", false)))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void registrationDoesNotRevealThatAnEmailIsAlreadyAMember() {
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("name", "taken@example.com", "Str0ng!Password1")))
            .isInstanceOfSatisfying(ApiException.class, e ->
                assertThat(e.getMessage())
                    .as("must not confirm membership to an unauthenticated caller")
                    .doesNotContain("이미 등록된"));
    }

    private static <T> T any() { return org.mockito.ArgumentMatchers.any(); }
    private static boolean anyBoolean() { return org.mockito.ArgumentMatchers.anyBoolean(); }
}
