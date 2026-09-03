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
    private static final String NOTICE_TRUNCATED = "\n\n... (생략)";

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
        // AppConfig#restTemplate에 설정된 타임아웃(connect 10s / read 120s)을 적용하기 위해
        // 인젝션된 빈을 사용. 자체 `new RestTemplate()`은 timeout 미설정 → Telegram API 행 시 스케줄러 스레드 영구 블록 위험.
        this.restTemplate = restTemplate;
        log.info("[Telegram] Notifier initialized (personal={}, channel={})", chatId,
                channelChatId != null && !channelChatId.isEmpty() ? channelChatId : "none");
    }

    public void send(String message) {
        String footer = "\n\n\ud83d\udd17 https://api.bit-man.net";
        String text;
        if (message.length() > MAX_MESSAGE_LENGTH) {
            // footer가 이미 포함되어 있으면 제거 후 잘라서 다시 붙이기
            String body = message.endsWith(footer)
                ? message.substring(0, message.length() - footer.length())
                : message;
            int maxBody = MAX_MESSAGE_LENGTH - footer.length() - 15; // "... (생략)" 여유
            text = (body.length() > maxBody ? body.substring(0, maxBody) + "\n\n... (생략)" : body) + footer;
        } else {
            text = message;
        }

        // 1) 개인 봇
        sendTo(chatId, text);

        // 2) 채널 (설정된 경우)
        if (channelChatId != null && !channelChatId.isEmpty()) {
            sendTo(channelChatId, text);
        }
    }

    /**
     * 지정한 회원 chat id 로만 전송한다.
     *
     * <p>기존 발송 메서드는 관리자/채널 고정 대상이라 회원 개인에게 보낼 수단이 없었다.
     * 회원 알림은 실패해도 다른 회원 발송이나 호출자 로직을 막으면 안 되므로
     * 예외를 던지지 않고 성공 여부만 돌려준다.
     *
     * @return 전송 시도가 성공했으면 true
     */
    /**
     * parse_mode 가 HTML 이므로 사용자 입력(이름 등)은 반드시 이스케이프해야 한다.
     * 이스케이프하지 않으면 이름에 '<' 나 '&' 가 있는 회원은 텔레그램이 메시지를 거부해
     * 그 사람만 조용히 알림을 못 받는다.
     */
    public static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public boolean sendToMember(String memberChatId, String message) {
        if (memberChatId == null || memberChatId.isBlank()) return false;
        String text = message.length() > MAX_MESSAGE_LENGTH
            ? message.substring(0, MAX_MESSAGE_LENGTH) + NOTICE_TRUNCATED
            : message;
        // sendTo 는 예외를 내부에서 삼키므로 반환값으로만 성공을 판별할 수 있다.
        // 여기서 true 를 무조건 돌려주면 실패한 발송이 "보냄"으로 기록돼 재시도가 사라진다.
        return sendTo(memberChatId.trim(), text);
    }

    /** 관리자 개인 채팅에만 전송 (구독 승인 요청 등 운영 알림용). */
    public void sendToAdmin(String message) {
        String text = message.length() > MAX_MESSAGE_LENGTH
            ? message.substring(0, MAX_MESSAGE_LENGTH) + "\n\n... (생략)"
            : message;
        sendTo(chatId, text);
    }

    private boolean sendTo(String targetChatId, String text) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("[Telegram] botToken empty — cannot send to {} (check TELEGRAM_BOT_TOKEN env)", targetChatId);
            return false;
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
            // Telegram returns HTTP 200 even when ok=false for some cases — inspect body
            if (resp != null && resp.contains("\"ok\":false")) {
                log.warn("[Telegram] API rejected send to {}: {}", targetChatId,
                    resp.length() > 300 ? resp.substring(0, 300) : resp);
                return false;
            }
            log.info("[Telegram] ✉ sent to {} ({} chars)", targetChatId, text.length());
            return true;
        } catch (org.springframework.web.client.HttpStatusCodeException hse) {
            log.warn("[Telegram] HTTP {} sending to {}: {}",
                hse.getStatusCode(), targetChatId, hse.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("[Telegram] Failed to send to {}: {}", targetChatId, e.getMessage());
        }
        return false;
    }

    /** 모드 코드 → 한글 라벨 변환 (UI와 통일) */
    private static String localizeMode(String mode) {
        if (mode == null) return "분석";
        return switch (mode) {
            case "BREAKOUT"       -> "단타";
            case "REVERSAL_EDGE"  -> "스윙";
            case "FLOW_LEADER"    -> "주도주";
            case "CATALYST_BURST" -> "테마주";
            default -> mode;
        };
    }

    public void sendAnalysisResult(String mode, AnalysisResponse result) {
        log.info("[Telegram] sendAnalysisResult called: mode={}, metadata={}, picks={}",
            mode,
            result.metadata() != null,
            result.stockPicks() != null ? result.stockPicks().size() : 0);
        StringBuilder sb = new StringBuilder();

        // metadata 가 null 인 폴백 응답 경로에서도 안전하게 처리
        var meta = result.metadata();
        int agentsUsed = meta != null ? meta.agentsUsed() : 0;
        int agentsSucceeded = meta != null ? meta.agentsSucceeded() : 0;
        long totalDurationMs = meta != null ? meta.totalDurationMs() : 0L;

        String emoji = (agentsUsed > 0 && agentsSucceeded == agentsUsed) ? "\u2705" : "\u26a0\ufe0f";

        // 헤더
        sb.append(String.format("%s <b>[%s] \ubd84\uc11d \uc644\ub8cc</b>\n", emoji, localizeMode(mode)));
        sb.append(String.format("\u23f1 %.1f\ucd08 | \ud83e\udd16 %d/%d AI\n",
            totalDurationMs / 1000.0,
            agentsSucceeded,
            agentsUsed));
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

                String _cur = pick.currentPrice();
                String _tgt = pick.targetPrice();
                String _stp = pick.stopLoss();
                if (_cur != null && !_cur.isEmpty()) {
                    long _curN = parsePrice(_cur);
                    if ((_tgt == null || _tgt.isEmpty()) && _curN > 0) _tgt = String.format("%,d", Math.round(_curN * 1.10));
                    if ((_stp == null || _stp.isEmpty()) && _curN > 0) _stp = String.format("%,d", Math.round(_curN * 0.95));
                    sb.append(String.format("   \ub9e4\uc218: %s", _cur));
                    if (_tgt != null && !_tgt.isEmpty()) sb.append(String.format(" \u2192 \ubaa9\ud45c: %s", _tgt));
                    if (_stp != null && !_stp.isEmpty()) sb.append(String.format(" | \uc190\uc808: %s", _stp));
                    sb.append("\n");
                }

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

        // 재무 요약 — 상위 3개 pick 의 financialScore + summary
        if (picks != null && !picks.isEmpty()) {
            java.util.List<StockPick> finTop = picks.stream()
                .filter(p -> p.financialScore() != null && p.financialScore() > 0
                    && p.financialSummary() != null && !p.financialSummary().isBlank()
                    && !"\uC7AC\uBB34\uB370\uC774\uD130 \uC5C6\uC74C".equals(p.financialSummary()))
                .limit(3)
                .toList();
            if (!finTop.isEmpty()) {
                sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n");
                sb.append("\ud83d\udcb0 <b>\uc7ac\ubb34 \uc694\uc57d</b>\n\n");
                for (int i = 0; i < finTop.size(); i++) {
                    StockPick p = finTop.get(i);
                    sb.append(String.format("%d. %s (%s) — %d/100\n",
                        i + 1, p.name(), p.code(), p.financialScore()));
                    sb.append("   ").append(p.financialSummary()).append("\n");
                }
                sb.append("\n");
            }
        }

        // v2.8.8: removed final-analysis section
        sb.append("\n\n🔗 https://api.bit-man.net");
        send(sb.toString());
    }

    private long parsePrice(String s) {
        if (s == null) return -1;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try { return Long.parseLong(digits); } catch (NumberFormatException e) { return -1; }
    }

    /** finalContent에서 JSON 코드블록, HTML 태그, 검증 섹션 제거 */
    private String stripContent(String content) {
        return content
            // markdown 코드블록 제거 — backtick 3개로 감싼 모든 블록 (json:analysis, json 등)
            .replaceAll("(?s)```[\\w:]*\\s*[\\s\\S]*?```", "")
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
        // 시스템 메시지 → 관리자 개인에게만 (채널 발송 X)
        sendToAdmin(msg);
    }

    public void sendStartupComplete(int success, int total) {
        String msg = String.format(
            "\ud83d\ude80 <b>\uc11c\ubc84 \uc2dc\uc791 \uc644\ub8cc</b>\n\n"
            + "\ud83d\udcca \ud504\ub9ac\ucef4\ud4e8\ud2b8: %d/%d \uc644\ub8cc\n"
            + "\ud83d\udd17 JustBuy API \uc11c\ube44\uc2a4 \uc2dc\uc791",
            success, total
        );
        // 시스템 메시지 → 관리자 개인에게만 (채널 발송 X)
        sendToAdmin(msg);
    }
}
