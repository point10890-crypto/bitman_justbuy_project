package com.bitman.justbuy.controller;

import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * PRO 구독 접근 가드 — 단일 출처.
 *
 * <p>같은 판정이 4개 컨트롤러(analysis, deepseek, condition, performance)에 복붙돼 있었고
 * 문구도 조금씩 달랐다. 여기로 모아 응답 문구와 <b>에러 코드</b>를 통일한다.
 *
 * <p>코드를 내려주는 이유: 프론트가 "만료돼서 막힌 것"과 "애초에 구독한 적 없는 것"을
 * 구분해 재구독 페이지로 보낼지 신규 구독 페이지로 보낼지 정해야 하는데, 한국어 문구를
 * 파싱하는 방식은 문구가 바뀌는 순간 깨진다.
 */
@Component
public class SubscriptionAccessGuard {

    /** 구독 기간이 끝났다. 재구독(연장) 대상. */
    public static final String CODE_EXPIRED = "SUBSCRIPTION_EXPIRED";
    /** 신청은 했고 관리자 승인 대기 중. */
    public static final String CODE_PENDING = "SUBSCRIPTION_PENDING";
    /** 구독 이력이 없다. 신규 구독 대상. */
    public static final String CODE_REQUIRED = "SUBSCRIPTION_REQUIRED";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final SubscriptionService subscriptionService;

    public SubscriptionAccessGuard(UserRepository userRepository,
                                   SubscriptionService subscriptionService) {
        this.userRepository = userRepository;
        this.subscriptionService = subscriptionService;
    }

    /**
     * 유효한 PRO 구독이 아니면 403 을 던진다.
     *
     * @throws ApiException 401(사용자 없음) 또는 403(구독 필요/대기/만료)
     */
    public void requirePro(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));

        if (subscriptionService.isActivePro(user)) return;

        if (user.getSubscription() == SubscriptionStatus.PENDING) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                "구독 승인 대기 중입니다. 입금 확인 후 관리자가 승인합니다.", CODE_PENDING);
        }

        if (hasExpiredSubscription(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                "PRO 구독이 만료되었습니다. 재구독 신청을 해주세요.", CODE_EXPIRED);
        }

        throw new ApiException(HttpStatus.FORBIDDEN,
            "PRO 구독자만 사용 가능합니다.", CODE_REQUIRED);
    }

    /**
     * 과거에 구독했고 기간이 끝난 회원인지.
     *
     * <p>만료 배치 전에는 {@code PRO + 지난 종료일}, 배치 후에는 {@code FREE + 지난 종료일} 이다.
     * 배치가 종료일을 지우면 둘을 구분할 수 없어 재구독 안내를 못 하므로
     * {@code SubscriptionService} 는 만료 시에도 종료일을 보존한다.
     */
    static boolean hasExpiredSubscription(User user) {
        LocalDate endDate = user.getSubscriptionEndDate();
        if (endDate == null) return false;
        if (user.getSubscription() == SubscriptionStatus.PENDING) return false;
        return endDate.isBefore(LocalDate.now(KST));
    }
}
