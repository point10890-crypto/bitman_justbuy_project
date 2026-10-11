package com.bitman.justbuy.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipInputStream;

/** Identity metadata only. A verified master never substitutes for a market quote. */
@Service
public class StockUniverseService {
    private static final int MAX_ZIP_BYTES = 5_000_000;
    private static final int MAX_INFLATED_BYTES = 10_000_000;
    private static final Charset CP949 = Charset.forName("MS949");
    private static final Duration TTL = Duration.ofHours(24);
    private static final List<String> FILES = List.of("kospi_code.mst.zip", "kosdaq_code.mst.zip", "theme_code.mst.zip", "idxcode.mst.zip");
    public record Stock(String code, String name, String market, String sector, List<String> themes, boolean excluded) {
        public Stock { themes = List.copyOf(themes); }
    }
    private record Cache(Map<String, Stock> stocks, Instant fetchedAt) {}
    @FunctionalInterface interface MasterDownloader { byte[] download(String filename, long deadlineNanos) throws Exception; }
    private final BooleanSupplier enabled;
    private final MasterDownloader downloader;
    private final Clock clock;
    private volatile Cache cache;
    private volatile String lastStatus = "UNAVAILABLE";

    @Autowired public StockUniverseService(KisSourceGateway gateway) {
        this(gateway::isEnabled, new SecureMasterDownloader(), Clock.systemUTC());
    }
    StockUniverseService(BooleanSupplier enabled, MasterDownloader downloader, Clock clock) {
        this.enabled = enabled; this.downloader = downloader; this.clock = clock;
    }
    public Optional<Stock> find(String code) {
        Cache current = fresh();
        return code != null && current != null ? Optional.ofNullable(current.stocks().get(code)) : Optional.empty();
    }
    public List<Stock> stocks() {
        Cache current = fresh();
        return current == null ? List.of() : List.copyOf(current.stocks().values());
    }
    public String status() {
        if (!enabled.getAsBoolean()) return "DISABLED";
        return fresh() == null ? cache == null ? "UNAVAILABLE" : "STALE" : lastStatus;
    }
    public Instant fetchedAt() { Cache current = fresh(); return current == null ? null : current.fetchedAt(); }
    private Cache fresh() {
        Cache current = cache;
        Instant now = clock.instant();
        return enabled.getAsBoolean() && current != null && !now.isBefore(current.fetchedAt())
            && now.isBefore(current.fetchedAt().plus(TTL)) ? current : null;
    }

    /** Explicit startup/admin/scheduler hook; construction and readiness never download. */
    public synchronized void refresh() {
        if (!enabled.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
        long deadline = System.nanoTime() + Duration.ofSeconds(16).toNanos();
        try {
            Map<String, List<byte[]>> files = new LinkedHashMap<>();
            for (String file : FILES) {
                if (!enabled.getAsBoolean() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IllegalArgumentException();
                files.put(file, unzip(downloader.download(file, deadline), file.substring(0, file.length() - 4)));
            }
            Map<String, String> sectors = parseSectors(files.get("idxcode.mst.zip"));
            Map<String, List<String>> themes = parseThemes(files.get("theme_code.mst.zip"));
            Map<String, Stock> all = new TreeMap<>();
            // Official sample 228/222 includes LF; rows above have already stripped CR/LF.
            parseStocks(files.get("kospi_code.mst.zip"), "KOSPI", 227, sectors, themes, all);
            parseStocks(files.get("kosdaq_code.mst.zip"), "KOSDAQ", 221, sectors, themes, all);
            if (System.nanoTime() >= deadline || !enabled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalArgumentException();
            cache = new Cache(Collections.unmodifiableMap(new LinkedHashMap<>(all)), clock.instant());
            lastStatus = sectors.size() < files.get("idxcode.mst.zip").size() ? "PARTIAL" : "AVAILABLE";
        } catch (Exception ignored) {
            // Preserve the entire previous identity snapshot; no partial overwrite or raw URL logging.
            lastStatus = fresh() == null ? "UNAVAILABLE" : "PARTIAL";
        }
    }

    private static List<byte[]> unzip(byte[] compressed, String expectedName) throws Exception {
        if (compressed == null || compressed.length == 0 || compressed.length > MAX_ZIP_BYTES) throw new IllegalArgumentException();
        byte[] body;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(compressed))) {
            var entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory() || !expectedName.equals(entry.getName()) || entry.getSize() > MAX_INFLATED_BYTES) throw new IllegalArgumentException();
            body = readBounded(zip, MAX_INFLATED_BYTES);
            if (zip.getNextEntry() != null) throw new IllegalArgumentException();
        }
        List<byte[]> rows = new ArrayList<>();
        for (int start = 0, i = 0; i <= body.length; i++) {
            if (i < body.length && body[i] != '\n') continue;
            int end = i > start && body[i - 1] == '\r' ? i - 1 : i;
            if (end > start) {
                if (end - start > 2048 || rows.size() >= 20_000) throw new IllegalArgumentException();
                rows.add(Arrays.copyOfRange(body, start, end));
            }
            start = i + 1;
        }
        if (rows.isEmpty()) throw new IllegalArgumentException();
        return rows;
    }
    private static byte[] readBounded(java.io.InputStream input, int limit) throws java.io.IOException {
        var output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new java.io.IOException("source size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static String field(byte[] row, int from, int width) throws Exception {
        if (from < 0 || from + width > row.length) throw new IllegalArgumentException();
        return CP949.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(row, from, width)).toString().trim();
    }
    private static Map<String, String> parseSectors(List<byte[]> rows) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (byte[] row : rows) {
            if (row.length != 45) throw new IllegalArgumentException();
            String key = field(row, 0, 5), name = field(row, 5, 40);
            // The official index file also includes nonnumeric market keys and unnamed placeholders.
            // They are not domestic equity sector labels; keep the verified stock identities.
            if (!key.matches("[0-9]{5}") || name.isBlank()) continue;
            if (result.putIfAbsent(key, name) != null) throw new IllegalArgumentException();
        }
        if (result.isEmpty()) throw new IllegalArgumentException();
        return result;
    }
    private static Map<String, List<String>> parseThemes(List<byte[]> rows) throws Exception {
        Map<String, LinkedHashSet<String>> grouped = new HashMap<>();
        for (byte[] row : rows) {
            if (row.length != 52 || !field(row, 0, 3).matches("[0-9]{3}")) throw new IllegalArgumentException();
            String name = field(row, 3, 40), code = field(row, 43, 6);
            if (name.isBlank() || !code.matches("[0-9]{6}")) throw new IllegalArgumentException();
            grouped.computeIfAbsent(code, ignored -> new LinkedHashSet<>()).add(name);
        }
        Map<String, List<String>> result = new HashMap<>();
        grouped.forEach((code, names) -> result.put(code, List.copyOf(names)));
        return result;
    }
    private static void parseStocks(List<byte[]> rows, String market, int tailLength,
            Map<String, String> sectors, Map<String, List<String>> themes, Map<String, Stock> result) throws Exception {
        int before = result.size();
        for (byte[] row : rows) {
            int tail = row.length - tailLength;
            if (tail < 22) throw new IllegalArgumentException();
            String code = field(row, 0, 9), standardCode = field(row, 9, 12), name = field(row, 21, tail - 21);
            if (!code.matches("[0-9]{6}")) continue; // Non-stock instruments do not become invented six-digit identities.
            if (!standardCode.matches("[A-Z0-9]{12}") || name.isBlank() || result.containsKey(code)) throw new IllegalArgumentException();
            String sectorCode = field(row, tail + 3, 4);
            if (!sectorCode.matches("[0-9]{4}")) throw new IllegalArgumentException();
            boolean kospi = market.equals("KOSPI");
            int haltOffset = kospi ? 60 : 55;
            String etp = field(row, tail + (kospi ? 22 : 18), 1);
            String warning = field(row, tail + haltOffset + 3, 2);
            boolean excluded = !field(row, tail, 2).equals("ST") || !etp.isBlank() && !etp.equals("0")
                || field(row, tail + (kospi ? 29 : 24), 1).equals("Y")
                || field(row, tail + haltOffset, 1).equals("Y") || field(row, tail + haltOffset + 1, 1).equals("Y")
                || field(row, tail + haltOffset + 2, 1).equals("Y") || !warning.isBlank() && !warning.equals("00");
            String sector = sectors.getOrDefault((kospi ? "0" : "1") + sectorCode, sectorCode);
            result.put(code, new Stock(code, name, market, sector, themes.getOrDefault(code, List.of()), excluded));
        }
        if (result.size() == before) throw new IllegalArgumentException();
    }
    private static final class SecureMasterDownloader implements MasterDownloader {
        public byte[] download(String filename, long deadline) throws Exception {
            if (!FILES.contains(filename) || deadline - System.nanoTime() < Duration.ofSeconds(3).toNanos()) throw new IllegalArgumentException();
            URI uri = URI.create("https://new.real.download.dws.co.kr/common/master/" + filename);
            var connection = (HttpsURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(1000); connection.setReadTimeout(2000); connection.setInstanceFollowRedirects(false);
            try {
                if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_ZIP_BYTES) throw new IllegalArgumentException();
                try (var body = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = body.read(buffer)) != -1) {
                        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted() || output.size() + count > MAX_ZIP_BYTES) throw new IllegalArgumentException();
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
    }
}
