package com.bitman.justbuy.research.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class EventResearchStoreTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Test void restartRetainsFirstSeenAndPublishedCaseWithoutMutableReferenceLeaks() throws Exception {
        var store=new EventResearchStore(directory,mapper);
        Instant first=Instant.parse("2026-10-14T07:00:00Z");
        assertEquals(first,store.firstSeen("20261014000123",first));
        assertEquals(first,new EventResearchStore(directory,mapper).firstSeen("20261014000123",first.plusSeconds(90)));
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf",first.toString());
        snapshot.putArray("candidates").addObject().put("caseId","a".repeat(64)).put("firstSeenAt",first.toString());
        store.save(snapshot);snapshot.put("status","FORGED");
        assertEquals("COMPLETE",new EventResearchStore(directory,mapper).load().orElseThrow().path("status").asText());
        var read=store.load().orElseThrow();((com.fasterxml.jackson.databind.node.ObjectNode)read).put("status","FORGED");
        assertEquals("COMPLETE",store.load().orElseThrow().path("status").asText());
    }
    @Test void malformedOrOversizedReplacementLeavesPriorSuccessIntact() throws Exception {
        var store=new EventResearchStore(directory,mapper);
        var valid=mapper.createObjectNode().put("status","EMPTY").put("asOf","2026-10-14T07:00:00Z");valid.putArray("candidates");store.save(valid);
        assertThrows(IllegalArgumentException.class,()->store.save(mapper.createObjectNode().put("status","COMPLETE")));
        var giant=valid.deepCopy().put("text","x".repeat(4_000_001));
        assertThrows(IllegalArgumentException.class,()->store.save(giant));
        assertEquals("EMPTY",store.load().orElseThrow().path("status").asText());
    }
    @Test void unsafeReceiptAndSymlinkTargetsAreRejected() throws Exception {
        var store=new EventResearchStore(directory,mapper);
        assertThrows(IllegalArgumentException.class,()->store.firstSeen("../private",Instant.now()));
        Files.writeString(directory.resolve("latest.json"),"not JSON");
        assertTrue(store.load().isEmpty());
    }
    @Test void failedFinalPublicationCannotCreateReusableUnpublishedCase() {
        var store=new EventResearchStore(directory,mapper,(temporary,target)->{if(target.getFileName().toString().equals("latest.json"))throw new java.io.IOException("blocked");Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);});
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf","2026-10-14T07:00:00Z");snapshot.putArray("candidates").addObject().put("caseId","b".repeat(64)).put("publishedAt","2026-10-14T07:00:00Z");
        assertThrows(IllegalStateException.class,()->store.save(snapshot));
        assertTrue(new EventResearchStore(directory,mapper).caseById("b".repeat(64)).isEmpty());assertTrue(store.cases().isEmpty());
    }
    @Test void frozenPortfolioSelectionsCannotBeRewrittenAfterObservingReturns() {
        var store=new EventResearchStore(directory,mapper);var capture=mapper.createObjectNode().put("decisionDate","2026-10-14").put("decisionAt","2026-10-14T08:00:00Z").put("inputVersion","v1");
        capture.putObject("baselineWeights").put("000660","1");capture.putObject("challengerWeights").put("005930","1");capture.putArray("outcomes");
        assertTrue(store.capturePortfolio(capture));assertFalse(store.capturePortfolio(capture));
        var edited=capture.deepCopy();edited.withObject("/challengerWeights").put("005930","0");assertThrows(IllegalArgumentException.class,()->store.updatePortfolio(edited));
        assertEquals("1",store.portfolios().getFirst().path("challengerWeights").path("005930").asText());
    }
    @Test void abruptCaseWriteBeforeLatestCommitCannotInventClosingEvaluationAnchor() throws Exception {
        var store=new EventResearchStore(directory,mapper);var initial=mapper.createObjectNode().put("status","COMPLETE").put("asOf","2026-10-14T01:00:00Z");
        var caseNode=initial.putArray("candidates").addObject().put("caseId","d".repeat(64)).put("publishedAt","2026-10-14T01:00:00Z");store.save(initial);
        var uncommitted=caseNode.deepCopy().put("evaluationPublishedAt","2026-10-14T08:00:00Z");mapper.writeValue(directory.resolve("cases").resolve("d".repeat(64)+".json").toFile(),uncommitted);
        var recovered=new EventResearchStore(directory,mapper).caseById("d".repeat(64)).orElseThrow();assertFalse(recovered.has("evaluationPublishedAt"));
    }
    @Test void moreThanFiveThousandReceiptsAcrossDaysPreserveLegacyChronologyAndContinueWriting() throws Exception {
        var legacy=mapper.createObjectNode();Instant first=Instant.parse("2026-10-13T07:00:00Z");
        for(int i=1;i<=5000;i++)legacy.put("20261013"+String.format("%06d",i),first.toString());
        mapper.writeValue(directory.resolve("first-seen.json").toFile(),legacy);
        var store=new EventResearchStore(directory,mapper);Instant next=first.plusSeconds(86400);
        assertEquals(next,store.firstSeen("20261014000001",next));
        var restarted=new EventResearchStore(directory,mapper);
        assertEquals(first,restarted.firstSeen("20261013000001",next));
        assertEquals(next,restarted.firstSeen("20261014000001",next.plusSeconds(60)));
        assertEquals(5000,mapper.readTree(directory.resolve("first-seen.json").toFile()).size());
    }
    @Test void corruptExistingReceiptIndexCannotResetAnImmutableFirstSeenTime() throws Exception {
        Files.writeString(directory.resolve("first-seen.json"),"{broken");
        var store=new EventResearchStore(directory,mapper);
        assertThrows(IllegalStateException.class,()->store.firstSeen("20261014000001",Instant.parse("2026-10-14T07:00:00Z")));
        assertEquals("{broken",Files.readString(directory.resolve("first-seen.json")));
    }
    @Test void moreThanFiveHundredCasesContinuePublishingWithoutLosingImmatureCasesOrOldProof() throws Exception {
        var store=new EventResearchStore(directory,mapper);var legacyProof=mapper.createObjectNode();
        Instant first=Instant.now().minusSeconds(86400);
        for(int i=1;i<=502;i++) {
            String id=String.format("%064x",i);var candidate=mapper.createObjectNode().put("caseId",id).put("publishedAt",first.plusSeconds(i).toString()).put("origin","SUPPLIER_CONTRACT");
            if(i==502)candidate.put("amendmentStatus","WITHDRAWN").putNull("customerName");
            candidate.putArray("outcomes");if(i!=1)for(int horizon:new int[]{1,3,5,20})candidate.withArray("/outcomes").addObject().put("horizonSessions",horizon).put("state","COMPLETE");
            mapper.writeValue(directory.resolve("cases").resolve(id+".json").toFile(),candidate);
            legacyProof.putObject(id).put("publishedAt",candidate.path("publishedAt").asText()).putNull("evaluationPublishedAt").putNull("policyVersion");
        }
        mapper.writeValue(directory.resolve("committed.json").toFile(),legacyProof);
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf",Instant.now().toString());
        String newest=String.format("%064x",503);snapshot.putArray("candidates").addObject().put("caseId",newest).put("publishedAt",Instant.now().toString()).put("origin","SUPPLIER_CONTRACT");
        assertDoesNotThrow(()->store.save(snapshot));
        assertTrue(store.caseById(String.format("%064x",2)).isPresent());
        assertTrue(store.caseById(newest).isPresent());
        assertTrue(store.cases().stream().anyMatch(c->String.format("%064x",1).equals(c.path("caseId").asText())));
        assertEquals(501,store.cases().size());
        assertTrue(store.historyWindowLimited());
        assertEquals(500,store.relationshipHistory().size());
        assertEquals(newest,store.relationshipHistory().getFirst().path("caseId").asText());
        assertTrue(store.relationshipHistory().stream().anyMatch(c->"WITHDRAWN".equals(c.path("amendmentStatus").asText())&&c.path("customerName").isNull()));
        assertEquals(502,mapper.readTree(directory.resolve("committed.json").toFile()).size());
    }
    @Test void publicationProofsAreIndependentFilesAndDoNotCreateAGrowingAggregateIndex() throws Exception {
        var store=new EventResearchStore(directory,mapper);String id="e".repeat(64);
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf","2026-10-14T07:00:00Z");
        snapshot.putArray("candidates").addObject().put("caseId",id).put("publishedAt","2026-10-14T07:00:00Z");store.save(snapshot);
        var empty=mapper.createObjectNode().put("status","EMPTY").put("asOf","2026-10-14T08:00:00Z");empty.putArray("candidates");store.save(empty);
        assertFalse(Files.exists(directory.resolve("committed.json")));
        assertTrue(Files.isRegularFile(directory.resolve("proofs").resolve(id+".json")));
        assertTrue(new EventResearchStore(directory,mapper).caseById(id).isPresent());
    }
    @Test void corruptExistingReceiptShardCannotResetRecordedChronology() throws Exception {
        var store=new EventResearchStore(directory,mapper);Instant first=Instant.parse("2026-10-14T07:00:00Z");
        store.firstSeen("20261014000001",first);
        Files.createDirectories(directory.resolve("first-seen"));Files.writeString(directory.resolve("first-seen").resolve("20261014.json"),"{broken");
        assertThrows(IllegalStateException.class,()->store.firstSeen("20261014000002",first.plusSeconds(60)));
        assertEquals("{broken",Files.readString(directory.resolve("first-seen").resolve("20261014.json")));
    }
    @Test void failedArchivedProofPublicationRetainsTheOriginalClosingAnchor() {
        var rejectProof=new java.util.concurrent.atomic.AtomicBoolean(false);String id="f".repeat(64);
        var store=new EventResearchStore(directory,mapper,(temporary,target)->{if(rejectProof.get()&&target.getParent().getFileName().toString().equals("proofs"))throw new java.io.IOException("blocked");Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);});
        var snapshot=mapper.createObjectNode().put("status","COMPLETE").put("asOf","2026-10-14T01:00:00Z");
        var original=snapshot.putArray("candidates").addObject().put("caseId",id).put("publishedAt","2026-10-14T01:00:00Z");store.save(snapshot);
        var empty=mapper.createObjectNode().put("status","EMPTY").put("asOf","2026-10-14T02:00:00Z");empty.putArray("candidates");store.save(empty);
        rejectProof.set(true);assertThrows(IllegalStateException.class,()->store.updateCase(original.deepCopy().put("evaluationPublishedAt","2026-10-14T08:00:00Z")));
        assertFalse(new EventResearchStore(directory,mapper).caseById(id).orElseThrow().has("evaluationPublishedAt"));
    }
    @Test void retryableMissingBarsRemainEligibleAfterMoreThanFiveHundredNewerCases() throws Exception {
        var store=new EventResearchStore(directory,mapper);seedRetentionCases(false);
        assertTrue(store.cases().stream().anyMatch(c->String.format("%064x",1).equals(c.path("caseId").asText())));
    }
    @Test void delayedClosingAnchorStartsTheMaturityRetentionWindow() throws Exception {
        var store=new EventResearchStore(directory,mapper);seedRetentionCases(true);
        assertTrue(store.cases().stream().anyMatch(c->String.format("%064x",1).equals(c.path("caseId").asText())));
    }
    private void seedRetentionCases(boolean delayedAnchor) throws Exception {
        var index=mapper.createObjectNode();Instant day=Instant.now().minusSeconds(86400);
        for(int i=1;i<=502;i++) {
            String id=String.format("%064x",i);Instant published=i==1&&delayedAnchor?day.minusSeconds(65L*86400):day.plusSeconds(i);
            var candidate=mapper.createObjectNode().put("caseId",id).put("publishedAt",published.toString());
            if(i==1&&delayedAnchor)candidate.put("evaluationPublishedAt",day.toString());
            for(int horizon:new int[]{1,3,5,20}){var outcome=candidate.withArray("/outcomes").addObject().put("horizonSessions",horizon).put("state",i==1?(delayedAnchor?"PENDING":"UNMEASURABLE"):"COMPLETE");if(i==1)outcome.putArray("reasons").add(delayedAnchor?"HORIZON_NOT_MATURE":"MISSING_SESSION_BAR");}
            mapper.writeValue(directory.resolve("cases").resolve(id+".json").toFile(),candidate);
            index.putObject(id).put("publishedAt",published.toString()).put("evaluationPublishedAt",candidate.path("evaluationPublishedAt").asText(null)).putNull("policyVersion");
        }
        mapper.writeValue(directory.resolve("committed.json").toFile(),index);
    }
}
