package com.bitman.justbuy.research.event;

import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Independent, bounded discovery of publicly disclosed supplier/customer relationships. */
@Service
public class DartSupplyContractSource {
    private static final long BUDGET_NANOS=25_000_000_000L;
    private static final int MAX_JSON=524_288, MAX_ZIP=1_048_576;
    private static final ZoneId KOREA=ZoneId.of("Asia/Seoul");
    private final DartProperties properties;
    private final ObjectMapper mapper;
    private final Fetcher fetcher;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Map<String,CachedOriginal> originals=new LinkedHashMap<>();
    private final Map<String,Instant> filingFirstSeen=new LinkedHashMap<>();
    private List<Filing> filingCatalog=List.of();
    public record Snapshot(String status,Instant capturedAt,List<Contract> contracts,List<String> issues) {
        public Snapshot {contracts=List.copyOf(contracts);issues=List.copyOf(issues);}
    }
    public record Contract(String supplierCode,String supplierName,String customerName,String product,
        BigDecimal amountWon,BigDecimal previousAmountWon,BigDecimal recentSalesWon,LocalDate startDate,
        LocalDate endDate,LocalDate filingDate,String receiptNo,String sourceUrl,Instant firstSeenAt,
        String amendmentStatus,String evidenceText,String documentHash) {}
    public record Filing(String stockCode,String corpName,String receiptNo,String title,LocalDate filingDate,
        Instant firstSeenAt,String sourceUrl) {}
    /** Latest validated listing catalog. Reading it never starts another HTTP request. */
    public synchronized List<Filing> recentFilings() {return filingCatalog;}
    @FunctionalInterface interface Fetcher {byte[] fetch(String endpoint,Map<String,String> query,int byteLimit,Duration timeout);}
    private record Metadata(String supplierCode,String supplierName,LocalDate date,String receipt,String title,String amendment) {}
    private record CachedOriginal(Metadata metadata,Contract contract,List<String> issues) {}

    @Autowired
    public DartSupplyContractSource(DartProperties properties,ObjectMapper mapper) {
        this(properties,mapper,new HttpFetcher(),Clock.systemUTC(),System::nanoTime);
    }
    DartSupplyContractSource(DartProperties properties,ObjectMapper mapper,Fetcher fetcher,Clock clock,LongSupplier nanoTime) {
        this.properties=properties;this.mapper=mapper;this.fetcher=fetcher;this.clock=clock;this.nanoTime=nanoTime;
    }

    public synchronized Snapshot fetchRecent() {
        Instant captured=clock.instant();
        if(properties.apiKey()==null||properties.apiKey().isBlank()) return new Snapshot("MISSING_KEY",captured,List.of(),List.of("MISSING_KEY"));
        long started=nanoTime.getAsLong(); var issues=new LinkedHashSet<String>(); var contracts=new ArrayList<Contract>();
        var metadata=new LinkedHashMap<String,Metadata>(); var invalid=new HashSet<String>();
        LocalDate today=captured.atZone(KOREA).toLocalDate(), from=today.minusDays(30);
        String failureStatus=null;
        try {
            for(int page=1;page<=3;page++) {
                var query=new LinkedHashMap<String,String>();
                query.put("bgn_de",from.format(DateTimeFormatter.BASIC_ISO_DATE));query.put("end_de",today.format(DateTimeFormatter.BASIC_ISO_DATE));
                query.put("pblntf_ty","I");query.put("last_reprt_at","N");query.put("sort","date");query.put("sort_mth","desc");
                query.put("page_no",Integer.toString(page));query.put("page_count","100");
                JsonNode root=mapper.readTree(request("list.json",query,MAX_JSON,started));
                if(root==null||!root.isObject()) throw new SourceFailure("ERROR","INVALID_JSON_SCHEMA");
                String status=text(root,"status");
                if("013".equals(status)) break;
                if(!"000".equals(status)) throw providerFailure(status);
                JsonNode rows=root.path("list");
                if(!rows.isArray()) throw new SourceFailure("ERROR","INVALID_JSON_SCHEMA");
                int count=0;
                for(JsonNode row:rows) {
                    if(++count>100) {issues.add("DISCLOSURE_ROW_LIMIT");break;}
                    String title=text(row,"report_nm");
                    String receipt=text(row,"rcept_no"), stock=text(row,"stock_code"), name=text(row,"corp_name");
                    LocalDate date=parseDate(text(row,"rcept_dt"));
                    if(!receipt.matches("[0-9]{14}")||!stock.matches("[0-9]{6}")||name.isBlank()||name.length()>200
                        ||!text(row,"corp_code").matches("[0-9]{8}")||date==null||date.isAfter(today)||date.isBefore(from)||title.isBlank()||title.length()>300) {
                        issues.add("INVALID_DISCLOSURE_IDENTITY_OR_DATE");if(receipt.matches("[0-9]{14}"))invalid.add(receipt);continue;
                    }
                    String remarks=text(row,"rm");
                    String amendment=title.contains("철회")||title.contains("해지")||title.contains("취소")||remarks.contains("철")?"WITHDRAWN"
                        :title.contains("정정")||remarks.contains("정")?"AMENDED":"ORIGINAL";
                    var item=new Metadata(stock,name,date,receipt,title,amendment);
                    Metadata earlier=metadata.putIfAbsent(receipt,item);
                    if(earlier!=null&&!earlier.equals(item)){invalid.add(receipt);issues.add("CONFLICTING_DISCLOSURE_METADATA");}
                }
                int totalPages=root.path("total_page").asInt(1);
                if(totalPages<=page)break;
                if(page==3)issues.add("DISCLOSURE_PAGE_LIMIT");
            }
            var catalog=new ArrayList<Filing>();
            for(Metadata item:metadata.values()) {
                if(invalid.contains(item.receipt()))continue;
                Instant known=filingFirstSeen.get(item.receipt());
                if(known==null) {
                    known=captured;
                    if(filingFirstSeen.size()>=512)filingFirstSeen.remove(filingFirstSeen.keySet().iterator().next());
                    filingFirstSeen.put(item.receipt(),known);
                }
                catalog.add(new Filing(item.supplierCode(),item.supplierName(),item.receipt(),item.title(),item.date(),known,
                    "https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+item.receipt()));
            }
            filingCatalog=List.copyOf(catalog);
            int selected=0;
            for(Metadata item:metadata.values()) {
                if(invalid.contains(item.receipt()))continue;
                if(!supplyTitle(item.title()))continue;
                if(item.title().contains("자회사의 주요경영사항")||item.title().contains("종속회사의 주요경영사항")) {
                    issues.add("SUBSIDIARY_SUPPLIER_IDENTITY_UNVERIFIED");continue;
                }
                if(selected++>=12){issues.add("ORIGINAL_DOCUMENT_LIMIT");break;}
                checkBudget(started);
                CachedOriginal cached=originals.get(item.receipt());
                if(cached!=null) {
                    if(!cached.metadata().equals(item)) {
                        issues.add("CONFLICTING_CACHED_DISCLOSURE_METADATA");continue;
                    }
                    for(String issue:cached.issues())issues.add(item.receipt()+":"+issue);
                    contracts.add(cached.contract()); continue;
                }
                try {
                    byte[] archive=request("document.xml",Map.of("rcept_no",item.receipt()),MAX_ZIP,started);
                    if(archive.length<4||archive[0]!='P'||archive[1]!='K') {
                        var status=Pattern.compile("<status>\\s*([0-9]{3})\\s*</status>").matcher(new String(archive,StandardCharsets.UTF_8));
                        if(status.find()&&!"000".equals(status.group(1)))throw providerFailure(status.group(1));
                    }
                    var parsed=SupplyContractDocumentParser.parse(archive,item.amendment());
                    checkBudget(started);
                    for(String issue:parsed.issues())issues.add(item.receipt()+":"+issue);
                    Contract contract=new Contract(item.supplierCode(),item.supplierName(),parsed.customerName(),parsed.product(),
                        parsed.amountWon(),parsed.previousAmountWon(),parsed.recentSalesWon(),parsed.startDate(),parsed.endDate(),item.date(),
                        item.receipt(),"https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+item.receipt(),captured,parsed.amendmentStatus(),parsed.evidenceText(),parsed.documentHash());
                    contracts.add(contract);
                    // A successfully parsed partial original is immutable evidence too; retain its unknowns.
                    if(originals.size()>=256)originals.remove(originals.keySet().iterator().next());
                    originals.put(item.receipt(),new CachedOriginal(item,contract,parsed.issues()));
                } catch(SourceFailure failure) {
                    if(Set.of("BUDGET_EXHAUSTED","INTERRUPTED","PROVIDER_RATE_LIMIT","PROVIDER_KEY_REJECTED").contains(failure.issue))throw failure;
                    issues.add(item.receipt()+":"+failure.issue);
                } catch(RuntimeException failure) {issues.add(item.receipt()+":INVALID_ORIGINAL_DOCUMENT");}
            }
        } catch(SourceFailure failure){failureStatus=failure.status;issues.add(failure.issue);}
        catch(Exception failure){failureStatus="ERROR";issues.add("TRANSPORT_OR_PARSE_ERROR");}
        String status=contracts.isEmpty()?failureStatus!=null?failureStatus:issues.isEmpty()?"EMPTY":"ERROR"
            :issues.isEmpty()?"COMPLETE":"PARTIAL";
        return new Snapshot(status,captured,contracts,List.copyOf(issues));
    }
    private byte[] request(String endpoint,Map<String,String> query,int limit,long started) {
        checkBudget(started);
        var parameters=new LinkedHashMap<>(query);parameters.put("crtfc_key",properties.apiKey());
        long remaining=BUDGET_NANOS-(nanoTime.getAsLong()-started);
        byte[] response;
        try {response=fetcher.fetch(endpoint,Collections.unmodifiableMap(parameters),limit,Duration.ofNanos(Math.min(remaining,3_000_000_000L)));}
        catch(SourceFailure failure){throw failure;}
        catch(RuntimeException failure){throw new SourceFailure("ERROR","TRANSPORT_OR_PARSE_ERROR");}
        checkBudget(started);
        if(response==null||response.length==0)throw new SourceFailure("ERROR","EMPTY_HTTP_BODY");
        if(response.length>limit)throw new SourceFailure("ERROR","RESPONSE_SIZE_LIMIT");
        return response;
    }
    private void checkBudget(long started) {
        if(Thread.currentThread().isInterrupted())throw new SourceFailure("ERROR","INTERRUPTED");
        if(nanoTime.getAsLong()-started>=BUDGET_NANOS)throw new SourceFailure("PARTIAL","BUDGET_EXHAUSTED");
    }
    private static SourceFailure providerFailure(String status) {
        return "020".equals(status)?new SourceFailure("RATE_LIMITED","PROVIDER_RATE_LIMIT")
            :Set.of("010","011","012").contains(status)?new SourceFailure("KEY_REJECTED","PROVIDER_KEY_REJECTED")
            :new SourceFailure("ERROR","PROVIDER_STATUS_ERROR");
    }
    private static String text(JsonNode row,String key){JsonNode value=row.path(key);return value.isTextual()?value.textValue().strip():"";}
    private static boolean supplyTitle(String title){String value=title.replaceAll("\\s+","");return value.contains("단일판매")&&value.contains("공급계약");}
    private static LocalDate parseDate(String raw){try{return raw.matches("[0-9]{8}")?LocalDate.parse(raw,DateTimeFormatter.BASIC_ISO_DATE):null;}catch(RuntimeException ignored){return null;}}
    private static final class SourceFailure extends RuntimeException {
        final String status,issue;SourceFailure(String status,String issue){super(issue,null,false,false);this.status=status;this.issue=issue;}
    }
    private static final class HttpFetcher implements Fetcher {
        public byte[] fetch(String endpoint,Map<String,String> query,int limit,Duration timeout) {
            if(!Set.of("list.json","document.xml").contains(endpoint))throw new SourceFailure("ERROR","INVALID_ENDPOINT");
            var url=new StringBuilder("https://opendart.fss.or.kr/api/").append(endpoint).append('?');
            query.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->url.append(URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).append('&'));
            HttpURLConnection connection=null;
            try {
                long deadline=System.nanoTime()+timeout.toNanos();
                connection=(HttpURLConnection)URI.create(url.toString()).toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                int millis=(int)Math.max(1,Math.min(3000,timeout.toMillis()));
                connection.setConnectTimeout(Math.min(2000,millis));connection.setReadTimeout(millis);
                connection.setRequestMethod("GET");connection.setRequestProperty("Accept","application/json, application/zip, application/xml");
                connection.connect();
                connection.setReadTimeout(remainingMillis(deadline));
                if(connection.getResponseCode()!=200)throw new SourceFailure("ERROR","HTTP_STATUS_ERROR");
                if(connection.getContentLengthLong()>limit)throw new SourceFailure("ERROR","RESPONSE_SIZE_LIMIT");
                try(var input=connection.getInputStream();var output=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];int count;
                    while(true) {
                        connection.setReadTimeout(remainingMillis(deadline));
                        if((count=input.read(buffer))==-1)break;
                        if(Thread.currentThread().isInterrupted())throw new SourceFailure("ERROR","INTERRUPTED");
                        if(System.nanoTime()>deadline)throw new SourceFailure("PARTIAL","BUDGET_EXHAUSTED");
                        if(output.size()+count>limit)throw new SourceFailure("ERROR","RESPONSE_SIZE_LIMIT");
                        output.write(buffer,0,count);
                    }
                    return output.toByteArray();
                }
            } catch(SourceFailure failure){throw failure;}
            catch(Exception failure){throw new SourceFailure("ERROR","TRANSPORT_OR_PARSE_ERROR");}
            finally{if(connection!=null)connection.disconnect();}
        }
        private static int remainingMillis(long deadline) {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new SourceFailure("PARTIAL","BUDGET_EXHAUSTED");
            return (int)Math.max(1,Math.min(3000,remaining/1_000_000));
        }
    }
}
