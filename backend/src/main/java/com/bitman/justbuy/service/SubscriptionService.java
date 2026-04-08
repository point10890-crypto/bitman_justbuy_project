package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.beans.factory.ObjectProvider<TelegramNotifier> telegramNotifierProvider;

    public SubscriptionService(UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               org.springframework.beans.factory.ObjectProvider<TelegramNotifier> telegramNotifierProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.telegramNotifierProvider = telegramNotifierProvider;
    }

    public UserDto applyForSubscription(UUID userId, String depositorName) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (user.getSubscription() == SubscriptionStatus.PENDING) {
            throw new IllegalStateException("이미 구독 신청이 접수되었습니다. 관리자 승인을 기다려주세요.");
        }

        // 유효한 PRO(만료 전)는 재신청 불필요
        if (isActivePro(user)) {
            throw new IllegalStateException("이미 유효한 PRO 구독 중입니다. (만료일: "
                + user.getSubscriptionEndDate() + ")");
        }

        // 만료된 PRO 또는 FREE → PENDING으로 전환 (재신청 허용)
        user.setSubscription(SubscriptionStatus.PENDING);
        user.setDepositorName(depositorName);
        // 만료된 이전 endDate 초기화
        user.setSubscriptionEndDate(null);
        userRepository.save(user);
        log.info("Subscription applied: userId={}, depositor={}, previousStatus={}",
            userId, depositorName, user.getSubscription());

        // 관리자 텔레그램 알림 (실패해도 신청 자체는 성공 처리)
        try {
            TelegramNotifier notifier = telegramNotifierProvider.getIfAvailable();
            if (notifier != null) {
                long pendingCount = userRepository.findBySubscription(SubscriptionStatus.PENDING).size();
                String msg = String.format(
                    "🔔 <b>신규 구독 승인 요청</b>%n%n"
                    + "👤 이름: %s%n"
                    + "📧 이메일: %s%n"
                    + "💳 입금자명: %s%n"
                    + "🆔 userId: <code>%s</code>%n%n"
                    + "📋 대기 중: %d건%n"
                    + "👉 관리자 페이지에서 승인/거절 처리하세요.",
                    escape(user.getName()),
                    escape(user.getEmail()),
                    escape(depositorName),
                    user.getId(),
                    pendingCount
                );
                notifier.sendToAdmin(msg);
            }
        } catch (Exception e) {
            log.warn("[Subscription] 관리자 텔레그램 알림 실패: {}", e.getMessage());
        }

        return UserDto.from(user);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

    // ─── 구독 통계 ───

    public Map<String, Object> getSubscriptionStats() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        long proCount     = userRepository.findBySubscription(SubscriptionStatus.PRO).size();
        long pendingCount = userRepository.findBySubscription(SubscriptionStatus.PENDING).size();
        long freeCount    = userRepository.findBySubscription(SubscriptionStatus.FREE).size();
        long totalUsers   = userRepository.count();

        // 이번 주(7일 이내) 만료 예정
        long expiringThisWeek = userRepository.countExpiringBetween(today, today.plusDays(7));
        // 이번 달(30일 이내) 만료 예정
        long expiringThisMonth = userRepository.countExpiringBetween(today, today.plusDays(30));

        // 최근 7일 신규 가입
        long newUsersThisWeek = userRepository.countCreatedSince(
            today.minusDays(7).atStartOfDay());
        // 최근 30일 신규 가입
        long newUsersThisMonth = userRepository.countCreatedSince(
            today.minusDays(30).atStartOfDay());

        // 만료 임박 유저 목록 (7일 이내, 상세)
        List<UserDto> expiringSoon = userRepository.findBySubscription(SubscriptionStatus.PRO)
            .stream()
            .filter(u -> u.getSubscriptionEndDate() != null
                && !u.getSubscriptionEndDate().isBefore(today)
                && !u.getSubscriptionEndDate().isAfter(today.plusDays(7)))
            .sorted(java.util.Comparator.comparing(u -> u.getSubscriptionEndDate()))
            .map(UserDto::from)
            .toList();

        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalUsers", totalUsers);
        stats.put("proCount", proCount);
        stats.put("pendingCount", pendingCount);
        stats.put("freeCount", freeCount);
        stats.put("expiringThisWeek", expiringThisWeek);
        stats.put("expiringThisMonth", expiringThisMonth);
        stats.put("newUsersThisWeek", newUsersThisWeek);
        stats.put("newUsersThisMonth", newUsersThisMonth);
        stats.put("expiringSoonList", expiringSoon);
        stats.put("asOf", today.toString());
        return stats;
    }

    // ─── 구독 만료 자동 처리 ───

    /**
     * 매일 자정(KST) 만료된 PRO 유저를 FREE로 자동 전환.
     * Scheduler가 비활성화된 환경(Render 무료 티어)에서도 항상 실행되도록
     * PrecomputeScheduler와 별개로 항상 활성화.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void expireSubscriptions() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<User> expired = userRepository.findExpiredProUsers(today);
        if (expired.isEmpty()) return;

        for (User user : expired) {
            user.setSubscription(SubscriptionStatus.FREE);
            user.setSubscriptionEndDate(null);
            userRepository.save(user);
            log.info("[Subscription] 만료 처리: userId={}, email={}", user.getId(), user.getEmail());
        }
        log.info("[Subscription] 구독 만료 일괄 처리 완료: {}명 FREE 전환", expired.size());
    }

    /**
     * 유저가 현재 유효한 PRO 구독 상태인지 확인.
     * subscriptionEndDate가 오늘 이후여야 PRO로 인정.
     */
    public boolean isActivePro(User user) {
        if (user.getSubscription() != SubscriptionStatus.PRO) return false;
        LocalDate endDate = user.getSubscriptionEndDate();
        // endDate가 null이면 관리자가 수동 부여한 무기한 PRO로 허용
        return endDate == null || !endDate.isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }

    /** 관리자: 회원 검색 (이름 또는 이메일) */
    /** 관리자 회원검색. 결과는 50건으로 캡 (응답 폭발/정보 노출 방지). */
    private static final int USER_SEARCH_LIMIT = 50;

    public List<UserDto> searchUsers(String query) {
        if (query == null) return List.of();
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return List.of();
        return userRepository.findAll().stream()
            .filter(u -> (u.getName() != null && u.getName().toLowerCase().contains(q))
                      || (u.getEmail() != null && u.getEmail().toLowerCase().contains(q)))
            .limit(USER_SEARCH_LIMIT)
            .map(UserDto::from)
            .toList();
    }
}
