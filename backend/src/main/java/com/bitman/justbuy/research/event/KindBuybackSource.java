package com.bitman.justbuy.research.event;

import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import com.bitman.justbuy.util.KoreanMarketCalendar;

/** Public official application/execution reports; estimates never become actual orders. */
@Service
public class KindBuybackSource {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final Pattern TR=Pattern.compile("<tr\\b[^>]*>(.*?)</tr>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern TD=Pattern.compile("<(?:td|th)\\b[^>]*>(.*?)</(?:td|th)>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    private static final Pattern RECEIPT=Pattern.compile("openDisclsViewer\\('([0-9]{14})'");
    private volatile Snapshot cached;
    public record ReportRow(String market,String code,String name,String category,LocalDate date,
        boolean execution,BigDecimal applied,BigDecimal executed,BigDecimal amountWon,String url,Instant knownAt) {}
    public record Snapshot(String status,Instant asOf,List<Map<String,Object>> rows,List<String> issues) {}
    public synchronized Snapshot fetchRecent() {
        Instant now=Instant.now();if(cached!=null&&cached.asOf().plusSeconds(900).isAfter(now))return cached;
        long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();List<String> issues=new ArrayList<>();List<ReportRow> rows=new ArrayList<>();
        LocalDate day=now.atZone(KST).toLocalDate();int reports=0;
        for(int window=0;window<3;window++) {
            try {
                String form="method=searchTodayDisclosureSub&forward=todaydisclosure_sub&currentPageSize=100&pageIndex=1&orderMode=0&orderStat=D&todayFlag=N&selDate="+day;
                String listing=fetch("https://kind.krx.co.kr/disclosure/todaydisclosure.do",form,deadline);
                var matches=TR.matcher(listing);
                while(matches.find()) {
                    String row=matches.group(1),plain=text(row);
                    if(!plain.contains("자기주식매매")||!(plain.contains("신청내역")||plain.contains("체결내역")))continue;
                    var receipt=RECEIPT.matcher(row);if(!receipt.find())continue;
                    List<String> cells=cells(row);String time=cells.isEmpty()?"":cells.getFirst();
                    Instant known;try{known=day.atTime(LocalTime.parse(time)).atZone(KST).toInstant();}catch(RuntimeException e){issues.add("UNKNOWN_PUBLICATION_TIME");continue;}
                    if(known.isAfter(now))continue;
                    if(++reports>12){issues.add("REPORT_LIMIT");break;}
                    String viewer=fetch("https://kind.krx.co.kr/common/disclsviewer.do?method=search&acptno="+receipt.group(1),null,deadline);
                    var doc=Pattern.compile("<option\\s+value=['\"]([0-9]{14})\\|Y['\"]",Pattern.CASE_INSENSITIVE).matcher(viewer);
                    if(!doc.find()){issues.add("DOCUMENT_ID_UNAVAILABLE");continue;}
                    String path=fetch("https://kind.krx.co.kr/common/disclsviewer.do?method=searchContents&docNo="+doc.group(1),null,deadline);
                    var url=Pattern.compile("https://kind\\.krx\\.co\\.kr/external/[A-Za-z0-9/_.-]+\\.htm").matcher(path);
                    if(!url.find()){issues.add("DOCUMENT_PATH_UNAVAILABLE");continue;}
                    String source=url.group();String body=fetch(source,null,deadline);
                    rows.addAll(parseDocument(body,plain.contains("코스닥")?"KOSDAQ":"KOSPI",plain.contains("체결내역"),source,known));
                }
            }catch(RuntimeException e){issues.add("KIND_TRANSPORT_OR_PARSE_ERROR");}
            day=KoreanMarketCalendar.previousTradingDay(day);if(day==null||System.nanoTime()>=deadline){issues.add("KIND_BUDGET_LIMIT");break;}
        }
        Snapshot result=summarize(rows,now,issues);
        if(!result.rows().isEmpty()||result.issues().isEmpty())cached=result;
        return result;
    }
    public static List<ReportRow> parseDocument(String html,String market,boolean execution,String url,Instant knownAt) {
        official(url);if(html==null||html.length()>2_000_000||knownAt==null)return List.of();
        List<ReportRow> rows=new ArrayList<>();LocalDate date=null;BigDecimal moneyMultiplier=null;Map<String,Integer> headings=new LinkedHashMap<>();
        boolean documentWon=Pattern.compile("단위\\s*[:：]\\s*주\\s*,\\s*원").matcher(text(html)).find();
        var matches=TR.matcher(html);
        while(matches.find()) {
            List<String> cell=cells(matches.group(1));
            if(cell.size()==2&&(cell.getFirst().contains("일자")))try{date=LocalDate.parse(cell.get(1));}catch(RuntimeException ignored){}
            if(cell.contains("종목코드")&&(cell.contains("신청수량")||cell.contains("체결수량"))) {
                headings.clear();for(int i=0;i<cell.size();i++)headings.put(cell.get(i),i);
            }
            if(cell.stream().anyMatch(s->s.contains("체결금액"))) {
                String heading=cell.stream().filter(s->s.contains("체결금액")).findFirst().orElse("");
                moneyMultiplier=heading.contains("천원")?new BigDecimal("1000"):heading.contains("(원)")||documentWon?BigDecimal.ONE:null;
            }
            int codeIndex=headings.getOrDefault("종목코드",-1),sideIndex=headings.getOrDefault("구분",-1),categoryIndex=headings.getOrDefault("신고서구분",-1);
            int nameIndex=headings.containsKey("종목명")?headings.get("종목명"):headings.getOrDefault("종목",-1),applicationIndex=headings.getOrDefault("신청수량",-1);
            if(codeIndex<0||sideIndex<0||categoryIndex<0||nameIndex<0||cell.size()<=Math.max(Math.max(codeIndex,sideIndex),Math.max(nameIndex,categoryIndex))||!cell.get(codeIndex).matches("A?[0-9]{6}")||!"매수".equals(cell.get(sideIndex))||date==null)continue;
            BigDecimal application=applicationIndex>=0&&cell.size()>applicationIndex?integer(cell.get(applicationIndex)):null;if(!execution&&application==null)continue;
            BigDecimal executed=null,amount=null;
            if(execution) {
                int executedIndex=headings.getOrDefault("체결수량",-1),moneyIndex=headings.entrySet().stream().filter(e->e.getKey().contains("체결금액")).map(Map.Entry::getValue).findFirst().orElse(-1);
                if(executedIndex<0||moneyIndex<0||cell.size()<=Math.max(executedIndex,moneyIndex)||moneyMultiplier==null)continue;
                executed=integer(cell.get(executedIndex));amount=integer(cell.get(moneyIndex));if(executed==null||amount==null)continue;amount=amount.multiply(moneyMultiplier);
            }
            if(cell.get(nameIndex).length()>100||!Set.of("직접","신탁").contains(cell.get(categoryIndex)))continue;
            rows.add(new ReportRow(market,cell.get(codeIndex).replaceFirst("^A",""),cell.get(nameIndex),cell.get(categoryIndex),date,execution,application,executed,amount,url,knownAt));
        }
        return List.copyOf(rows);
    }
    public static Snapshot summarize(List<ReportRow> source,Instant cutoff,List<String> originalIssues) {
        List<String> issues=new ArrayList<>(originalIssues);List<ReportRow> available=source.stream().filter(r->!r.knownAt().isAfter(cutoff)).toList();
        LocalDate latest=available.stream().filter(r->!r.execution()).map(ReportRow::date).max(LocalDate::compareTo).orElse(null);
        List<Map<String,Object>> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(ReportRow plan:available) {
            if(plan.execution()||!plan.date().equals(latest))continue;
            String key=plan.market()+":"+plan.code()+":"+plan.category();
            if(!seen.add(key)){issues.add("CONFLICTING_APPLICATION_ROW");result.removeIf(r->key.equals(r.get("identity")));continue;}
            Map<LocalDate,ReportRow> byDate=new TreeMap<>();Set<LocalDate> conflicts=new HashSet<>();
            for(ReportRow execution:available)if(execution.execution()&&execution.market().equals(plan.market())&&execution.code().equals(plan.code())
                &&execution.category().equals(plan.category())&&execution.date().isBefore(plan.date())) {
                ReportRow prior=byDate.putIfAbsent(execution.date(),execution);if(prior!=null&&!prior.equals(execution))conflicts.add(execution.date());
            }
            conflicts.forEach(byDate::remove);if(!conflicts.isEmpty())issues.add("CONFLICTING_EXECUTION_ROW");
            BigDecimal applied=BigDecimal.ZERO,executed=BigDecimal.ZERO;int history=0;
            for(ReportRow r:byDate.values()) {
                List<ReportRow> matching=available.stream().filter(p->!p.execution()&&p.market().equals(r.market())&&p.code().equals(r.code())&&p.category().equals(r.category())&&p.date().equals(r.date())).toList();
                BigDecimal denominator=r.applied();
                if(matching.size()>1){issues.add("CONFLICTING_HISTORICAL_APPLICATION");continue;}
                if(matching.size()==1){BigDecimal observed=matching.getFirst().applied();if(denominator!=null&&denominator.compareTo(observed)!=0){issues.add("APPLICATION_EXECUTION_MISMATCH");continue;}denominator=observed;}
                if(denominator!=null&&denominator.signum()>0&&r.executed().compareTo(denominator)<=0){applied=applied.add(denominator);executed=executed.add(r.executed());history++;}
            }
            BigDecimal ratio=applied.signum()>0?executed.divide(applied,12,RoundingMode.HALF_UP):null;
            ReportRow last=byDate.isEmpty()?null:new ArrayList<>(byDate.values()).getLast();
            Map<String,Object> item=new LinkedHashMap<>();item.put("identity",key);item.put("market",plan.market());item.put("stockCode",plan.code());item.put("stockName",plan.name());item.put("category",plan.category());
            item.put("applicationDate",plan.date().toString());item.put("applicationQuantity",plan.applied().toPlainString());item.put("executionDate",last==null?null:last.date().toString());
            item.put("executionQuantity",last==null?null:last.executed().toPlainString());item.put("executionAmountWon",last==null?null:last.amountWon().toPlainString());
            item.put("fulfilmentRatePct",ratio==null?null:ratio.multiply(new BigDecimal("100")).setScale(4,RoundingMode.HALF_UP).toPlainString());
            item.put("expectedQuantity",ratio==null?null:plan.applied().multiply(ratio).setScale(0,RoundingMode.DOWN).toPlainString());item.put("historyDays",history);
            item.put("sourceUrl",plan.url());item.put("executionSourceUrl",last==null?null:last.url());item.put("knownAt",plan.knownAt().toString());
            item.put("status",ratio==null?"OBSERVED_PLAN_HISTORY_UNKNOWN":"OBSERVED_PLAN_WITH_HISTORICAL_SCENARIO");item.put("supportGuaranteed",false);
            result.add(Collections.unmodifiableMap(item));
        }
        if(result.size()>80){issues.add("BUYBACK_ROW_LIMIT");var balanced=new ArrayList<Map<String,Object>>();for(String market:List.of("KOSPI","KOSDAQ"))result.stream().filter(r->market.equals(r.get("market"))).limit(40).forEach(balanced::add);result=balanced;}
        issues.add("HISTORY_WINDOW_MAX_THREE_REPORT_DAYS");
        return new Snapshot(result.isEmpty()?"UNAVAILABLE":"PARTIAL",cutoff,List.copyOf(result),List.copyOf(new LinkedHashSet<>(issues)));
    }
    private String fetch(String url,String form,long deadline) {
        official(url);long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalStateException("BUDGET");
        java.net.HttpURLConnection connection=null;
        try {
            connection=(java.net.HttpURLConnection)URI.create(url).toURL().openConnection();connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout((int)Math.max(1,Math.min(3000,remaining/1_000_000)));connection.setReadTimeout(5000);connection.setRequestProperty("User-Agent","BitManPublicResearch/1.0");
            if(form!=null){connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");connection.setDoOutput(true);try(var output=connection.getOutputStream()){output.write(form.getBytes(StandardCharsets.UTF_8));}}
            if(connection.getResponseCode()!=200)throw new IllegalStateException("HTTP");
            try(var stream=connection.getInputStream();var body=new java.io.ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];while(true){remaining=deadline-System.nanoTime();if(remaining<=0||Thread.currentThread().isInterrupted())throw new IllegalStateException("BUDGET");
                    connection.setReadTimeout((int)Math.max(1,Math.min(5000,remaining/1_000_000)));int count=stream.read(buffer);if(count<0)break;if(body.size()+count>2_000_000)throw new IllegalStateException("SIZE");body.write(buffer,0,count);}
                byte[] bytes=body.toByteArray();
                String utf=new String(bytes,StandardCharsets.UTF_8);return utf.contains("charset=euc-kr")?new String(bytes,java.nio.charset.Charset.forName("EUC-KR")):utf;
            }
        }catch(Exception e){throw new IllegalStateException("SOURCE_READ_FAILED");}finally{if(connection!=null)connection.disconnect();}
    }
    private static void official(String url){try{URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||!"kind.krx.co.kr".equals(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1)throw new IllegalArgumentException("UNTRUSTED_SOURCE");}catch(RuntimeException e){throw new IllegalArgumentException("UNTRUSTED_SOURCE");}}
    private static List<String> cells(String row){List<String> result=new ArrayList<>();var matcher=TD.matcher(row);while(matcher.find())result.add(text(matcher.group(1)));return result;}
    private static String text(String html){return html.replaceAll("<[^>]*>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").trim();}
    private static BigDecimal integer(String value){return value.matches("[0-9]{1,3}(,[0-9]{3})*|[0-9]{1,20}")?new BigDecimal(value.replace(",","")):null;}
}
