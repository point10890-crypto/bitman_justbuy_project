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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Market-only observations for matched-input research. Never a publication or order source. */
@Service
public class ConditionCaptureStore {
    private static final Logger log = LoggerFactory.getLogger(ConditionCaptureStore.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long SHARED_CAP_BYTES = 128L * 1024 * 1024;
    private static final long BREAKOUT_CAP_BYTES = 16L * 1024 * 1024;
    private static final Set<String> BREAKOUT_KINDS = Set.of(
        "source", "candidate_selection", "verified_publication", "realtime_snapshot");
    private final ObjectMapper mapper;
    private final KisApiService kis;
    private final Path directory;
    private final Clock clock;
    private final Cache<String, Long> written = Caffeine.newBuilder().maximumSize(20000).build();
    private final Map<String, String> watched = new LinkedHashMap<>();
    private int cursor;
    private LocalDate lastPruned;

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
        boolean shared = !Objects.equals(written.getIfPresent(key), minute);
        boolean sidecar = isBreakoutStage(kind, mode, code, data)
            && !Objects.equals(written.getIfPresent(sidecarKey), minute);
        if (!shared && !sidecar) return;
        LocalDate day = recordedAt.atZone(KST).toLocalDate();
        prune(day);
        try {
            var row=new LinkedHashMap<String,Object>();
            row.put("schemaVersion",1); row.put("kind",kind); row.put("mode",mode); row.put("code",code);
            row.put("observedAt",observedAt.toString()); row.put("recordedAt",recordedAt.toString());
            row.put("data",data);
            byte[] line = (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
            // Keep the legacy log intact; its daily budget does not govern the dedicated scanner history.
            if (sidecar && append(day + "-breakout.jsonl", line, BREAKOUT_CAP_BYTES - line.length))
                written.put(sidecarKey, minute);
            if (shared && append(day + ".jsonl", line, SHARED_CAP_BYTES))
                written.put(key, minute);
        } catch(Exception e) { log.warn("[ConditionCapture] serialization unavailable: {}",e.getClass().getSimpleName()); }
    }

    private static boolean isBreakoutStage(String kind, String mode, String code, Object data) {
        if (!"BREAKOUT".equals(mode) || !"pool".equals(code)) return false;
        if (BREAKOUT_KINDS.contains(kind)) return true;
        if (!"publication".equals(kind) || !(data instanceof Map<?, ?> publication)) return false;
        Object status = publication.get("sourceStatus");
        return "REALTIME_SCAN".equals(status) || "REALTIME_EMPTY".equals(status);
    }

    private boolean append(String name, byte[] line, long maxExistingBytes) {
        try {
            Path file = directory.resolve(name);
            if (maxExistingBytes < 0 || Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) > maxExistingBytes) {
                log.warn("[ConditionCapture] daily budget exceeded for {}", name);
                return false;
            }
            Files.write(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (IOException e) {
            log.warn("[ConditionCapture] write unavailable for {}: {}", name, e.getClass().getSimpleName());
            return false;
        }
    }

    private void prune(LocalDate day) {
        if (day.equals(lastPruned)) return;
        try (var files = Files.list(directory)) {
            for (Path owned : files.filter(p -> p.getFileName().toString()
                    .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:-breakout)?\\.jsonl"))
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
