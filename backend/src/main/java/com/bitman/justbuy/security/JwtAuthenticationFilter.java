package com.bitman.justbuy.security;

import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 토큰을 검증하고 <b>DB 를 진실의 출처로</b> 권한을 세운다.
 *
 * <p>이전 구현은 토큰의 {@code role} 클레임을 그대로 신뢰했다. 그래서 관리자를 강등해도
 * 이미 발급된 토큰이 만료될 때까지 ROLE_ADMIN 이 유지됐고, 탈퇴·비밀번호 초기화도
 * 세션을 끊지 못했다. 요청당 조회 1회를 지불하고 그 창을 닫는다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                if (jwtService.isValid(token)) {
                    Claims claims = jwtService.parseToken(token);
                    UUID userId = UUID.fromString(claims.getSubject());

                    Optional<User> found = userRepository.findById(userId);
                    if (found.isEmpty()) {
                        // 탈퇴한 회원의 토큰. 서명은 유효하지만 주체가 없다.
                        log.debug("Rejected token for unknown user {}", userId);
                    } else if (isIssuedBeforeRevocation(found.get(), claims.getIssuedAt())) {
                        log.info("Rejected token issued before credential change for user {}", userId);
                    } else {
                        String role = found.get().getRole().name();
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                        var authentication = new UsernamePasswordAuthenticationToken(
                            userId, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (Exception e) {
                log.warn("JWT authentication failed for request {}: {}", request.getRequestURI(), e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 비밀번호 변경·초기화 이전에 발급된 토큰인지.
     *
     * <p>JWT {@code iat} 는 초 단위라 같은 초에 발급된 토큰이 억울하게 잘리지 않도록
     * 경계에서는 통과시킨다({@code isBefore} 사용).
     */
    private static boolean isIssuedBeforeRevocation(User user, Date issuedAt) {
        LocalDateTime validFrom = user.getTokenValidFrom();
        if (validFrom == null || issuedAt == null) return false;
        LocalDateTime issued = LocalDateTime.ofInstant(issuedAt.toInstant(), KST);
        return issued.isBefore(validFrom.withNano(0));
    }
}
