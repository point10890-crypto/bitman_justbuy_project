package com.bitman.justbuy.service;

import com.bitman.justbuy.controller.ApiException;
import com.bitman.justbuy.dto.AuthRequest;
import com.bitman.justbuy.dto.AuthResponse;
import com.bitman.justbuy.dto.RegisterRequest;
import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final org.springframework.beans.factory.ObjectProvider<TelegramNotifier> telegramNotifierProvider;

    @Value("${bitman.admin.email:}")
    private String adminEmail;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       org.springframework.beans.factory.ObjectProvider<TelegramNotifier> telegramNotifierProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.telegramNotifierProvider = telegramNotifierProvider;
    }

    @PostConstruct
    @Transactional
    public void ensureAdminExistsOnStartup() {
        if (adminEmail == null || adminEmail.isBlank()) return;

        String normalizedAdminEmail = normalizeEmail(adminEmail);
        var existing = userRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtAsc(normalizedAdminEmail);
        if (existing.isEmpty()) {
            // 계정이 없을 때만 ADMIN_DEFAULT_PASSWORD 사용해 부트스트랩.
            // 환경변수 미설정 OR 약한 비밀번호 감지 시: 예측 불가능한 랜덤 비밀번호를 1회 생성.
            // (하드코딩 폴백 'Admin1234' 제거 — 평문 유출 리스크 차단)
            String defaultPassword = System.getenv("ADMIN_DEFAULT_PASSWORD");
            boolean generated = false;

            if (defaultPassword == null || defaultPassword.isBlank()) {
                defaultPassword = generateStrongRandomPassword();
                generated = true;
                log.warn("===============================================================");
                log.warn("ADMIN_DEFAULT_PASSWORD env var NOT SET.");
            } else if (!PasswordPolicy.isStrong(defaultPassword)) {
                // 약한 비밀번호 감지 (12자 미만 또는 복잡도 부족) — 환경변수를 무시하고 랜덤 생성.
                defaultPassword = generateStrongRandomPassword();
                generated = true;
                log.error("===============================================================");
                log.error("ADMIN_DEFAULT_PASSWORD is WEAK (needs 12+ chars, upper/lower/digit/special).");
                log.error("Ignoring weak env value; generated strong random password instead.");
            }

            if (generated) {
                // ★ v2.8.4 (2026-04-26): 비밀번호를 로그에 찍지 않음.
                //    대신 data/.admin-bootstrap-password 파일에 1회성으로 기록.
                //    파일 권한은 lock-env.ps1 / OS-level ACL 로 제한.
                //    관리자가 읽고 즉시 삭제해야 함.
                java.nio.file.Path passwordFile = java.nio.file.Path.of("data", ".admin-bootstrap-password");
                try {
                    java.nio.file.Files.createDirectories(passwordFile.getParent());
                    java.nio.file.Files.writeString(passwordFile,
                        "ADMIN: " + normalizedAdminEmail + "\n"
                        + "ONE-TIME PASSWORD: " + defaultPassword + "\n"
                        + "GENERATED: " + java.time.Instant.now() + "\n\n"
                        + "ACTION REQUIRED:\n"
                        + "  1. Login at https://api.bit-man.net/admin with above credentials\n"
                        + "  2. Change password via admin UI\n"
                        + "  3. DELETE THIS FILE: data/.admin-bootstrap-password\n"
                        + "  4. Set strong ADMIN_DEFAULT_PASSWORD env var (12+ chars, mixed case/digit/special)\n"
                    );
                    log.warn("===============================================================");
                    log.warn("Admin one-time password written to: {}", passwordFile.toAbsolutePath());
                    log.warn("Read it ON THE SERVER, then DELETE the file.");
                    log.warn("DO NOT transmit this file over network.");
                    log.warn("===============================================================");
                } catch (java.io.IOException ioe) {
                    // 파일 쓰기 실패 시 부득이 로그로 (서버가 부팅 못하면 더 큰 문제)
                    log.error("Failed to write bootstrap password file: {}. Falling back to log.", ioe.getMessage());
                    log.warn("ONE-TIME ADMIN PASSWORD: {} (rotate immediately!)", defaultPassword);
                }
            }

            var admin = new User(normalizedAdminEmail, "Admin", passwordEncoder.encode(defaultPassword));
            admin.setRole(Role.ADMIN);
            admin.setSubscription(SubscriptionStatus.PRO);
            admin.setSubscriptionEndDate(null);
            userRepository.save(admin);
            log.info("Admin account created: {}", normalizedAdminEmail);
        } else {
            // 계정이 이미 존재하면 비밀번호는 절대 건드리지 않음 (재시작마다 reset 되던 버그 수정).
            // role/subscription 만 보장.
            var admin = existing.get();
            boolean changed = false;
            if (admin.getRole() != Role.ADMIN) {
                admin.setRole(Role.ADMIN);
                changed = true;
            }
            if (admin.getSubscription() != SubscriptionStatus.PRO || admin.getSubscriptionEndDate() != null) {
                admin.setSubscription(SubscriptionStatus.PRO);
                admin.setSubscriptionEndDate(null);
                changed = true;
            }
            if (changed) {
                userRepository.save(admin);
                log.info("Admin role/subscription re-applied for {}", normalizedAdminEmail);
            }
        }
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("이미 등록된 이메일입니다.");
        }

        var user = new User(
            email,
            request.name(),
            passwordEncoder.encode(request.password())
        );

        // 첫 번째 가입자 또는 지정된 관리자 이메일은 ADMIN + 무기한 PRO
        if (userRepository.count() == 0
                || (adminEmail != null && !adminEmail.isBlank()
                    && email.equalsIgnoreCase(normalizeEmail(adminEmail)))) {
            user.setRole(Role.ADMIN);
            user.setSubscription(SubscriptionStatus.PRO);
            user.setSubscriptionEndDate(null);
        }

        user = userRepository.save(user);
        log.info("User registered: {}", user.getEmail());

        // 관리자 텔레그램 알림 (실패해도 가입 자체는 성공 처리)
        try {
            TelegramNotifier notifier = telegramNotifierProvider.getIfAvailable();
            if (notifier != null) {
                long totalUsers = userRepository.count();
                String msg = String.format(
                    "🆕 <b>[JUST BUY] 신규 회원가입</b>%n%n"
                    + "👤 이름: %s%n"
                    + "📧 이메일: %s%n"
                    + "🛡 권한: %s%n"
                    + "🆔 userId: <code>%s</code>%n%n"
                    + "📊 전체 회원: %d명",
                    escape(user.getName()),
                    escape(user.getEmail()),
                    user.getRole().name(),
                    user.getId(),
                    totalUsers
                );
                notifier.sendToAdmin(msg);
            }
        } catch (Exception e) {
            log.warn("[Auth] 관리자 텔레그램 알림 실패: {}", e.getMessage());
        }

        var token = jwtService.generateToken(
            user.getId(), user.getEmail(), user.getRole().name());

        return new AuthResponse(token, UserDto.from(user));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 관리자 부트스트랩용 강력 랜덤 비밀번호 (PasswordPolicy 정책 자동 만족). */
    private static String generateStrongRandomPassword() {
        return PasswordPolicy.generateStrong();
    }

    @Transactional
    public AuthResponse login(AuthRequest request) {
        String email = normalizeEmail(request.email());
        var user = userRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtAsc(email)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        ensureAdminPro(user);

        var token = jwtService.generateToken(
            user.getId(), user.getEmail(), user.getRole().name(), request.rememberMe());

        return new AuthResponse(token, UserDto.from(user));
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Transactional
    public UserDto getCurrentUser(UUID userId) {
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        ensureAdminPro(user);

        return UserDto.from(user);
    }

    /** 관리자 이메일이면 ADMIN 승격 + ADMIN은 항상 무기한 PRO 보장 */
    private void ensureAdminPro(User user) {
        // 관리자 이메일인데 아직 ADMIN이 아니면 승격
        if (adminEmail != null && !adminEmail.isBlank()
                && user.getEmail().equalsIgnoreCase(normalizeEmail(adminEmail))
                && user.getRole() != Role.ADMIN) {
            user.setRole(Role.ADMIN);
            log.info("User promoted to ADMIN: {}", user.getEmail());
        }

        if (user.getRole() == Role.ADMIN
                && (user.getSubscription() != SubscriptionStatus.PRO || user.getSubscriptionEndDate() != null)) {
            user.setSubscription(SubscriptionStatus.PRO);
            user.setSubscriptionEndDate(null);
            userRepository.save(user);
        }
    }

    @Transactional
    public UserDto updateProfile(UUID userId, String name) {
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        user.setName(name.trim());
        userRepository.save(user);
        return UserDto.from(user);
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}
