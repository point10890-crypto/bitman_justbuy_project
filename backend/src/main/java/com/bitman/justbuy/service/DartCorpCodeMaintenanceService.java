package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import javax.xml.XMLConstants;
import javax.xml.stream.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.zip.*;

/** Independent, bounded maintenance; analysis lookups never wait for this download. */
@Service
public class DartCorpCodeMaintenanceService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DartCorpCodeMaintenanceService.class);
    private static final String JOB = "DART_CORPORATE_MAP";
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(5);
    record Limits(long zipBytes, long xmlBytes, int rows, int mappings, long budgetNanos) {
        static Limits defaults() { return new Limits(16 * 1_048_576L, 96 * 1_048_576L, 500_000, 20_000, TimeUnit.SECONDS.toNanos(30)); }
    }
    private final DartProperties properties;
    private final DartCorpCodeMapper mapper;
    private final RestTemplate client;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final BoundedMarketRefreshQueue queue;
    private final Limits limits;
    private final ScheduledThreadPoolExecutor watchdog;
    private final Object stateLock = new Object();
    private volatile boolean closed;
    private volatile ActiveRefresh active;
    private Instant cooldownUntil = Instant.EPOCH, lastSuccess;
    private String lastFailure = "NOT_ATTEMPTED";

    @Autowired
    public DartCorpCodeMaintenanceService(DartProperties properties, DartCorpCodeMapper mapper,
        @Qualifier("dartCorpCodeMaintenance") RestTemplate client) {
        this(properties, mapper, client, Clock.systemUTC(), System::nanoTime,
            new BoundedMarketRefreshQueue("dart-corp-maintenance", 1), Limits.defaults());
    }
    DartCorpCodeMaintenanceService(DartProperties properties, DartCorpCodeMapper mapper, RestTemplate client,
        Clock clock, LongSupplier nanoTime, BoundedMarketRefreshQueue queue, Limits limits) {
        this.properties = Objects.requireNonNull(properties); this.mapper = Objects.requireNonNull(mapper);
        this.client = Objects.requireNonNull(client); this.clock = Objects.requireNonNull(clock);
        this.nanoTime = Objects.requireNonNull(nanoTime); this.queue = Objects.requireNonNull(queue);
        this.limits = Objects.requireNonNull(limits);
        if (limits.zipBytes <= 0 || limits.zipBytes > 16 * 1_048_576L || limits.xmlBytes <= 0
            || limits.xmlBytes > 96 * 1_048_576L || limits.rows <= 0 || limits.rows > 500_000
            || limits.mappings <= 0 || limits.mappings > 20_000 || limits.budgetNanos <= 0
            || limits.budgetNanos > TimeUnit.SECONDS.toNanos(30)) throw new IllegalArgumentException("Invalid maintenance limits");
        watchdog = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "dart-corp-deadline"); thread.setDaemon(true); return thread;
        });
        watchdog.setRemoveOnCancelPolicy(true);
    }

    public boolean requestRefreshIfNeeded() {
        synchronized (stateLock) {
            if (closed || Thread.currentThread().isInterrupted() || properties.apiKey() == null
                || properties.apiKey().isBlank() || clock.instant().isBefore(cooldownUntil) || !mapper.cacheNeedsRefresh()) return false;
            return queue.offer(JOB, this::refresh);
        }
    }
    @EventListener(ApplicationReadyEvent.class)
    public void onReady(ApplicationReadyEvent event) { requestRefreshIfNeeded(); }
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void maintain() { requestRefreshIfNeeded(); }

    public Map<String, Object> status() {
        Map<String, Object> values = new LinkedHashMap<>(queue.snapshot());
        synchronized (stateLock) {
            values.put("lastFailure", lastFailure); values.put("cooldownUntil", cooldownUntil.toString());
            values.put("lastSuccess", lastSuccess == null ? null : lastSuccess.toString());
        }
        values.put("verifiedMappings", mapper.cachedMappingCount()); values.put("closed", closed);
        return Collections.unmodifiableMap(values);
    }

    private void refresh() {
        synchronized (stateLock) {
            if (closed || Thread.currentThread().isInterrupted() || clock.instant().isBefore(cooldownUntil) || !mapper.cacheNeedsRefresh()) return;
        }
        Budget budget = new Budget(nanoTime.getAsLong(), limits.budgetNanos);
        ActiveRefresh running = new ActiveRefresh(Thread.currentThread()); active = running;
        ScheduledFuture<?> timeout = watchdog.schedule(running::cancel, limits.budgetNanos, TimeUnit.NANOSECONDS);
        try {
            budget.check();
            var uri = UriComponentsBuilder.fromUriString("https://opendart.fss.or.kr/api/corpCode.xml")
                .queryParam("crtfc_key", properties.apiKey()).build().encode().toUri();
            byte[] archive = client.execute(uri, HttpMethod.GET, request -> {
                request.getHeaders().set("User-Agent", "BitMan-JustBuy/1.0");
                request.getHeaders().set("Accept", "application/zip,application/octet-stream,application/xml");
            }, response -> {
                running.response.set(response);
                try {
                    budget.check();
                    int status = response.getStatusCode().value();
                    if (status != 200) throw new Failure("HTTP_" + status);
                    if (response.getHeaders().getContentLength() > limits.zipBytes) throw new Failure("ZIP_BYTE_LIMIT");
                    return readArchive(response.getBody(), budget);
                } finally { running.response.compareAndSet(response, null); }
            });
            budget.check();
            Map<String, String> verified = parseArchive(archive, budget);
            budget.check(); mapper.publishVerifiedMapping(verified, clock.instant(), budget::check);
            synchronized (stateLock) { lastSuccess = clock.instant(); lastFailure = "NONE"; cooldownUntil = Instant.EPOCH; }
        } catch (Exception error) {
            String reason = budget.expired() ? "DEADLINE_EXCEEDED" : Thread.currentThread().isInterrupted() ? "INTERRUPTED"
                : findFailure(error);
            synchronized (stateLock) { lastFailure = reason; cooldownUntil = clock.instant().plus(FAILURE_COOLDOWN); }
            // Never log provider exception messages/URIs: they can contain the authentication key.
            log.warn("[DartCorpCode] 공식 목록 갱신 실패: {}", reason);
        } finally {
            timeout.cancel(false); running.closeResponse(); if (active == running) active = null;
        }
    }

    private byte[] readArchive(InputStream input, Budget budget) throws IOException {
        if (input == null) throw new Failure("EMPTY_ARCHIVE");
        var output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; long total = 0;
        while (true) {
            budget.check(); int read = input.read(buffer); budget.check(); if (read < 0) break;
            total += read; if (total > limits.zipBytes) throw new Failure("ZIP_BYTE_LIMIT");
            output.write(buffer, 0, read);
        }
        if (total == 0) throw new Failure("EMPTY_ARCHIVE");
        return output.toByteArray();
    }

    private Map<String, String> parseArchive(byte[] archive, Budget budget) throws IOException, XMLStreamException {
        if (archive == null || archive.length == 0 || archive.length > limits.zipBytes) throw new Failure("ZIP_BYTE_LIMIT");
        // ZipFile validates the central directory, unlike a local-header-only streaming parser.
        Path temporary = Files.createTempFile("bitman-dart-corp-", ".zip");
        try {
            budget.check(); Files.write(temporary, archive); budget.check();
            try (ZipFile zip = new ZipFile(temporary.toFile())) {
                var entries = zip.entries(); if (!entries.hasMoreElements()) throw new Failure("EMPTY_ARCHIVE");
                ZipEntry entry = entries.nextElement();
                if (entries.hasMoreElements() || entry.isDirectory() || !"CORPCODE.xml".equalsIgnoreCase(entry.getName()))
                    throw new Failure("INVALID_ZIP_ENTRY");
                if (entry.getSize() > limits.xmlBytes) throw new Failure("XML_BYTE_LIMIT");
                try (InputStream input = zip.getInputStream(entry)) {
                    return parseXml(new LimitedInput(input, limits.xmlBytes, budget), budget);
                }
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private Map<String, String> parseXml(InputStream input, Budget budget) throws XMLStreamException, IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External entity prohibited"); });
        XMLStreamReader xml = factory.createXMLStreamReader(input);
        Map<String, String> verified = new LinkedHashMap<>();
        int depth = 0, rows = 0; boolean rootClosed = false, stockSeen = false, corpSeen = false;
        String field = null; StringBuilder stock = new StringBuilder(), corp = new StringBuilder();
        try {
            while (xml.hasNext()) {
                budget.check(); int event = xml.next(); budget.check();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw new Failure("UNSAFE_XML");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++; String name = xml.getLocalName();
                    if (xml.getNamespaceURI() != null && !xml.getNamespaceURI().isEmpty()) throw new Failure("INVALID_XML_STRUCTURE");
                    if (depth == 1) { if (rootClosed || !"result".equals(name)) throw new Failure("INVALID_XML_ROOT"); }
                    else if (depth == 2) {
                        if (!"list".equals(name) || ++rows > limits.rows) throw new Failure(rows > limits.rows ? "ROW_LIMIT" : "INVALID_XML_STRUCTURE");
                        stock.setLength(0); corp.setLength(0); stockSeen = false; corpSeen = false;
                    } else if (depth == 3) {
                        field = name;
                        if ("stock_code".equals(name)) { if (stockSeen) throw new Failure("DUPLICATE_XML_FIELD"); stockSeen = true; }
                        if ("corp_code".equals(name)) { if (corpSeen) throw new Failure("DUPLICATE_XML_FIELD"); corpSeen = true; }
                    } else throw new Failure("INVALID_XML_STRUCTURE");
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    StringBuilder target = "stock_code".equals(field) ? stock : "corp_code".equals(field) ? corp : null;
                    if (target != null) { if (target.length() + xml.getTextLength() > 32) throw new Failure("FIELD_LIMIT"); target.append(xml.getText()); }
                    else if (depth < 3 && !xml.isWhiteSpace()) throw new Failure("INVALID_XML_STRUCTURE");
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 3) field = null;
                    else if (depth == 2) {
                        String code = stock.toString().trim(), company = corp.toString().trim();
                        if (DartCorpCodeMapper.validStock(code)) {
                            if (!DartCorpCodeMapper.validCorp(company)) throw new Failure("INVALID_COMPANY_CODE");
                            String previous = verified.putIfAbsent(code, company);
                            if (previous != null && !previous.equals(company)) throw new Failure("CONFLICTING_MAPPING");
                            if (verified.size() > limits.mappings) throw new Failure("MAPPING_LIMIT");
                        }
                    } else if (depth == 1) rootClosed = true;
                    depth--;
                }
            }
            budget.check();
            if (!rootClosed || depth != 0 || verified.isEmpty()) throw new Failure("NO_VERIFIED_MAPPINGS");
            // Consume the entire entry before publication, including any decompression boundary.
            byte[] tail = new byte[8192]; while (true) { budget.check(); int read = input.read(tail); budget.check(); if (read < 0) break; }
            return verified;
        } finally { xml.close(); }
    }

    private static String findFailure(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof Failure failure) return failure.reason;
        return "ARCHIVE_OR_STORAGE_FAILURE";
    }
    private final class Budget {
        final long started, duration;
        Budget(long started, long duration) { this.started = started; this.duration = duration; }
        boolean expired() { return nanoTime.getAsLong() - started >= duration; }
        void check() {
            if (expired()) throw new Failure("DEADLINE_EXCEEDED");
            if (closed || Thread.currentThread().isInterrupted()) throw new Failure("INTERRUPTED");
        }
    }
    private static final class Failure extends RuntimeException {
        final String reason; Failure(String reason) { super(reason); this.reason = reason; }
    }
    private final class LimitedInput extends InputStream {
        final InputStream input; final long limit; final Budget budget; long consumed;
        LimitedInput(InputStream input, long limit, Budget budget) { this.input = input; this.limit = limit; this.budget = budget; }
        private int account(int bytes) { budget.check(); if (bytes > 0 && (consumed += bytes) > limit) throw new Failure("XML_BYTE_LIMIT"); return bytes; }
        @Override public int read() throws IOException { budget.check(); int value = input.read(); account(value < 0 ? 0 : 1); return value; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException { budget.check(); return account(input.read(buffer, offset, length)); }
    }
    private static final class ActiveRefresh {
        final Thread worker; final AtomicReference<ClientHttpResponse> response = new AtomicReference<>();
        ActiveRefresh(Thread worker) { this.worker = worker; }
        void closeResponse() { ClientHttpResponse current = response.getAndSet(null); if (current != null) current.close(); }
        void cancel() { closeResponse(); worker.interrupt(); }
    }
    @PreDestroy @Override public void close() {
        closed = true; queue.close(); ActiveRefresh running = active; if (running != null) running.cancel(); watchdog.shutdownNow();
    }
}
