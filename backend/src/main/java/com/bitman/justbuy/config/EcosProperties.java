package com.bitman.justbuy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "bitman.ecos")
public record EcosProperties(boolean enabled, String apiKey, List<Series> series) {
    public EcosProperties {
        apiKey = apiKey == null ? "" : apiKey;
        series = series == null || series.isEmpty() ? List.of(
            new Series("USD_KRW", "원/미국달러 매매기준율", "731Y001", "0000001", "D", "원", 7),
            new Series("KTB_3Y", "국고채 3년", "817Y002", "010200000", "D", "연%", 7),
            new Series("BOK_RATE", "한국은행 기준금리", "722Y001", "0101000", "D", "연%", 7)
        ) : List.copyOf(series);
    }

    public record Series(String id, String name, String table, String item, String cycle, String unit, int maxAgeDays) {}

    @Override public String toString() {
        return "EcosProperties[enabled=" + enabled + ", credentialsConfigured=" + !apiKey.isBlank() + ", series=" + series + "]";
    }
}
