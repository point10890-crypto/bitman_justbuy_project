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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Bounded, request-bound OpenDART full financial statement evidence. */
@Service
public class DartFinancialEvidenceService {
    private static final String API = "https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json";
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long CALL_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_REQUESTS = 15;
    private static final int MAX_JSON_BYTES = 2 * 1_048_576;
    private static final Map<String, String> ACCOUNTS = Map.ofEntries(
        Map.entry("ifrs-full_Revenue", "revenue"), Map.entry("ifrs_Revenue", "revenue"),
        Map.entry("dart_OperatingIncomeLoss", "operatingProfit"),
        Map.entry("ifrs-full_ProfitLoss", "netIncome"), Map.entry("ifrs_ProfitLoss", "netIncome"),
        Map.entry("ifrs-full_CashFlowsFromUsedInOperatingActivities", "operatingCashFlow"),
        Map.entry("ifrs_CashFlowsFromUsedInOperatingActivities", "operatingCashFlow"),
        Map.entry("ifrs-full_Equity", "equity"), Map.entry("ifrs_Equity", "equity"),
        Map.entry("ifrs-full_Liabilities", "debt"), Map.entry("ifrs_Liabilities", "debt"));
    private static final Set<String> INCOME_ACCOUNTS = Set.of("revenue", "operatingProfit", "netIncome");
    private static final Set<String> STATEMENTS = Set.of("BS", "IS", "CIS", "CF", "SCE");
    private record Report(int year, String code) {}
    private record Amounts(BigDecimal current, BigDecimal previous) {}
    private record Cached(StockEvidence evidence, Instant expiresAt) {}
    public record EvidenceSnapshot(String status, Instant capturedAt, List<StockEvidence> stocks) {}
    public record StockEvidence(String stockCode, String corpCode, String status, Instant capturedAt,
                                FinancialFacts facts, List<String> issues) {}
    public record FinancialFacts(int businessYear, String reportCode, String fsDiv, String currency,
        String receiptNo, LocalDate filingDate, String period, BigDecimal revenue, BigDecimal operatingProfit,
        BigDecimal netIncome, BigDecimal operatingCashFlow, BigDecimal equity, BigDecimal debt,
        BigDecimal previousRevenue, BigDecimal revenueYoYPct, BigDecimal operatingMarginPct,
        BigDecimal debtRatioPct, String sourceUrl, List<String> risks, String cashFlowPeriod,
        String balancePeriod, String selectionBasis) {}

    private final DartProperties properties;
    private final DartCorpCodeMapper corpMapper;
    private final RestTemplate client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final long budgetNanos;
    // One worker and one queue slot: an uninterruptible transport cannot spawn more I/O.
    private final ThreadPoolExecutor transportExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "dart-financial-evidence"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private final ReentrantLock requestLock = new ReentrantLock();
    private volatile Instant cooldownUntil = Instant.EPOCH;

    @Autowired
    public DartFinancialEvidenceService(DartProperties properties, DartCorpCodeMapper corpMapper,
        @Qualifier("dartFinancialEvidence") RestTemplate client, ObjectMapper mapper) {
        this(properties, corpMapper, client, mapper, Clock.systemUTC(), System::nanoTime);
    }

    DartFinancialEvidenceService(DartProperties properties, DartCorpCodeMapper corpMapper,
        RestTemplate client, ObjectMapper mapper, Clock clock, LongSupplier nanoTime) {
        this(properties, corpMapper, client, mapper, clock, nanoTime, BUDGET_NANOS);
    }

    DartFinancialEvidenceService(DartProperties properties, DartCorpCodeMapper corpMapper,
        RestTemplate client, ObjectMapper mapper, Clock clock, LongSupplier nanoTime, long budgetNanos) {
        this.properties = properties;
        this.corpMapper = corpMapper;
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.budgetNanos = budgetNanos;
    }

    public EvidenceSnapshot fetchEvidence(List<String> stockCodes) {
        Instant captured = clock.instant();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (stockCodes != null) for (String code : stockCodes) {
            if (code != null && code.matches("[0-9]{6}")) selected.add(code);
            if (selected.size() == 5) break;
        }
        List<StockEvidence> stocks = new ArrayList<>();
        Budget budget = new Budget(nanoTime.getAsLong());
        for (String code : selected) {
            String corp = "";
            try {
                if (properties.apiKey() == null || properties.apiKey().isBlank())
                    throw new EvidenceFailure("MISSING_KEY");
                // This lookup only reads the existing validated cache; it never calls corpCode.xml.
                corp = corpMapper.getCachedCorpCode(code);
                if (corp == null || corp.isBlank()) throw new EvidenceFailure("MISSING_CORP_MAPPING");
                if (!corp.matches("[0-9]{8}")) throw new EvidenceFailure("INVALID_CORP_MAPPING");
                String key = code + ":" + corp;
                StockEvidence cached = cached(key, clock.instant());
                if (cached != null) { stocks.add(cached); continue; }
                budget.check();
                if (clock.instant().isBefore(cooldownUntil)) throw new EvidenceFailure("PROVIDER_COOLDOWN");
                boolean locked = requestLock.tryLock(budget.remaining(), TimeUnit.NANOSECONDS);
                if (!locked) throw new EvidenceFailure("BUDGET_EXCEEDED");
                try {
                    cached = cached(key, clock.instant());
                    if (cached != null) { stocks.add(cached); continue; }
                    budget.check();
                    if (clock.instant().isBefore(cooldownUntil)) throw new EvidenceFailure("PROVIDER_COOLDOWN");
                    StockEvidence stock = boundedFetchStock(code, corp, budget);
                    stocks.add(stock);
                    remember(key, stock);
                } finally { requestLock.unlock(); }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                stocks.add(failed(code, corp, "INTERRUPTED"));
            } catch (EvidenceFailure failure) {
                StockEvidence failed = failed(code, corp, failure.issue);
                stocks.add(failed);
                if (corp != null && corp.matches("[0-9]{8}") && !Set.of("BUDGET_EXCEEDED", "INTERRUPTED", "PROVIDER_COOLDOWN", "IN_FLIGHT_BUSY").contains(failure.issue))
                    remember(code + ":" + corp, failed);
            } catch (RuntimeException error) {
                // Provider exceptions may contain key-bearing URIs. Do not log the message/cause/raw body.
                StockEvidence failed = failed(code, corp, "TRANSPORT_OR_PARSE_ERROR");
                stocks.add(failed);
                if (corp != null && corp.matches("[0-9]{8}")) remember(code + ":" + corp, failed);
            }
        }
        long usable = stocks.stream().filter(stock -> stock.facts() != null).count();
        String status = stocks.isEmpty() ? "EMPTY" : usable == 0 ? "UNAVAILABLE"
            : usable == stocks.size() && stocks.stream().allMatch(stock -> "COMPLETE".equals(stock.status())) ? "COMPLETE" : "PARTIAL";
        return new EvidenceSnapshot(status, captured, List.copyOf(stocks));
    }

    private StockEvidence boundedFetchStock(String stock, String corp, Budget budget) throws InterruptedException {
        Future<StockEvidence> future;
        try { future = transportExecutor.submit(() -> fetchStock(stock, corp, budget)); }
        catch (RejectedExecutionException error) { throw new EvidenceFailure("IN_FLIGHT_BUSY"); }
        try {
            StockEvidence result = future.get(budget.remaining(), TimeUnit.NANOSECONDS);
            budget.check();
            return result;
        } catch (TimeoutException error) { throw new EvidenceFailure("BUDGET_EXCEEDED"); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof EvidenceFailure failure) throw failure;
            throw new EvidenceFailure("TRANSPORT_OR_PARSE_ERROR");
        } finally {
            // Even when a socket ignores interrupts, only the one bounded worker remains occupied.
            if (!future.isDone()) {
                future.cancel(true);
                transportExecutor.remove((Runnable) future);
            }
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() { transportExecutor.shutdownNow(); }

    private StockEvidence fetchStock(String stock, String corp, Budget budget) {
        LocalDate today = clock.instant().atZone(KOREA).toLocalDate();
        for (Report report : reports(today)) {
            for (String fsDiv : List.of("CFS", "OFS")) {
                JsonNode response = request(corp, report, fsDiv, budget);
                if ("013".equals(text(response, "status"))) continue;
                FinancialFacts facts = parse(response, stock, corp, report, fsDiv, today);
                if (facts == null) continue;
                boolean complete = facts.revenue() != null && facts.operatingProfit() != null && facts.netIncome() != null
                    && facts.operatingCashFlow() != null && facts.equity() != null && facts.debt() != null;
                return new StockEvidence(stock, corp, complete ? "COMPLETE" : "PARTIAL", clock.instant(), facts, facts.risks());
            }
        }
        return failed(stock, corp, "NO_REPORT_IN_BOUNDED_LOOKUP");
    }

    private JsonNode request(String corp, Report report, String fsDiv, Budget budget) {
        budget.check();
        // A call's fixed connect+read timeouts total five seconds. Do not start it without that reserve.
        if (budget.remaining() < Math.min(CALL_RESERVE_NANOS, budgetNanos) || budget.requests >= MAX_REQUESTS)
            throw new EvidenceFailure("BUDGET_EXCEEDED");
        budget.requests++;
        URI uri = UriComponentsBuilder.fromUriString(API).queryParam("crtfc_key", properties.apiKey())
            .queryParam("corp_code", corp).queryParam("bsns_year", report.year()).queryParam("reprt_code", report.code())
            .queryParam("fs_div", fsDiv).build().encode().toUri();
        try {
            JsonNode result = client.execute(uri, HttpMethod.GET, null, response -> {
                budget.check();
                int status = response.getStatusCode().value();
                if (status == 429) {
                    cooldownUntil = clock.instant().plusSeconds(60);
                    throw new EvidenceFailure("PROVIDER_QUOTA_EXCEEDED");
                }
                if (status == 401 || status == 403) cooldownUntil = clock.instant().plusSeconds(300);
                if (status != 200) throw new EvidenceFailure("HTTP_ERROR");
                if (response.getHeaders().getContentLength() > MAX_JSON_BYTES) throw new EvidenceFailure("RESPONSE_TOO_LARGE");
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                var stream = response.getBody();
                while (true) {
                    budget.check();
                    int count = stream.read(buffer, 0, Math.min(buffer.length, MAX_JSON_BYTES + 1 - body.size()));
                    budget.check();
                    if (count < 0) break;
                    if (count > 0) body.write(buffer, 0, count);
                    if (body.size() > MAX_JSON_BYTES) throw new EvidenceFailure("RESPONSE_TOO_LARGE");
                }
                byte[] bytes = body.toByteArray();
                budget.check();
                if (bytes.length > MAX_JSON_BYTES) throw new EvidenceFailure("RESPONSE_TOO_LARGE");
                return mapper.readTree(bytes);
            });
            budget.check();
            if (result == null || !result.isObject()) throw new EvidenceFailure("INVALID_RESPONSE");
            String status = text(result, "status");
            if ("020".equals(status)) {
                cooldownUntil = clock.instant().plusSeconds(60);
                throw new EvidenceFailure("PROVIDER_QUOTA_EXCEEDED");
            }
            if (Set.of("010", "011", "012", "901").contains(status)) {
                cooldownUntil = clock.instant().plusSeconds(300);
                throw new EvidenceFailure("PROVIDER_AUTH_UNAVAILABLE");
            }
            if (!"000".equals(status) && !"013".equals(status)) throw new EvidenceFailure("PROVIDER_ERROR");
            return result;
        } catch (org.springframework.web.client.HttpStatusCodeException error) {
            if (error.getStatusCode().value() == 429) {
                cooldownUntil = clock.instant().plusSeconds(60);
                throw new EvidenceFailure("PROVIDER_QUOTA_EXCEEDED");
            }
            if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403)
                cooldownUntil = clock.instant().plusSeconds(300);
            throw new EvidenceFailure("HTTP_ERROR");
        }
    }

    private FinancialFacts parse(JsonNode response, String stock, String corp, Report report, String fsDiv, LocalDate today) {
        JsonNode rows = response.path("list");
        if (!rows.isArray() || rows.size() > 2000) throw new EvidenceFailure("INVALID_STATEMENT_LIST");
        if (rows.isEmpty()) return null;
        String receipt = null;
        LocalDate filingDate = null;
        for (JsonNode row : rows) {
            // Full-statement docs bind stock and fs_div through the request and corp mapping; optional supplied values still must agree.
            if (!row.isObject() || !corp.equals(text(row, "corp_code")) || !Integer.toString(report.year()).equals(text(row, "bsns_year"))
                || !report.code().equals(text(row, "reprt_code")) || (row.has("stock_code") && !stock.equals(text(row, "stock_code")))
                || (row.has("fs_div") && !fsDiv.equals(text(row, "fs_div")))) throw new EvidenceFailure("IDENTITY_MISMATCH");
            if (!"KRW".equals(text(row, "currency"))) throw new EvidenceFailure("UNSUPPORTED_OR_MIXED_CURRENCY");
            if (!STATEMENTS.contains(text(row, "sj_div"))) throw new EvidenceFailure("INVALID_STATEMENT_TYPE");
            String currentReceipt = text(row, "rcept_no");
            if (!validReceipt(currentReceipt, today) || (receipt != null && !receipt.equals(currentReceipt)))
                throw new EvidenceFailure("INVALID_OR_MIXED_RECEIPT");
            receipt = currentReceipt;
            if (row.hasNonNull("rcept_dt") && !text(row, "rcept_dt").isBlank()) {
                LocalDate supplied = date(text(row, "rcept_dt"));
                if (supplied == null || supplied.isAfter(today) || (filingDate != null && !filingDate.equals(supplied)))
                    throw new EvidenceFailure("INVALID_FILING_DATE");
                filingDate = supplied;
            }
        }
        String incomeStatement = "IS";
        boolean hasIs = false;
        for (JsonNode row : rows) if ("IS".equals(text(row, "sj_div"))
            && INCOME_ACCOUNTS.contains(ACCOUNTS.getOrDefault(text(row, "account_id"), ""))) { hasIs = true; break; }
        if (!hasIs) incomeStatement = "CIS";
        Map<String, Amounts> amounts = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        List<String> risks = new ArrayList<>();
        for (JsonNode row : rows) {
            String metric = ACCOUNTS.get(text(row, "account_id"));
            if (metric == null || !text(row, "account_detail").isBlank()) continue;
            String statement = text(row, "sj_div");
            if (INCOME_ACCOUNTS.contains(metric) && !incomeStatement.equals(statement)) continue;
            if (Set.of("equity", "debt").contains(metric) && !"BS".equals(statement)) continue;
            if ("operatingCashFlow".equals(metric) && !"CF".equals(statement)) continue;
            BigDecimal current = number(text(row, "thstrm_amount"));
            BigDecimal previous = INCOME_ACCOUNTS.contains(metric)
                ? number(text(row, "11011".equals(report.code()) ? "frmtrm_amount" : "frmtrm_q_amount")) : null;
            Amounts value = new Amounts(current, previous);
            Amounts old = amounts.putIfAbsent(metric, value);
            if (old != null && !old.equals(value)) ambiguous.add(metric);
            if (current == null) risks.add("MISSING_OR_INVALID_" + metric.toUpperCase(Locale.ROOT));
        }
        for (String metric : ambiguous) {
            amounts.remove(metric);
            risks.add("AMBIGUOUS_ACCOUNT_" + metric.toUpperCase(Locale.ROOT));
        }
        if (amounts.isEmpty()) throw new EvidenceFailure("NO_VERIFIED_STANDARD_ACCOUNTS");
        BigDecimal revenue = current(amounts, "revenue"), operating = current(amounts, "operatingProfit");
        BigDecimal income = current(amounts, "netIncome"), cash = current(amounts, "operatingCashFlow");
        BigDecimal equity = current(amounts, "equity"), debt = current(amounts, "debt");
        BigDecimal previousRevenue = amounts.containsKey("revenue") ? amounts.get("revenue").previous() : null;
        if (equity != null && equity.signum() <= 0) risks.add("NON_POSITIVE_EQUITY");
        if (debt != null && debt.signum() < 0) { risks.add("INVALID_NEGATIVE_DEBT"); debt = null; }
        if (revenue != null && revenue.signum() <= 0) risks.add("NON_POSITIVE_REVENUE");
        if (previousRevenue != null && previousRevenue.signum() <= 0) risks.add("YOY_NON_POSITIVE_BASE");
        if (income != null && income.signum() < 0) risks.add("NET_LOSS");
        if (cash != null && cash.signum() < 0) risks.add("NEGATIVE_OPERATING_CASH_FLOW");
        BigDecimal margin = revenue != null && revenue.signum() > 0 && operating != null ? percent(operating, revenue) : null;
        BigDecimal yoy = revenue != null && previousRevenue != null && previousRevenue.signum() > 0
            ? percent(revenue.subtract(previousRevenue), previousRevenue) : null;
        BigDecimal debtRatio = debt != null && equity != null && equity.signum() > 0 ? percent(debt, equity) : null;
        String period = period(report, false);
        return new FinancialFacts(report.year(), report.code(), fsDiv, "KRW", receipt, filingDate, period,
            revenue, operating, income, cash, equity, debt, previousRevenue, yoy, margin, debtRatio,
            "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receipt, List.copyOf(new LinkedHashSet<>(risks)),
            period(report, true), report.year() + " 사업연도 " + reportName(report.code()) + " 보고서 기말(날짜 unknown)",
            "BOUNDED_REPORT_LOOKUP_LATEST_UNKNOWN;REQUEST_BOUND_STOCK_FS;INCOME_" + incomeStatement);
    }

    public String formatEvidence(EvidenceSnapshot snapshot) {
        if (snapshot == null || snapshot.stocks() == null || snapshot.stocks().isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n[DART 검증 재무 근거] 상태=").append(snapshot.status())
            .append(" 조회시각=").append(snapshot.capturedAt()).append('\n')
            .append("조회 범위: 최대5종목, 배치10초/15요청, 연결(CFS) 우선·같은 보고서 별도(OFS) 대체. ")
            .append("전체 최신 보고서 여부는 unknown. 접수일 unknown은 접수번호로 날짜를 추정하지 않는다. ")
            .append("당분기 손익과 누적 현금흐름은 다른 기간이며 섞어 비율을 계산하지 않는다. ROE는 산출하지 않는다.\n")
            .append("산업별 비교기준은 unknown. 금융·보험 등은 일반 부채비율만으로 기업의 품질을 단정할 수 없다.\n");
        for (StockEvidence stock : snapshot.stocks()) {
            out.append("종목=").append(stock.stockCode()).append(" corp_code=").append(stock.corpCode())
                .append(" 상태=").append(stock.status()).append(" 근거조회시각=").append(stock.capturedAt()).append('\n');
            FinancialFacts facts = stock.facts();
            if (facts == null) { out.append("미확인 사유=").append(String.join(",", stock.issues())).append('\n'); continue; }
            out.append("기준=").append(facts.fsDiv()).append(" 통화=").append(facts.currency())
                .append(" 사업연도=").append(facts.businessYear()).append(" 보고서=").append(facts.reportCode())
                .append(" 손익기간=").append(facts.period()).append(" 접수일=")
                .append(facts.filingDate() == null ? "unknown" : facts.filingDate()).append('\n');
            amount(out, "매출액", facts.revenue(), facts.currency());
            amount(out, "전년동기 매출액", facts.previousRevenue(), facts.currency());
            amount(out, "영업이익", facts.operatingProfit(), facts.currency());
            amount(out, "당기순이익", facts.netIncome(), facts.currency());
            out.append("현금흐름기간=").append(facts.cashFlowPeriod()).append('\n');
            amount(out, "영업현금흐름", facts.operatingCashFlow(), facts.currency());
            out.append("재무상태표기준=").append(facts.balancePeriod()).append('\n');
            amount(out, "자본총계", facts.equity(), facts.currency());
            amount(out, "부채총계", facts.debt(), facts.currency());
            amount(out, "매출전년동기증감률", facts.revenueYoYPct(), "%");
            amount(out, "영업이익률", facts.operatingMarginPct(), "%");
            amount(out, "부채비율", facts.debtRatioPct(), "%");
            out.append("리스크=").append(facts.risks().isEmpty() ? "조회 지표 내 감지 없음(전체 안전을 뜻하지 않음)" : String.join(",", facts.risks()))
                .append(" 선택근거=").append(facts.selectionBasis()).append(" 원문=").append(facts.sourceUrl()).append('\n');
        }
        return out.toString();
    }

    public String fetchFormattedEvidence(List<String> stockCodes) { return formatEvidence(fetchEvidence(stockCodes)); }

    private synchronized StockEvidence cached(String key, Instant now) {
        cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()) || now.isBefore(entry.getValue().evidence().capturedAt()));
        Cached cached = cache.get(key); return cached == null ? null : cached.evidence();
    }

    private synchronized void remember(String key, StockEvidence evidence) {
        Instant now = clock.instant();
        cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()) || now.isBefore(entry.getValue().evidence().capturedAt()));
        if (cache.size() >= 128 && !cache.containsKey(key)) cache.remove(cache.keySet().iterator().next());
        cache.put(key, new Cached(evidence, now.plusSeconds(evidence.facts() == null ? 30 : 24 * 3600)));
    }

    private StockEvidence failed(String stock, String corp, String issue) {
        return new StockEvidence(stock, corp == null ? "" : corp, "UNAVAILABLE", clock.instant(), null, List.of(issue));
    }
    private static List<Report> reports(LocalDate today) {
        int year = today.getYear(); List<Report> reports = new ArrayList<>();
        if (today.getMonthValue() >= 10) reports.add(new Report(year, "11014"));
        if (today.getMonthValue() >= 7) reports.add(new Report(year, "11012"));
        if (today.getMonthValue() >= 4) reports.add(new Report(year, "11013"));
        reports.add(new Report(year - 1, "11011"));
        return reports;
    }
    private static String reportName(String code) {
        return switch (code) { case "11013" -> "1분기"; case "11012" -> "반기"; case "11014" -> "3분기"; default -> "연간"; };
    }
    private static String period(Report report, boolean cashFlow) {
        if ("11011".equals(report.code())) return report.year() + " 사업연도 연간";
        return report.year() + " 사업연도 " + reportName(report.code()) + (cashFlow ? " 누적" : " 당분기(3개월)");
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field); return value.isTextual() || value.isNumber() ? value.asText().trim() : "";
    }
    private static BigDecimal number(String value) {
        if (value == null || value.length() > 40 || !value.matches("-?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,4})?")) return null;
        try { BigDecimal amount = new BigDecimal(value.replace(",", "")).stripTrailingZeros(); return amount.precision() <= 25 ? amount : null; }
        catch (NumberFormatException error) { return null; }
    }
    private static BigDecimal current(Map<String, Amounts> values, String metric) {
        Amounts value = values.get(metric); return value == null ? null : value.current();
    }
    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 4, RoundingMode.HALF_UP).stripTrailingZeros();
    }
    private static LocalDate date(String value) {
        try { return value.matches("[0-9]{8}") ? LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) : LocalDate.parse(value); }
        catch (RuntimeException error) { return null; }
    }
    private static boolean validReceipt(String receipt, LocalDate today) {
        if (!receipt.matches("[0-9]{14}")) return false;
        LocalDate prefix = date(receipt.substring(0, 8));
        return prefix != null && !prefix.isAfter(today);
    }
    private static void amount(StringBuilder out, String name, BigDecimal value, String unit) {
        out.append(name).append('=').append(value == null ? "unknown" : value.toPlainString() + " " + unit).append('\n');
    }
    private final class Budget {
        final long started; int requests;
        Budget(long started) { this.started = started; }
        long remaining() { return Math.max(0, budgetNanos - (nanoTime.getAsLong() - started)); }
        void check() {
            if (Thread.currentThread().isInterrupted()) throw new EvidenceFailure("INTERRUPTED");
            long elapsed = nanoTime.getAsLong() - started;
            if (elapsed < 0 || elapsed >= budgetNanos) throw new EvidenceFailure("BUDGET_EXCEEDED");
        }
    }
    private static final class EvidenceFailure extends RuntimeException {
        final String issue;
        EvidenceFailure(String issue) { super(issue); this.issue = issue; }
    }
}
