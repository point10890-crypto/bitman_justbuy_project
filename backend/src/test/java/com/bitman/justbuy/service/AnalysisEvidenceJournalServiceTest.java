package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisEvidenceJournalServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T06:00:00Z"), ZoneOffset.UTC);

    @Test void recordsImmutableOriginalFactsAndDoesNotStoreQueriesOrSecrets() throws Exception {
        try (var journal = new AnalysisEvidenceJournalService(mapper, directory.toString(), clock, 4)) {
            var facts = new LinkedHashMap<String,Object>();
            facts.put("technical", new LinkedHashMap<>(Map.of("return20dPct", 12.5, "source", "KIS", "observedDate", "2026-10-08")));
            facts.put("apiKey", "SECRET_KEY_TEST"); facts.put("query", "private user question");
            facts.put("url", "https://example.test/?key=SECRET_KEY_TEST");
            assertThat(journal.capture("CANDIDATE_EVIDENCE", "REVERSAL_EDGE", List.of("005930"), facts, List.of())).isTrue();
            facts.clear();
            awaitCompleted(journal, 1);
            var files = snapshots(); assertThat(files).hasSize(1);
            String original = Files.readString(files.getFirst());
            assertThat(original).contains("12.5", "005930", "2026-10-08", "CANDIDATE_EVIDENCE", "schemaVersion", "inputDigest")
                .doesNotContain("SECRET_KEY_TEST", "private user question", "example.test");
            assertThat(journal.capture("FINAL_PUBLICATION", "REVERSAL_EDGE", List.of("005930"), Map.of(), List.of(Map.of("stockCode", "005930", "financialScore", 0)))).isTrue();
            awaitCompleted(journal, 2);
            assertThat(snapshots()).hasSize(2);
            assertThat(Files.readString(files.getFirst())).isEqualTo(original);
        }
    }

    @Test void storageFailureIsIsolatedAndShutdownRejectsNewWork() throws Exception {
        Path obstructed = directory.resolve("blocked"); Files.writeString(obstructed, "file");
        var journal = new AnalysisEvidenceJournalService(mapper, obstructed.toString(), clock, 4);
        assertThat(journal.capture("CANDIDATE_EVIDENCE", "BREAKOUT", List.of("005930"), Map.of(), List.of())).isTrue();
        for (int i=0;i<100 && ((Number)journal.status().get("failed")).intValue()==0;i++) Thread.sleep(10);
        assertThat(journal.status()).containsEntry("failed", 1L);
        journal.close();
        assertThat(journal.capture("CANDIDATE_EVIDENCE", "BREAKOUT", List.of("005930"), Map.of(), List.of())).isFalse();
    }

    @Test void retentionOnlyRemovesManagedSnapshotsAndBoundsFileCount() throws Exception {
        Path root = directory.resolve("analysis-evidence"); Files.createDirectories(root);
        Path owned = root.resolve("20200101-000000000-00000000-0000-0000-0000-000000000000.json"); Files.writeString(owned,"{}");
        Files.setLastModifiedTime(owned, java.nio.file.attribute.FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        Path unrelated = root.resolve("keep.txt"); Files.writeString(unrelated,"keep");
        try(var journal=new AnalysisEvidenceJournalService(mapper,directory.toString(),clock,2)) {
            for(int i=1;i<=3;i++) {
                assertThat(journal.capture("FINAL_PUBLICATION", "FLOW_LEADER",List.of("005930"),Map.of("value",i),List.of())).isTrue();
                awaitCompleted(journal,i);
            }
            assertThat(snapshots()).hasSize(2); assertThat(owned).doesNotExist(); assertThat(unrelated).exists();
        }
    }

    @Test void financialPeriodAndReportIdentitySurviveThePublicFactFilter() throws Exception {
        try(var journal=new AnalysisEvidenceJournalService(mapper,directory.toString(),clock,4)){
            var facts=Map.<String,Object>of("reportCode","11014","period","2026 사업연도 3분기 당분기(3개월)","cashFlowPeriod","2026 사업연도 3분기 누적",
                "balancePeriod","2026 사업연도 3분기 보고서 기말(날짜 unknown)","selectionBasis","BOUNDED_REPORT_LOOKUP_LATEST_UNKNOWN;REQUEST_BOUND_STOCK_FS;INCOME_IS","revenue",100);
            assertThat(journal.capture("CANDIDATE_EVIDENCE","REVERSAL_EDGE",List.of("005930"),facts,List.of())).isTrue();
            awaitCompleted(journal,1);
            assertThat(Files.readString(snapshots().getFirst())).contains("11014","당분기(3개월)","3분기 누적","기말(날짜 unknown)","BOUNDED_REPORT_LOOKUP_LATEST_UNKNOWN");
        }
    }

    @Test void keywordFactsKeepTheirMetricIdentityAndDistinctObservationPeriods() throws Exception {
        try(var journal=new AnalysisEvidenceJournalService(mapper,directory.toString(),clock,4)){
            var facts=Map.<String,Object>of("currentPrice","70,000","flow",Map.of("period","2026-10-06 ~ 2026-10-08","source","KIWOOM_REST_MOCK","environment","MOCK","foreignNet",300),
                "factCatalog",List.of(Map.of("factId","005930:/metrics/flow/foreignNet","field","/metrics/flow/foreignNet","value",300)));
            assertThat(journal.capture("CONDITION_VERIFY","KEYWORD_DUAL_NET_BUY",List.of("005930"),facts,List.of())).isTrue();
            awaitCompleted(journal,1);
            String json=Files.readString(snapshots().getFirst());
            assertThat(json).contains("KEYWORD_DUAL_NET_BUY","2026-10-06 ~ 2026-10-08","005930:/metrics/flow/foreignNet","/metrics/flow/foreignNet","70000");
        }
    }

    private List<Path> snapshots() throws Exception {
        try(var files=Files.list(directory.resolve("analysis-evidence"))) {return files.filter(p->p.toString().endsWith(".json")).toList();}
    }
    private static void awaitCompleted(AnalysisEvidenceJournalService service, int count) throws Exception {
        for(int i=0;i<200 && ((Number)service.status().get("completed")).intValue()<count;i++) Thread.sleep(10);
        assertThat(((Number)service.status().get("completed")).intValue()).isEqualTo(count);
    }
}
