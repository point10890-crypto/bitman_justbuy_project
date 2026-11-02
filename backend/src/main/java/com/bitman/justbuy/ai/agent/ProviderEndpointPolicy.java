package com.bitman.justbuy.ai.agent;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Credentials may leave the server only for the provider or an explicitly trusted HTTPS proxy base. */
public final class ProviderEndpointPolicy {
    private ProviderEndpointPolicy() {}

    public static String deepSeekBaseUrl(String baseUrl, String trustedProxyUrls) {
        String candidate = normalize(baseUrl);
        Set<String> trusted = Arrays.stream((trustedProxyUrls == null ? "" : trustedProxyUrls).split(","))
            .filter(s -> !s.isBlank()).map(ProviderEndpointPolicy::normalize).collect(Collectors.toSet());
        if (!candidate.equals("https://api.deepseek.com") && !candidate.equals("https://api.deepseek.com/v1")
                && !trusted.contains(candidate)) {
            throw new IllegalArgumentException("DeepSeek endpoint is not an approved HTTPS provider or trusted proxy.");
        }
        return candidate;
    }

    private static String normalize(String value) {
        try {
            URI uri = URI.create(value == null || value.isBlank() ? "https://api.deepseek.com" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            if (path.contains("..") || path.contains("%")) throw new IllegalArgumentException();
            return "https://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + path;
        } catch (Exception e) {
            throw new IllegalArgumentException("DeepSeek endpoint must be a valid HTTPS base URL.");
        }
    }

    public static boolean hasMeaningfulContent(String content) {
        if (content == null || content.isBlank()) return false;
        String compact = content.replaceAll("\\s+", "");
        return !Set.of("{}", "[]", "null", "```json{}```", "```json[]```", "```{}```").contains(compact);
    }

    private static final Pattern ERROR_CODE = Pattern.compile("\"(?:code|type)\"\\s*:\\s*\"([a-z0-9_.\\-]{1,60})\"");

    /**
     * Why a provider call failed, safe to store and send to the admin: the HTTP status and the
     * provider's machine error code only (e.g. {@code HTTP 429 credit_balance_exhausted}).
     * Never the request, the key, or the free-text body. A swallowed "Provider request failed"
     * hid an exhausted OpenAI balance on 2026-11-02.
     */
    public static String failureReason(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException http) {
            String body = http.getResponseBodyAsString();
            Matcher m = ERROR_CODE.matcher(body == null ? "" : body);
            String code = null;
            while (m.find()) {
                code = m.group(1);
                if (!m.group(0).contains("\"type\"")) break;      // prefer error.code over error.type
            }
            return "HTTP " + http.getStatusCode().value() + (code != null ? " " + code : "");
        }
        if (e instanceof org.springframework.web.client.ResourceAccessException) return "Provider unreachable or timed out";
        return "Provider request failed (" + e.getClass().getSimpleName() + ")";
    }

    /** Failures that will not clear by themselves: an admin has to act (billing, key). */
    public static boolean needsOperatorAction(String reason) {
        return reason != null && (reason.contains("insufficient_quota") || reason.contains("credit_balance_exhausted")
            || reason.contains("invalid_api_key") || reason.startsWith("HTTP 401") || reason.startsWith("HTTP 403"));
    }
}
