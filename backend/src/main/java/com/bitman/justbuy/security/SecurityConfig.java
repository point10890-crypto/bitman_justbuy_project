package com.bitman.justbuy.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${bitman.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
            .authorizeHttpRequests(auth -> auth
                // Static files + SPA routes (non-API paths)
                .requestMatchers(new AntPathRequestMatcher("/")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/index.html")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/assets/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/icons/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/manifest.json")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/sw.js")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/favicon.ico")).permitAll()
                // SPA client-side routes (forwarded to index.html by SpaWebConfig)
                .requestMatchers(new AntPathRequestMatcher("/login")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/register")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/landing")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/home")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/supply")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/my")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/subscribe")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/admin")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/admin/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/search")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/history")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/history/**")).permitAll()

                // Public API
                .requestMatchers(new AntPathRequestMatcher("/api/health")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/market/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/kr/jongga-v2/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/auth/register"),
                                 new AntPathRequestMatcher("/api/auth/login")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/analysis/**")).authenticated()
                .requestMatchers(new AntPathRequestMatcher("/h2-console/**")).hasRole("ADMIN")
                .requestMatchers(new AntPathRequestMatcher("/error")).permitAll()

                // Swagger/OpenAPI
                .requestMatchers(new AntPathRequestMatcher("/swagger-ui/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/swagger-ui.html")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/v3/api-docs/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/v3/api-docs")).permitAll()

                // Actuator — health/info만 public, 나머지는 management.exposure.include에서 차단됨
                .requestMatchers(new AntPathRequestMatcher("/actuator/health")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/actuator/health/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/actuator/info")).permitAll()

                // Monitor: ping은 public, health/logs는 관리자 전용
                .requestMatchers(new AntPathRequestMatcher("/api/monitor/ping")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/monitor/**")).hasRole("ADMIN")

                // Admin only
                .requestMatchers(new AntPathRequestMatcher("/api/admin/**")).hasRole("ADMIN")

                // Authenticated
                .requestMatchers(new AntPathRequestMatcher("/api/auth/me")).authenticated()
                .requestMatchers(new AntPathRequestMatcher("/api/auth/me/**")).authenticated()
                .requestMatchers(new AntPathRequestMatcher("/api/subscription/**")).authenticated()
                .requestMatchers(new AntPathRequestMatcher("/api/feedback/**")).authenticated()

                .anyRequest().authenticated()
            )
            // 인증이 없거나 토큰이 무효면 401. 기본값은 403 이었는데, 403 은 구독 가드가
            // "PRO 구독자만 사용 가능"에도 쓰는 코드라 프론트가 "다시 로그인" 상황과
            // "구독 유도" 상황을 구분할 수 없었다.
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"인증이 필요합니다. 다시 로그인해 주세요.\"}");
                }))
            .addFilterBefore(jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
