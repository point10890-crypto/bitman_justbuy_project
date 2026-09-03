package com.bitman.justbuy.service;

import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 텔레그램 연결 입력 검증 — 소유 증명이 없는 단계이므로 최소한의 방어선이다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TelegramLinkValidationTest {

    @Mock UserRepository repository;
    @Mock ObjectProvider<TelegramNotifier> notifierProvider;

    private SubscriptionService service() {
        return new SubscriptionService(repository, null, notifierProvider);
    }

    private UUID stubUser() {
        UUID id = UUID.randomUUID();
        User u = new User("m@example.com", "회원", "hash");
        when(repository.findById(id)).thenReturn(Optional.of(u));
        when(repository.findByTelegramChatId(anyString())).thenReturn(List.of());
        when(repository.save(org.mockito.ArgumentMatchers.any(User.class))).thenAnswer(i -> i.getArgument(0));
        return id;
    }

    @Test
    void acceptsAPersonalChatId() {
        UUID id = stubUser();
        assertThat(service().linkTelegram(id, " 123456789 ").telegramLinked()).isTrue();
    }

    @Test
    void rejectsGroupAndChannelIdsWhichAreNegative() {
        UUID id = stubUser();
        // 음수는 그룹/채널 — 개인 만료 안내가 단체에 노출된다
        assertThatThrownBy(() -> service().linkTelegram(id, "-1001234567890"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("양수");
    }

    @Test
    void rejectsNonNumericAndOutOfRangeInput() {
        UUID id = stubUser();
        for (String bad : new String[]{"abc", "", "  ", "123", "0123456789", null}) {
            assertThatThrownBy(() -> service().linkTelegram(id, bad))
                .as("input=%s", bad)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsChatIdAlreadyBoundToAnotherAccount() {
        UUID id = UUID.randomUUID();
        User me = new User("me@example.com", "나", "hash");
        User other = new User("other@example.com", "남", "hash");
        when(repository.findById(id)).thenReturn(Optional.of(me));
        when(repository.findByTelegramChatId("123456789")).thenReturn(List.of(other));

        // 소유 증명이 없으므로, 최소한 타인 chat id 재사용은 막아야 한다
        assertThatThrownBy(() -> service().linkTelegram(id, "123456789"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이미 다른 계정");
    }

    @Test
    void relinkingOwnChatIdIsAllowed() throws Exception {
        User me = new User("me@example.com", "나", "hash");
        UUID id = UUID.randomUUID();
        // 영속 엔티티처럼 id 를 채워야 "같은 계정"으로 인식된다 (실서비스에서는 항상 채워져 있다)
        var field = User.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(me, id);

        when(repository.findById(id)).thenReturn(Optional.of(me));
        when(repository.findByTelegramChatId("123456789")).thenReturn(List.of(me));
        when(repository.save(org.mockito.ArgumentMatchers.any(User.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service().linkTelegram(id, "123456789").telegramLinked()).isTrue();
    }
}
