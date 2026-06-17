package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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

    @Transactional
    public UserDto applyForSubscription(UUID userId, String depositorName) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        SubscriptionStatus previousStatus = user.getSubscription();
        String normalizedDepositorName = depositorName == null ? "" : depositorName.trim();
        if (normalizedDepositorName.isBlank()) {
            throw new IllegalArgumentException("입금자명을 입력해주세요.");
        }

        if (user.getSubscription() == SubscriptionStatus.PENDING) {
            throw new IllegalStateException("이미 구독 신청이 접수되었습니다. 관리자 승인을 기다려주세요.");
        }

        boolean keepCurrentAccess = isActivePro(user);

        // 만료된 PRO/FREE는 신규 신청, 활성 PRO는 기존 이용권을 유지한 채 연장 승인 대기로 전환한다.
        user.setSubscription(SubscriptionStatus.PENDING);
        user.setDepositorName(normalizedDepositorName);
        user.setSubscriptionRequestedAt(LocalDateTime.now(KST));
        if (!keepCurrentAccess) {
            user.setSubscriptionEndDate(null);
            user.setSubscriptionApprovedAt(null);
        }
        userRepository.save(user);
        log.info("Subscription applied: userId={}, depositor={}, previousStatus={}",
            userId, normalizedDepositorName, previousStatus);

        // 관리자 텔레그램 알림 (실패해도 신청 자체는 성공 처리)
        try {
            TelegramNotifier notifier = telegramNotifierProvider.getIfAvailable();
            if (notifier != null) {
                long pendingCount = userRepository.findBySubscription(SubscriptionStatus.PENDING).size();
                String msg = String.format(
                    "🔔 <b>[JUST BUY] 신규 구독 승인 요청</b>%n%n"
                    + "👤 이름: %s%n"
                    + "📧 이메일: %s%n"
                    + "💳 입금자명: %s%n"
                    + "🆔 userId: <code>%s</code>%n%n"
                    + "📋 대기 중: %d건%n"
                    + "👉 관리자 페이지에서 승인/거절 처리하세요.",
                    escape(user.getName()),
                    escape(user.getEmail()),
                    escape(normalizedDepositorName),
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
            .sorted(newestApprovalRequestFirst())
            .map(UserDto::from)
            .toList();
    }

    private static Comparator<User> newestApprovalRequestFirst() {
        return Comparator
            .comparing(SubscriptionService::approvalRequestTime, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed()
            .thenComparing(User::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    private static LocalDateTime approvalRequestTime(User user) {
        LocalDateTime requestedAt = user.getSubscriptionRequestedAt();
        return requestedAt != null ? requestedAt : user.getCreatedAt();
    }

    @Transactional
    public UserDto approveSubscription(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (user.getRole() == Role.ADMIN) {
            throw new IllegalStateException("관리자 계정의 구독 상태는 반려할 수 없습니다.");
        }

        if (user.getSubscription() != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태가 아닙니다.");
        }

        LocalDate today = LocalDate.now(KST);
        LocalDate baseDate = user.getSubscriptionEndDate() != null && !user.getSubscriptionEndDate().isBefore(today)
            ? user.getSubscriptionEndDate()
            : today;

        user.setSubscription(SubscriptionStatus.PRO);
        user.setSubscriptionEndDate(baseDate.plusMonths(1));
        user.setSubscriptionApprovedAt(LocalDateTime.now(KST));
        user.setSubscriptionRequestedAt(null);
        userRepository.save(user);
        log.info("Subscription approved: userId={}", userId);

        return UserDto.from(user);
    }

    @Transactional
    public UserDto rejectSubscription(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (user.getSubscription() != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태가 아닙니다.");
        }

        user.setDepositorName(null);
        user.setSubscriptionRequestedAt(null);
        if (hasPendingRenewalAccess(user, LocalDate.now(KST))) {
            user.setSubscription(SubscriptionStatus.PRO);
        } else {
            user.setSubscription(SubscriptionStatus.FREE);
            user.setSubscriptionEndDate(null);
            user.setSubscriptionApprovedAt(null);
        }
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

    @Transactional
    public UserDto revokeSubscription(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (user.getRole() == Role.ADMIN) {
            throw new IllegalStateException("관리자 계정의 구독 상태는 해제할 수 없습니다.");
        }

        if (user.getSubscription() != SubscriptionStatus.PRO) {
            throw new IllegalStateException("PRO 구독 상태가 아닙니다.");
        }

        user.setSubscription(SubscriptionStatus.FREE);
        user.setSubscriptionEndDate(null);
        user.setSubscriptionApprovedAt(null);
        user.setSubscriptionRequestedAt(null);
        userRepository.save(user);
        log.info("Subscription revoked: userId={}", userId);

        return UserDto.from(user);
    }

    // ─── 관리자 회원관리 ───

    /** 관리자: 회원 정보 수정 (이름, 이메일, 구독상태) */
    @Transactional
    public UserDto adminUpdateUser(UUID userId, String name, String email, String subscription) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (name != null && !name.isBlank()) {
            user.setName(name.trim());
        }

        if (email != null && !email.isBlank()) {
            String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
            userRepository.findAllByEmailIgnoreCase(normalizedEmail).forEach(existing -> {
                if (!existing.getId().equals(userId)) {
                    throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
                }
            });
            user.setEmail(normalizedEmail);
        }

        if (subscription != null && !subscription.isBlank()) {
            SubscriptionStatus newStatus;
            try {
                newStatus = SubscriptionStatus.valueOf(subscription);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("올바르지 않은 구독 상태입니다: " + subscription);
            }
            if (user.getRole() == Role.ADMIN && newStatus != SubscriptionStatus.PRO) {
                throw new IllegalStateException("관리자 계정은 항상 PRO 상태여야 합니다.");
            }
            user.setSubscription(newStatus);
            if (newStatus == SubscriptionStatus.PRO) {
                user.setSubscriptionRequestedAt(null);
                if (user.getRole() == Role.ADMIN) {
                    user.setSubscriptionEndDate(null);
                } else if (user.getSubscriptionEndDate() == null
                    || user.getSubscriptionEndDate().isBefore(LocalDate.now(KST))) {
                    user.setSubscriptionEndDate(LocalDate.now(KST).plusMonths(1));
                }
            }
            if (newStatus == SubscriptionStatus.PRO && user.getSubscriptionApprovedAt() == null) {
                user.setSubscriptionApprovedAt(LocalDateTime.now(KST));
            }
            if (newStatus == SubscriptionStatus.PENDING) {
                user.setSubscriptionRequestedAt(LocalDateTime.now(KST));
                user.setSubscriptionEndDate(null);
                user.setSubscriptionApprovedAt(null);
            }
            if (newStatus == SubscriptionStatus.FREE) {
                user.setSubscriptionRequestedAt(null);
                user.setSubscriptionEndDate(null);
                user.setSubscriptionApprovedAt(null);
                user.setDepositorName(null);
            }
        }

        userRepository.save(user);
        return UserDto.from(user);
    }

    @Transactional
    public void adminResetPassword(UUID userId, String newPassword) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Admin reset password: userId={}", userId);
    }

    // ─── 구독 통계 ───

    public Map<String, Object> getSubscriptionStats() {
        LocalDate today = LocalDate.now(KST);

        long proCount     = userRepository.findAll().stream().filter(this::isActivePro).count();
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
        List<UserDto> expiringSoon = userRepository.findAll()
            .stream()
            .filter(this::isActivePro)
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
    @Transactional
    public void expireSubscriptions() {
        try {
            expireExpiredProUsers(LocalDate.now(KST));
        } catch (Exception e) {
            log.error("[Subscription] 만료 배치 실패 — cron skipped: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> expireSubscriptionsNow() {
        return expireExpiredProUsers(LocalDate.now(KST));
    }

    private Map<String, Object> expireExpiredProUsers(LocalDate today) {
        List<User> expired = userRepository.findExpiredProUsers(today);
        if (expired.isEmpty()) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("status", "ok");
            result.put("asOf", today.toString());
            result.put("targetCount", 0);
            result.put("expiredCount", 0);
            result.put("failedCount", 0);
            return result;
        }

        int ok = 0;
        int failed = 0;
        for (User user : expired) {
            try {
                if (user.getRole() == Role.ADMIN) {
                    log.warn("[Subscription] 관리자 계정은 만료 처리에서 제외: userId={}, email={}", user.getId(), user.getEmail());
                    continue;
                }
                user.setSubscription(SubscriptionStatus.FREE);
                user.setSubscriptionEndDate(null);
                user.setSubscriptionApprovedAt(null);
                userRepository.save(user);
                log.info("[Subscription] 만료 처리: userId={}, email={}", user.getId(), user.getEmail());
                ok++;
            } catch (Exception e) {
                // 단일 유저 실패가 전체 배치를 중단시키지 않도록 격리
                failed++;
                log.error("[Subscription] 만료 처리 실패 userId={}, email={}: {}",
                    user.getId(), user.getEmail(), e.getMessage(), e);
            }
        }
        log.info("[Subscription] 구독 만료 일괄 처리 완료: 성공 {}명, 실패 {}명 (대상 {}명)",
            ok, failed, expired.size());

        // 실패가 있으면 관리자에게 텔레그램 알림 (사일런트 실패 방지)
        if (failed > 0) {
            try {
                TelegramNotifier notifier = telegramNotifierProvider.getIfAvailable();
                if (notifier != null) {
                    notifier.sendToAdmin(String.format(
                        "⚠️ <b>구독 만료 배치 부분 실패</b>%n%n"
                        + "성공: %d명 / 실패: %d명 (대상 %d명)%n"
                        + "로그를 확인해주세요.",
                        ok, failed, expired.size()));
                }
            } catch (Exception ignore) { /* 알림 실패는 조용히 무시 */ }
        }

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", failed == 0 ? "ok" : "partial");
        result.put("asOf", today.toString());
        result.put("targetCount", expired.size());
        result.put("expiredCount", ok);
        result.put("failedCount", failed);
        return result;
    }

    /**
     * 유저가 현재 유효한 PRO 구독 상태인지 확인.
     * subscriptionEndDate가 오늘 이후여야 PRO로 인정.
     */
    public boolean isActivePro(User user) {
        if (user.getRole() == Role.ADMIN) return true;
        LocalDate today = LocalDate.now(KST);
        if (user.getSubscription() == SubscriptionStatus.PRO) {
            return hasProAccessWindow(user, today);
        }
        if (user.getSubscription() == SubscriptionStatus.PENDING) {
            return hasPendingRenewalAccess(user, today);
        }
        return false;
    }

    private boolean hasProAccessWindow(User user, LocalDate today) {
        LocalDate endDate = user.getSubscriptionEndDate();
        return endDate == null || !endDate.isBefore(today);
    }

    private boolean hasPendingRenewalAccess(User user, LocalDate today) {
        LocalDate endDate = user.getSubscriptionEndDate();
        return endDate != null && !endDate.isBefore(today);
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
