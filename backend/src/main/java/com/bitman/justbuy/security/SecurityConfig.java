package com.bitman.justbuy.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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

                // Public API
                .requestMatchers(new AntPathRequestMatcher("/api/health")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/market/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/auth/register"),
                                 new AntPathRequestMatcher("/api/auth/login")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/api/analysis/**")).authenticated()
                .requestMatchers(new AntPathRequestMatcher("/h2-console/**")).hasRole("ADMIN")
                .requestMatchers(new AntPathRequestMatcher("/error")).permitAll()

                // Swagger/OpenAPI
                .requestMatchers(new AntPathRequestMatcher("/swagger-ui/**")).permitAll()
                .requestMatchers(new AntPathRequestMatcher("/v3/api-docs/**")).permitAll()

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
