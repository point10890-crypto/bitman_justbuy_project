package com.bitman.justbuy.research.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Own public research files only; never opens the application's member DataSource. */
public final class EventResearchStore {
    private static final int MAX_BYTES=3_000_000;
    private static final int HISTORY_WINDOW=500, MAX_RECEIPTS_PER_DAY=5000;
    private static final long PENDING_HISTORY_SECONDS=60L*24*60*60;
    private static final Set<String> PERMANENT_UNMEASURABLE_REASONS=Set.of("INVALID_POLICY","INVALID_SIGNAL","SIGNAL_BEFORE_REGULAR_CLOSE","OFFICIAL_CALENDAR_COVERAGE_UNCONFIRMED","INVALID_RISK_LEVELS","RISK_PATH_REQUIRES_SIMULATION");
    private final Path directory;
    private final ObjectMapper mapper;
    interface Committer {void replace(Path temporary,Path target)throws IOException;}
    private final Committer committer;
    public EventResearchStore(Path directory,ObjectMapper mapper) {
        this(directory,mapper,(temporary,target)->Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING));
    }
    EventResearchStore(Path directory,ObjectMapper mapper,Committer committer) {
        this.directory=directory.toAbsolutePath().normalize();this.mapper=mapper.copy().findAndRegisterModules();
        this.committer=committer;
        try { Files.createDirectories(this.directory);rejectLink(this.directory);for(String child:List.of("cases","portfolios","first-seen","proofs")){Files.createDirectories(this.directory.resolve(child));rejectLink(this.directory.resolve(child));} }
        catch(IOException e){throw new IllegalStateException("RESEARCH_STORAGE_UNAVAILABLE");}
    }
    public synchronized Optional<JsonNode> load() { return read(directory.resolve("latest.json")); }
    public synchronized Instant firstSeen(String receipt,Instant proposed) {
        if(receipt==null||!receipt.matches("[0-9]{14}")||proposed==null)throw new IllegalArgumentException("INVALID_RECEIPT");
        String day=receipt.substring(0,8);try{LocalDate.parse(day,DateTimeFormatter.BASIC_ISO_DATE);}catch(RuntimeException e){throw new IllegalArgumentException("INVALID_RECEIPT");}
        ObjectNode legacy=existingObject(directory.resolve("first-seen.json"));
        if(legacy.has(receipt))return recordedFirstSeen(legacy.path(receipt));
        Path file=directory.resolve("first-seen").resolve(day+".json");ObjectNode all=existingObject(file);
        if(all.has(receipt))return recordedFirstSeen(all.path(receipt));
        if(all.size()>=MAX_RECEIPTS_PER_DAY)throw new IllegalStateException("RESEARCH_DAILY_RECEIPT_LIMIT");
        all.put(receipt,proposed.toString());write(file,all);return proposed;
    }
    public synchronized void save(JsonNode snapshot) {
        if(snapshot==null||!snapshot.isObject()||snapshot.path("status").asText().isBlank()||!snapshot.path("candidates").isArray()
            ||snapshot.path("candidates").size()>40)throw new IllegalArgumentException("INVALID_SNAPSHOT");
        try{Instant.parse(snapshot.path("asOf").asText());}catch(RuntimeException e){throw new IllegalArgumentException("INVALID_SNAPSHOT_TIME");}
        byte[] serialized=serialize(snapshot); // Validate before touching old success or case history.
        // Only a previously committed latest snapshot proves that a case was published.
        for(JsonNode candidate:snapshot.path("candidates"))validateCaseId(candidate.path("caseId").asText());
        load().ifPresent(prior->prior.path("candidates").forEach(this::writeProof));
        Map<Path,byte[]> before=new LinkedHashMap<>();
        try {for(JsonNode candidate:snapshot.path("candidates")) {
            String id=candidate.path("caseId").asText();if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_CASE_ID");
            Path file=directory.resolve("cases").resolve(id+".json");
            try{before.put(file,Files.exists(file)?Files.readAllBytes(file):null);}catch(IOException e){throw new IllegalStateException("RESEARCH_CASE_READ_FAILED");}
            write(file,candidate);
        }
        atomic(directory.resolve("latest.json"),serialized);
        }catch(RuntimeException failure){
            before.forEach((file,bytes)->{try{if(bytes==null)Files.deleteIfExists(file);else atomic(file,bytes);}catch(IOException|RuntimeException ignored){}});
            throw failure;
        }
    }
    public synchronized Optional<JsonNode> caseById(String id) {
        if(id==null||!id.matches("[a-f0-9]{64}"))return Optional.empty();
        return caseById(id,committedIndex(),latestCases());
    }
    private Optional<JsonNode> caseById(String id,ObjectNode legacy,Map<String,JsonNode> latest) {
        if(latest.containsKey(id)){JsonNode candidate=latest.get(id);
            var stored=read(directory.resolve("cases").resolve(id+".json"));return Optional.of(stored.filter(n->proof(n).equals(proof(candidate))).orElse(candidate.deepCopy()));
        }
        JsonNode expected=committedRecord(id,legacy);if(!expected.isObject())return Optional.empty();JsonNode confirmation=proof(expected);
        var stored=read(directory.resolve("cases").resolve(id+".json")).filter(n->confirmation.equals(proof(n)));
        return stored.isPresent()?stored:read(directory.resolve("cases").resolve(id+".previous.json")).filter(n->confirmation.equals(proof(n)));
    }
    /** Recent research plus still-maturing cases; old records remain addressable by their immutable ID. */
    public synchronized List<JsonNode> cases() {return selectHistory(true,false);}
    /** A recent, explicitly limited supplier-contract window, including amendments and withdrawals. */
    public synchronized List<JsonNode> relationshipHistory() {return selectHistory(false,true);}
    public synchronized boolean historyWindowLimited() {
        ObjectNode legacy=committedIndex();Map<String,JsonNode> latest=latestCases();int count=0;
        try(var files=Files.list(directory.resolve("cases"))) {var iterator=files.filter(this::isCasePath).iterator();while(iterator.hasNext()){
            String id=caseId(iterator.next());if(latest.containsKey(id)||committedRecord(id,legacy).isObject())if(++count>HISTORY_WINDOW)return true;
        }return false;}catch(IOException e){throw new IllegalStateException("RESEARCH_STORAGE_UNAVAILABLE");}
    }
    public synchronized void updateCase(JsonNode candidate) {
        String id=candidate.path("caseId").asText();if(!id.matches("[a-f0-9]{64}")||caseById(id).isEmpty())throw new IllegalArgumentException("UNKNOWN_CASE");
        Path file=directory.resolve("cases").resolve(id+".json");JsonNode previous=caseById(id).orElseThrow();
        write(directory.resolve("cases").resolve(id+".previous.json"),previous);write(file,candidate);
        try{writeProof(candidate);}catch(RuntimeException e){write(file,previous);throw e;}
    }
    public synchronized boolean capturePortfolio(JsonNode capture) {
        String day=capture.path("decisionDate").asText();try{java.time.LocalDate.parse(day);}catch(RuntimeException e){throw new IllegalArgumentException("INVALID_PORTFOLIO_DATE");}
        if(!day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!capture.path("baselineWeights").isObject()||!capture.path("challengerWeights").isObject())throw new IllegalArgumentException("INVALID_PORTFOLIO_CAPTURE");
        Path file=directory.resolve("portfolios").resolve(day+".json");if(Files.exists(file))return false;write(file,capture);return true;
    }
    public synchronized List<JsonNode> portfolios() {
        try(var files=Files.list(directory.resolve("portfolios"))){return files.filter(p->p.getFileName().toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")).sorted(Comparator.reverseOrder()).limit(366).sorted().map(this::read).flatMap(Optional::stream).toList();}
        catch(IOException e){throw new IllegalStateException("RESEARCH_PORTFOLIO_READ_FAILED");}
    }
    public synchronized void updatePortfolio(JsonNode capture) {
        String day=capture.path("decisionDate").asText();if(!day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("INVALID_PORTFOLIO_DATE");
        Path file=directory.resolve("portfolios").resolve(day+".json");JsonNode previous=read(file).orElseThrow(()->new IllegalArgumentException("UNKNOWN_PORTFOLIO"));
        for(String field:List.of("decisionAt","decisionDate","baselineWeights","challengerWeights","inputVersion"))if(!previous.path(field).equals(capture.path(field)))throw new IllegalArgumentException("FROZEN_PORTFOLIO_MUTATION");write(file,capture);
    }
    private record HistoryEntry(String id,Instant publishedAt) {}
    private static final Comparator<HistoryEntry> HISTORY_ORDER=Comparator.comparing(HistoryEntry::publishedAt).thenComparing(HistoryEntry::id);
    private List<JsonNode> selectHistory(boolean includeMaturing,boolean supplierOnly) {
        ObjectNode legacy=committedIndex();Map<String,JsonNode> latest=latestCases();
        PriorityQueue<HistoryEntry> recent=new PriorityQueue<>(HISTORY_ORDER);Map<String,HistoryEntry> maturing=new HashMap<>();
        Instant cutoff=Instant.now().minusSeconds(PENDING_HISTORY_SECONDS);
        try(var files=Files.list(directory.resolve("cases"))) {var iterator=files.filter(this::isCasePath).iterator();while(iterator.hasNext()){
            String id=caseId(iterator.next());JsonNode header=latest.containsKey(id)?latest.get(id):committedRecord(id,legacy);if(!header.isObject())continue;
            // Legacy aggregate proofs contain no selection metadata; validate and derive it from the published case.
            if(!header.has("evaluationTerminal")&&!latest.containsKey(id))header=caseById(id,legacy,latest).orElse(null);
            if(header==null||supplierOnly&&"CUSTOMER_EVENT_PROPAGATION".equals(header.path("origin").asText()))continue;
            Instant published;try{published=Instant.parse(publication(header));}catch(RuntimeException invalid){continue;}
            HistoryEntry entry=new HistoryEntry(id,published);recent.offer(entry);if(recent.size()>HISTORY_WINDOW)recent.poll();
            boolean terminal=header.has("evaluationTerminal")?header.path("evaluationTerminal").asBoolean():evaluationTerminal(header);
            Instant maturityAnchor=published;try{if(header.hasNonNull("evaluationPublishedAt"))maturityAnchor=Instant.parse(header.path("evaluationPublishedAt").asText());}catch(RuntimeException ignored){}
            if(includeMaturing&&!terminal&&!maturityAnchor.isBefore(cutoff))maturing.put(id,entry);
        }}catch(IOException e){throw new IllegalStateException("RESEARCH_STORAGE_UNAVAILABLE");}
        Map<String,HistoryEntry> selected=new HashMap<>(maturing);for(HistoryEntry entry:recent)selected.put(entry.id(),entry);
        var order=supplierOnly?HISTORY_ORDER.reversed():HISTORY_ORDER;
        return selected.values().stream().sorted(order).map(e->caseById(e.id(),legacy,latest)).flatMap(Optional::stream).toList();
    }
    private boolean isCasePath(Path path){return path.getFileName().toString().matches("[a-f0-9]{64}\\.json");}
    private static String caseId(Path path){return path.getFileName().toString().replace(".json","");}
    private Map<String,JsonNode> latestCases(){Map<String,JsonNode> cases=new HashMap<>();load().ifPresent(snapshot->snapshot.path("candidates").forEach(c->{String id=c.path("caseId").asText();if(id.matches("[a-f0-9]{64}"))cases.put(id,c);}));return cases;}
    private JsonNode committedRecord(String id,ObjectNode legacy){Path file=directory.resolve("proofs").resolve(id+".json");return Files.exists(file,LinkOption.NOFOLLOW_LINKS)?read(file).orElse(mapper.getNodeFactory().missingNode()):legacy.path(id);}
    private void writeProof(JsonNode candidate){String id=candidate.path("caseId").asText();validateCaseId(id);ObjectNode record=proof(candidate);record.put("origin",candidate.path("origin").asText());record.put("evaluationTerminal",evaluationTerminal(candidate));write(directory.resolve("proofs").resolve(id+".json"),record);}
    private static boolean evaluationTerminal(JsonNode candidate){
        Set<Integer> terminal=new HashSet<>();
        for(JsonNode outcome:candidate.path("outcomes")){
            String state=outcome.path("state").asText();boolean finished=Set.of("COMPLETE","UNFILLABLE").contains(state);
            if("UNMEASURABLE".equals(state)&&outcome.path("reasons").isArray()&&!outcome.path("reasons").isEmpty()){
                finished=true;for(JsonNode reason:outcome.path("reasons"))if(!PERMANENT_UNMEASURABLE_REASONS.contains(reason.asText()))finished=false;
            }
            if(finished)terminal.add(outcome.path("horizonSessions").asInt());
        }
        return terminal.containsAll(Set.of(1,3,5,20));
    }
    private static void validateCaseId(String id){if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_CASE_ID");}
    private ObjectNode existingObject(Path file){if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return mapper.createObjectNode();return (ObjectNode)read(file).orElseThrow(()->new IllegalStateException("RESEARCH_INDEX_UNREADABLE"));}
    private static Instant recordedFirstSeen(JsonNode value){try{return Instant.parse(value.asText());}catch(RuntimeException e){throw new IllegalStateException("INVALID_FIRST_SEEN_RECORD");}}
    private Optional<JsonNode> read(Path file) {
        try {rejectLink(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)return Optional.empty();
            JsonNode node=mapper.readTree(Files.readAllBytes(file));return node!=null&&node.isObject()?Optional.of(node.deepCopy()):Optional.empty();
        }catch(IOException|RuntimeException e){return Optional.empty();}
    }
    private byte[] serialize(JsonNode node) {
        try {byte[] bytes=mapper.writeValueAsBytes(node);if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("RESEARCH_SNAPSHOT_TOO_LARGE");return bytes;}
        catch(IOException e){throw new IllegalArgumentException("INVALID_RESEARCH_JSON");}
    }
    private void write(Path path,JsonNode node){atomic(path,serialize(node));}
    private void atomic(Path target,byte[] bytes) {
        Path temporary=null;
        try {rejectLink(target);rejectLink(target.getParent());temporary=Files.createTempFile(target.getParent(),".research-",".tmp");
            Files.write(temporary,bytes);committer.replace(temporary,target);
        }catch(IOException e){throw new IllegalStateException("RESEARCH_ATOMIC_WRITE_FAILED");}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    private static void rejectLink(Path path)throws IOException {if(Files.isSymbolicLink(path))throw new IOException("RESEARCH_LINK_REJECTED");}
    private ObjectNode committedIndex(){return read(directory.resolve("committed.json")).filter(JsonNode::isObject).map(n->(ObjectNode)n).orElseGet(mapper::createObjectNode);}
    private static String publication(JsonNode candidate){return candidate.path("publishedAt").asText(candidate.path("firstSeenAt").asText(""));}
    private ObjectNode proof(JsonNode candidate){return mapper.createObjectNode().put("publishedAt",publication(candidate)).put("evaluationPublishedAt",candidate.path("evaluationPublishedAt").asText(null)).put("policyVersion",candidate.path("policyVersion").asText(null));}
}
