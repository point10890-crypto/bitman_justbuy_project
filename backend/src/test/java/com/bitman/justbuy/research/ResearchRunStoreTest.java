package com.bitman.justbuy.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchRunStoreTest {
    @TempDir Path temp;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);

    @Test void validatedOutputSurvivesReopenAndRunCreationIsIdempotentPerOwnerAndInput() {
        String run;
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            run = store.createRun("public-owner", "input-v1");
            var lease = store.reserveStage(run, "review", "fingerprint-v1", 0);
            store.completeStage(lease, "{\"actualMeasurements\":0}", true);
        }
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            assertEquals(run, store.createRun("public-owner", "input-v1"));
            assertNotEquals(run, store.createRun("other-owner", "input-v1"));
            assertNotEquals(run, store.createRun("public-owner", "input-v2"));
            assertEquals("{\"actualMeasurements\":0}", store.reusedOutput(run, "review", "fingerprint-v1").orElseThrow());
            assertTrue(store.reusedOutput(run, "review", "fingerprint-v2").isEmpty());
            assertThrows(IllegalStateException.class, () -> store.reserveStage(run, "review", "fingerprint-v1", 0));
        }
        assertTrue(Files.exists(temp.resolve("research-ledger/ledger.mv.db")));
        assertFalse(Files.exists(temp.resolve("users.db")));
    }

    @Test void duplicateReservationAndSupersededLeaseCannotPublish() {
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            String run = store.createRun("owner", "input");
            var old = store.reserveStage(run, "review", "v1", 0);
            assertThrows(IllegalStateException.class, () -> store.reserveStage(run, "review", "v1", 0));
            var fresh = store.reserveStage(run, "review", "v2", 0);
            assertThrows(IllegalStateException.class, () -> store.completeStage(old, "{}", true));
            store.completeStage(fresh, "{\"revision\":2}", true);
            assertTrue(store.reusedOutput(run, "review", "v1").isEmpty());
            assertEquals("{\"revision\":2}", store.reusedOutput(run, "review", "v2").orElseThrow());
        }
    }

    @Test void cancellationIsDurableAndLateCompletionCannotOverwrite() {
        String run;
        ResearchRunStore.Lease lease;
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            run = store.createRun("owner", "input");
            lease = store.reserveStage(run, "review", "v1", 25);
            store.cancel(run);
            assertThrows(IllegalStateException.class, () -> store.completeStage(lease, "{}", true));
        }
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            assertTrue(store.view(run).cancelled());
            assertEquals(25, store.view(run).stages().getFirst().reservedCostMicros());
            assertEquals("UNCERTAIN", store.view(run).stages().getFirst().status());
            assertThrows(IllegalStateException.class, () -> store.reserveStage(run, "review", "v1", 0));
        }
    }

    @Test void uncertainPaidReservationIsRetainedAcrossReopenAndCannotRetry() {
        String run;
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            run = store.createRun("owner", "input");
            var lease = store.reserveStage(run, "model", "v1", 750);
            store.markUncertain(lease);
            assertThrows(IllegalStateException.class, () -> store.completeStage(lease, "{}", true));
        }
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            assertEquals(750, store.view(run).stages().getFirst().reservedCostMicros());
            assertThrows(IllegalStateException.class, () -> store.reserveStage(run, "model", "v2", 750));
        }
    }

    @Test void expiredLeaseIsFencedAndFreeStageCanRecover() {
        String run;
        ResearchRunStore.Lease old;
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            run = store.createRun("owner", "input");
            old = store.reserveStage(run, "review", "v1", 0);
        }
        try (var store = new ResearchRunStore(temp, Clock.offset(CLOCK, Duration.ofMinutes(6)))) {
            assertThrows(IllegalStateException.class, () -> store.completeStage(old, "{}", true));
            var fresh = store.reserveStage(run, "review", "v1", 0);
            store.completeStage(fresh, "{}", true);
        }
    }

    @Test void cancelledCompleteRunDoesNotExposeReusablePublication() {
        try (var store=new ResearchRunStore(temp,CLOCK)) {
            String run=store.createRun("owner","input");
            store.completeStage(store.reserveStage(run,"review","v1",0),"{}",true);
            store.cancel(run);
            assertTrue(store.reusedOutput(run,"review","v1").isEmpty());
            assertEquals("COMPLETE",store.view(run).stages().getFirst().status());
        }
    }

    @Test void expiredPaidLeasePersistsUncertaintyWhenRetryIsRejected() {
        String run;
        try(var store=new ResearchRunStore(temp,CLOCK)) {
            run=store.createRun("owner","input");
            store.reserveStage(run,"model","v1",123);
        }
        try(var store=new ResearchRunStore(temp,Clock.offset(CLOCK,Duration.ofMinutes(6)))) {
            assertThrows(IllegalStateException.class,()->store.reserveStage(run,"model","v1",123));
            assertEquals("UNCERTAIN",store.view(run).stages().getFirst().status());
        }
        try(var store=new ResearchRunStore(temp,CLOCK)) {
            assertEquals(123,store.view(run).stages().getFirst().reservedCostMicros());
            assertThrows(IllegalStateException.class,()->store.reserveStage(run,"model","v2",123));
        }
    }

    @Test void windowsJunctionAncestorIsRejectedWithoutTouchingTarget() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path target=Files.createDirectory(temp.resolve("actual-junction-target"));
        Files.writeString(target.resolve("sentinel.txt"),"untouched");
        Path junction=temp.resolve("junction");
        Process process=new ProcessBuilder("cmd.exe","/c","mklink","/J",junction.toString(),target.toString()).redirectErrorStream(true).start();
        String message=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),message);
        try {
            assertThrows(IllegalArgumentException.class,()->new ResearchRunStore(junction.resolve("research"),CLOCK));
            assertEquals("untouched",Files.readString(target.resolve("sentinel.txt")));
            assertFalse(Files.exists(target.resolve("research")));
        } finally { Files.delete(junction); }
    }

    @Test void outputMustBeBoundedValidJsonAndUnvalidatedOutputsAreNeverReused() {
        try (var store = new ResearchRunStore(temp, CLOCK)) {
            String run = store.createRun("owner", "input");
            var lease = store.reserveStage(run, "review", "v1", 0);
            assertThrows(IllegalArgumentException.class, () -> store.completeStage(lease, "{} {}", true));
            assertThrows(IllegalArgumentException.class, () -> store.completeStage(lease, "\"" + "가".repeat(400_000) + "\"", true));
            assertThrows(IllegalArgumentException.class, () -> store.completeStage(lease, "{bad}", true));
            store.completeStage(lease, "{}", false);
            assertTrue(store.reusedOutput(run, "review", "v1").isEmpty());
            var retry = store.reserveStage(run, "review", "v1", 0);
            store.completeStage(retry, "{}", true);
        }
    }

    @Test void separateStoreHandlesRespectReservationAndLeaseForgeryIsRejected() {
        try (var a = new ResearchRunStore(temp, CLOCK); var b = new ResearchRunStore(temp, CLOCK)) {
            String run = a.createRun("owner", "input");
            var lease = a.reserveStage(run, "review", "v1", 0);
            assertThrows(IllegalStateException.class, () -> b.reserveStage(run, "review", "v1", 0));
            var forged = new ResearchRunStore.Lease(run, "review", "wrong", lease.token(), 0);
            assertThrows(IllegalStateException.class, () -> b.completeStage(forged, "{}", true));
            b.completeStage(lease, "{}", true);
        }
    }

    @Test void simultaneousStoreHandlesReturnOneRunAndOnlyOneReservation() throws Exception {
        try(var a=new ResearchRunStore(temp,CLOCK);var b=new ResearchRunStore(temp,CLOCK)) {
            try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var gate=new java.util.concurrent.CyclicBarrier(2);
                var first=executor.submit(()->{gate.await();return a.createRun("owner","input");});
                var second=executor.submit(()->{gate.await();return b.createRun("owner","input");});
                String run=first.get(); assertEquals(run,second.get());
                var reserveGate=new java.util.concurrent.CyclicBarrier(2);
                var r1=executor.submit(()->{reserveGate.await();try{a.reserveStage(run,"review","v1",0);return true;}catch(IllegalStateException busy){return false;}});
                var r2=executor.submit(()->{reserveGate.await();try{b.reserveStage(run,"review","v1",0);return true;}catch(IllegalStateException busy){return false;}});
                assertNotEquals(r1.get(),r2.get()); assertEquals(1,a.view(run).stages().size());
            }
        }
    }

    @Test void refusesSharedUnownedWorkspaceAndJdbcPathInjection() throws Exception {
        Files.writeString(temp.resolve("users.db"), "member sentinel");
        assertThrows(IllegalArgumentException.class, () -> new ResearchRunStore(temp, CLOCK));
        assertEquals("member sentinel", Files.readString(temp.resolve("users.db")));
        assertThrows(IllegalArgumentException.class, () -> new ResearchRunStore(temp.resolve("bad;MODE=MySQL"), CLOCK));
    }

    @Test void rejectsSymlinkWorkspaceAndNamespaceWhenAvailable() throws Exception {
        Path actual = Files.createDirectory(temp.resolve("actual"));
        Path link = temp.resolve("link");
        try { Files.createSymbolicLink(link, actual); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "OS does not grant symlink creation");
        }
        assertThrows(IllegalArgumentException.class, () -> new ResearchRunStore(link, CLOCK));
        Path workspace = Files.createDirectory(temp.resolve("owned"));
        Files.writeString(workspace.resolve(".research-core-owner"), "research-core-v1");
        Files.createSymbolicLink(workspace.resolve("research-ledger"), actual);
        assertThrows(IllegalArgumentException.class, () -> new ResearchRunStore(workspace, CLOCK));
    }
}
