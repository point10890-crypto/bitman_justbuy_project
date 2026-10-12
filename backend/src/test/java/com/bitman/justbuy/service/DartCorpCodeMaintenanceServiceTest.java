package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.bitman.justbuy.config.DartFinancialEvidenceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.mockito.Mockito.*;

class DartCorpCodeMaintenanceServiceTest {
    @TempDir Path directory;
    final MutableClock clock = new MutableClock();
    final AtomicLong nanos = new AtomicLong();
    final ObjectMapper json = spy(new ObjectMapper());
    final BoundedMarketRefreshQueueTest.ManualExecutor executor = new BoundedMarketRefreshQueueTest.ManualExecutor();
    final BoundedMarketRefreshQueue queue = new BoundedMarketRefreshQueue(1, clock, executor);
    final org.springframework.web.client.RestTemplate client = new DartFinancialEvidenceConfig().dartFinancialEvidenceRestTemplate();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
    DartCorpCodeMapper mapper;
    DartCorpCodeMaintenanceService maintenance;
    @AfterEach void shutdown() { if (maintenance != null) maintenance.close(); queue.close(); }

    void setup() { setup(DartCorpCodeMaintenanceService.Limits.defaults()); }
    void setup(DartCorpCodeMaintenanceService.Limits limits) {
        mapper = new DartCorpCodeMapper(new DartProperties("fixture"), json, directory.toString());
        maintenance = new DartCorpCodeMaintenanceService(new DartProperties("fixture"), mapper, client, clock, nanos::get, queue, limits);
    }
    Path cache() { return directory.resolve("dart_corp_codes.json"); }
    void existingNeedsRepair() throws Exception {
        Files.writeString(cache(), "{\"005930\":\"00126380\",\"000660\":\"BAD\"}");
        assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380");
    }
    void expectZip(byte[] bytes) {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/corpCode.xml"))
            .andExpect(header("User-Agent", "BitMan-JustBuy/1.0"))
            .andExpect(header("Accept", "application/zip,application/octet-stream,application/xml"))
            .andRespond(withSuccess(bytes, MediaType.APPLICATION_OCTET_STREAM));
    }
    @Test void startupAndPeriodicDemandShareOneWorkerAndCacheLookupNeverFetches() throws Exception {
        setup(); assertThat(mapper.getCachedCorpCode("005930")).isNull();
        expectZip(zip(xml(row("005930", "00126380") + row("00088K", "00123456")), "CORPCODE.xml"));
        maintenance.onReady(null); for (int i=0;i<20;i++) maintenance.maintain();
        assertThat(queue.snapshot()).containsEntry("enqueued", 1L).containsEntry("pendingJobs", 1);
        assertThat(mapper.getCachedCorpCode("005930")).isNull();
        executor.drain(); assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380");
        assertThat(mapper.getCachedCorpCode("00088K")).isNull();
        assertThat(Files.readString(cache())).contains("005930").doesNotContain("00088K");
        assertThat(maintenance.requestRefreshIfNeeded()).isFalse(); server.verify();
    }
    @Test void freshMixedSupportedSubsetDoesNotCauseUnneededNetworkRefresh() throws Exception {
        setup(); Files.writeString(cache(), "{\"005930\":\"00126380\",\"00088K\":\"00123456\"}");
        assertThat(maintenance.requestRefreshIfNeeded()).isFalse();
        assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380");
        assertThat(queue.snapshot()).containsEntry("enqueued", 0L);
    }
    @Test void sixAndAHalfDayOldCacheRemainsUsableWhileMaintenanceRefreshesBeforeExpiry() throws Exception {
        setup(); Files.writeString(cache(), "{\"005930\":\"00126380\"}");
        Files.setLastModifiedTime(cache(), FileTime.from(clock.instant().minusSeconds(6 * 24 * 3600 + 12 * 3600)));
        assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380");
        expectZip(zip(xml(row("005930", "00126380") + row("000660", "00164779")), "CORPCODE.xml"));
        assertThat(maintenance.requestRefreshIfNeeded()).isTrue();
        assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380");
        executor.drain(); assertThat(mapper.getCachedCorpCode("000660")).isEqualTo("00164779"); server.verify();
    }
    @Test void expiredFileIsNotRestampedOrUsedUntilAValidatedDownloadSucceeds() throws Exception {
        setup(); Files.writeString(cache(), "{\"005930\":\"00126380\"}");
        Files.setLastModifiedTime(cache(), FileTime.from(clock.instant().minusSeconds(8*24*3600)));
        var original = Files.getLastModifiedTime(cache());
        assertThat(mapper.getCachedCorpCode("005930")).isNull();
        expectZip(zip(xml(row("000660", "00164779")), "CORPCODE.xml"));
        assertThat(maintenance.requestRefreshIfNeeded()).isTrue();
        assertThat(Files.getLastModifiedTime(cache())).isEqualTo(original);
        executor.drain(); assertThat(mapper.getCachedCorpCode("005930")).isNull();
        assertThat(mapper.getCachedCorpCode("000660")).isEqualTo("00164779"); server.verify();
    }
    @ParameterizedTest @ValueSource(ints={302,401,403,429,500})
    void rejectedHttpStatusNeverReadsHtmlAndPreservesFreshExistingCacheWithCooldown(int status) throws Exception {
        setup(); existingNeedsRepair(); var bytes = Files.readAllBytes(cache()); var time = Files.getLastModifiedTime(cache());
        AtomicInteger bodyReads = new AtomicInteger();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/corpCode.xml"))).andRespond(request ->
            new MockClientHttpResponse(new byte[0], HttpStatus.valueOf(status)) {
                @Override public InputStream getBody() { bodyReads.incrementAndGet(); return new ByteArrayInputStream("<html>error</html>".getBytes()); }
                @Override public void close() {}
            });
        assertThat(maintenance.requestRefreshIfNeeded()).isTrue(); executor.drain();
        assertThat(bodyReads).hasValue(0); assertThat(Files.readAllBytes(cache())).isEqualTo(bytes);
        assertThat(Files.getLastModifiedTime(cache())).isEqualTo(time);
        assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380");
        for(int i=0;i<20;i++) assertThat(maintenance.requestRefreshIfNeeded()).isFalse();
        assertThat(maintenance.status().get("lastFailure").toString()).contains("HTTP_"); server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"TRUNCATED", "DTD", "CONFLICT", "WRONG_ROOT", "EMPTY", "PATH"})
    void invalidArchiveNeverPublishesAPartialOrExternalEntityMapping(String kind) throws Exception {
        setup(); existingNeedsRepair(); var bytes=Files.readAllBytes(cache());
        String valid=row("000660", "00164779"); String document=switch(kind) {
            case "TRUNCATED" -> "<result>"+valid+"<list><corp_code>";
            case "DTD" -> "<!DOCTYPE result [<!ENTITY x SYSTEM 'file:///missing-external-fixture'>]><result><list><stock_code>000660</stock_code><corp_code>&x;</corp_code></list></result>";
            case "CONFLICT" -> xml(valid+row("000660", "00999999"));
            case "WRONG_ROOT" -> "<error>"+valid+"</error>";
            case "EMPTY" -> xml(row("", "00164779")); default -> xml(valid);
        };
        expectZip(zip(document, "PATH".equals(kind)?"../CORPCODE.xml":"CORPCODE.xml"));
        assertThat(maintenance.requestRefreshIfNeeded()).isTrue(); executor.drain();
        assertThat(Files.readAllBytes(cache())).isEqualTo(bytes);
        assertThat(mapper.getCachedCorpCode("000660")).isNull();
        assertThat(maintenance.status().get("lastFailure")).isNotEqualTo("NONE"); server.verify();
    }
    @Test void compressedAndExpandedLimitsPreventZipBombPromotion() throws Exception {
        setup(new DartCorpCodeMaintenanceService.Limits(1_048_576,128,100,10,TimeUnit.SECONDS.toNanos(30)));
        existingNeedsRepair(); var original=Files.readAllBytes(cache());
        expectZip(zip(xml(row("000660","00164779")+"<padding>"+"x".repeat(1000)+"</padding>"),"CORPCODE.xml"));
        assertThat(maintenance.requestRefreshIfNeeded()).isTrue(); executor.drain();
        assertThat(Files.readAllBytes(cache())).isEqualTo(original); assertThat(maintenance.status().get("lastFailure").toString()).contains("LIMIT"); server.verify();
    }
    @Test void failedAtomicStorageDoesNotPromoteMemoryFreshnessOrLoseTheExistingFile() throws Exception {
        setup(); existingNeedsRepair(); var original=Files.readAllBytes(cache()); var time=Files.getLastModifiedTime(cache());
        doThrow(new IllegalStateException("fixture write failure")).when(json).writeValueAsBytes(any());
        expectZip(zip(xml(row("000660","00164779")),"CORPCODE.xml"));
        assertThat(maintenance.requestRefreshIfNeeded()).isTrue(); executor.drain();
        assertThat(Files.readAllBytes(cache())).isEqualTo(original); assertThat(Files.getLastModifiedTime(cache())).isEqualTo(time);
        assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380"); assertThat(mapper.getCachedCorpCode("000660")).isNull(); server.verify();
    }
    @Test void deadlineAcrossStreamingChunksRejectsLateDataAndStartsNoRetryLoop() throws Exception {
        setup(); existingNeedsRepair(); var original=Files.readAllBytes(cache()); byte[] archive=zip(xml(row("000660","00164779")),"CORPCODE.xml");
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/corpCode.xml"))).andRespond(request ->
            new MockClientHttpResponse(new byte[0],HttpStatus.OK) {
                @Override public InputStream getBody() { return new FilterInputStream(new ByteArrayInputStream(archive)) {
                    @Override public int read(byte[] buffer,int offset,int length) throws IOException { nanos.addAndGet(TimeUnit.SECONDS.toNanos(31)); return super.read(buffer,offset,length); }
                }; }
            });
        assertThat(maintenance.requestRefreshIfNeeded()).isTrue(); executor.drain();
        assertThat(Files.readAllBytes(cache())).isEqualTo(original);
        assertThat(maintenance.status().get("lastFailure")).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(maintenance.requestRefreshIfNeeded()).isFalse(); server.verify();
    }
    @Test void stoppingMaintenanceCancelsQueuedDemandWithoutDeletingExistingCache() throws Exception {
        setup(); existingNeedsRepair(); var bytes=Files.readAllBytes(cache());
        assertThat(maintenance.requestRefreshIfNeeded()).isTrue(); maintenance.close(); executor.drain();
        assertThat(maintenance.requestRefreshIfNeeded()).isFalse(); assertThat(queue.snapshot()).containsEntry("pendingJobs",0);
        assertThat(Files.readAllBytes(cache())).isEqualTo(bytes);
    }
    static String xml(String rows) { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><result>"+rows+"</result>"; }
    static String row(String stock,String corp) { return "<list><corp_code>"+corp+"</corp_code><corp_name>fixture</corp_name><stock_code>"+stock+"</stock_code></list>"; }
    static byte[] zip(String document,String entry) throws IOException {
        var output=new ByteArrayOutputStream(); try(var zipped=new ZipOutputStream(output)) { zipped.putNextEntry(new ZipEntry(entry)); zipped.write(document.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zipped.closeEntry(); } return output.toByteArray();
    }
    static final class MutableClock extends Clock {
        Instant value=Instant.now(); public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return value;}
    }
}
