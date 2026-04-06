package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * 텔레그램 봇을 통해 개인 + 채널 동시 알림을 전송합니다.
 * bitman.telegram.bot-token 설정이 있을 때만 활성화됩니다.
 */
@Component
@ConditionalOnProperty(name = "bitman.telegram.bot-token")
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final String API_URL = "https://api.telegram.org/bot%s/sendMessage";
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final String botToken;
    private final String chatId;
    private final String channelChatId;
    private final RestTemplate restTemplate;

    public TelegramNotifier(@Value("${bitman.telegram.bot-token}") String botToken,
                            @Value("${bitman.telegram.chat-id}") String chatId,
                            @Value("${bitman.telegram.channel-chat-id:}") String channelChatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.channelChatId = channelChatId;
        this.restTemplate = new RestTemplate();
        log.info("[Telegram] Notifier initialized (personal={}, channel={})", chatId,
                channelChatId != null && !channelChatId.isEmpty() ? channelChatId : "none");
    }

    public void send(String message) {
        String text = message.length() > MAX_MESSAGE_LENGTH
            ? message.substring(0, MAX_MESSAGE_LENGTH) + "\n\n... (생략)"
            : message;

        // 1) 개인 봇
        sendTo(chatId, text);

        // 2) 채널 (설정된 경우)
        if (channelChatId != null && !channelChatId.isEmpty()) {
            sendTo(channelChatId, text);
        }
    }

    private void sendTo(String targetChatId, String text) {
        try {
            String url = String.format(API_URL, botToken);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                "chat_id", targetChatId,
                "text", text,
                "parse_mode", "HTML"
            );

            restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            log.debug("[Telegram] Message sent to {}", targetChatId);
        } catch (Exception e) {
            log.warn("[Telegram] Failed to send to {}: {}", targetChatId, e.getMessage());
        }
    }

    public void sendAnalysisResult(String mode, AnalysisResponse result) {
        StringBuilder sb = new StringBuilder();

        String emoji = result.metadata().agentsSucceeded() == result.metadata().agentsUsed()
            ? "\u2705" : "\u26a0\ufe0f";

        // 헤더
        sb.append(String.format("%s <b>[%s] \ubd84\uc11d \uc644\ub8cc</b>\n", emoji, mode));
        sb.append(String.format("\u23f1 %.1f\ucd08 | \ud83e\udd16 %d/%d AI\n",
            result.metadata().totalDurationMs() / 1000.0,
            result.metadata().agentsSucceeded(),
            result.metadata().agentsUsed()));
        sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\n");

        // 추천 종목 리스트
        List<StockPick> picks = result.stockPicks();
        if (picks != null && !picks.isEmpty()) {
            sb.append("\ud83d\udcca <b>\ucd94\ucc9c \uc885\ubaa9</b>\n\n");
            for (int i = 0; i < picks.size(); i++) {
                StockPick pick = picks.get(i);
                sb.append(String.format("<b>%d. %s</b>", i + 1, pick.name()));
                if (pick.code() != null && !pick.code().isEmpty()) {
                    sb.append(String.format(" (%s)", pick.code()));
                }
                sb.append("\n");

                if (pick.currentPrice() != null && !pick.currentPrice().isEmpty()) {
                    sb.append(String.format("   \ud604\uc7ac\uac00: %s", pick.currentPrice()));
                }
                if (pick.targetPrice() != null && !pick.targetPrice().isEmpty()) {
                    sb.append(String.format(" \u2192 \ubaa9\ud45c: %s", pick.targetPrice()));
                }
                if (pick.stopLoss() != null && !pick.stopLoss().isEmpty()) {
                    sb.append(String.format(" | \uc190\uc808: %s", pick.stopLoss()));
                }
                sb.append("\n");

                if (pick.action() != null && !pick.action().isEmpty()) {
                    sb.append(String.format("   \ud83d\udccc %s", pick.action()));
                    sb.append("\n");
                }

                if (pick.reason() != null && !pick.reason().isEmpty()) {
                    String reason = pick.reason().length() > 100
                        ? pick.reason().substring(0, 100) + "..."
                        : pick.reason();
                    sb.append(String.format("   \ud83d\udcdd %s", reason));
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }

        // 종합 분석 요약 (finalContent — JSON/코드블록 제거)
        if (result.finalContent() != null && !result.finalContent().isEmpty()) {
            sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
            sb.append("\ud83d\udcdd <b>\uc885\ud569 \ubd84\uc11d</b>\n\n");

            String plainContent = stripContent(result.finalContent());

            // 남은 여유 공간에 맞춰 자르기
            int remaining = MAX_MESSAGE_LENGTH - sb.length() - 30;
            if (remaining > 200) {
                String summary = plainContent.length() > remaining
                    ? plainContent.substring(0, remaining) + "..."
                    : plainContent;
                sb.append(summary);
            }
        }

        sb.append("\n\n\ud83d\udd17 https://api.bit-man.net");

        send(sb.toString());
    }

    /** finalContent에서 JSON 코드블록, HTML 태그, 검증 섹션 제거 */
    private String stripContent(String content) {
        return content
            // markdown 코드블록 제거 (```json:analysis ... ``` 등)
            .replaceAll("(?s)```[\\w:]*\\s*\\{.*?```", "")
            .replaceAll("(?s)```[\\w:]*.*?```", "")
            // HTML 태그 제거
            .replaceAll("<[^>]+>", "")
            // 실시간 검증 섹션 제거 (하단 부가 정보)
            .replaceAll("(?s)---.*?검증.*$", "")
            .replaceAll("(?s)\ud83d\udce1.*$", "")
            // HTML 엔티티
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&nbsp;", " ")
            // 연속 줄바꿈 정리
            .replaceAll("\n{3,}", "\n\n")
            .trim();
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
