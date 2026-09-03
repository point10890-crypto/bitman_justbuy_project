package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.performance.DailyClosePerformanceResponse;
import com.bitman.justbuy.dto.performance.MemberTrackRecordResponse;
import com.bitman.justbuy.dto.performance.SwingCumulativePerformanceResponse;
import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.SubscriptionService;
import com.bitman.justbuy.service.MemberTrackRecordService;
import com.bitman.justbuy.service.TrackRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

@RestController
@RequestMapping("/api/performance")
public class PerformanceController {

    private final TrackRecordService trackRecordService;
    private final UserRepository userRepository;
    private final SubscriptionAccessGuard subscriptionAccessGuard;
    private final MemberTrackRecordService memberTrackRecordService;

    public PerformanceController(TrackRecordService trackRecordService,
                                 UserRepository userRepository,
                                 SubscriptionAccessGuard subscriptionAccessGuard,
                                 MemberTrackRecordService memberTrackRecordService) {
        this.trackRecordService = trackRecordService;
        this.userRepository = userRepository;
        this.subscriptionAccessGuard = subscriptionAccessGuard;
        this.memberTrackRecordService = memberTrackRecordService;
    }

    /**
     * 회원용 모드별 트랙레코드 — 승률·평균수익·시장 대비 초과수익.
     *
     * <p>성과 데이터는 쌓여 있는데 회원에게 보이는 곳이 종가매매 히스토리뿐이었다.
     * "왜 믿어야 하는가"에 답하는 화면의 데이터 소스다.
     */
    @GetMapping("/track-record")
    public ResponseEntity<MemberTrackRecordResponse> trackRecord(
        @AuthenticationPrincipal UUID userId,
        @RequestParam(required = false) Integer days
    ) {
        requireProSubscription(userId);
        return ResponseEntity.ok(memberTrackRecordService.getTrackRecord(days));
    }

    @GetMapping("/short-term/daily-close")
    public ResponseEntity<DailyClosePerformanceResponse> shortTermDailyClose(
        @AuthenticationPrincipal UUID userId,
        @RequestParam(required = false) LocalDate date
    ) {
        requireProSubscription(userId);
        return ResponseEntity.ok(trackRecordService.getShortTermDailyClose(date, false));
    }

    @PostMapping("/short-term/daily-close/refresh")
    public ResponseEntity<DailyClosePerformanceResponse> refreshShortTermDailyClose(
        @AuthenticationPrincipal UUID userId,
        @RequestParam(required = false) LocalDate date
    ) {
        requireAdmin(userId);
        validateShortTermRefreshWindow(date);
        return ResponseEntity.ok(trackRecordService.getShortTermDailyClose(date, true));
    }

    @GetMapping("/swing/cumulative")
    public ResponseEntity<SwingCumulativePerformanceResponse> swingCumulative(
        @AuthenticationPrincipal UUID userId,
        @RequestParam(required = false) LocalDate from,
        @RequestParam(required = false) LocalDate to
    ) {
        requireProSubscription(userId);
        return ResponseEntity.ok(trackRecordService.getSwingCumulative(from, to));
    }

    private void requireProSubscription(UUID userId) {
        subscriptionAccessGuard.requirePro(userId);
    }

    private void requireAdmin(UUID userId) {
        var user = userRepository.findById(userId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다."));
        if (user.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 성과 검증을 새로고침할 수 있습니다.");
        }
    }

    private void validateShortTermRefreshWindow(LocalDate date) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate targetDate = date != null ? date : today;
        if (targetDate.isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "과거 날짜는 저장된 검증 결과만 조회할 수 있습니다.");
        }
        if (targetDate.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "미래 날짜는 성과 검증을 새로고침할 수 없습니다.");
        }
        if (LocalTime.now(ZoneId.of("Asia/Seoul")).isBefore(LocalTime.of(15, 30))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "단타 마감 성과 검증은 장마감 이후 가능합니다.");
        }
    }
}
