package com.bitman.justbuy.security;

import com.bitman.justbuy.config.JwtProperties;
import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.JwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 토큰 수명과 무효화.
 *
 * <p>세 결함이 한 덩어리다: rememberMe 가 수명에 영향을 못 주고(1),
 * 비밀번호를 바꿔도 기존 토큰이 살아 있으며(2), 권한을 토큰 클레임에서만 읽어
 * 강등이 반영되지 않는다(5). 셋 중 하나만 고치면 피해 창이 그대로 남는다.
 */
class TokenSessionAndRevocationTest {

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    JwtService jwtService;
    UserRepository userRepository;
    JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        String secret = "test-secret-that-is-definitely-long-enough-for-hs512-abcdefghijklmnop";
        jwtService = new JwtService(new JwtProperties(secret, ONE_DAY_MS), new MockEnvironment());
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthenticationFilter(jwtService, userRepository);
        SecurityContextHolder.clearContext();
    }

    private static User memberWithId(UUID id, Role role) {
        User user = new User("member@example.com", "member", "hash");
        ReflectionTestUtils.setField(user, "id", id);
        user.setRole(role);
        return user;
    }

    private void authenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void rememberMeOffIssuesAShorterSessionThanRememberMeOn() {
        UUID userId = UUID.randomUUID();

        long sessionExp = jwtService.parseToken(
            jwtService.generateToken(userId, "member@example.com", "USER", false)
        ).getExpiration().getTime();
        long rememberExp = jwtService.parseToken(
            jwtService.generateToken(userId, "member@example.com", "USER", true)
        ).getExpiration().getTime();

        assertThat(sessionExp)
            .as("rememberMe=false must not last as long as rememberMe=true")
            .isLessThan(rememberExp);
    }

    @Test
    void authorityComesFromTheDatabaseNotFromTheTokenClaim() throws Exception {
        UUID userId = UUID.randomUUID();
        // Token was minted while the member was an admin; the role has since been revoked.
        String staleAdminToken = jwtService.generateToken(userId, "member@example.com", "ADMIN", false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(memberWithId(userId, Role.USER)));

        authenticate(staleAdminToken);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
            .as("a demoted admin must lose ROLE_ADMIN immediately, not at token expiry")
            .extracting(Object::toString)
            .containsExactly("ROLE_USER");
    }

    @Test
    void tokenIssuedBeforeAPasswordChangeIsRejected() throws Exception {
        UUID userId = UUID.randomUUID();
        String stolenToken = jwtService.generateToken(userId, "member@example.com", "USER", true);

        User user = memberWithId(userId, Role.USER);
        // Password was reset one minute after the stolen token was issued.
        user.setTokenValidFrom(LocalDateTime.now().plusMinutes(1));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        authenticate(stolenToken);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
            .as("resetting a password must cut existing sessions, not wait 30 days")
            .isNull();
    }

    @Test
    void tokenIssuedAfterAPasswordChangeStillWorks() throws Exception {
        UUID userId = UUID.randomUUID();
        User user = memberWithId(userId, Role.USER);
        user.setTokenValidFrom(LocalDateTime.now().minusMinutes(5));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        authenticate(jwtService.generateToken(userId, "member@example.com", "USER", false));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void tokenOfADeletedMemberDoesNotAuthenticate() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        authenticate(jwtService.generateToken(userId, "member@example.com", "ADMIN", false));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
