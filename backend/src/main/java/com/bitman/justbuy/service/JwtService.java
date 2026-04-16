package com.bitman.justbuy.service;

import com.bitman.justbuy.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    /** Auto-generated JWT secret 보관 위치. JWT_SECRET 환경변수가 없을 때만 사용. */
    private static final Path AUTO_SECRET_PATH = Path.of("data", ".jwt-secret");

    private final SecretKey key;
    private final long expirationMs;
    private static final long REMEMBER_ME_EXPIRATION_MS = 30L * 24 * 60 * 60 * 1000; // 30일

    public JwtService(JwtProperties props) {
        String secret = props.secret();
        if (secret == null || secret.isBlank()) {
            secret = loadOrCreatePersistedSecret();
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = props.expirationMs();
    }

    /**
     * JWT_SECRET 환경변수가 없을 때 디스크에 영속화된 시크릿을 로드하거나 새로 생성한다.
     * 과거에는 매 부팅마다 random UUID 를 생성해 모든 발급 토큰이 즉시 무효화됐다.
     * 이제 data/.jwt-secret 에 한 번만 만들어두고 재기동 시 재사용한다.
     */
    private static String loadOrCreatePersistedSecret() {
        try {
            if (Files.exists(AUTO_SECRET_PATH)) {
                String existing = Files.readString(AUTO_SECRET_PATH).trim();
                if (existing.length() >= 32) {
                    log.warn("JWT_SECRET env not set — reusing persisted secret at {}. Set JWT_SECRET to override.",
                            AUTO_SECRET_PATH.toAbsolutePath());
                    return existing;
                }
            }
            byte[] randomBytes = new byte[48];
            new SecureRandom().nextBytes(randomBytes);
            String generated = Base64.getEncoder().encodeToString(randomBytes);
            Files.createDirectories(AUTO_SECRET_PATH.getParent());
            Files.writeString(AUTO_SECRET_PATH, generated);
            log.error("JWT_SECRET not set — generated and persisted new secret to {}. Tokens now survive restart, "
                    + "but for production you should set JWT_SECRET env var.", AUTO_SECRET_PATH.toAbsolutePath());
            return generated;
        } catch (Exception e) {
            // 파일 시스템 접근 실패 시에는 기존 동작(휘발성 random)으로 fallback.
            String fallback = UUID.randomUUID().toString() + UUID.randomUUID().toString();
            log.error("JWT_SECRET not set AND persistence failed ({}) — using volatile random secret; "
                    + "all issued tokens will be invalidated on next restart.", e.getMessage());
            return fallback;
        }
    }

    public String generateToken(UUID userId, String email, String role) {
        return generateToken(userId, email, role, false);
    }

    public String generateToken(UUID userId, String email, String role, boolean rememberMe) {
        var now = new Date();
        long expMs = rememberMe ? REMEMBER_ME_EXPIRATION_MS : expirationMs;
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
