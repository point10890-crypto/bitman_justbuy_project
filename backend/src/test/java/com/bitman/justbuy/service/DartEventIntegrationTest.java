package com.bitman.justbuy.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bitman.justbuy.config.DartProperties;
import com.bitman.justbuy.config.DartEventEvidenceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DartEventIntegrationTest {
    @Test
    void documentedNestedFeatureFlagControlsTheActualAdapter() {
        new ApplicationContextRunner().withUserConfiguration(DartEventEvidenceConfig.class)
            .withPropertyValues("bitman.dart.event-evidence.enabled=true")
            .run(context -> assertThat(context.getBean(DartEventEvidenceService.Options.class).enabled()).isTrue());
    }

    @Test
    void additiveBatchDelegatesSelectedStocksAndKeepsExistingConstructorUsable() {
        var eventEvidence = mock(DartEventEvidenceService.class);
        when(eventEvidence.fetchFormattedEvidence(List.of("005930", "000660"))).thenReturn("DART fixture evidence");
        var dart = new DartApiService(new DartProperties(""), null, null, new ObjectMapper(), eventEvidence);
        assertThat(dart.fetchFormattedEventEvidence(List.of("005930", "000660"))).isEqualTo("DART fixture evidence");
        assertThat(new DartApiService(new DartProperties(""), null, null, new ObjectMapper())
            .fetchFormattedEventEvidence(List.of("005930"))).isEmpty();
        verify(eventEvidence).fetchFormattedEvidence(List.of("005930", "000660"));
    }

    @Test
    void legacyDartErrorLoggingCannotExposeProviderRequestUrlsOrKeys() {
        var logger = (Logger) LoggerFactory.getLogger(DartApiService.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        var previous = logger.getLevel(); logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            var corpMapper = mock(DartCorpCodeMapper.class);
            when(corpMapper.getCachedCorpCode("005930")).thenReturn("00126380");
            var client = mock(RestTemplate.class);
            when(client.getForObject(anyString(), eq(String.class))).thenThrow(new IllegalStateException(
                "https://opendart.fss.or.kr/api/company.json?crtfc_key=fixture-sensitive-marker"));
            new DartApiService(new DartProperties("fixture"), corpMapper, client, new ObjectMapper())
                .fetchFormattedDartData("005930");
            verify(corpMapper, never()).getCorpCode(anyString());
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("crtfc_key", "fixture-sensitive-marker", "https://");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
    }
}
