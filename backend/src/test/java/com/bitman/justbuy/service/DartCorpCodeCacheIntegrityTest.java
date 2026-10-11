package com.bitman.justbuy.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DartCorpCodeCacheIntegrityTest {
    @TempDir Path dataDir;
    @Test
    void cacheOnlyLookupReadsABoundedPublicFixtureAndNeverDownloads() throws Exception {
        Files.writeString(dataDir.resolve("dart_corp_codes.json"), "{\"005930\":\"00126380\"}");
        var mapper = spy(new DartCorpCodeMapper(new DartProperties(""), new ObjectMapper(), dataDir.toString()));
        assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380");
        verify(mapper, never()).getCorpCode(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifiedExistingMemoryMappingNeedsNoFileRead() {
        var mapper = new DartCorpCodeMapper(new DartProperties(""), new ObjectMapper(), dataDir.toString());
        ((Map<String, String>) ReflectionTestUtils.getField(mapper, "stockToCorpCode")).put("005930", "00126380");
        ReflectionTestUtils.setField(mapper, "lastLoaded", Instant.now());
        assertThat(mapper.getCachedCorpCode("005930")).isEqualTo("00126380");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshingThePublicCacheCannotRevalidateAnExpiredRemovedMapping() throws Exception {
        var mapper = new DartCorpCodeMapper(new DartProperties(""), new ObjectMapper(), dataDir.toString());
        ((Map<String, String>) ReflectionTestUtils.getField(mapper, "stockToCorpCode")).put("005930", "00126380");
        ReflectionTestUtils.setField(mapper, "lastLoaded", Instant.now().minusSeconds(8 * 24 * 60 * 60));
        Files.writeString(dataDir.resolve("dart_corp_codes.json"), "{\"000660\":\"00164779\"}");
        assertThat(mapper.getCachedCorpCode("005930")).isNull();
        assertThat(mapper.getCachedCorpCode("005930")).isNull();
        assertThat(mapper.getCachedCorpCode("000660")).isEqualTo("00164779");
    }

    @Test
    void missingStaleOversizedAndMalformedMappingRemainUnavailable() throws Exception {
        var mapper = new DartCorpCodeMapper(new DartProperties(""), new ObjectMapper(), dataDir.toString());
        Path path = dataDir.resolve("dart_corp_codes.json");
        assertThat(mapper.getCachedCorpCode("005930")).isNull();
        Files.writeString(path, "{\"005930\":\"00126380\"}");
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minusSeconds(8 * 24 * 60 * 60)));
        assertThat(mapper.getCachedCorpCode("005930")).isNull();
        Files.writeString(path, "x".repeat(4 * 1_048_576 + 1));
        assertThat(mapper.getCachedCorpCode("005930")).isNull();
        Files.writeString(path, "{\"005930\":\"BAD\",\"bad\":\"00126380\"}");
        assertThat(mapper.getCachedCorpCode("005930")).isNull();
        assertThat(mapper.getCachedCorpCode("bad")).isNull();
    }

    @Test
    void existingMapperExceptionLoggingDoesNotExposeUrlsOrCredentials() throws Exception {
        Path path = dataDir.resolve("dart_corp_codes.json"); Files.writeString(path, "{}");
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.readValue(any(File.class), org.mockito.ArgumentMatchers.<TypeReference<Map<String, String>>>any()))
            .thenThrow(new IllegalStateException("https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=fixture-sensitive-marker"));
        var logger = (Logger) LoggerFactory.getLogger(DartCorpCodeMapper.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            new DartCorpCodeMapper(new DartProperties(""), objectMapper, dataDir.toString()).getCorpCode("005930");
            assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("crtfc_key", "fixture-sensitive-marker", "https://");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
