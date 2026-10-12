package com.bitman.justbuy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Configuration
public class DartFinancialEvidenceConfig {
    @Bean("dartFinancialEvidence")
    public RestTemplate dartFinancialEvidenceRestTemplate() {
        var factory = createRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        var client = new RestTemplate(factory);
        // Let the bounded extractor reject non-200 statuses before any error body is consumed.
        client.setErrorHandler(new NoOpResponseErrorHandler());
        return client;
    }

    protected SimpleClientHttpRequestFactory createRequestFactory() { return new DisconnectingRequestFactory(); }

    /** Close the dedicated financial connection without Spring's unbounded response-body drain. */
    public static class DisconnectingRequestFactory extends SimpleClientHttpRequestFactory {
        private final ThreadLocal<HttpURLConnection> prepared = new ThreadLocal<>();

        @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
            super.prepareConnection(connection, method);
            connection.setRequestProperty("Connection", "close");
            connection.setInstanceFollowRedirects(false);
            prepared.set(connection);
        }

        @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
            ClientHttpRequest delegate;
            HttpURLConnection connection;
            try {
                delegate = super.createRequest(uri, method);
                connection = prepared.get();
                if (connection == null) throw new IOException("Financial connection unavailable");
            } catch (IOException | RuntimeException error) {
                HttpURLConnection failed = prepared.get();
                if (failed != null) failed.disconnect();
                throw error;
            } finally { prepared.remove(); }
            return new ClientHttpRequest() {
                @Override public HttpMethod getMethod() { return delegate.getMethod(); }
                @Override public URI getURI() { return delegate.getURI(); }
                @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
                @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
                @Override public OutputStream getBody() throws IOException { return delegate.getBody(); }
                @Override public ClientHttpResponse execute() throws IOException {
                    try {
                        ClientHttpResponse response = delegate.execute();
                        return new ClientHttpResponse() {
                            @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                            @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                            @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
                            @Override public InputStream getBody() throws IOException { return response.getBody(); }
                            // Never call delegate.close(): it drains the body, even after a deadline or rejected status.
                            @Override public void close() { connection.disconnect(); }
                        };
                    } catch (IOException | RuntimeException error) {
                        connection.disconnect();
                        throw error;
                    }
                }
            };
        }
    }
}
