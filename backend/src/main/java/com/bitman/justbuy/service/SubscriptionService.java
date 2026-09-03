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

        // 활성 PRO는 기존 이용권을 유지한 채 연장 승인 대기로 전환한다.
        user.setSubscription(SubscriptionStatus.PENDING);
        user.setDepositorName(normalizedDepositorName);
        user.setSubscriptionRequestedAt(LocalDateTime.now(KST));
        if (!keepCurrentAccess) {
            // 구독 이력(종료일·승인일)은 지우지 않는다. 지우면 재구독 신청이 신규 가입과
            // 구별되지 않아 관리자 대기 목록에도 이력 없는 신규 신청으로 뜨고,
            // 반려되면 NONE 으로 떨어져 그 뒤로 계속 신규 구독 흐름을 타게 된다.
            //
            // 다만 남은 이용 기간이 생기면 안 된다. PENDING + 종료일이 오늘 이후면
            // isActivePro 가 접근을 열어주므로, 과거 데이터에 미래 종료일이 남아 있다면
            // 어제로 당겨 무료 접근이 새지 않게 막는다.
            LocalDate today = LocalDate.now(KST);
            LocalDate endDate = user.getSubscriptionEndDate();
            if (endDate != null && !endDate.isBefore(today)) {
                user.setSubscriptionEndDate(today.minusDays(1));
            }
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

    /**
     * 회원 텔레그램 연결. 숫자 chat id 만 허용한다.
     *
     * <p>회원이 봇에게 말을 걸어 받은 chat id 를 그대로 입력하는 방식이다.
     * 형식 검증을 두는 이유는 잘못된 값이 저장되면 매일 발송이 조용히 실패하기 때문이다.
     */
    @Transactional
    public UserDto linkTelegram(UUID userId, String chatId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        String normalized = chatId == null ? "" : chatId.trim();
        if (!normalized.matches("[1-9]\\d{4,19}")) {
            // 음수는 그룹/채널 id 다. 허용하면 봇이 단체 채팅에 글을 쓰게 만들 수 있고,
            // 개인 만료 안내가 여러 사람에게 노출된다. 개인 chat id(양수)만 받는다.
            throw new IllegalArgumentException("텔레그램 개인 chat id 형식이 올바르지 않습니다. 양수 숫자만 입력해 주세요.");
        }

        // 소유 증명은 아직 없다. 최소한 한 chat id 가 여러 계정에 붙는 것은 막아
        // 타인 chat id 를 등록해 그 사람에게 내 만료 안내가 가는 상황을 방지한다.
        for (User other : userRepository.findByTelegramChatId(normalized)) {
            if (!userId.equals(other.getId())) {   // other 가 아직 id 없는 전이 상태일 수 있다
                throw new IllegalArgumentException("이미 다른 계정에 연결된 텔레그램 ID 입니다.");
            }
        }

        user.setTelegramChatId(normalized);
        // 연결 직후 예고 마커는 비운다 — 연결 전에 지나간 단계를 놓치지 않도록.
        user.setExpiryNoticeFor(null);
        user.setExpiryNoticeStage(null);
        userRepository.save(user);
        log.info("[Subscription] 텔레그램 연결: userId={}", userId);
        return UserDto.from(user);
    }

    /** 회원 텔레그램 연결 해제. 이후 알림 대상에서 빠진다. */
    @Transactional
    public UserDto unlinkTelegram(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        user.setTelegramChatId(null);
        user.setExpiryNoticeFor(null);
        user.setExpiryNoticeStage(null);
        userRepository.save(user);
        log.info("[Subscription] 텔레그램 해제: userId={}", userId);
        return UserDto.from(user);
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
            throw new IllegalStateException("관리자 계정은 항상 PRO 이므로 승인 처리할 수 없습니다.");
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

        // approve/revoke 와 동일한 보호 — 관리자 계정만 이 가드가 빠져 있었다.
        if (user.getRole() == Role.ADMIN) {
            throw new IllegalStateException("관리자 계정의 구독 신청은 반려할 수 없습니다.");
        }

        if (user.getSubscription() != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("승인 대기 상태가 아닙니다.");
        }

        user.setDepositorName(null);
        user.setSubscriptionRequestedAt(null);
        if (hasPendingRenewalAccess(user, LocalDate.now(KST))) {
            user.setSubscription(SubscriptionStatus.PRO);
        } else {
            // 반려해도 구독 이력은 남긴다. 지우면 과거 회원이 "구독한 적 없음"으로 떨어져
            // 그 뒤로 재구독이 아니라 신규 구독 흐름을 타게 된다. 접근은 FREE 가 막고,
            // 종료일은 이미 과거이므로 남겨도 권한이 새지 않는다.
            user.setSubscription(SubscriptionStatus.FREE);
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
        // 접근은 subscription=FREE 가 막는다. 종료일/승인일은 지우지 않고 "오늘 끝났다"로
        // 기록한다 — 지우면 이 회원이 EXPIRED 가 아니라 NONE 으로 분류돼
        // 재구독이 아니라 신규 구독 유도로 새어나간다.
        user.setSubscriptionEndDate(LocalDate.now(KST));
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
                user.setDepositorName(null);
                // revoke 와 같은 이유로 구독 이력(종료일·승인일)은 보존한다.
                // 이력이 있는 회원만 종료일을 오늘로 당겨 "여기서 끝났다"를 남긴다.
                if (user.getSubscriptionApprovedAt() != null || user.getSubscriptionEndDate() != null) {
                    LocalDate today = LocalDate.now(KST);
                    if (user.getSubscriptionEndDate() == null || user.getSubscriptionEndDate().isAfter(today)) {
                        user.setSubscriptionEndDate(today);
                    }
                }
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
        // 관리자 초기화는 계정 탈취 대응인 경우가 많다. 기존 세션을 반드시 끊는다.
        user.setTokenValidFrom(LocalDateTime.now(KST));
        userRepository.save(user);
        log.info("Admin reset password: userId={}", userId);
    }

    // ─── 구독 통계 ───

    public Map<String, Object> getSubscriptionStats() {
        LocalDate today = LocalDate.now(KST);

        // 회원 목록은 한 번만 읽는다. 이전에는 같은 findAll() 을 세 번 돌렸다.
        List<User> allUsers = userRepository.findAll();

        // 버킷은 겹치면 안 된다. 연장 신청한 기존 PRO 는 상태가 PENDING 이면서 접근은
        // 살아 있어 isActivePro/findBySubscription 을 따로 쓰면 양쪽에 중복 계상됐다.
        long proCount     = allUsers.stream().filter(u -> tierOf(u) == MemberTier.ACTIVE).count();
        long pendingCount = allUsers.stream().filter(u -> tierOf(u) == MemberTier.PENDING).count();
        long totalUsers   = allUsers.size();
        long freeCount    = totalUsers - proCount - pendingCount;

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
        List<UserDto> expiringSoon = allUsers
            .stream()
            .filter(this::isActivePro)
            .filter(u -> u.getSubscriptionEndDate() != null
                && !u.getSubscriptionEndDate().isBefore(today)
                && !u.getSubscriptionEndDate().isAfter(today.plusDays(7)))
            .sorted(java.util.Comparator.comparing(u -> u.getSubscriptionEndDate()))
            .map(UserDto::from)
            .toList();

        // freeCount 는 "한 번도 구독 안 함"과 "만료됨"이 섞여 있어 유도 대상 파악에 쓸 수 없다.
        // 티어로 갈라서 각각의 모수를 준다.
        List<User> members = allUsers.stream()
            .filter(u -> u.getRole() != Role.ADMIN)
            .toList();
        long neverSubscribedCount = members.stream().filter(u -> tierOf(u) == MemberTier.NONE).count();
        long expiredCount = members.stream().filter(u -> tierOf(u) == MemberTier.EXPIRED).count();
        long convertibleCount = neverSubscribedCount + expiredCount;

        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalUsers", totalUsers);
        stats.put("proCount", proCount);
        stats.put("pendingCount", pendingCount);
        stats.put("freeCount", freeCount);
        stats.put("neverSubscribedCount", neverSubscribedCount);
        stats.put("expiredCount", expiredCount);
        stats.put("convertibleCount", convertibleCount);
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
                // 종료일/승인일은 남긴다. 지우면 "구독한 적 있는데 만료된 회원"과
                // "구독한 적 없는 회원"을 구분할 수 없어 재구독 안내를 못 한다.
                // 접근 권한은 subscription 상태(FREE)가 막으므로 남겨도 안전하다.
                userRepository.save(user);
                log.info("[Subscription] 만료 처리: userId={}, email={}, 종료일={}",
                    user.getId(), user.getEmail(), user.getSubscriptionEndDate());
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
     * 회원 티어 판정 — 접근 제어와 통계가 같은 기준을 쓰도록 여기 하나로 모은다.
     *
     * <p>만료 배치가 종료일을 보존하므로, 배치 전({@code PRO} + 지난 종료일)과
     * 배치 후({@code FREE} + 지난 종료일)가 모두 {@link MemberTier#EXPIRED} 로 잡힌다.
     */
    public MemberTier tierOf(User user) {
        if (user == null) return MemberTier.NONE;
        if (isActivePro(user)) return MemberTier.ACTIVE;
        if (user.getSubscription() == SubscriptionStatus.PENDING) return MemberTier.PENDING;
        if (hasSubscriptionHistory(user)) return MemberTier.EXPIRED;
        return MemberTier.NONE;
    }

    /**
     * 한 번이라도 구독이 승인된 적이 있는지 — EXPIRED 와 NONE 을 가르는 단일 기준.
     *
     * <p>종료일만 보면 관리자가 오늘 해제한 회원(종료일 = 오늘)을 놓친다.
     * 승인 이력을 함께 보면 만료 배치·해제·강등 어느 경로로 끝났든 같게 잡힌다.
     */
    public static boolean hasSubscriptionHistory(User user) {
        if (user == null) return false;
        return user.getSubscriptionApprovedAt() != null || user.getSubscriptionEndDate() != null;
    }

    /** 가입만 하고 한 번도 구독한 적 없는 회원(NO티어). 신규 구독 유도 대상. */
    public List<UserDto> getNeverSubscribedUsers() {
        return userRepository.findAll().stream()
            .filter(user -> user.getRole() != Role.ADMIN)
            .filter(user -> tierOf(user) == MemberTier.NONE)
            .sorted(Comparator.comparing(User::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(UserDto::from)
            .toList();
    }

    /** 구독했다가 만료된 회원. 재구독 유도 대상. */
    public List<UserDto> getExpiredUsers() {
        return userRepository.findAll().stream()
            .filter(user -> user.getRole() != Role.ADMIN)
            .filter(user -> tierOf(user) == MemberTier.EXPIRED)
            .sorted(Comparator.comparing(User::getSubscriptionEndDate,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(UserDto::from)
            .toList();
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
