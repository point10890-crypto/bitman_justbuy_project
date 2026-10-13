package com.bitman.justbuy.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ResearchCoreCliTest {
    @TempDir Path temp;
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC);
    private Path publicFile(){return Path.of("../docs/DETECTED_STOCK_QUALITY_SNAPSHOT_2026_10_12.json");}
    @Test void actualArchiveFlowsThroughScreeningAndLearningWithoutInventedPerformance()throws Exception {
        var r=ResearchCoreCli.execute(publicFile(),temp.resolve("owned"),clock);
        assertEquals(9,r.get("savedSlots"));assertEquals(4,r.get("uniqueStocks"));
        assertEquals(4,r.get("priceDistanceWarnings"));assertEquals(0,r.get("measuredOutcomes"));
        assertEquals("INSUFFICIENT_EVIDENCE",r.get("profitabilityStatus"));assertEquals(false,r.get("productionActivationAllowed"));
        assertInstanceOf(List.class,r.get("outcomes"));
        assertEquals(4,((List<?>)r.get("outcomes")).size());
        assertEquals("UNMEASURABLE",((Map<?,?>)((List<?>)r.get("outcomes")).getFirst()).get("state"));
        assertTrue(Files.isRegularFile(temp.resolve("owned/report.json")));
        assertTrue(Files.readString(temp.resolve("owned/report.md")).contains("실증 부족"));
    }
    @Test void sameFrozenInputReusesValidatedStageAfterClosingStore()throws Exception {
        var first=ResearchCoreCli.execute(publicFile(),temp.resolve("owned"),clock);
        var second=ResearchCoreCli.execute(publicFile(),temp.resolve("owned"),clock);
        assertEquals(false,first.get("reusedValidatedStage"));assertEquals(true,second.get("reusedValidatedStage"));
        assertEquals(first.get("resultDigest"),second.get("resultDigest"));assertEquals(first.get("runId"),second.get("runId"));
    }
    @Test void changedDecisionCutoffDoesNotReuseOldStage()throws Exception {
        var first=ResearchCoreCli.execute(publicFile(),temp.resolve("owned"),clock);
        var second=ResearchCoreCli.execute(publicFile(),temp.resolve("owned"),Clock.offset(clock,Duration.ofSeconds(1)));
        assertEquals(false,second.get("reusedValidatedStage"));assertNotEquals(first.get("runId"),second.get("runId"));
    }
    @Test void changedSourceDoesNotReuseOldStage()throws Exception {
        Path p=Files.writeString(temp.resolve("public.json"),Files.readString(publicFile()));
        var first=ResearchCoreCli.execute(p,temp.resolve("owned"),clock);
        Files.writeString(p,Files.readString(publicFile()).replace("6,510","6,520"));
        var second=ResearchCoreCli.execute(p,temp.resolve("owned"),clock);
        assertEquals(false,second.get("reusedValidatedStage"));assertNotEquals(first.get("runId"),second.get("runId"));
    }
    @Test void sharedUnownedDirectoryCannotBeUsedAsResearchStorage()throws Exception {
        Files.writeString(temp.resolve("keep.txt"),"unrelated");
        assertThrows(IllegalArgumentException.class,()->ResearchCoreCli.execute(publicFile(),temp,clock));
        assertEquals("unrelated",Files.readString(temp.resolve("keep.txt")));
    }
    @Test void olderRunFinishingLateCannotOverwriteNewerPublicReport()throws Exception {
        var paused=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        Clock older=new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){
                if(calls.incrementAndGet()==4){paused.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Barrier timeout");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
                return clock.instant();
            }
        };
        try(var workers=Executors.newSingleThreadExecutor()) {
            var old=workers.submit(()->ResearchCoreCli.execute(publicFile(),temp.resolve("owned"),older));
            try {
                assertTrue(paused.await(10,TimeUnit.SECONDS));
                var newer=ResearchCoreCli.execute(publicFile(),temp.resolve("owned"),Clock.offset(clock,Duration.ofSeconds(60)));
                release.countDown();var oldResult=old.get(10,TimeUnit.SECONDS);
                assertNotEquals(newer.get("runId"),oldResult.get("runId"));
                var published=new ObjectMapper().readTree(temp.resolve("owned/report.json").toFile());
                assertEquals(newer.get("runId"),published.path("runId").asText());
                assertTrue(Files.readString(temp.resolve("owned/report.md")).contains(newer.get("runId").toString()));
            } finally{release.countDown();}
        }
    }
}
