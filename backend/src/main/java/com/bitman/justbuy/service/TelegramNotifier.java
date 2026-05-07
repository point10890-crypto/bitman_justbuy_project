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

@Component
@ConditionalOnProperty(name = "bitman.telegram.bot-token")
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final String API_URL = "https://api.telegram.org/bot%s/sendMessage";
    private static final String FOOTER = "\n\n\ud83d\udd17 https://api.bit-man.net";
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int MAX_ANALYSIS_PICKS = 3;
    private static final int MAX_REASON_LENGTH = 70;

    private final String botToken;
    private final String chatId;
    private final String channelChatId;
    private final RestTemplate restTemplate;

    public TelegramNotifier(@Value("${bitman.telegram.bot-token}") String botToken,
                            @Value("${bitman.telegram.chat-id}") String chatId,
                            @Value("${bitman.telegram.channel-chat-id:}") String channelChatId,
                            RestTemplate restTemplate) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.channelChatId = channelChatId;
        this.restTemplate = restTemplate;
        log.info("[Telegram] Notifier initialized (personal={}, channel={})", chatId,
                channelChatId != null && !channelChatId.isEmpty() ? channelChatId : "none");
    }

    public void send(String message) {
        String text;
        if (message.length() > MAX_MESSAGE_LENGTH) {
            String body = message.endsWith(FOOTER)
                ? message.substring(0, message.length() - FOOTER.length())
                : message;
            int maxBody = MAX_MESSAGE_LENGTH - FOOTER.length() - 15;
            text = (body.length() > maxBody ? body.substring(0, maxBody) + "\n\n... (생략)" : body) + FOOTER;
        } else {
            text = message;
        }

        sendTo(chatId, text);

        if (channelChatId != null && !channelChatId.isEmpty()) {
            sendTo(channelChatId, text);
        }
    }

    public void sendToAdmin(String message) {
        String text = message.length() > MAX_MESSAGE_LENGTH
            ? message.substring(0, MAX_MESSAGE_LENGTH) + "\n\n... (생략)"
            : message;
        sendTo(chatId, text);
    }

    private void sendTo(String targetChatId, String text) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("[Telegram] botToken empty - cannot send to {} (check TELEGRAM_BOT_TOKEN env)", targetChatId);
            return;
        }
        try {
            String url = String.format(API_URL, botToken);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = Map.of(
                "chat_id", targetChatId,
                "text", text,
                "parse_mode", "HTML"
            );

            String resp = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            if (resp != null && resp.contains("\"ok\":false")) {
                log.warn("[Telegram] API rejected send to {}: {}", targetChatId,
                    resp.length() > 300 ? resp.substring(0, 300) : resp);
            } else {
                log.info("[Telegram] sent to {} ({} chars)", targetChatId, text.length());
            }
        } catch (org.springframework.web.client.HttpStatusCodeException hse) {
            log.warn("[Telegram] HTTP {} sending to {}: {}",
                hse.getStatusCode(), targetChatId, hse.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("[Telegram] Failed to send to {}: {}", targetChatId, e.getMessage());
        }
    }

    private static String localizeMode(String mode) {
        if (mode == null) return "분석";
        return switch (mode) {
            case "BREAKOUT" -> "돌파매수";
            case "FLOW_LEADER" -> "수급주도";
            case "CATALYST_BURST" -> "급등재료";
            case "REVERSAL_EDGE" -> "반전매수";
            default -> mode;
        };
    }

    private static String candidateLabel(String mode, String action) {
        return switch (mode == null ? "" : mode) {
            case "BREAKOUT" -> "돌파 후보";
            case "FLOW_LEADER" -> "수급 후보";
            case "CATALYST_BURST" -> "재료 후보";
            case "REVERSAL_EDGE" -> "반전 후보";
            default -> {
                String value = normalizeText(action);
                if (value.isBlank() || value.contains("매도")) {
                    yield "관찰 후보";
                }
                yield value;
            }
        };
    }

    public void sendAnalysisResult(String mode, AnalysisResponse result) {
        log.info("[Telegram] sendAnalysisResult called: mode={}, metadata={}, picks={}",
            mode,
            result.metadata() != null,
            result.stockPicks() != null ? result.stockPicks().size() : 0);

        var meta = result.metadata();
        int agentsUsed = meta != null ? meta.agentsUsed() : 0;
        int agentsSucceeded = meta != null ? meta.agentsSucceeded() : 0;
        long totalDurationMs = meta != null ? meta.totalDurationMs() : 0L;
        String emoji = (agentsUsed > 0 && agentsSucceeded == agentsUsed) ? "\u2705" : "\u26a0\ufe0f";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s <b>[%s]</b> 분석 완료\n", emoji, escapeHtml(localizeMode(mode))));
        sb.append(String.format("\u23f1 %.1f초 | \ud83e\udd16 AI %d/%d\n\n",
            totalDurationMs / 1000.0, agentsSucceeded, agentsUsed));

        List<StockPick> picks = result.stockPicks();
        if (picks == null || picks.isEmpty()) {
            sb.append("추천 후보 없음");
            sb.append(FOOTER);
            send(sb.toString());
            return;
        }

        int count = Math.min(MAX_ANALYSIS_PICKS, picks.size());
        for (int i = 0; i < count; i++) {
            StockPick pick = picks.get(i);
            appendPickSummary(sb, mode, pick, i + 1);
            if (i < count - 1) {
                sb.append("\n");
            }
        }

        if (picks.size() > MAX_ANALYSIS_PICKS) {
            sb.append("\n외 ").append(picks.size() - MAX_ANALYSIS_PICKS).append("개 후보는 앱에서 확인");
        }
        sb.append(FOOTER);

        send(sb.toString());
    }

    private static void appendPickSummary(StringBuilder sb, String mode, StockPick pick, int rank) {
        String code = normalizeText(pick.code());
        String name = displayName(code, pick.name());
        sb.append(String.format("<b>%d. %s", rank, escapeHtml(name)));
        if (!code.isBlank()) {
            sb.append(" (").append(escapeHtml(code)).append(")");
        }
        sb.append("</b>\n");

        sb.append("   ").append(escapeHtml(candidateLabel(mode, pick.action())));
        Integer financialScore = pick.financialScore();
        if (financialScore != null && financialScore > 0) {
            sb.append(" | 재무 ").append(financialScore).append("/100");
        }
        sb.append("\n");

        String priceLine = priceLine(pick);
        if (!priceLine.isBlank()) {
            sb.append("   ").append(escapeHtml(priceLine)).append("\n");
        }

        String reason = cleanReason(pick.reason());
        if (!reason.isBlank()) {
            sb.append("   ").append(escapeHtml(reason)).append("\n");
        }
    }

    private static String priceLine(StockPick pick) {
        String current = normalizeText(pick.currentPrice());
        String target = normalizeText(pick.targetPrice());
        String stop = normalizeText(pick.stopLoss());
        StringBuilder line = new StringBuilder();
        if (!current.isBlank()) {
            line.append("현재 ").append(current);
        }
        if (!target.isBlank()) {
            if (!line.isEmpty()) line.append(" | ");
            line.append("목표 ").append(target);
        }
        if (!stop.isBlank()) {
            if (!line.isEmpty()) line.append(" | ");
            line.append("손절 ").append(stop);
        }
        return line.toString();
    }

    private static String displayName(String code, String aiName) {
        String name = normalizeText(aiName);
        if ("001440".equals(code) && (name.isBlank() || name.contains("한전") || name.contains("대한전선") == false)) {
            return "대한전선";
        }
        if ("015760".equals(code) && (name.isBlank() || name.contains("한전") || name.contains("한국전력"))) {
            return "한국전력";
        }
        return name.isBlank() ? code : name;
    }

    private static String cleanReason(String reason) {
        String value = normalizeText(reason)
            .replaceAll("(?s)```[\\w:]*.*", "")
            .replaceAll("<[^>]+>", "")
            .replaceAll("\\[[^\\]]{0,40}/100[^\\]]*\\]", "")
            .replaceAll("\\[재무:[^\\]]*\\]", "")
            .replaceAll("\\s+", " ")
            .trim();

        if (value.matches("^(현재가|현재)[:\\s].*")) {
            return "";
        }
        if (value.length() > MAX_REASON_LENGTH) {
            return value.substring(0, MAX_REASON_LENGTH).trim() + "...";
        }
        return value;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String escapeHtml(String value) {
        return normalizeText(value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    public void sendAnalysisFailed(String mode, String error) {
        String msg = String.format(
            "\u274c <b>%s</b> 분석 실패\n\n"
            + "\ud83d\udea8 오류: %s",
            escapeHtml(localizeMode(mode)), escapeHtml(error != null ? error : "unknown")
        );
        sendToAdmin(msg);
    }

    public void sendStartupComplete(int success, int total) {
        String msg = String.format(
            "\ud83d\ude80 <b>서버 시작 완료</b>\n\n"
            + "\ud83d\udcca 프리컴퓨트: %d/%d 완료\n"
            + "\ud83d\udd17 JustBuy API 서비스 시작",
            success, total
        );
        sendToAdmin(msg);
    }
}
