package com.bitman.justbuy.service;

import com.bitman.justbuy.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Path AUTO_SECRET_PATH = Path.of("data", ".jwt-secret");
    private static final int MIN_SECRET_BYTES = 64;
    private static final long REMEMBER_ME_EXPIRATION_MS = 30L * 24 * 60 * 60 * 1000; // 30 days

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(JwtProperties props, Environment environment) {
        String secret = props.secret();
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        if (secret == null || secret.isBlank()) {
            if (isProd) {
                throw new IllegalStateException(
                    "JWT_SECRET env var is REQUIRED in production profile. "
                    + "Generate one with: openssl rand -base64 64"
                );
            }
            secret = loadOrCreatePersistedSecret();
        }

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes for HS512 "
                + "(current: " + secretBytes.length + "). "
                + "Generate one with: openssl rand -base64 64"
            );
        }

        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expirationMs = props.expirationMs();
    }

    private static String loadOrCreatePersistedSecret() {
        try {
            if (Files.exists(AUTO_SECRET_PATH)) {
                String existing = Files.readString(AUTO_SECRET_PATH).trim();
                if (existing.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES) {
                    log.warn("JWT_SECRET env not set; reusing persisted secret at {} (dev only).",
                            AUTO_SECRET_PATH.toAbsolutePath());
                    return existing;
                }
            }

            byte[] randomBytes = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(randomBytes);
            String generated = Base64.getEncoder().encodeToString(randomBytes);
            Files.createDirectories(AUTO_SECRET_PATH.getParent());
            Files.writeString(AUTO_SECRET_PATH, generated);
            log.error("JWT_SECRET not set; generated and persisted new dev secret to {}. "
                    + "For production, set JWT_SECRET env var (openssl rand -base64 64).",
                    AUTO_SECRET_PATH.toAbsolutePath());
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException(
                "JWT secret persistence failed and no JWT_SECRET env provided: " + e.getMessage(), e);
        }
    }

    public String generateToken(UUID userId, String email, String role) {
        return generateToken(userId, email, role, false);
    }

    public String generateToken(UUID userId, String email, String role, boolean rememberMe) {
        var now = new Date();
        // rememberMe 를 끈 세션은 짧아야 한다. 이전 구현은 Math.max 라서 어느 쪽이든
        // 30일이 나왔고 "로그인 유지" 체크박스가 아무 의미가 없었다.
        long expMs = rememberMe
            ? REMEMBER_ME_EXPIRATION_MS
            : Math.min(expirationMs, REMEMBER_ME_EXPIRATION_MS);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expMs))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }
}
