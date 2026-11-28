package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Market-only observations for matched-input research. Never a publication or order source. */
@Service
public class ConditionCaptureStore {
    private static final Logger log = LoggerFactory.getLogger(ConditionCaptureStore.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long SHARED_SEGMENT_BYTES = 128L * 1024 * 1024;
    private static final long BREAKOUT_SEGMENT_BYTES = 16L * 1024 * 1024;
    private static final int SHARED_DAILY_SEGMENTS = 3;
    private static final int BREAKOUT_DAILY_SEGMENTS = 2;
    private static final Pattern CAPTURE_FILE = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}(?:-breakout)?(?:-part-([0-9]{6}))?\\.jsonl");
    private static final Set<String> BREAKOUT_KINDS = Set.of(
        "source", "candidate_selection", "verified_publication", "realtime_snapshot");
    private final ObjectMapper mapper;
    private final KisApiService kis;
    private final Path directory;
    private final Clock clock;
    private final Cache<String, Long> written = Caffeine.newBuilder().maximumSize(20000).build();
    private final Cache<String, PayloadFingerprint> payloads = Caffeine.newBuilder().maximumSize(5000)
        .expireAfterWrite(Duration.ofHours(36)).build();
    private final Cache<String, Boolean> writeWarnings = Caffeine.newBuilder().maximumSize(16)
        .expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Map<String, Integer> activeSegments = new HashMap<>();
    private final Map<String, String> watched = new LinkedHashMap<>();
    private int cursor;
    private LocalDate lastPruned;
    private record PayloadFingerprint(LocalDate day, byte[] digest) {}

    @Autowired
    public ConditionCaptureStore(ObjectMapper mapper, KisApiService kis,
            @Value("${bitman.condition.capture-directory:data/condition-captures}") String directory) {
        this(mapper, kis, Path.of(directory), Clock.systemUTC());
    }
    ConditionCaptureStore(ObjectMapper mapper, KisApiService kis, Path directory, Clock clock) {
        this.mapper=mapper; this.kis=kis; this.directory=directory; this.clock=clock;
        try {
            Files.createDirectories(directory);
            Path file=directory.resolve("watchlist.json");
            if (Files.exists(file) && Files.size(file)<100000) {
                Map<String,String> saved=mapper.readValue(file.toFile(), new TypeReference<>() {});
                saved.forEach((code, at) -> { if (code.matches("[0-9]{6}")) {
                    try { if (Instant.parse(at).isAfter(clock.instant().minus(Duration.ofDays(35)))) watched.put(code,at); }
                    catch (RuntimeException ignored) { }
                }});
            }
        } catch(Exception e) { log.warn("[ConditionCapture] initialization unavailable: {}", e.getClass().getSimpleName()); }
    }
    public synchronized void record(String kind, String mode, String code, Instant observedAt, Object data) {
        if (observedAt==null || kind==null || !kind.matches("[a-z_]{1,30}") || mode==null
            || !mode.matches("[A-Z][A-Z0-9_]{0,39}") || code==null || !(code.matches("[0-9]{6}") || code.equals("pool"))) return;
        Instant recordedAt = clock.instant();
        long minute=recordedAt.getEpochSecond()/60;
        String key=kind+":"+mode+":"+code;
        String sidecarKey = "breakout-sidecar:" + key;
        // These callers repeatedly refetch a daily chart or reread a stored snapshot, including before the market opens.
        boolean distinctPayload = ("daily".equals(kind) && "ALL_SECTIONS".equals(mode) && !"pool".equals(code))
            || ("source".equals(kind) && "JONGGA_V2".equals(mode) && "pool".equals(code));
        boolean shared = distinctPayload || !Objects.equals(written.getIfPresent(key), minute);
        boolean sidecar = isBreakoutStage(kind, mode, code, data)
            && !Objects.equals(written.getIfPresent(sidecarKey), minute);
        if (!shared && !sidecar) return;
        LocalDate day = recordedAt.atZone(KST).toLocalDate();
        prune(day);
        try {
            byte[] digest = null;
            if (distinctPayload) {
                digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(data));
                PayloadFingerprint previous = payloads.getIfPresent(key);
                if (previous != null && previous.day().equals(day) && Arrays.equals(previous.digest(), digest)) return;
            }
            var row=new LinkedHashMap<String,Object>();
            row.put("schemaVersion",1); row.put("kind",kind); row.put("mode",mode); row.put("code",code);
            row.put("observedAt",observedAt.toString()); row.put("recordedAt",recordedAt.toString());
            row.put("data",data);
            byte[] line = (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
            if (sidecar && append(day + "-breakout", line, BREAKOUT_SEGMENT_BYTES, BREAKOUT_DAILY_SEGMENTS))
                written.put(sidecarKey, minute);
            if (shared && append(day.toString(), line, SHARED_SEGMENT_BYTES, SHARED_DAILY_SEGMENTS)) {
                if (distinctPayload) payloads.put(key, new PayloadFingerprint(day, digest));
                else written.put(key, minute);
            }
        } catch(Exception e) { log.warn("[ConditionCapture] serialization unavailable: {}",e.getClass().getSimpleName()); }
    }

    private static boolean isBreakoutStage(String kind, String mode, String code, Object data) {
        if (!"BREAKOUT".equals(mode) || !"pool".equals(code)) return false;
        if (BREAKOUT_KINDS.contains(kind)) return true;
        if (!"publication".equals(kind) || !(data instanceof Map<?, ?> publication)) return false;
        Object status = publication.get("sourceStatus");
        return "REALTIME_SCAN".equals(status) || "REALTIME_EMPTY".equals(status);
    }

    private boolean append(String stem, byte[] line, long segmentBytes, int dailySegments) {
        if (line.length > segmentBytes) {
            warnWriteFailed(stem, "row exceeds segment size");
            return false;
        }
        try {
            Integer active = activeSegments.get(stem);
            int part = active == null ? latestSegment(stem) : active;
            while (part <= dailySegments) {
                Path target = directory.resolve(part == 1 ? stem + ".jsonl"
                    : String.format(Locale.ROOT, "%s-part-%06d.jsonl", stem, part));
                if (appendIfFits(target, line, segmentBytes)) {
                    activeSegments.put(stem, part);
                    return true;
                }
                part++;
            }
            warnWriteFailed(stem, "daily segment limit reached");
        } catch (IOException | OverlappingFileLockException e) {
            warnWriteFailed(stem, e.getClass().getSimpleName());
        }
        return false;
    }

    private int latestSegment(String stem) throws IOException {
        int latest = 1;
        try (var files = Files.newDirectoryStream(directory, stem + "-part-??????.jsonl")) {
            for (Path file : files) {
                var name = CAPTURE_FILE.matcher(file.getFileName().toString());
                if (name.matches()) latest = Math.max(latest, Integer.parseInt(name.group(1)));
            }
        }
        return latest;
    }

    private static boolean appendIfFits(Path path, byte[] line, long segmentBytes) throws IOException {
        try (var file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = file.lock()) {
            long size = file.size();
            if (size > segmentBytes - line.length) return false;
            file.position(size);
            try {
                ByteBuffer buffer = ByteBuffer.wrap(line);
                while (buffer.hasRemaining()) file.write(buffer);
            } catch (IOException e) {
                try { file.truncate(size); }
                catch (IOException failedTruncate) { e.addSuppressed(failedTruncate); }
                throw e;
            }
            return true;
        }
    }

    private void warnWriteFailed(String stem, String reason) {
        if (writeWarnings.asMap().putIfAbsent(stem + ":" + reason, true) == null)
            log.warn("[ConditionCapture] write unavailable for {}: {}", stem, reason);
    }

    private void prune(LocalDate day) {
        if (day.equals(lastPruned)) return;
        try (var files = Files.list(directory)) {
            activeSegments.keySet().removeIf(stem -> !stem.startsWith(day.toString()));
            for (Path owned : files.filter(p -> CAPTURE_FILE.matcher(p.getFileName().toString()).matches())
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = owned.getFileName().toString();
                try {
                    if (LocalDate.parse(name.substring(0, 10)).isBefore(day.minusDays(60))) Files.deleteIfExists(owned);
                } catch (DateTimeParseException ignored) { }
            }
            lastPruned = day;
        } catch (IOException e) {
            log.warn("[ConditionCapture] retention cleanup unavailable: {}", e.getClass().getSimpleName());
        }
    }
    public synchronized void watch(List<ConditionSignalDto> signals) {
        watched.entrySet().removeIf(e -> Instant.parse(e.getValue()).isBefore(clock.instant().minus(Duration.ofDays(35))));
        for (var signal:signals) if (signal.stockCode()!=null && signal.stockCode().matches("[0-9]{6}"))
            watched.put(signal.stockCode(),clock.instant().toString());
        while (watched.size()>500) watched.remove(watched.keySet().iterator().next());
        try {
            Path temp=directory.resolve("watchlist.tmp");
            mapper.writeValue(temp.toFile(),watched);
            Files.move(temp,directory.resolve("watchlist.json"),StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception e) { log.warn("[ConditionCapture] watchlist unavailable: {}",e.getClass().getSimpleName()); }
    }
    synchronized List<String> watchedCodes() {
        watched.entrySet().removeIf(e -> Instant.parse(e.getValue()).isBefore(clock.instant().minus(Duration.ofDays(35))));
        return List.copyOf(watched.keySet());
    }
    @Scheduled(fixedDelayString="${bitman.condition.capture-interval-ms:180000}", initialDelay=90000)
    public void collect() {
        Instant start=clock.instant(); var now=start.atZone(KST);
        if (!KoreanMarketCalendar.isTradingDay(now.toLocalDate()) || now.getHour()<9
            || now.toLocalTime().isAfter(LocalTime.of(15,40))) return;
        var codes=watchedCodes(); if(codes.isEmpty()) return;
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        int attempted=0;
        for(int i=0;i<Math.min(60,codes.size()) && System.nanoTime()<deadline;i++) {
            String code=codes.get(Math.floorMod(cursor++,codes.size())); attempted++;
            try {
                var minute=kis.fetchMinuteEvidence(code,clock.instant(),deadline);
                if(minute!=null) record("minute","FOLLOWUP",code,minute.fetchedAt(),minute);
            } catch(RuntimeException ignored) { record("minute","FOLLOWUP",code,clock.instant(),Map.of("status","UNAVAILABLE")); }
        }
        record("coverage","FOLLOWUP","pool",start,Map.of("watched",codes.size(),"attempted",attempted,
            "basis","PROVIDER_MINUTE_BARS_PARTIAL_COVERAGE","deadlineSeconds",20));
    }
}
