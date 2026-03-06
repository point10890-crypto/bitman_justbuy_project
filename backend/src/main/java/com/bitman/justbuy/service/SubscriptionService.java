package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SubscriptionService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserDto applyForSubscription(UUID userId, String depositorName) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (user.getSubscription() == SubscriptionStatus.PRO) {
            throw new IllegalStateException("이미 PRO 구독 중입니다.");
        }
        if (user.getSubscription() == SubscriptionStatus.PENDING) {
            throw new IllegalStateException("이미 구독 신청이 접수되었습니다.");
        }

        user.setSubscription(SubscriptionStatus.PENDING);
        user.setDepositorName(depositorName);
        userRepository.save(user);
        log.info("Subscription applied: userId={}, depositor={}", userId, depositorName);

        return UserDto.from(user);
    }

    public List<UserDto> getPendingSubscriptions() {
        return userRepository.findBySubscription(SubscriptionStatus.PENDING)
            .stream()
            .map(UserDto::from)
            .toList();
    }

    public UserDto approveSubscription(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (user.getSubscription() != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태가 아닙니다.");
        }

        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(LocalDate.now().plusMonths(1));
        userRepository.save(user);
        log.info("Subscription approved: userId={}", userId);

        return UserDto.from(user);
    }

    public UserDto rejectSubscription(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (user.getSubscription() != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태가 아닙니다.");
        }

        user.setSubscription(SubscriptionStatus.FREE);
        user.setDepositorName(null);
        userRepository.save(user);
        log.info("Subscription rejected: userId={}", userId);

        return UserDto.from(user);
    }

    public List<UserDto> getAllUsers() {
        return userRepository.findAll()
            .stream()
            .map(UserDto::from)
            .toList();
    }

    public UserDto revokeSubscription(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (user.getSubscription() != SubscriptionStatus.PRO) {
            throw new IllegalStateException("PRO 구독 상태가 아닙니다.");
        }

        user.setSubscription(SubscriptionStatus.FREE);
        user.setSubscriptionEndDate(null);
        userRepository.save(user);
        log.info("Subscription revoked: userId={}", userId);

        return UserDto.from(user);
    }

    // ─── 관리자 회원관리 ───

    /** 관리자: 회원 정보 수정 (이름, 이메일, 구독상태) */
    public UserDto adminUpdateUser(UUID userId, String name, String email, String subscription) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (name != null && !name.isBlank()) {
            user.setName(name.trim());
        }

        if (email != null && !email.isBlank()) {
            userRepository.findByEmail(email).ifPresent(existing -> {
                if (!existing.getId().equals(userId)) {
                    throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
                }
            });
            user.setEmail(email.trim());
        }

        if (subscription != null && !subscription.isBlank()) {
            SubscriptionStatus newStatus;
            try {
                newStatus = SubscriptionStatus.valueOf(subscription);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("올바르지 않은 구독 상태입니다: " + subscription);
            }
            user.setSubscription(newStatus);
            if (newStatus == SubscriptionStatus.PRO && user.getSubscriptionEndDate() == null) {
                user.setSubscriptionEndDate(LocalDate.now().plusMonths(1));
            }
            if (newStatus == SubscriptionStatus.FREE) {
                user.setSubscriptionEndDate(null);
                user.setDepositorName(null);
            }
        }

        userRepository.save(user);
        return UserDto.from(user);
    }

    public void adminResetPassword(UUID userId, String newPassword) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Admin reset password: userId={}", userId);
    }

    /** 관리자: 회원 검색 (이름 또는 이메일) */
    public List<UserDto> searchUsers(String query) {
        String q = query.toLowerCase().trim();
        return userRepository.findAll().stream()
            .filter(u -> u.getName().toLowerCase().contains(q)
                      || u.getEmail().toLowerCase().contains(q))
            .map(UserDto::from)
            .toList();
    }
}
