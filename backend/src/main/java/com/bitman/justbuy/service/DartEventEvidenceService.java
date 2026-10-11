package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.LongSupplier;

@Service
public class DartEventEvidenceService {
    private static final String API = "https://opendart.fss.or.kr/api/";
    private static final long BUDGET_NANOS = 10_000_000_000L;
    private static final int MAX_JSON = 65_536;
    private static final int MAX_ZIP = 1_048_576;
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private record Definition(String endpoint, String type, List<String> money, List<String> shares,
        List<String> percent, List<String> dates, List<String> text) {}
    private static final List<Definition> DEFINITIONS = List.of(
        new Definition("tsstkAqDecsn.json", "BUYBACK", List.of("aqpln_prc_ostk", "aqpln_prc_estk"),
            List.of("aqpln_stk_ostk", "aqpln_stk_estk"), List.of(),
            List.of("aqexpd_bgd", "aqexpd_edd", "aq_dd"), List.of("aq_pp", "aq_mth")),
        new Definition("cvbdIsDecsn.json", "CONVERTIBLE_BOND", List.of("bd_fta", "cv_prc"),
            List.of("cvisstk_cnt"), List.of("bd_intr_ex", "bd_intr_sf", "cvisstk_tisstk_vs"),
            List.of("bd_mtd", "cvrqpd_bgd", "cvrqpd_edd", "pymd", "bddd"), List.of()),
        new Definition("piicDecsn.json", "RIGHTS_ISSUE", List.of("fdpp_fclt", "fdpp_op", "fdpp_dtrp", "fdpp_ocsa", "fdpp_etc"),
            List.of("nstk_ostk_cnt", "nstk_estk_cnt", "bfic_tisstk_ostk", "bfic_tisstk_estk"),
            List.of(), List.of(), List.of("ic_mthn")));

    private final DartProperties properties;
    private final DartCorpCodeMapper corpMapper;
    private final RestTemplate client;
    private final ObjectMapper mapper;
    private final Options options;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private record Cached(StockEvidence evidence, Instant expiresAt) {}
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    public record Options(boolean enabled) {}
    public record EvidenceSnapshot(String status, String capturedAt, List<StockEvidence> stocks) {}
    public record StockEvidence(String stockCode, String corpCode, String status,
        String capturedAt, List<EventFact> events, List<String> issues) {}
    public record EventFact(String type, String receiptNo, String filingDate, String title,
        String sourceUrl, String amendmentStatus, Map<String, String> facts, List<String> unknownFields) {}

    @Autowired
    public DartEventEvidenceService(DartProperties properties, DartCorpCodeMapper corpMapper,
        @Qualifier("dartEventEvidence") RestTemplate client, ObjectMapper mapper, Options options) {
        this(properties, corpMapper, client, mapper, options, Clock.systemUTC(), System::nanoTime);
    }

    DartEventEvidenceService(DartProperties properties, DartCorpCodeMapper corpMapper,
        RestTemplate client, ObjectMapper mapper, Options options, Clock clock, LongSupplier nanoTime) {
        this.properties = properties;
        this.corpMapper = corpMapper;
        this.client = client;
        this.mapper = mapper;
        this.options = options;
        this.clock = clock;
        this.nanoTime = nanoTime;
    }

    public EvidenceSnapshot fetchEvidence(List<String> stockCodes) {
        Instant captured = clock.instant();
        if (!options.enabled()) return new EvidenceSnapshot("DISABLED", captured.toString(), List.of());
        if (properties.apiKey() == null || properties.apiKey().isBlank())
            return new EvidenceSnapshot("MISSING_KEY", captured.toString(), List.of());
        Budget budget = new Budget(nanoTime.getAsLong());
        if (Thread.currentThread().isInterrupted()) return new EvidenceSnapshot("ERROR", captured.toString(), List.of());
        List<StockEvidence> stocks = new ArrayList<>();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (stockCodes != null) for (String code : stockCodes) {
            if (code != null && code.matches("[0-9]{6}")) selected.add(code);
            if (selected.size() == 5) break;
        }
        for (String code : selected) {
            try {
                budget.check();
                StockEvidence cached = cached(code, captured);
                if (cached != null) { stocks.add(cached); continue; }
                String corpCode = corpMapper.getCachedCorpCode(code);
                budget.check();
                if (corpCode == null) {
                    stocks.add(failed(code, "", captured, "MISSING_CORP_MAPPING"));
                    continue;
                }
                if (!corpCode.matches("[0-9]{8}")) {
                    stocks.add(failed(code, "", captured, "INVALID_CORP_CODE"));
                    continue;
                }
                StockEvidence stock = fetchStock(code, corpCode, captured, budget);
                stocks.add(stock);
                if ("COMPLETE".equals(stock.status()) || "EMPTY".equals(stock.status())) remember(code, stock, captured);
            } catch (EvidenceFailure failure) {
                stocks.add(failed(code, "", captured, failure.issue));
                if (failure.stopBatch) break;
            } catch (RuntimeException error) {
                // HTTP exceptions can contain the key-bearing request URL. Never log their message or cause.
                stocks.add(failed(code, "", captured, "TRANSPORT_OR_PARSE_ERROR"));
            }
        }
        String status = stocks.isEmpty() ? "EMPTY" : batchStatus(stocks);
        return new EvidenceSnapshot(status, captured.toString(), List.copyOf(stocks));
    }

    public String fetchFormattedEvidence(List<String> stockCodes) {
        EvidenceSnapshot snapshot = fetchEvidence(stockCodes);
        if ("DISABLED".equals(snapshot.status())) return "";
        StringBuilder text = new StringBuilder("\n[DART 이벤트 근거] 상태=").append(snapshot.status())
            .append(" 조회시각=").append(snapshot.capturedAt())
            .append("\n금액은 원, 주식수는 주, 비율은 % 단위. 정정·철회 및 unknown은 확정 호재가 아니며 점수를 자동 가산하지 않는다.\n");
        text.append("조회 범위: 최근 30일, 목록 1페이지 최대 20건, 각 구조화 API 최대 20건, 종목별 원문 최대 2건. ")
            .append("EMPTY는 이 조회 범위에 근거가 없다는 뜻이며 전체 공시 부재를 증명하지 않는다.\n");
        for (StockEvidence stock : snapshot.stocks()) {
            text.append("종목=").append(stock.stockCode()).append(" corp_code=").append(stock.corpCode())
                .append(" 상태=").append(stock.status()).append(" 근거조회시각=").append(stock.capturedAt()).append('\n');
            if (!stock.issues().isEmpty()) text.append("미확인 사유=").append(String.join(",", stock.issues())).append('\n');
            for (EventFact event : stock.events()) {
                StringBuilder item = new StringBuilder("  ").append(event.type()).append(" 접수번호=")
                    .append(event.receiptNo()).append(" 공시일=").append(event.filingDate().isBlank() ? "unknown" : event.filingDate())
                    .append(" 정정상태=").append(event.amendmentStatus()).append(" 제목=").append(event.title()).append('\n')
                    .append("  원문=").append(event.sourceUrl()).append('\n');
                event.facts().forEach((field, value) -> item.append("  ").append(field).append('=').append(value)
                    .append(value.endsWith("원") || value.endsWith("%") ? "" : unit(field)).append('\n'));
                if (!event.unknownFields().isEmpty()) item.append("  unknown=").append(String.join(",", event.unknownFields())).append('\n');
                if (text.length() + item.length() > 24_000) {
                    text.append("추가 근거는 표시 한도 초과로 생략됨.\n");
                    return text.toString();
                }
                text.append(item);
            }
        }
        return text.toString();
    }

    private StockEvidence fetchStock(String stock, String corp, Instant captured, Budget budget) {
        LocalDate today = captured.atZone(KOREA).toLocalDate();
        LocalDate start = today.minusDays(30);
        Map<String, String> query = Map.of("corp_code", corp, "bgn_de", start.format(BASIC_DATE), "end_de", today.format(BASIC_DATE));
        List<EventFact> events = new ArrayList<>();
        LinkedHashSet<String> issues = new LinkedHashSet<>();
        for (Definition definition : DEFINITIONS) {
            JsonNode rows = requestRows(definition.endpoint(), query, budget);
            int count = 0;
            for (JsonNode row : rows) {
                if (++count > 20) { issues.add("STRUCTURED_ROW_LIMIT"); break; }
                String receipt = string(row, "rcept_no");
                if (!row.isObject() || !corp.equals(string(row, "corp_code")) || !receipt.matches("[0-9]{14}")
                    || (row.has("stock_code") && !stock.equals(string(row, "stock_code")))) {
                    issues.add("INVALID_STRUCTURED_IDENTITY"); continue;
                }
                Map<String, String> facts = new LinkedHashMap<>();
                List<String> unknown = new ArrayList<>();
                collectFields(row, definition.money(), "INTEGER", facts, unknown, issues);
                collectFields(row, definition.shares(), "INTEGER", facts, unknown, issues);
                collectFields(row, definition.percent(), "PERCENT", facts, unknown, issues);
                collectFields(row, definition.dates(), "DATE", facts, unknown, issues);
                collectFields(row, definition.text(), "TEXT", facts, unknown, issues);
                events.add(event(definition.type(), receipt, "", definition.type(), "UNKNOWN", facts, unknown));
            }
        }
        Map<String, String> listQuery = new LinkedHashMap<>(query);
        listQuery.putAll(Map.of("last_reprt_at", "N", "sort", "date", "sort_mth", "desc", "page_no", "1", "page_count", "20"));
        Rows listResponse = requestPage("list.json", listQuery, budget);
        JsonNode list = listResponse.rows;
        if (listResponse.morePages) issues.add("DISCLOSURE_PAGE_LIMIT");
        Map<String, ListMetadata> metadata = new LinkedHashMap<>();
        Set<String> invalidReceipts = new HashSet<>();
        int count = 0;
        for (JsonNode row : list) {
            if (++count > 20) { issues.add("DISCLOSURE_ROW_LIMIT"); break; }
            String receipt = string(row, "rcept_no");
            LocalDate date = parseDate(string(row, "rcept_dt"));
            if (!row.isObject() || !corp.equals(string(row, "corp_code")) || !stock.equals(string(row, "stock_code"))
                || !receipt.matches("[0-9]{14}") || date == null || date.isAfter(today) || date.isBefore(start)) {
                issues.add("INVALID_DISCLOSURE_IDENTITY_OR_DATE");
                if (receipt.matches("[0-9]{14}")) invalidReceipts.add(receipt);
                continue;
            }
            String title = safeText(string(row, "report_nm"), 200);
            if (title.isBlank()) { issues.add("MISSING_DISCLOSURE_TITLE"); invalidReceipts.add(receipt); continue; }
            String remarks = string(row, "rm");
            String amendment = remarks.contains("철") || title.contains("철회") ? "WITHDRAWN"
                : remarks.contains("정") || title.contains("정정") ? "AMENDED" : "ORIGINAL";
            var item = new ListMetadata(date.toString(), title, amendment);
            if (metadata.containsKey(receipt) && !metadata.get(receipt).equals(item)) {
                invalidReceipts.add(receipt); issues.add("CONFLICTING_DISCLOSURE_METADATA");
            } else metadata.put(receipt, item);
        }
        List<EventFact> linked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EventFact event : events) {
            if (invalidReceipts.contains(event.receiptNo())) continue;
            String identity = event.type() + ":" + event.receiptNo();
            if (!seen.add(identity)) { issues.add("DUPLICATE_STRUCTURED_RECEIPT"); continue; }
            ListMetadata item = metadata.get(event.receiptNo());
            if (item == null) {
                issues.add("UNKNOWN_FILING_DATE_OR_AMENDMENT");
                linked.add(event);
            } else linked.add(event(event.type(), event.receiptNo(), item.date, item.title, item.amendment, event.facts(), event.unknownFields()));
        }
        int originals = 0;
        for (Map.Entry<String, ListMetadata> entry : metadata.entrySet()) {
            if (invalidReceipts.contains(entry.getKey())) continue;
            ListMetadata item = entry.getValue();
            String type = originalType(item.title);
            if (type == null) continue;
            if (originals++ == 2) { issues.add("ORIGINAL_DOCUMENT_LIMIT"); break; }
            budget.check();
            Map<String, String> facts = Map.of();
            List<String> unknown = originalFields(type);
            try {
                byte[] body = requestBytes("document.xml", Map.of("rcept_no", entry.getKey()), MAX_ZIP, budget);
                var parsed = DartOriginalDocumentParser.parse(body, type);
                budget.check();
                facts = parsed.facts(); unknown = parsed.unknownFields();
            } catch (EvidenceFailure failure) {
                throw failure;
            } catch (RuntimeException error) {
                issues.add("INVALID_ORIGINAL_DOCUMENT");
            }
            if (!unknown.isEmpty()) issues.add("UNKNOWN_ORIGINAL_FIELDS");
            linked.add(event(type, entry.getKey(), item.date, item.title, item.amendment, facts, unknown));
        }
        budget.check();
        boolean onlyCoverageLimit = !issues.isEmpty() && issues.stream().allMatch(issue ->
            Set.of("DISCLOSURE_PAGE_LIMIT", "DISCLOSURE_ROW_LIMIT", "STRUCTURED_ROW_LIMIT", "ORIGINAL_DOCUMENT_LIMIT").contains(issue));
        String status = issues.isEmpty() ? linked.isEmpty() ? "EMPTY" : "COMPLETE"
            : !linked.isEmpty() || onlyCoverageLimit ? "PARTIAL" : "ERROR";
        return new StockEvidence(stock, corp, status, captured.toString(), List.copyOf(linked), List.copyOf(issues));
    }

    private JsonNode requestRows(String endpoint, Map<String, String> parameters, Budget budget) {
        return requestPage(endpoint, parameters, budget).rows;
    }

    private record Rows(JsonNode rows, boolean morePages) {}
    private Rows requestPage(String endpoint, Map<String, String> parameters, Budget budget) {
        byte[] body = requestBytes(endpoint, parameters, MAX_JSON, budget);
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) throw new EvidenceFailure("INVALID_JSON_SCHEMA", false);
            String status = string(root, "status");
            if ("013".equals(status)) return new Rows(mapper.createArrayNode(), false);
            if (!"000".equals(status)) {
                if (Set.of("010", "011", "012", "020").contains(status))
                    throw new EvidenceFailure("PROVIDER_STATUS_" + status, true);
                throw new EvidenceFailure("PROVIDER_STATUS_ERROR", false);
            }
            JsonNode rows = root.path("list");
            if (!rows.isArray()) throw new EvidenceFailure("INVALID_JSON_SCHEMA", false);
            boolean morePages = "list.json".equals(endpoint)
                && (root.path("total_page").asInt(0) > 1 || root.path("total_count").asInt(0) > 20);
            return new Rows(rows, morePages);
        } catch (EvidenceFailure failure) { throw failure; }
        catch (Exception error) { throw new EvidenceFailure("INVALID_JSON", false); }
    }

    private byte[] requestBytes(String endpoint, Map<String, String> parameters, int limit, Budget budget) {
        budget.check();
        var uri = UriComponentsBuilder.fromUriString(API + endpoint).queryParam("crtfc_key", properties.apiKey());
        parameters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> uri.queryParam(entry.getKey(), entry.getValue()));
        URI target = uri.build().encode().toUri();
        byte[] body = client.execute(target, HttpMethod.GET, null, response -> {
            budget.check();
            if (!response.getStatusCode().is2xxSuccessful()) throw new EvidenceFailure("HTTP_STATUS_ERROR", false);
            if (response.getHeaders().getContentLength() > limit) throw new EvidenceFailure("RESPONSE_TOO_LARGE", false);
            var output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = response.getBody().read(buffer)) != -1) {
                budget.check();
                if (output.size() + count > limit) throw new EvidenceFailure("RESPONSE_TOO_LARGE", false);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        });
        budget.check();
        if (body == null) throw new EvidenceFailure("EMPTY_HTTP_BODY", false);
        return body;
    }

    private void collectFields(JsonNode row, List<String> fields, String kind, Map<String, String> facts,
        List<String> unknown, Set<String> issues) {
        for (String field : fields) {
            String raw = string(row, field).trim();
            boolean valid = switch (kind) {
                case "INTEGER" -> number(raw, false);
                case "PERCENT" -> number(raw, true);
                case "DATE" -> parseDate(raw) != null;
                default -> !raw.isBlank() && raw.length() <= 300 && safeText(raw, 300).equals(raw);
            };
            if (valid) facts.put(field, raw);
            else {
                unknown.add(field);
                if (!raw.isBlank() && !"-".equals(raw)) issues.add("INVALID_STRUCTURED_FIELD");
            }
        }
    }

    private static boolean number(String raw, boolean percent) {
        if (raw.length() > 50 || !raw.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)" + (percent ? "(?:\\.[0-9]+)?" : ""))) return false;
        try {
            BigDecimal value = new BigDecimal(raw.replace(",", ""));
            return value.precision() <= 32 && value.signum() >= 0 && (!percent || value.compareTo(BigDecimal.valueOf(100)) <= 0);
        } catch (RuntimeException error) { return false; }
    }

    private static LocalDate parseDate(String raw) {
        try {
            if (raw.matches("[0-9]{8}")) return LocalDate.parse(raw, BASIC_DATE);
            if (raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(raw);
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static String string(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isTextual() ? value.textValue() : "";
    }
    private static String safeText(String raw, int limit) {
        String text = raw.replaceAll("[\\p{Cntrl}]", " ").strip();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
    private static String originalType(String title) {
        String normalized = title.replaceAll("\\s+", "");
        if (normalized.contains("단일판매") && normalized.contains("공급계약")) return "SUPPLY_CONTRACT";
        if (normalized.contains("대량보유") || normalized.contains("최대주주변경")) return "SHAREHOLDER_CHANGE";
        return null;
    }
    private static List<String> originalFields(String type) {
        return "SHAREHOLDER_CHANGE".equals(type) ? List.of("ownershipPct", "ownershipChangePct") : List.of("contractAmountWon", "contractPeriod");
    }
    private static EventFact event(String type, String receipt, String date, String title, String amendment,
        Map<String, String> facts, List<String> unknown) {
        return new EventFact(type, receipt, date, title, "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt,
            amendment, Collections.unmodifiableMap(new LinkedHashMap<>(facts)), List.copyOf(unknown));
    }
    private record ListMetadata(String date, String title, String amendment) {}
    private static StockEvidence failed(String stock, String corp, Instant captured, String issue) {
        return new StockEvidence(stock, corp, "ERROR", captured.toString(), List.of(), List.of(issue));
    }
    private static String batchStatus(List<StockEvidence> stocks) {
        if (stocks.stream().allMatch(stock -> "EMPTY".equals(stock.status()))) return "EMPTY";
        if (stocks.stream().allMatch(stock -> Set.of("EMPTY", "COMPLETE").contains(stock.status()))) return "COMPLETE";
        return stocks.stream().anyMatch(stock -> "PARTIAL".equals(stock.status()) || !stock.events().isEmpty()) ? "PARTIAL" : "ERROR";
    }
    private synchronized StockEvidence cached(String code, Instant now) {
        cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt)
            || Instant.parse(entry.getValue().evidence.capturedAt()).isAfter(now));
        Cached cached = cache.get(code);
        return cached == null ? null : cached.evidence;
    }
    private synchronized void remember(String code, StockEvidence evidence, Instant captured) {
        if (cache.size() >= 128 && !cache.containsKey(code)) cache.remove(cache.keySet().iterator().next());
        cache.put(code, new Cached(evidence, captured.plusSeconds(15 * 60)));
    }
    private static String unit(String field) {
        if ("contractAmountWon".equals(field) || DEFINITIONS.stream().anyMatch(d -> d.money().contains(field))) return " 원";
        if (field.endsWith("Pct") || DEFINITIONS.stream().anyMatch(d -> d.percent().contains(field))) return " %";
        if (DEFINITIONS.stream().anyMatch(d -> d.shares().contains(field))) return " 주";
        return "";
    }
    private final class Budget {
        private final long started;
        Budget(long started) { this.started = started; }
        void check() {
            if (Thread.currentThread().isInterrupted()) throw new EvidenceFailure("INTERRUPTED", true);
            if (nanoTime.getAsLong() - started >= BUDGET_NANOS) throw new EvidenceFailure("BUDGET_EXHAUSTED", true);
        }
    }
    private static final class EvidenceFailure extends RuntimeException {
        private final String issue;
        private final boolean stopBatch;
        EvidenceFailure(String issue, boolean stopBatch) {
            super(issue, null, false, false);
            this.issue = issue; this.stopBatch = stopBatch;
        }
    }
}
