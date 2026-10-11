package com.bitman.justbuy.ai.agent;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
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
}
