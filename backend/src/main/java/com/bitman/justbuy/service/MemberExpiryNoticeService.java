package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 구독 만료 D-3 / D-1 예고를 회원 텔레그램으로 보낸다.
 *
 * <p>지금까지 회원에게 가는 알림이 하나도 없어 만료를 모른 채 서비스가 끊겼다.
 * 만료 배치(자정)는 이미 끝난 구독을 정리할 뿐 예고를 하지 않는다.
 *
 * <p><b>정확히 한 번</b> 보장: {@code expiryNoticeFor}(예고를 보낸 대상 종료일)와
 * {@code expiryNoticeStage}(마지막으로 보낸 단계)를 저장한다. 같은 종료일에 대해
 * 더 급한 단계(3 → 1)로만 다시 보내므로 재실행해도 중복 발송되지 않는다.
 * 회원이 연장하면 종료일이 바뀌어 마커가 자연히 무효가 되고 다음 주기에 다시 예고된다.
 */
@Service
public class MemberExpiryNoticeService {

    private static final Logger log = LoggerFactory.getLogger(MemberExpiryNoticeService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 예고 단계 = 남은 일수. 급한 순서(작은 수)가 뒤에 오도록 정렬해 사용한다. */
    private static final List<Integer> STAGES = List.of(3, 1);

    private final UserRepository userRepository;
    private final TelegramNotifier telegramNotifier;

    @org.springframework.beans.factory.annotation.Autowired
    public MemberExpiryNoticeService(UserRepository userRepository,
                                     org.springframework.beans.factory.ObjectProvider<TelegramNotifier> notifierProvider) {
        this.userRepository = userRepository;
        this.telegramNotifier = notifierProvider.getIfAvailable();
    }

    MemberExpiryNoticeService(UserRepository userRepository, TelegramNotifier telegramNotifier) {
        this.userRepository = userRepository;
        this.telegramNotifier = telegramNotifier;
    }

    /** 매일 09:00 KST. 만료 배치(자정) 이후라 이미 끝난 구독은 대상에서 빠져 있다. */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void sendExpiryNotices() {
        try {
            run(LocalDate.now(KST));
        } catch (Exception e) {
            log.error("[ExpiryNotice] 예고 배치 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * @param today 기준일
     * @return 단계별 발송 건수
     */
    @Transactional
    public Map<String, Integer> run(LocalDate today) {
        Map<String, Integer> sentByStage = new LinkedHashMap<>();
        STAGES.forEach(stage -> sentByStage.put("D-" + stage, 0));

        if (telegramNotifier == null) {
            log.info("[ExpiryNotice] 텔레그램 미설정 — 건너뜀");
            return sentByStage;
        }

        List<LocalDate> targetDates = STAGES.stream().map(today::plusDays).toList();
        List<User> candidates = userRepository.findExpiryNoticeTargets(targetDates);
        if (candidates.isEmpty()) return sentByStage;

        for (User user : candidates) {
            LocalDate endDate = user.getSubscriptionEndDate();
            if (endDate == null) continue;

            int stage = (int) java.time.temporal.ChronoUnit.DAYS.between(today, endDate);
            if (!STAGES.contains(stage)) continue;
            if (alreadyNotified(user, endDate, stage)) continue;

            boolean ok = telegramNotifier.sendToMember(user.getTelegramChatId(), message(user, endDate, stage));
            if (!ok) {
                // 실패 시 마커를 남기지 않아 다음 주기에 재시도된다.
                log.warn("[ExpiryNotice] 발송 실패 — 재시도 대상: userId={}, D-{}", user.getId(), stage);
                continue;
            }

            user.setExpiryNoticeFor(endDate);
            user.setExpiryNoticeStage(stage);
            userRepository.save(user);
            sentByStage.merge("D-" + stage, 1, Integer::sum);
        }

        log.info("[ExpiryNotice] 만료 예고 발송: {}", sentByStage);
        return sentByStage;
    }

    /**
     * 같은 종료일에 대해 이미 같거나 더 급한 단계를 보냈는지.
     * stage 는 남은 일수라 <b>작을수록 급하다</b>.
     */
    static boolean alreadyNotified(User user, LocalDate endDate, int stage) {
        LocalDate sentFor = user.getExpiryNoticeFor();
        Integer sentStage = user.getExpiryNoticeStage();
        if (sentFor == null || sentStage == null) return false;
        if (!sentFor.equals(endDate)) return false;   // 연장 등으로 종료일이 바뀌면 마커 무효
        return sentStage <= stage;
    }

    private static String message(User user, LocalDate endDate, int stage) {
        String rawName = user.getName() == null || user.getName().isBlank() ? "회원" : user.getName();
        // parse_mode=HTML 이므로 회원 이름을 그대로 넣으면 안 된다.
        String name = TelegramNotifier.escapeHtml(rawName);
        StringBuilder sb = new StringBuilder();
        sb.append("[JUST BUY] 이용권 만료 안내\n\n");
        sb.append(name).append("님, 월간 이용권이 ");
        sb.append(stage == 1 ? "내일" : stage + "일 뒤").append(" 만료됩니다.\n");
        sb.append("만료일: ").append(endDate).append("\n\n");
        sb.append("만료되면 단타·스윙·주도주·테마주·종가매매 결과가 잠깁니다.\n");
        sb.append("연장은 앱에서 신청하실 수 있습니다.\n");
        sb.append("https://bitman-justbuy.pages.dev/subscribe");
        return sb.toString();
    }
}
