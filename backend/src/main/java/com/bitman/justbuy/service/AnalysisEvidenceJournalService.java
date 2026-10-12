package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Bounded, asynchronous audit of public stock facts, never raw prompts or member information. */
@Service
public class AnalysisEvidenceJournalService implements AutoCloseable {
    public static final String VERSION = "analysis-evidence-v1-20261012";
    private static final int MAX_BYTES = 1_048_576;
    private static final Set<String> STAGES = Set.of("CANDIDATE_EVIDENCE", "AGENT_PUBLICATION_DRAFT", "FINAL_PUBLICATION", "CONDITION_VERIFY", "SOURCE_PROBE");
    private static final Set<String> MODES = Set.of("BREAKOUT", "FLOW_LEADER", "REVERSAL_EDGE", "CATALYST_BURST", "KEYWORD_DUAL_NET_BUY");
    private static final java.util.regex.Pattern OWNED = java.util.regex.Pattern.compile("[0-9]{8}-[0-9]{9}-[0-9a-f-]{36}\\.json");
    private final ObjectMapper mapper;
    private final Path directory;
    private final Clock clock;
    private final int fileLimit;
    private final BoundedMarketRefreshQueue queue = new BoundedMarketRefreshQueue("analysis-evidence-journal", 64);

    @Autowired
    public AnalysisEvidenceJournalService(ObjectMapper mapper, @Value("${bitman.storage.data-dir:./data}") String dataDir) {
        this(mapper, dataDir, Clock.systemUTC(), 1000);
    }
    AnalysisEvidenceJournalService(ObjectMapper mapper, String dataDir, Clock clock, int fileLimit) {
        this.mapper=mapper; this.directory=Path.of(dataDir).toAbsolutePath().normalize().resolve("analysis-evidence");
        this.clock=clock; this.fileLimit=Math.max(1,Math.min(1000,fileLimit));
    }

    /** Capture-time serialization prevents later caller mutations from rewriting historical evidence. */
    public boolean capture(String stage, String mode, List<String> candidates, Map<String,Object> facts, List<? extends Map<String,?>> decisions) {
        if(!STAGES.contains(stage))return false;
        try {
            Instant capturedAt=clock.instant();
            Object safeFacts=sanitize(mapper.convertValue(facts == null ? Map.of() : facts,Object.class),0);
            Object safeDecisions=sanitize(decisions == null ? List.of() : decisions,0);
            var payload=new LinkedHashMap<String,Object>();
            payload.put("schemaVersion",VERSION); payload.put("stage",stage); payload.put("mode",MODES.contains(mode)?mode:"OTHER");
            payload.put("capturedAt",capturedAt.toString());
            payload.put("candidateCodes",candidates==null?List.of():candidates.stream().filter(Objects::nonNull).filter(c->c.matches("[0-9]{6}")).distinct().limit(100).toList());
            payload.put("facts",safeFacts); payload.put("decisions",safeDecisions);
            payload.put("inputDigest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(safeFacts))));
            byte[] bytes=mapper.writeValueAsBytes(payload);
            if(bytes.length>MAX_BYTES)return false;
            String id=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC).format(capturedAt)+"-"+UUID.randomUUID();
            return queue.offer(id,()->write(id,bytes,capturedAt));
        }catch(Exception ignored){return false;}
    }

    public Map<String,Object> status() {
        var result=new LinkedHashMap<>(queue.snapshot()); result.put("schemaVersion",VERSION);
        result.put("retentionDays",30); result.put("fileLimit",fileLimit); result.put("status","BOUNDED_ASYNC");
        return Collections.unmodifiableMap(result);
    }

    private void write(String id,byte[] bytes,Instant capturedAt) {
        Path temporary=directory.resolve(id+".tmp"),target=directory.resolve(id+".json");
        try {
            Files.createDirectories(directory);
            // Do not follow a symlink into another data directory.
            if(Files.isSymbolicLink(directory))throw new java.io.IOException("Invalid journal directory");
            Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,target);}
            Files.setLastModifiedTime(target,java.nio.file.attribute.FileTime.from(capturedAt));
            prune(capturedAt);
        }catch(Exception ignored){
            try{Files.deleteIfExists(temporary);}catch(Exception cleanupIgnored){}
            throw new IllegalStateException("Journal write unavailable");
        }
    }

    private void prune(Instant now) throws java.io.IOException {
        List<Path> owned;
        try(var files=Files.list(directory)){owned=files.filter(p->OWNED.matcher(p.getFileName().toString()).matches())
            .filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted(Comparator.comparing(this::modified)).toList();}
        int remaining=owned.size();
        for(Path path:owned)if(modified(path).isBefore(now.minus(Duration.ofDays(30)))||remaining>fileLimit){Files.deleteIfExists(path);remaining--;}
    }
    private Instant modified(Path path) {try{return Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant();}catch(Exception ignored){return Instant.EPOCH;}}

    // Only numeric facts, provenance enums and dates survive; free-form prose and URLs are omitted.
    private static Object sanitize(Object value,int depth) {
        if(depth>8)return null;
        if(value instanceof Map<?,?> map){
            var clean=new TreeMap<String,Object>();
            for(var entry:map.entrySet()){
                if(clean.size()>=150)break;
                if(!(entry.getKey() instanceof String key)||!key.matches("[a-zA-Z0-9_]{1,64}")||key.toLowerCase(Locale.ROOT).matches(".*(?:key|token|secret|password|email|query|content|reason|summary|url|user|name).*"))continue;
                Object item=publicFactContext(key,entry.getValue());
                if(item==null)item=sanitize(entry.getValue(),depth+1);if(item!=null)clean.put(key,item);
            }
            return clean;
        }
        if(value instanceof Collection<?> list)return list.stream().limit(100).map(v->sanitize(v,depth+1)).filter(Objects::nonNull).toList();
        if(value instanceof Boolean)return value;
        if(value instanceof Number n){try{return new BigDecimal(n.toString());}catch(RuntimeException ignored){return null;}}
        if(value instanceof String s && s.length()<=80){
            if(s.matches("[0-9]{6,14}")||s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9:.]+(?:Z|[+-][0-9:]+))?")
                ||s.matches("[A-Z][A-Z0-9_]{0,60}")||Set.of("chatgpt","deepseek","grok","gemini","success","error","skipped","KIS OpenAPI","Kiwoom REST","DART OpenAPI").contains(s))return s;
        }
        return null;
    }
    private static Object publicFactContext(String key,Object value){
        if(!(value instanceof String text)||text.length()>256)return null;
        if("currentPrice".equals(key)&&text.matches("(?:[0-9]{1,12}|[0-9]{1,3}(?:,[0-9]{3}){1,3})(?:\\.[0-9]{1,6})?"))return new BigDecimal(text.replace(",",""));
        if("field".equals(key)&&safeFactPointer(text))return text;
        if("factId".equals(key)&&text.matches("[0-9]{6}:/.+")&&safeFactPointer(text.substring(7)))return text;
        if("period".equals(key)&&text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} ~ [0-9]{4}-[0-9]{2}-[0-9]{2}")){
            try{LocalDate from=LocalDate.parse(text.substring(0,10)),to=LocalDate.parse(text.substring(13));if(!to.isBefore(from))return text;}
            catch(java.time.format.DateTimeParseException ignored){}
        }
        if("reportCode".equals(key)&&Set.of("11011","11012","11013","11014").contains(text))return text;
        if(Set.of("unit","priceUnit","quantityUnit","netBuyUnit","volumeUnit","derivedPercentUnit").contains(key)&&Set.of("KRW","shares","percent","%","주","원","ratio","score").contains(text))return text;
        if(Set.of("period","cashFlowPeriod").contains(key)&&text.matches("[0-9]{4} 사업연도 (?:연간|(?:1분기|반기|3분기) (?:누적|당분기\\(3개월\\)))"))return text;
        if("balancePeriod".equals(key)&&text.matches("[0-9]{4} 사업연도 (?:연간|1분기|반기|3분기) 보고서 기말\\(날짜 unknown\\)"))return text;
        if("selectionBasis".equals(key)&&text.matches("BOUNDED_REPORT_LOOKUP_LATEST_UNKNOWN;REQUEST_BOUND_STOCK_FS;INCOME_(?:IS|CIS)"))return text;
        return null;
    }
    private static boolean safeFactPointer(String text){
        return text.matches("(?:/[a-zA-Z0-9_]{1,64}){1,8}")
            &&!text.toLowerCase(Locale.ROOT).matches(".*(?:key|token|secret|password|email|query|content|reason|summary|url|user|name).*" );
    }
    @Override @PreDestroy public void close(){queue.close();}
}
