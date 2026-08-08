package com.bitman.justbuy.security;

import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import com.bitman.justbuy.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 만료 회원이 재구독 안내를 받기까지의 서버 계약을 실제로 태워본다.
 *
 * <p>단위 테스트는 각 조각만 봤다. 여기서는 진짜 회원을 저장하고, 진짜 토큰을 발급받아,
 * 진짜 시큐리티 체인과 가드를 통과시켜 프론트가 분기 근거로 쓰는 <b>코드</b>가 실제로
 * 응답 본문에 실려 나오는지 확인한다. 코드가 없으면 클라이언트는 만료와 미구독을
 * 구분할 수 없어 재구독 페이지 대신 신규 구독 페이지로 보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExpiredMemberRenewalFlowTest {

    private static final String GUARDED_ENDPOINT = "/api/conditions/capture-times";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;

    private String tokenFor(User user) {
        return jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name(), false);
    }

    private User save(String email, SubscriptionStatus status, LocalDate endDate, LocalDateTime approvedAt) {
        User user = new User(email + "-" + UUID.randomUUID() + "@test.local", "member", "hash");
        user.setRole(Role.USER);
        user.setSubscription(status);
        user.setSubscriptionEndDate(endDate);
        user.setSubscriptionApprovedAt(approvedAt);
        return userRepository.save(user);
    }

    @Test
    void lapsedMemberIsToldToRenewNotToSubscribeFromScratch() throws Exception {
        // 자정 배치가 PRO -> FREE 로 내렸고 종료일은 보존된 상태.
        User lapsed = save("lapsed", SubscriptionStatus.FREE,
            LocalDate.now().minusDays(3), LocalDateTime.now().minusMonths(1));

        mockMvc.perform(get(GUARDED_ENDPOINT).header("Authorization", "Bearer " + tokenFor(lapsed)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXPIRED"))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("재구독")));
    }

    @Test
    void memberStillMarkedProButPastTheirEndDateAlsoGetsTheRenewalCode() throws Exception {
        // 배치가 아직 돌기 전 시점.
        User justLapsed = save("just-lapsed", SubscriptionStatus.PRO,
            LocalDate.now().minusDays(1), LocalDateTime.now().minusMonths(1));

        mockMvc.perform(get(GUARDED_ENDPOINT).header("Authorization", "Bearer " + tokenFor(justLapsed)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXPIRED"));
    }

    @Test
    void adminRevokedMemberAlsoLandsInTheRenewalFunnel() throws Exception {
        // 관리자가 오늘 해제 — 종료일이 오늘로 남는다. 이 경우가 예전에 NONE 으로
        // 잘못 분류돼 신규 구독 페이지로 새어나갔다.
        User revoked = save("revoked", SubscriptionStatus.FREE,
            LocalDate.now(), LocalDateTime.now().minusMonths(1));

        mockMvc.perform(get(GUARDED_ENDPOINT).header("Authorization", "Bearer " + tokenFor(revoked)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXPIRED"));
    }

    @Test
    void memberWhoNeverSubscribedGetsTheNewSubscriptionCodeInstead() throws Exception {
        User newcomer = save("newcomer", SubscriptionStatus.FREE, null, null);

        mockMvc.perform(get(GUARDED_ENDPOINT).header("Authorization", "Bearer " + tokenFor(newcomer)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"));
    }

    @Test
    void firstTimeApplicantWaitingForApprovalGetsThePendingCode() throws Exception {
        User applicant = save("applicant", SubscriptionStatus.PENDING, null, null);

        mockMvc.perform(get(GUARDED_ENDPOINT).header("Authorization", "Bearer " + tokenFor(applicant)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_PENDING"));
    }

    @Test
    void activeMemberIsNotSentToTheRenewalPage() throws Exception {
        User active = save("active", SubscriptionStatus.PRO,
            LocalDate.now().plusDays(10), LocalDateTime.now().minusDays(20));

        mockMvc.perform(get(GUARDED_ENDPOINT).header("Authorization", "Bearer " + tokenFor(active)))
            .andExpect(status().isOk());
    }

    @Test
    void renewalApplicantKeepsAccessWhileTheirPaidWindowLasts() throws Exception {
        // 연장 신청한 기존 구독자 — 남은 기간 동안 막으면 안 된다.
        User renewing = save("renewing", SubscriptionStatus.PENDING,
            LocalDate.now().plusDays(5), LocalDateTime.now().minusMonths(1));

        mockMvc.perform(get(GUARDED_ENDPOINT).header("Authorization", "Bearer " + tokenFor(renewing)))
            .andExpect(status().isOk());
    }
}
