package com.bitman.justbuy.research;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Standalone research ledger. Never uses Spring's DataSource or a member database. */
public final class ResearchRunStore implements AutoCloseable {
    public static final int MAX_JSON_BYTES = 1_048_576;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final String OWNER_MARKER = "research-core-v1";
    private final Clock clock;
    private final String jdbcUrl;
    private final ObjectMapper mapper;
    private volatile boolean closed;

    public record Lease(String runId,String stage,String fingerprint,String token,long reservedCostMicros) {}
    public record StageView(String stage,String fingerprint,String status,long reservedCostMicros,String outputDigest,boolean validated) {}
    public record RunView(String runId,String owner,String inputDigest,boolean cancelled,List<StageView> stages) {
        public RunView { stages = List.copyOf(stages); }
    }

    public ResearchRunStore(Path workspace, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        Path owned = ownWorkspace(workspace);
        jdbcUrl = "jdbc:h2:file:" + owned.resolve("research-ledger/ledger").toString().replace('\\','/') + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000";
        mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(MAX_JSON_BYTES).maxNumberLength(1000).build());
        transaction(c -> {
            try (Statement s=c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS research_runs (run_id VARCHAR(64) PRIMARY KEY, owner_scope VARCHAR(512) NOT NULL, input_digest VARCHAR(512) NOT NULL, cancelled BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS research_attempts (token VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL REFERENCES research_runs(run_id), stage_name VARCHAR(512) NOT NULL, fingerprint VARCHAR(512) NOT NULL, generation BIGINT NOT NULL, status VARCHAR(32) NOT NULL, reserved_cost BIGINT NOT NULL, lease_until BIGINT NOT NULL, output_json CLOB, output_digest VARCHAR(64), validated BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(run_id,stage_name,generation))");
            }
            return null;
        });
    }

    /** Same owner/input returns the existing immutable run, including a cancelled run. */
    public String createRun(String owner,String digest) {
        text(owner); text(digest);
        String id = sha256(owner.length() + ":" + owner + digest);
        return transaction(c -> {
            try (var s=prepare(c,"INSERT INTO research_runs(run_id,owner_scope,input_digest,created_at) SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM research_runs WHERE run_id=?)",id,owner,digest,clock.millis(),id)) { s.executeUpdate(); }
            catch(SQLException conflict) { if(!"23505".equals(conflict.getSQLState())) throw conflict; }
            RunIdentity existing=lockRun(c,id,true);
            if(!existing.owner.equals(owner) || !existing.digest.equals(digest)) throw new IllegalStateException("Research run identity collision");
            return id;
        });
    }

    public Optional<String> reusedOutput(String run,String stage,String fingerprint) {
        text(stage); text(fingerprint);
        return transaction(c -> {
            if(lockRun(c,run,true).cancelled) return Optional.empty();
            Attempt latest = latest(c,run,stage);
            if(latest==null || !latest.fingerprint.equals(fingerprint) || !latest.status.equals("COMPLETE") || !latest.validated) return Optional.empty();
            if(!sha256(latest.output).equals(latest.outputDigest)) throw new IllegalStateException("Stored output digest mismatch");
            validateJson(latest.output);
            return Optional.of(latest.output);
        });
    }

    public Lease reserveStage(String run,String stage,String fingerprint,long costMicros) {
        text(stage); text(fingerprint);
        if(costMicros<0) throw new IllegalArgumentException("Cost reservation must be nonnegative");
        return transaction(c -> {
            lockRun(c,run,false);
            Attempt old=latest(c,run,stage);
            if(old!=null) {
                if(old.status.equals("UNCERTAIN")) throw new IllegalStateException("Uncertain reservation requires reconciliation");
                if(old.status.equals("COMPLETE") && old.validated && old.fingerprint.equals(fingerprint)) throw new IllegalStateException("Validated stage already complete; reuse it");
                if(old.status.equals("RESERVED")) {
                    if(old.fingerprint.equals(fingerprint) && old.leaseUntil>clock.millis()) throw new IllegalStateException("Stage already reserved");
                    if(old.cost>0) {
                        setStatus(c,old.token,"UNCERTAIN");
                        throw new CommitRejection("Paid request completion unknown; reservation retained");
                    }
                    setStatus(c,old.token,"SUPERSEDED");
                }
            }
            String token=UUID.randomUUID().toString();
            try(var s=prepare(c,"INSERT INTO research_attempts(token,run_id,stage_name,fingerprint,generation,status,reserved_cost,lease_until) VALUES(?,?,?,?,?,'RESERVED',?,?)",token,run,stage,fingerprint,old==null?1:Math.addExact(old.generation,1),costMicros,Math.addExact(clock.millis(),LEASE_DURATION.toMillis()))) { s.executeUpdate(); }
            return new Lease(run,stage,fingerprint,token,costMicros);
        });
    }

    public void completeStage(Lease lease,String outputJson,boolean validated) {
        Objects.requireNonNull(lease); validateJson(outputJson);
        transaction(c -> {
            requireLease(c,lease);
            try(var s=prepare(c,"UPDATE research_attempts SET status=?,output_json=?,output_digest=?,validated=? WHERE token=?",validated?"COMPLETE":"UNVALIDATED",outputJson,sha256(outputJson),validated,lease.token())) { s.executeUpdate(); }
            return null;
        });
    }

    public void markUncertain(Lease lease) {
        Objects.requireNonNull(lease);
        transaction(c -> { requireLease(c,lease); setStatus(c,lease.token(),"UNCERTAIN"); return null; });
    }

    public void cancel(String run) {
        transaction(c -> {
            lockRun(c,run,true);
            try(var s=prepare(c,"UPDATE research_runs SET cancelled=TRUE WHERE run_id=?",run)) { s.executeUpdate(); }
            try(var s=prepare(c,"UPDATE research_attempts SET status=CASE WHEN reserved_cost>0 THEN 'UNCERTAIN' ELSE 'CANCELLED' END WHERE run_id=? AND status='RESERVED'",run)) { s.executeUpdate(); }
            return null;
        });
    }

    public RunView view(String run) {
        return transaction(c -> {
            RunIdentity identity=lockRun(c,run,true);
            var stages=new ArrayList<StageView>();
            try(var s=prepare(c,"SELECT stage_name,fingerprint,status,reserved_cost,output_digest,validated FROM research_attempts WHERE run_id=? ORDER BY stage_name,generation",run);var r=s.executeQuery()) {
                while(r.next()) stages.add(new StageView(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5),r.getBoolean(6)));
            }
            return new RunView(run,identity.owner,identity.digest,identity.cancelled,stages);
        });
    }

    private Attempt requireLease(Connection c,Lease lease) throws SQLException {
        lockRun(c,lease.runId(),false);
        Attempt a=latest(c,lease.runId(),lease.stage());
        if(a==null || !a.token.equals(lease.token()) || !a.fingerprint.equals(lease.fingerprint()) || a.cost!=lease.reservedCostMicros() || !a.status.equals("RESERVED") || a.leaseUntil<=clock.millis()) throw new IllegalStateException("Stale or invalid stage lease");
        return a;
    }

    private record RunIdentity(String owner,String digest,boolean cancelled) {}
    private RunIdentity lockRun(Connection c,String run,boolean allowCancelled) throws SQLException {
        text(run);
        try(var s=prepare(c,"SELECT owner_scope,input_digest,cancelled FROM research_runs WHERE run_id=? FOR UPDATE",run);var r=s.executeQuery()) {
            if(!r.next()) throw new IllegalArgumentException("Unknown research run");
            RunIdentity id=new RunIdentity(r.getString(1),r.getString(2),r.getBoolean(3));
            if(id.cancelled && !allowCancelled) throw new IllegalStateException("Research run cancelled");
            return id;
        }
    }

    private record Attempt(String token,String fingerprint,long generation,String status,long cost,long leaseUntil,String output,String outputDigest,boolean validated) {}
    private Attempt latest(Connection c,String run,String stage) throws SQLException {
        try(var s=prepare(c,"SELECT token,fingerprint,generation,status,reserved_cost,lease_until,output_json,output_digest,validated FROM research_attempts WHERE run_id=? AND stage_name=? ORDER BY generation DESC LIMIT 1",run,stage);var r=s.executeQuery()) {
            return r.next()?new Attempt(r.getString(1),r.getString(2),r.getLong(3),r.getString(4),r.getLong(5),r.getLong(6),r.getString(7),r.getString(8),r.getBoolean(9)):null;
        }
    }

    private static void setStatus(Connection c,String token,String status) throws SQLException {
        try(var s=prepare(c,"UPDATE research_attempts SET status=? WHERE token=?",status,token)) { s.executeUpdate(); }
    }
    private static PreparedStatement prepare(Connection c,String sql,Object... args) throws SQLException {
        var s=c.prepareStatement(sql); for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); return s;
    }
    private interface Transaction<T> { T run(Connection c) throws SQLException; }
    private static final class CommitRejection extends RuntimeException { CommitRejection(String message) { super(message); } }
    private <T> T transaction(Transaction<T> operation) {
        if(closed) throw new IllegalStateException("Research ledger closed");
        try(Connection c=DriverManager.getConnection(jdbcUrl,"sa","")) {
            c.setAutoCommit(false);
            try { T result=operation.run(c); c.commit(); return result; }
            catch(CommitRejection reject) { c.commit(); throw new IllegalStateException(reject.getMessage()); }
            catch(RuntimeException | SQLException failure) { c.rollback(); throw failure; }
        } catch(SQLException failure) { throw new IllegalStateException("Research ledger transaction failed",failure); }
    }

    private void validateJson(String json) {
        if(json==null || json.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES) throw new IllegalArgumentException("Output JSON exceeds bound or is absent");
        try { if(mapper.readTree(json)==null) throw new IllegalArgumentException("Output JSON absent"); }
        catch(IOException failure) { throw new IllegalArgumentException("Invalid output JSON"); }
    }
    private static void text(String value) {
        if(value==null || value.isBlank() || value.length()>512 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid ledger identifier");
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Path ownWorkspace(Path workspace) {
        Objects.requireNonNull(workspace);
        Path owned=workspace.toAbsolutePath().normalize();
        if(owned.getParent()==null || owned.toString().contains(";") || owned.toString().contains("\n")) throw new IllegalArgumentException("Invalid research workspace path");
        try {
            rejectLinks(owned);
            Files.createDirectories(owned);
            Path marker=owned.resolve(".research-core-owner");
            if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) {
                try(var children=Files.list(owned)) { if(children.findAny().isPresent()) throw new IllegalArgumentException("Workspace is not an empty owned research directory"); }
                Files.writeString(marker,OWNER_MARKER,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            } else if(Files.isSymbolicLink(marker) || !Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) || Files.size(marker)>64 || !Files.readString(marker).equals(OWNER_MARKER)) throw new IllegalArgumentException("Invalid research workspace ownership marker");
            Path namespace=owned.resolve("research-ledger");
            rejectLinks(namespace);
            Files.createDirectories(namespace);
            try(var children=Files.list(namespace)) {
                for(Path child:children.toList()) {
                    rejectLinks(child);
                    if(!Set.of("ledger.mv.db","ledger.lock.db","ledger.trace.db","ledger.temp.db").contains(child.getFileName().toString())) throw new IllegalArgumentException("Unowned file in research namespace");
                }
            }
            return owned;
        } catch(IOException failure) { throw new IllegalArgumentException("Cannot own research workspace",failure); }
    }
    private static void rejectLinks(Path path) throws IOException {
        for(Path part=path;part!=null;part=part.getParent()) {
            if(Files.isSymbolicLink(part)) throw new IllegalArgumentException("Symlink research boundary rejected");
            if(Files.exists(part,LinkOption.NOFOLLOW_LINKS) && !part.toRealPath().equals(part.toAbsolutePath().normalize())) throw new IllegalArgumentException("Redirected research boundary rejected");
        }
    }
    @Override public void close() { closed=true; }
}
