package com.bitman.justbuy.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 조건검색 엔진(condition_engine, 같은 호스트 127.0.0.1:8090) 앞단.
 *
 * <p>엔진에는 인증이 없다. 회원 인증과 PRO 구독 확인은 여기서 하고, 엔진의 공개 가능한
 * 엔드포인트 7개만 그대로 전달한다. 갱신·라벨링 같은 관리용 경로는 열지 않는다.
 */
@RestController
@RequestMapping("/api/engine")
public class ConditionEngineController {

    /** 엔진 요청 본문 상한. 정상 요청(조건식·종목코드 50개)은 수 KB 이내다. */
    static final int MAX_BODY_CHARS = 20_000;

    record EngineReply(int status, String body) {}

    @FunctionalInterface
    interface EngineTransport {
        EngineReply exchange(String method, String path, String jsonBody) throws IOException, InterruptedException;
    }

    private final SubscriptionAccessGuard guard;
    private final EngineTransport transport;

    @Autowired
    public ConditionEngineController(SubscriptionAccessGuard guard,
                                     @Value("${bitman.engine.url:http://127.0.0.1:8090}") String engineUrl) {
        this(guard, httpTransport(engineUrl));
    }

    ConditionEngineController(SubscriptionAccessGuard guard, EngineTransport transport) {
        this.guard = guard;
        this.transport = transport;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health(@AuthenticationPrincipal UUID userId) {
        return forward(userId, "GET", "/engine/health", null);
    }

    @GetMapping("/regime")
    public ResponseEntity<String> regime(@AuthenticationPrincipal UUID userId) {
        return forward(userId, "GET", "/engine/regime", null);
    }

    @GetMapping("/strategies")
    public ResponseEntity<String> strategies(@AuthenticationPrincipal UUID userId) {
        return forward(userId, "GET", "/engine/strategies", null);
    }

    @PostMapping("/translate")
    public ResponseEntity<String> translate(@AuthenticationPrincipal UUID userId, @RequestBody String body) {
        return forward(userId, "POST", "/engine/translate", body);
    }

    @PostMapping("/screen")
    public ResponseEntity<String> screen(@AuthenticationPrincipal UUID userId, @RequestBody String body) {
        return forward(userId, "POST", "/engine/screen", body);
    }

    @PostMapping("/backtest")
    public ResponseEntity<String> backtest(@AuthenticationPrincipal UUID userId, @RequestBody String body) {
        return forward(userId, "POST", "/engine/backtest", body);
    }

    @PostMapping("/risk")
    public ResponseEntity<String> risk(@AuthenticationPrincipal UUID userId, @RequestBody String body) {
        return forward(userId, "POST", "/engine/risk", body);
    }

    private ResponseEntity<String> forward(UUID userId, String method, String path, String body) {
        guard.requirePro(userId);
        if (body != null && body.length() > MAX_BODY_CHARS) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "요청이 너무 깁니다.");
        }
        EngineReply reply;
        try {
            reply = transport.exchange(method, path, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (IOException e) {
            return unavailable();
        }
        // 엔진 내부 오류의 상세는 회원에게 넘기지 않는다. 4xx 와 503(데이터 로딩 중)은 그대로 전달한다.
        if (reply.status() >= 500 && reply.status() != 503) return unavailable();
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    private static ResponseEntity<String> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
            .body("{\"detail\":\"조건검색 엔진에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.\"}");
    }

    static EngineTransport httpTransport(String engineUrl) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        String base = engineUrl.endsWith("/") ? engineUrl.substring(0, engineUrl.length() - 1) : engineUrl;
        return (method, path, jsonBody) -> {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path))
                // 번역은 OpenAI → Gemini 순서로 시도하므로 가장 길다
                .timeout(Duration.ofSeconds(70))
                .header("Accept", "application/json");
            if ("POST".equals(method)) {
                req.header("Content-Type", "application/json")
                   .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody, StandardCharsets.UTF_8));
            } else {
                req.GET();
            }
            HttpResponse<String> res = client.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new EngineReply(res.statusCode(), res.body());
        };
    }
}
