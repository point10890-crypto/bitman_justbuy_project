package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.assertThat;

class StockUniverseServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);

    @Test void disabledGateNeverDownloadsOrExposesCachedStocks() {
        AtomicInteger calls = new AtomicInteger();
        var service = new StockUniverseService(() -> false, (file, deadline) -> { calls.incrementAndGet(); return new byte[0]; }, CLOCK);
        service.refresh();
        assertThat(service.stocks()).isEmpty();
        assertThat(service.status()).isEqualTo("DISABLED");
        assertThat(calls.get()).isZero();
    }

    @Test void validCp949MastersJoinExplicitThemeAndSectorWithoutTreatingMetadataAsPrice() throws Exception {
        var files = fixtures(false);
        var service = new StockUniverseService(() -> true, (file, deadline) -> files.get(file), CLOCK);
        service.refresh();
        assertThat(service.status()).isEqualTo("AVAILABLE");
        var stock = service.find("005930").orElseThrow();
        assertThat(stock.name()).isEqualTo("삼성전자");
        assertThat(stock.market()).isEqualTo("KOSPI");
        assertThat(stock.sector()).isEqualTo("전기전자");
        assertThat(stock.themes()).containsExactly("반도체");
        assertThat(stock.excluded()).isFalse();
        assertThat(service.find("035720").orElseThrow().market()).isEqualTo("KOSDAQ");
    }

    @Test void invalidZipNeverReplacesPreviouslyVerifiedAtomicSnapshot() throws Exception {
        var files = fixtures(false);
        AtomicInteger round = new AtomicInteger();
        var service = new StockUniverseService(() -> true, (file, deadline) -> round.get() == 0 ? files.get(file) : zip("../bad.mst", "bad".getBytes()), CLOCK);
        service.refresh();
        round.incrementAndGet();
        service.refresh();
        assertThat(service.find("005930")).isPresent();
        assertThat(service.stocks()).hasSize(2);
        assertThat(service.status()).isEqualTo("PARTIAL");
    }

    @Test void duplicateCodesRejectTheWholeMasterRefresh() throws Exception {
        var files = fixtures(false);
        files.put("kospi_code.mst.zip", zip("kospi_code.mst", (row("005930", "삼성전자", 227, false) + row("005930", "중복", 227, false)).getBytes(Charset.forName("MS949"))));
        var service = new StockUniverseService(() -> true, (file, deadline) -> files.get(file), CLOCK);
        service.refresh();
        assertThat(service.stocks()).isEmpty();
        assertThat(service.status()).isEqualTo("UNAVAILABLE");
    }

    @Test void tradingHaltIsExcluded() throws Exception {
        var files = fixtures(true);
        var service = new StockUniverseService(() -> true, (file, deadline) -> files.get(file), CLOCK);
        service.refresh();
        assertThat(service.find("005930").orElseThrow().excluded()).isTrue();
    }

    @Test void cachedMetadataExpiresAfterTwentyFourHoursAndDisabledGateHidesIt() throws Exception {
        var files = fixtures(false);
        var enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        var instant = new java.util.concurrent.atomic.AtomicReference<>(CLOCK.instant());
        Clock changing = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return instant.get(); }
        };
        var service = new StockUniverseService(enabled::get, (file, deadline) -> files.get(file), changing);
        service.refresh();
        assertThat(service.stocks()).hasSize(2);
        enabled.set(false);
        assertThat(service.stocks()).isEmpty();
        assertThat(service.status()).isEqualTo("DISABLED");
        enabled.set(true);
        instant.set(CLOCK.instant().plusSeconds(24 * 3600));
        assertThat(service.stocks()).isEmpty();
        assertThat(service.status()).isEqualTo("STALE");
    }

    @Test void unsupportedIndexAndEmptyPlaceholderDoNotDiscardValidStockIdentities() throws Exception {
        var files = fixtures(false);
        files.put("idxcode.mst.zip", zip("idxcode.mst", "0" + fixed("0001", 4) + fixed("전기전자", 40) + "\n"
            + "A" + fixed("0001", 4) + fixed("범위밖지수", 40) + "\n" + "0" + fixed("0000", 4) + fixed("", 40) + "\n"));
        var service = new StockUniverseService(() -> true, (file, deadline) -> files.get(file), CLOCK);
        service.refresh();
        assertThat(service.status()).isEqualTo("PARTIAL");
        assertThat(service.find("005930").orElseThrow().sector()).isEqualTo("전기전자");
        assertThat(service.stocks()).hasSize(2);
    }

    static Map<String, byte[]> fixtures(boolean halted) throws Exception {
        return new java.util.HashMap<>(Map.of(
            "kospi_code.mst.zip", zip("kospi_code.mst", row("005930", "삼성전자", 227, halted).getBytes(Charset.forName("MS949"))),
            "kosdaq_code.mst.zip", zip("kosdaq_code.mst", row("035720", "가상검증회사", 221, false).getBytes(Charset.forName("MS949"))),
            "theme_code.mst.zip", zip("theme_code.mst", fixed("001", 3) + fixed("반도체", 40) + fixed("005930", 6) + "   \n"),
            "idxcode.mst.zip", zip("idxcode.mst", "0" + fixed("0001", 4) + fixed("전기전자", 40) + "\n")
        ));
    }

    private static String row(String code, String name, int tailSize, boolean halted) {
        char[] tail = new char[tailSize]; Arrays.fill(tail, ' ');
        tail[0] = 'S'; tail[1] = 'T';
        "0001".getChars(0, 4, tail, 3);
        if (halted) tail[60] = 'Y';
        return fixed(code, 9) + fixed("KR7005930003", 12) + fixed(name, 40) + new String(tail) + "\n";
    }

    private static String fixed(String value, int byteLength) {
        int padding = byteLength - value.getBytes(Charset.forName("MS949")).length;
        return value + " ".repeat(Math.max(0, padding));
    }

    private static byte[] zip(String name, String body) throws java.io.IOException { return zip(name, body.getBytes(Charset.forName("MS949"))); }
    private static byte[] zip(String name, byte[] body) throws java.io.IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry(name)); zip.write(body); zip.closeEntry(); }
        return out.toByteArray();
    }
}
