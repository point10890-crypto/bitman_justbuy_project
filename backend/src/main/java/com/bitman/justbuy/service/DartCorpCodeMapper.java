package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Cache-only stock-to-company lookup. Official downloads belong to independent maintenance. */
@Service
public class DartCorpCodeMapper {
    private static final Logger log = LoggerFactory.getLogger(DartCorpCodeMapper.class);
    private static final int MAX_BYTES = 4 * 1_048_576;
    private final ObjectMapper objectMapper;
    private final Path cacheFilePath;
    private final Clock clock;
    private final Map<String,String> stockToCorpCode = new ConcurrentHashMap<>();
    private volatile Instant lastLoaded = Instant.EPOCH;
    private boolean invalidNumericEntries;
    private final Object boundedCacheLock = new Object();

    @Autowired
    public DartCorpCodeMapper(DartProperties properties, ObjectMapper mapper,
        @Value("${bitman.storage.data-dir:./data}") String dataDir) {
        this(properties, mapper, dataDir, Clock.systemUTC());
    }
    DartCorpCodeMapper(DartProperties properties, ObjectMapper mapper, String dataDir, Clock clock) {
        this.objectMapper=mapper; this.cacheFilePath=Path.of(dataDir,"dart_corp_codes.json"); this.clock=clock;
    }

    public String getCorpCode(String code) { return getCachedCorpCode(code); }
    public boolean isReady() {
        synchronized(boundedCacheLock) { if(!freshMemory()) loadFreshDisk(); return freshMemory(); }
    }
    public String getCachedCorpCode(String code) {
        if(!validStock(code)||Thread.currentThread().isInterrupted()) return null;
        synchronized(boundedCacheLock) {
            if(!freshMemory()&&!loadFreshDisk()) return null;
            String corp=stockToCorpCode.get(code); return validCorp(corp)?corp:null;
        }
    }
    boolean cacheNeedsRefresh() {
        synchronized(boundedCacheLock) {
            if(!freshMemory()) loadFreshDisk();
            return !freshMemory() || invalidNumericEntries || !lastLoaded.isAfter(clock.instant().minus(Duration.ofDays(6)));
        }
    }
    int cachedMappingCount() { synchronized(boundedCacheLock) { return freshMemory()?stockToCorpCode.size():0; } }
    private boolean freshMemory() { return !stockToCorpCode.isEmpty()&&fresh(lastLoaded); }
    private boolean fresh(Instant time) {
        Instant now=clock.instant(); return !time.isAfter(now)&&time.isAfter(now.minus(Duration.ofDays(7)));
    }
    private boolean loadFreshDisk() {
        try {
            if(!Files.isRegularFile(cacheFilePath)||Files.size(cacheFilePath)>MAX_BYTES) return false;
            Instant modified=Files.getLastModifiedTime(cacheFilePath).toInstant(); if(!fresh(modified)) return false;
            byte[] bytes;
            try(InputStream input=Files.newInputStream(cacheFilePath)) { bytes=input.readNBytes(MAX_BYTES+1); }
            if(bytes.length>MAX_BYTES||Thread.currentThread().isInterrupted()) return false;
            JsonNode root;
            try(JsonParser parser=objectMapper.getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION); root=objectMapper.readTree(parser);
            }
            if(root==null||!root.isObject()||root.size()>20_000) return false;
            Map<String,String> valid=new LinkedHashMap<>(); boolean invalidSupported=false;
            var entries=root.fields();
            while(entries.hasNext()) {
                var entry=entries.next(); if(!validStock(entry.getKey())) continue;
                JsonNode value=entry.getValue();
                if(value.isTextual()&&validCorp(value.textValue())) valid.put(entry.getKey(),value.textValue());
                else invalidSupported=true;
            }
            if(valid.isEmpty()||Thread.currentThread().isInterrupted()||!fresh(modified)) return false;
            // All accesses and publications use this same lock: removed entries cannot inherit the new timestamp.
            stockToCorpCode.clear(); stockToCorpCode.putAll(valid); lastLoaded=modified; invalidNumericEntries=invalidSupported;
            return true;
        } catch(Exception error) {
            log.warn("[DartCorpCode] 캐시 확인 실패: {}",error.getClass().getSimpleName()); return false;
        }
    }

    /** Publish only after a successful full archive parse and atomic durable replacement. */
    void publishVerifiedMapping(Map<String,String> verified, Instant captured, Runnable check) throws IOException {
        if(verified==null||verified.isEmpty()||verified.size()>20_000||captured==null
            ||captured.isAfter(clock.instant())||verified.entrySet().stream().anyMatch(e->!validStock(e.getKey())||!validCorp(e.getValue())))
            throw new IOException("Invalid corporate mapping");
        check.run();
        byte[] bytes=objectMapper.writeValueAsBytes(new TreeMap<>(verified));
        if(bytes.length>MAX_BYTES) throw new IOException("Corporate cache exceeds limit");
        synchronized(boundedCacheLock) {
            check.run();
            Path parent=cacheFilePath.toAbsolutePath().getParent(); Files.createDirectories(parent);
            Path temporary=Files.createTempFile(parent,"dart-corp-", ".tmp");
            try {
                Files.write(temporary,bytes); Files.setLastModifiedTime(temporary,FileTime.from(captured)); check.run();
                Files.move(temporary,cacheFilePath.toAbsolutePath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                stockToCorpCode.clear(); stockToCorpCode.putAll(verified); lastLoaded=captured; invalidNumericEntries=false;
            } finally { Files.deleteIfExists(temporary); }
        }
    }
    static boolean validStock(String stock) { return stock!=null&&stock.matches("[0-9]{6}"); }
    static boolean validCorp(String corp) { return corp!=null&&corp.matches("[0-9]{8}"); }
}
