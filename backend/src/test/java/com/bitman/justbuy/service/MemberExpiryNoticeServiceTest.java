package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 만료 D-3 / D-1 예고 — 핵심은 "정확히 한 번" 이다.
 * 중복 발송은 회원에게 스팸이 되고, 누락은 조용한 이탈로 이어진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberExpiryNoticeServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);

    @Mock UserRepository repository;
    @Mock TelegramNotifier notifier;

    private MemberExpiryNoticeService service() {
        when(notifier.sendToMember(anyString(), anyString())).thenReturn(true);
        return new MemberExpiryNoticeService(repository, notifier);
    }

    @Test
    void sendsBothStagesToTheRightMembers() {
        User d3 = member("111", TODAY.plusDays(3));
        User d1 = member("222", TODAY.plusDays(1));
        when(repository.findExpiryNoticeTargets(any())).thenReturn(List.of(d3, d1));

        Map<String, Integer> sent = service().run(TODAY);

        assertThat(sent).containsEntry("D-3", 1).containsEntry("D-1", 1);
        verify(notifier).sendToMember(eq("111"), contains("3일 뒤"));
        verify(notifier).sendToMember(eq("222"), contains("내일"));
    }

    @Test
    void doesNotSendTwiceForTheSameStage() {
        User user = member("111", TODAY.plusDays(3));
        when(repository.findExpiryNoticeTargets(any())).thenReturn(List.of(user));

        MemberExpiryNoticeService svc = service();
        svc.run(TODAY);
        svc.run(TODAY);   // 같은 날 재실행 (배치 재시도 상황)

        verify(notifier, times(1)).sendToMember(anyString(), anyString());
    }

    @Test
    void stillSendsTheMoreUrgentStageAfterTheEarlierOne() {
        User user = member("111", TODAY.plusDays(3));
        when(repository.findExpiryNoticeTargets(any())).thenReturn(List.of(user));
        MemberExpiryNoticeService svc = service();

        svc.run(TODAY);                       // D-3 발송
        // 이틀 뒤: 같은 종료일이지만 D-1 이므로 다시 보내야 한다
        Map<String, Integer> sent = svc.run(TODAY.plusDays(2));

        assertThat(sent).containsEntry("D-1", 1);
        verify(notifier, times(2)).sendToMember(anyString(), anyString());
    }

    @Test
    void renewalResetsTheMarkerSoTheNextCycleWarnsAgain() {
        User user = member("111", TODAY.plusDays(3));
        user.setExpiryNoticeFor(TODAY.plusDays(3));
        user.setExpiryNoticeStage(3);
        // 연장으로 종료일이 바뀌면 옛 마커는 무효여야 한다
        user.setSubscriptionEndDate(TODAY.plusDays(33));

        assertThat(MemberExpiryNoticeService.alreadyNotified(user, TODAY.plusDays(33), 3)).isFalse();
    }

    @Test
    void failedSendLeavesNoMarkerSoItRetriesNextRun() {
        User user = member("111", TODAY.plusDays(1));
        when(repository.findExpiryNoticeTargets(any())).thenReturn(List.of(user));
        when(notifier.sendToMember(anyString(), anyString())).thenReturn(false);

        Map<String, Integer> sent = new MemberExpiryNoticeService(repository, notifier).run(TODAY);

        assertThat(sent).containsEntry("D-1", 0);
        assertThat(user.getExpiryNoticeFor()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void skipsQuietlyWhenTelegramIsNotConfigured() {
        Map<String, Integer> sent = new MemberExpiryNoticeService(repository, (TelegramNotifier) null).run(TODAY);
        assertThat(sent.values()).allMatch(v -> v == 0);
        verify(repository, never()).findExpiryNoticeTargets(any());
    }

    @Test
    void ignoresMembersWhoseRemainingDaysAreNotAWarningStage() {
        User d2 = member("333", TODAY.plusDays(2));   // 쿼리가 넓게 잡아와도 단계가 아니면 무시
        when(repository.findExpiryNoticeTargets(any())).thenReturn(List.of(d2));

        service().run(TODAY);

        verify(notifier, never()).sendToMember(anyString(), anyString());
    }

    private static User member(String chatId, LocalDate endDate) {
        User u = new User("m@example.com", "회원", "hash");
        u.setSubscription(SubscriptionStatus.PRO);
        u.setSubscriptionEndDate(endDate);
        u.setTelegramChatId(chatId);
        return u;
    }

    @Test
    void escapesMemberNameBecauseTelegramParsesHtml() {
        // 이름에 '<' 가 있으면 parse_mode=HTML 이 메시지를 거부해 그 회원만 알림을 못 받는다
        User user = member("111", TODAY.plusDays(1));
        user.setName("<b>김철수</b> & co");
        when(repository.findExpiryNoticeTargets(any())).thenReturn(List.of(user));

        service().run(TODAY);

        org.mockito.ArgumentCaptor<String> msg = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifier).sendToMember(eq("111"), msg.capture());
        assertThat(msg.getValue()).contains("&lt;b&gt;김철수&lt;/b&gt; &amp; co");
        assertThat(msg.getValue()).doesNotContain("<b>");
    }

    @Test
    void escapeHtmlHandlesNullAndSpecialChars() {
        assertThat(TelegramNotifier.escapeHtml(null)).isEmpty();
        assertThat(TelegramNotifier.escapeHtml("a<b>&c")).isEqualTo("a&lt;b&gt;&amp;c");
    }
}
