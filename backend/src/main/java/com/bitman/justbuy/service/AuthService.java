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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${bitman.admin.email:}")
    private String adminEmail;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

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

        var token = jwtService.generateToken(
            user.getId(), user.getEmail(), user.getRole().name());

        return new AuthResponse(token, UserDto.from(user));
    }

    public AuthResponse login(AuthRequest request) {
        var user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        ensureAdminPro(user);

        var token = jwtService.generateToken(
            user.getId(), user.getEmail(), user.getRole().name());

        return new AuthResponse(token, UserDto.from(user));
    }

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

    public UserDto updateProfile(UUID userId, String name) {
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        user.setName(name.trim());
        userRepository.save(user);
        return UserDto.from(user);
    }

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
