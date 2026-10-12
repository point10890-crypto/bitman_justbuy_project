package com.bitman.justbuy.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DartFinancialEvidenceConfigTest {
    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void rejectedHttpResponseDisconnectsTheRealFactoryWithoutReadingOrDrainingTheBody(int status) throws Exception {
        var connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(status);
        when(connection.getRequestMethod()).thenReturn("GET");
        AtomicInteger bodyReads = new AtomicInteger();
        when(connection.getErrorStream()).thenReturn(new InputStream() {
            boolean emitted;
            @Override public int read() { bodyReads.incrementAndGet(); if (emitted) return -1; emitted = true; return 'x'; }
            @Override public int read(byte[] bytes, int offset, int length) {
                bodyReads.incrementAndGet(); if (emitted) return -1; emitted = true; bytes[offset] = 'x'; return 1;
            }
        });
        var config = new DartFinancialEvidenceConfig() {
            @Override protected SimpleClientHttpRequestFactory createRequestFactory() {
                return new DisconnectingRequestFactory() {
                    @Override protected HttpURLConnection openConnection(URL url, Proxy proxy) throws IOException {
                        return connection;
                    }
                };
            }
        };
        var client = config.dartFinancialEvidenceRestTemplate();
        var response = client.execute(URI.create("https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json"),
            HttpMethod.GET, null, result -> result.getStatusCode().value());
        assertThat(response).isEqualTo(status);
        assertThat(bodyReads).hasValue(0);
        verify(connection).setConnectTimeout(2000);
        verify(connection).setReadTimeout(3000);
        verify(connection).disconnect();
        verify(connection, never()).getErrorStream();
        verify(connection, never()).getInputStream();
    }
}
