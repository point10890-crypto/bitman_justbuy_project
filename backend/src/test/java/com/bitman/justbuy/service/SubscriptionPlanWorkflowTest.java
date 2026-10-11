package com.bitman.justbuy.service;

import com.bitman.justbuy.controller.GlobalExceptionHandler;
import com.bitman.justbuy.controller.SubscriptionController;
import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SubscriptionPlanWorkflowTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    @Mock UserRepository repository;
    @Mock PasswordEncoder encoder;
    @Mock ObjectProvider<TelegramNotifier> notifierProvider;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID userId = UUID.randomUUID();
    private SubscriptionService service;
    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User("subscriber@example.com", "subscriber", "hash");
        ReflectionTestUtils.setField(user, "id", userId);
        lenient().when(repository.findById(userId)).thenReturn(Optional.of(user));
        service = new SubscriptionService(repository, encoder, notifierProvider);
        mvc = MockMvcBuilders.standaloneSetup(new SubscriptionController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sixMonthApprovalGrantsSixCalendarMonthsAndRetainsApprovedPlan() throws Exception {
        apply("SIX_MONTHS");
        var approved = service.approveSubscription(userId);

        assertThat(approved.subscriptionEndDate()).isEqualTo(LocalDate.now(KST).plusMonths(6));
        assertThat(approved.subscription()).isEqualTo("PRO");
        assertThat(dtoJson(approved).path("subscriptionPlan").asText()).isEqualTo("SIX_MONTHS");
        assertThat(dtoJson(approved).path("requestedSubscriptionPlan").isNull()).isTrue();
        assertThat(approved.subscriptionRequestedAt()).isNull();
    }

    @Test
    void renewalPreservesAccessAndApprovedPlanThenExtendsFromExistingEndDate() throws Exception {
        approveMonthly();
        // Use a future month-end to catch accidental day-count arithmetic and loss of remaining time.
        LocalDate existingEnd = LocalDate.of(LocalDate.now(KST).getYear() + 1, 8, 31);
        user.setSubscriptionEndDate(existingEnd);
        LocalDateTime existingApproval = user.getSubscriptionApprovedAt();

        JsonNode pending = apply("SIX_MONTHS");
        assertThat(service.isActivePro(user)).isTrue();
        assertThat(user.getSubscriptionEndDate()).isEqualTo(existingEnd);
        assertThat(user.getSubscriptionApprovedAt()).isEqualTo(existingApproval);
        assertThat(pending.path("subscriptionPlan").asText()).isEqualTo("MONTHLY");
        assertThat(pending.path("requestedSubscriptionPlan").asText()).isEqualTo("SIX_MONTHS");

        var approved = service.approveSubscription(userId);
        assertThat(approved.subscriptionEndDate()).isEqualTo(existingEnd.plusMonths(6));
        assertThat(dtoJson(approved).path("subscriptionPlan").asText()).isEqualTo("SIX_MONTHS");
        assertThat(dtoJson(approved).path("requestedSubscriptionPlan").isNull()).isTrue();
    }

    @Test
    void rejectionClearsRequestedPlanButKeepsPreviouslyApprovedPlanAndAccess() throws Exception {
        approveMonthly();
        LocalDate existingEnd = user.getSubscriptionEndDate();
        LocalDateTime existingApproval = user.getSubscriptionApprovedAt();
        apply("SIX_MONTHS");

        var rejected = service.rejectSubscription(userId);
        assertThat(service.isActivePro(user)).isTrue();
        assertThat(rejected.subscription()).isEqualTo("PRO");
        assertThat(rejected.subscriptionEndDate()).isEqualTo(existingEnd);
        assertThat(rejected.subscriptionApprovedAt()).isEqualTo(existingApproval);
        assertThat(dtoJson(rejected).path("subscriptionPlan").asText()).isEqualTo("MONTHLY");
        assertThat(dtoJson(rejected).path("requestedSubscriptionPlan").isNull()).isTrue();
    }

    @Test
    void legacyPendingRowWithoutPlanStillApprovesOneMonth() {
        user.setSubscription(SubscriptionStatus.PENDING);

        var approved = service.approveSubscription(userId);
        assertThat(approved.subscriptionEndDate()).isEqualTo(LocalDate.now(KST).plusMonths(1));
        assertThat(dtoJson(approved).path("subscriptionPlan").asText()).isEqualTo("MONTHLY");
    }

    @Test
    void legacyApplicationWithoutPlanRecordsMonthlyRequest() throws Exception {
        String body = mvc.perform(post("/api/subscription/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositorName\":\"payer\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(body).path("requestedSubscriptionPlan").asText()).isEqualTo("MONTHLY");
        assertThat(service.approveSubscription(userId).subscriptionEndDate())
            .isEqualTo(LocalDate.now(KST).plusMonths(1));
    }

    @Test
    void legacyServiceSignatureKeepsMonthlyCompatibility() {
        var pending = service.applyForSubscription(userId, "payer");
        assertThat(dtoJson(pending).path("requestedSubscriptionPlan").asText()).isEqualTo("MONTHLY");
        assertThat(service.approveSubscription(userId).subscriptionEndDate())
            .isEqualTo(LocalDate.now(KST).plusMonths(1));
    }

    @Test
    void unknownPlanIsRejectedWithoutChangingMemberState() throws Exception {
        mvc.perform(post("/api/subscription/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositorName\":\"payer\",\"plan\":\"YEARLY\"}"))
            .andExpect(status().isBadRequest());
        assertThat(user.getSubscription()).isEqualTo(SubscriptionStatus.FREE);
        assertThat(user.getSubscriptionRequestedAt()).isNull();
    }

    @Test
    void unlimitedLegacyProCannotLoseAccessThroughAnUnnecessaryRenewal() {
        user.setSubscription(SubscriptionStatus.PRO);
        LocalDateTime approvedAt = LocalDateTime.of(2026, 6, 1, 12, 0);
        user.setSubscriptionApprovedAt(approvedAt);

        assertThatThrownBy(() -> service.applyForSubscription(userId, "payer"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("기간 제한 없는 이용권은 연장 신청이 필요하지 않습니다.");

        assertThat(user.getSubscription()).isEqualTo(SubscriptionStatus.PRO);
        assertThat(user.getSubscriptionEndDate()).isNull();
        assertThat(user.getSubscriptionApprovedAt()).isEqualTo(approvedAt);
        assertThat(user.getSubscriptionRequestedAt()).isNull();
        assertThat(user.getDepositorName()).isNull();
        assertThat(service.isActivePro(user)).isTrue();
        verify(repository, never()).save(user);
    }

    @Test
    void adminCannotApplyForSixMonthsOrChangeExistingProAccount() throws Exception {
        user.setRole(Role.ADMIN);
        user.setSubscription(SubscriptionStatus.PRO);
        LocalDate existingEnd = LocalDate.now(KST).plusMonths(3);
        LocalDateTime existingApproval = LocalDateTime.of(2026, 6, 1, 12, 0);
        user.setSubscriptionEndDate(existingEnd);
        user.setSubscriptionApprovedAt(existingApproval);
        user.setDepositorName("previous payer");

        String response = mvc.perform(post("/api/subscription/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"depositorName\":\"new payer\",\"plan\":\"SIX_MONTHS\"}"))
            .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(response).path("error").asText()).contains("관리자", "필요하지 않습니다");
        assertThat(user.getSubscription()).isEqualTo(SubscriptionStatus.PRO);
        assertThat(user.getSubscriptionEndDate()).isEqualTo(existingEnd);
        assertThat(user.getSubscriptionApprovedAt()).isEqualTo(existingApproval);
        assertThat(user.getSubscriptionRequestedAt()).isNull();
        assertThat(user.getRequestedSubscriptionPlan()).isNull();
        assertThat(user.getDepositorName()).isEqualTo("previous payer");
        assertThat(user.getName()).isEqualTo("subscriber");
        assertThat(user.getEmail()).isEqualTo("subscriber@example.com");
        assertThat(service.isActivePro(user)).isTrue();
        verify(repository, never()).save(user);
        verifyNoInteractions(notifierProvider);
    }

    @Test
    void adminDirectApprovalHonorsPendingPlanAndPreservesProfileEdits() throws Exception {
        approveMonthly();
        LocalDate existingEnd = user.getSubscriptionEndDate();
        apply("SIX_MONTHS");

        var approved = service.adminUpdateUser(userId, "Updated member", "updated@example.com", "PRO");

        assertThat(approved.subscriptionEndDate()).isEqualTo(existingEnd.plusMonths(6));
        assertThat(approved.subscription()).isEqualTo("PRO");
        assertThat(dtoJson(approved).path("subscriptionPlan").asText()).isEqualTo("SIX_MONTHS");
        assertThat(dtoJson(approved).path("requestedSubscriptionPlan").isNull()).isTrue();
        assertThat(approved.subscriptionRequestedAt()).isNull();
        assertThat(approved.name()).isEqualTo("Updated member");
        assertThat(approved.email()).isEqualTo("updated@example.com");
    }

    @Test
    void adminCancellationClearsPendingPlanWithoutErasingApprovedPlan() throws Exception {
        approveMonthly();
        apply("SIX_MONTHS");

        var cancelled = service.adminUpdateUser(userId, null, null, "FREE");

        assertThat(cancelled.subscription()).isEqualTo("FREE");
        assertThat(dtoJson(cancelled).path("requestedSubscriptionPlan").isNull()).isTrue();
        assertThat(dtoJson(cancelled).path("subscriptionPlan").asText()).isEqualTo("MONTHLY");
        assertThat(cancelled.subscriptionRequestedAt()).isNull();
    }

    @Test
    void adminProfileEditWithUnchangedPendingStatusPreservesRequestAndAccessWindow() throws Exception {
        approveMonthly();
        LocalDate existingEnd = user.getSubscriptionEndDate();
        apply("SIX_MONTHS");
        LocalDateTime requestedAt = user.getSubscriptionRequestedAt();

        var edited = service.adminUpdateUser(userId, "Updated member", "updated@example.com", "PENDING");

        assertThat(edited.subscriptionEndDate()).isEqualTo(existingEnd);
        assertThat(edited.subscriptionRequestedAt()).isEqualTo(requestedAt);
        assertThat(service.isActivePro(user)).isTrue();
        assertThat(dtoJson(edited).path("requestedSubscriptionPlan").asText()).isEqualTo("SIX_MONTHS");
        assertThat(edited.name()).isEqualTo("Updated member");
        assertThat(edited.email()).isEqualTo("updated@example.com");
    }

    private void approveMonthly() {
        service.applyForSubscription(userId, "payer");
        service.approveSubscription(userId);
    }

    private JsonNode apply(String plan) throws Exception {
        String body = mvc.perform(post("/api/subscription/apply").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("depositorName", "payer", "plan", plan))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private JsonNode dtoJson(UserDto dto) {
        return mapper.valueToTree(dto);
    }
}
