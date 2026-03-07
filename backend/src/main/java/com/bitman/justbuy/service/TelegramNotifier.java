package com.bitman.justbuy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 텔레그램 봇을 통해 관리자에게 알림을 전송합니다.
 * bitman.telegram.bot-token 설정이 있을 때만 활성화됩니다.
 */
@Component
@ConditionalOnProperty(name = "bitman.telegram.bot-token")
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final String API_URL = "https://api.telegram.org/bot%s/sendMessage";

    private final String botToken;
    private final String chatId;
    private final RestTemplate restTemplate;

    public TelegramNotifier(@Value("${bitman.telegram.bot-token}") String botToken,
                            @Value("${bitman.telegram.chat-id}") String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.restTemplate = new RestTemplate();
        log.info("[Telegram] Notifier initialized (chatId={})", chatId);
    }

    public void send(String message) {
        try {
            String url = String.format(API_URL, botToken);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", message,
                "parse_mode", "HTML"
            );

            restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            log.debug("[Telegram] Message sent successfully");
        } catch (Exception e) {
            log.warn("[Telegram] Failed to send message: {}", e.getMessage());
        }
    }

    public void sendAnalysisComplete(String mode, long durationMs, int agentsSucceeded, int agentsUsed) {
        String emoji = agentsSucceeded == agentsUsed ? "\u2705" : "\u26a0\ufe0f";
        String msg = String.format(
            "%s <b>%s</b> \ubd84\uc11d \uc644\ub8cc\n\n"
            + "\u23f1 \uc18c\uc694\uc2dc\uac04: %.1f\ucd08\n"
            + "\ud83e\udd16 AI\uc5d4\uc9c4: %d/%d \uc131\uacf5\n"
            + "\ud83d\udcc5 \uc5c5\ub370\uc774\ud2b8 \uc644\ub8cc",
            emoji, mode, durationMs / 1000.0, agentsSucceeded, agentsUsed
        );
        send(msg);
    }

    public void sendAnalysisFailed(String mode, String error) {
        String msg = String.format(
            "\u274c <b>%s</b> \ubd84\uc11d \uc2e4\ud328\n\n"
            + "\ud83d\udea8 \uc624\ub958: %s",
            mode, error != null ? error : "unknown"
        );
        send(msg);
    }

    public void sendStartupComplete(int success, int total) {
        String msg = String.format(
            "\ud83d\ude80 <b>\uc11c\ubc84 \uc2dc\uc791 \uc644\ub8cc</b>\n\n"
            + "\ud83d\udcca \ud504\ub9ac\ucef4\ud4e8\ud2b8: %d/%d \uc644\ub8cc\n"
            + "\ud83d\udd17 JustBuy API \uc11c\ube44\uc2a4 \uc2dc\uc791",
            success, total
        );
        send(msg);
    }
}
