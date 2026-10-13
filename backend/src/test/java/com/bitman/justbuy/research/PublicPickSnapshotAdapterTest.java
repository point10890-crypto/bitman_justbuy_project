package com.bitman.justbuy.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class PublicPickSnapshotAdapterTest {
    @TempDir Path temp;
    private static final Instant NOW=Instant.parse("2026-10-14T00:00:00Z");
    private Path write(String text)throws Exception {return Files.writeString(temp.resolve("snapshot.json"),text);}
    private String one(String extra){return "[{\"mode\":\"BREAKOUT\",\"stockPicks\":[{\"code\":\"028670\",\"currentPrice\":\"6,510\",\"targetPrice\":\"6,750\",\"stopLoss\":\"6,420\",\"quoteSource\":\"NAVER_FINANCE\",\"quoteCheckedAt\":\"2026-10-12T08:32:29Z\""+extra+"}]}]";}
    @Test void realPublicSnapshotPreservesNineSlotsFourStocksAndFourDistanceWarnings()throws Exception {
        var a=new PublicPickSnapshotAdapter().read(Path.of("../docs/DETECTED_STOCK_QUALITY_SNAPSHOT_2026_10_12.json"),NOW,Duration.ofMinutes(30));
        assertEquals(9,a.slotCount());assertEquals(4,a.distinctStocks());assertEquals(4,a.rewardRiskBelowOne());
        assertFalse(a.snapshot().complete());assertNull(a.snapshot().universeTotal());
        assertTrue(a.limitations().contains("LEGACY_IDENTITY_NOT_POINT_IN_TIME_MASTER"));
    }
    @Test void importTimeIsNotBackdatedToQuoteTime()throws Exception {
        var a=new PublicPickSnapshotAdapter().read(write(one("")),NOW,Duration.ofMinutes(30));
        var price=a.snapshot().candidates().getFirst().facts().stream().filter(f->f.metric()==ResearchConditionEngine.Metric.CURRENT_PRICE).findFirst().orElseThrow();
        assertEquals(NOW,price.availableAt());assertEquals(Instant.parse("2026-10-12T08:32:29Z"),price.observedAt());
        assertEquals(new BigDecimal("6510"),price.value());
    }
    @Test void oldestSavedQuotesCannotPassTodaysCondition()throws Exception {
        var a=new PublicPickSnapshotAdapter().read(write(one("")),NOW,Duration.ofMinutes(30));
        var request=new ResearchConditionEngine.Request(new ResearchConditionEngine.Compare(ResearchConditionEngine.Metric.REWARD_RISK,ResearchConditionEngine.Operator.GTE,BigDecimal.ONE,ResearchConditionEngine.Unit.MULTIPLE),ResearchConditionEngine.Metric.REWARD_RISK,ResearchConditionEngine.Direction.DESC,ResearchConditionEngine.SelectionOrder.FILTER_THEN_RANK,20);
        var result=new ResearchConditionEngine().evaluate(a.snapshot(),request);
        assertTrue(result.matched().isEmpty());assertEquals(1,result.coverage().unknown());
    }
    @Test void unknownInputFieldsAndSecretLikeNamesNeverReachAdapterOutput()throws Exception {
        var a=new PublicPickSnapshotAdapter().read(write(one(",\"query\":\"SECRET_SENTINEL\",\"apiKey\":\"SECRET_SENTINEL\",\"name\":\"SECRET_SENTINEL\"")),NOW,Duration.ofMinutes(30));
        assertFalse(a.toString().contains("SECRET_SENTINEL"));assertEquals(1,a.slotCount());
    }
    @Test void invalidPricesAreUnknownWithoutInventingZero()throws Exception {
        var a=new PublicPickSnapshotAdapter().read(write(one("").replace("6,510","six thousand")),NOW,Duration.ofMinutes(30));
        var fact=a.snapshot().candidates().getFirst().facts().getFirst();
        assertNull(fact.value());assertEquals(ResearchConditionEngine.FactStatus.UNKNOWN,fact.status());
        assertEquals(0,a.rewardRiskBelowOne());
    }
    @Test void duplicateJsonKeysAndTrailingValuesAreRejected()throws Exception {
        assertThrows(IllegalArgumentException.class,()->new PublicPickSnapshotAdapter().read(write(one(",\"code\":\"005930\"")),NOW,Duration.ofMinutes(30)));
        assertThrows(IllegalArgumentException.class,()->new PublicPickSnapshotAdapter().read(write(one("")+" {}"),NOW,Duration.ofMinutes(30)));
    }
    @Test void contentFingerprintChangesWhenQuoteChanges()throws Exception {
        var adapter=new PublicPickSnapshotAdapter();
        String first=adapter.read(write(one("")),NOW,Duration.ofMinutes(30)).inputDigest();
        String second=adapter.read(write(one("").replace("6,510","6,520")),NOW,Duration.ofMinutes(30)).inputDigest();
        assertNotEquals(first,second);assertEquals(64,first.length());
    }
    @Test void quoteConflictsAtSameObservedTimeRemainVisible()throws Exception {
        String a=one("");String b=one("").replace("6,510","6,520");
        String both=a.substring(0,a.length()-1)+","+b.substring(1);
        var result=new PublicPickSnapshotAdapter().read(write(both),NOW,Duration.ofMinutes(30));
        assertEquals(2,result.slotCount());assertEquals(1,result.distinctStocks());
        assertEquals(ResearchConditionEngine.FactStatus.CONFLICT,result.snapshot().candidates().getFirst().facts().getFirst().status());
    }
    @Test void matchingNumbersDoNotEraseDifferentProviderEnvironments()throws Exception {
        String a=one("").replace("NAVER_FINANCE","KIS_REAL");
        String b=one("").replace("NAVER_FINANCE","KIWOOM_REST_MOCK");
        var result=new PublicPickSnapshotAdapter().read(write(a.substring(0,a.length()-1)+","+b.substring(1)),NOW,Duration.ofMinutes(30));
        assertEquals(ResearchConditionEngine.FactStatus.CONFLICT,result.snapshot().candidates().getFirst().facts().getFirst().status());
    }
}
