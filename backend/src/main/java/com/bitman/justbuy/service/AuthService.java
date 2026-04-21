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

        var existing = userRepository.findByEmail(adminEmail.trim());
        if (existing.isEmpty()) {
            // 계정이 없을 때만 ADMIN_DEFAULT_PASSWORD 사용해 부트스트랩.
            // 환경변수 미설정 시: 예측 불가능한 랜덤 비밀번호를 1회 생성해 로그에 출력.
            // (하드코딩 폴백 'Admin1234' 제거 — 평문 유출 리스크 차단)
            String defaultPassword = System.getenv("ADMIN_DEFAULT_PASSWORD");
            if (defaultPassword == null || defaultPassword.isBlank()) {
                defaultPassword = UUID.randomUUID().toString().replace("-", "") + "!A1";
                log.warn("===============================================================");
                log.warn("ADMIN_DEFAULT_PASSWORD env var NOT SET.");
                log.warn("Generated one-time random admin password for {}:", adminEmail.trim());
                log.warn("  {}", defaultPassword);
                log.warn("SAVE THIS NOW — it will NOT be logged again. Then set ADMIN_DEFAULT_PASSWORD in .env and restart, or change via admin UI.");
                log.warn("===============================================================");
            }
            var admin = new User(adminEmail.trim(), "Admin", passwordEncoder.encode(defaultPassword));
            admin.setRole(Role.ADMIN);
            admin.setSubscription(SubscriptionStatus.PRO);
            admin.setSubscriptionEndDate(null);
            userRepository.save(admin);
            log.info("Admin account created: {}", adminEmail.trim());
        } else {
            // 계정이 이미 존재하면 비밀번호는 절대 건드리지 않음 (재시작마다 reset 되던 버그 수정).
            // role/subscription 만 보장.
            var admin = existing.get();
            boolean changed = false;
            if (admin.getRole() != Role.ADMIN) {
                admin.setRole(Role.ADMIN);
                changed = true;
            }
            if (admin.getSubscription() != SubscriptionStatus.PRO) {
                admin.setSubscription(SubscriptionStatus.PRO);
                admin.setSubscriptionEndDate(null);
                changed = true;
            }
            if (changed) {
                userRepository.save(admin);
                log.info("Admin role/subscription re-applied for {}", adminEmail.trim());
            }
        }
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("이미 등록된 이메일입니다.");
        }

        var user = new User(
            request.email(),
            request.name(),
            passwordEncoder.encode(request.password())
        );

        // 첫 번째 가입자 또는 지정된 관리자 이메일은 ADMIN + 무기한 PRO
        if (userRepository.count() == 0
                || (adminEmail != null && !adminEmail.isBlank()
                    && request.email().equalsIgnoreCase(adminEmail.trim()))) {
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

    @Transactional
    public AuthResponse login(AuthRequest request) {
        var user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        ensureAdminPro(user);

        var token = jwtService.generateToken(
            user.getId(), user.getEmail(), user.getRole().name(), request.rememberMe());

        return new AuthResponse(token, UserDto.from(user));
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
                && user.getEmail().equalsIgnoreCase(adminEmail.trim())
                && user.getRole() != Role.ADMIN) {
            user.setRole(Role.ADMIN);
            log.info("User promoted to ADMIN: {}", user.getEmail());
        }

        if (user.getRole() == Role.ADMIN && user.getSubscription() != SubscriptionStatus.PRO) {
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
