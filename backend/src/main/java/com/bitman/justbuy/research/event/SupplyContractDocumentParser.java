package com.bitman.justbuy.research.event;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/** Strict extraction from DART's original XML; uncertainty never becomes a zero. */
public final class SupplyContractDocumentParser {
    private static final int MAX_ZIP=1_048_576, MAX_ENTRY=2_097_152, MAX_EXPANDED=4_194_304;
    private static final Pattern COMPLETED_WITHDRAWAL=Pattern.compile("계약(?:은|이|을)?[^.]{0,15}(?:해지|취소|철회)(?:되었습니다|하였습니다|되었|하였|했|됨)");
    public record Parsed(String customerName,String product,BigDecimal amountWon,BigDecimal previousAmountWon,
        BigDecimal recentSalesWon,LocalDate startDate,LocalDate endDate,String amendmentStatus,
        String evidenceText,String documentHash,List<String> issues) {}
    private record Value(String label,String text) {}
    private SupplyContractDocumentParser() {}

    public static Parsed parse(byte[] bytes,String amendmentStatus) {
        if(bytes==null||bytes.length<4||bytes.length>MAX_ZIP||bytes[0]!='P'||bytes[1]!='K'||bytes[2]!=3||bytes[3]!=4)
            throw new IllegalArgumentException("INVALID_ORIGINAL_ARCHIVE");
        Map<String,List<Value>> fields=new HashMap<>();
        var issues=new LinkedHashSet<String>();
        var evidence=new StringBuilder();
        int entries=0, expanded=0, xmlEntries=0;
        try(var input=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while((entry=input.getNextEntry())!=null) {
                if(++entries>8||unsafe(entry.getName())) throw new IllegalArgumentException("UNSAFE_ARCHIVE");
                var output=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int count;
                while((count=input.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("INTERRUPTED");
                    expanded+=count;
                    if(output.size()+count>MAX_ENTRY||expanded>MAX_EXPANDED) throw new IllegalArgumentException("ARCHIVE_SIZE_LIMIT");
                    output.write(buffer,0,count);
                }
                String lower=entry.getName().toLowerCase(Locale.ROOT);
                if(!entry.isDirectory()&&(lower.endsWith(".xml")||lower.endsWith(".html")||lower.endsWith(".htm"))) {
                    xmlEntries++;
                    byte[] document=output.toByteArray();
                    // OpenDART returns UTF-8 HTML in .xml entries. Route by an anchored root,
                    // never by catching and downgrading a strict XML/security parsing failure.
                    if(lower.endsWith(".xml")&&!hasHtmlRoot(document)) collect(document,fields,evidence);
                    else collectHtml(document,fields,evidence);
                }
                input.closeEntry();
            }
            if(xmlEntries==0) throw new IllegalArgumentException("NO_ORIGINAL_XML");
        } catch(Exception failure) { throw new IllegalArgumentException("INVALID_ORIGINAL_DOCUMENT"); }
        String status=Set.of("ORIGINAL","AMENDED","WITHDRAWN").contains(amendmentStatus)?amendmentStatus:"UNKNOWN";
        if(fields.containsKey("previousAmount")||fields.containsKey("correctedAmount")||fields.containsKey("amendedDocument")) status="WITHDRAWN".equals(status)?status:"AMENDED";
        if(fields.containsKey("changedContract")) {status="WITHDRAWN".equals(status)?status:"AMENDED";issues.add("CUMULATIVE_CHANGED_CONTRACT");}
        if(fields.containsKey("withdrawnDocument"))status="WITHDRAWN";
        BigDecimal amount=money(fields,"amount",issues,"AMOUNT");
        BigDecimal corrected=money(fields,"correctedAmount",issues,"CORRECTED_AMOUNT");
        if(fields.containsKey("correctedAmount")) {
            if(corrected==null||issues.contains("INVALID_AMOUNT")||issues.contains("AMBIGUOUS_AMOUNT")||(amount!=null&&amount.compareTo(corrected)!=0)) {
                amount=null; issues.add("AMBIGUOUS_AMOUNT");
            } else if(!issues.contains("AMBIGUOUS_AMOUNT")) amount=corrected;
        }
        BigDecimal previous=money(fields,"previousAmount",issues,"PRIOR_AMOUNT");
        if(!"AMENDED".equals(status)) previous=null;
        if("AMENDED".equals(status)&&previous==null) issues.add("AMENDMENT_PRIOR_AMOUNT_UNKNOWN");
        if("WITHDRAWN".equals(status)) issues.add("WITHDRAWN_CONTRACT");
        if(amount==null) issues.add("AMOUNT_UNKNOWN");
        String customer=text(fields,"customer",issues,"CUSTOMER");
        if(customer==null||customer.matches("(?s).*(미공개|비공개|미확정|비밀|추후공개|비밀유지|undisclosed|confidential).*")) {
            customer=null; issues.add("CUSTOMER_UNKNOWN");
        }
        String product=text(fields,"product",issues,"PRODUCT");
        if(product==null) issues.add("PRODUCT_UNKNOWN");
        LocalDate start=date(fields,"start",issues), end=date(fields,"end",issues);
        if(start!=null&&end!=null&&end.isBefore(start)) { start=null; end=null; issues.add("INVALID_CONTRACT_PERIOD"); }
        if(start==null||end==null) issues.add("CONTRACT_PERIOD_UNKNOWN");
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
        return new Parsed(customer,product,amount,previous,money(fields,"sales",issues,"RECENT_SALES"),start,end,
            status,evidence.toString(),hash,List.copyOf(issues));
    }

    private static void collect(byte[] xml,Map<String,List<Value>> fields,StringBuilder evidence) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",256);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->{throw new SAXException("EXTERNAL_ENTITY_DISABLED");});
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException error)throws SAXException {throw error;}
            @Override public void fatalError(SAXParseException error)throws SAXException {throw error;}
        });
        var doc=builder.parse(new ByteArrayInputStream(xml));
        note(doc.getDocumentElement().getTextContent(),fields,evidence);
        var tables=doc.getElementsByTagName("TABLE");
        for(int tableIndex=0;tableIndex<tables.getLength();tableIndex++) {
            Element table=(Element)tables.item(tableIndex);
            var rows=table.getElementsByTagName("TR"); var tableRows=new ArrayList<List<String>>();
            for(int rowIndex=0;rowIndex<rows.getLength();rowIndex++) {
                Node row=rows.item(rowIndex);
                if(nearestTable(row)!=table) continue;
                List<String> cells=new ArrayList<>(); var children=row.getChildNodes();
                for(int i=0;i<children.getLength();i++) if(children.item(i) instanceof Element cell&&Set.of("TD","TH").contains(cell.getTagName()))
                    cells.add(clean(cell.getTextContent()));
                tableRows.add(cells);
            }
            collectRows(tableRows,fields,evidence);
        }
    }
    private static final class HtmlTable {
        final List<List<String>> rows=new ArrayList<>();List<String> row;StringBuilder cell;
    }
    private static boolean hasHtmlRoot(byte[] bytes) {
        int offset=bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191?3:0;
        String root=new String(bytes,offset,Math.min(bytes.length-offset,1024),StandardCharsets.ISO_8859_1);
        return Pattern.compile("\\A\\s*(?:<\\?xml\\s[^<>]*\\?>\\s*)?<html(?:\\s|>)",Pattern.CASE_INSENSITIVE).matcher(root).find();
    }
    private static void collectHtml(byte[] bytes,Map<String,List<Value>> fields,StringBuilder evidence) throws Exception {
        String header=new String(bytes,0,Math.min(bytes.length,1000),StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        String html;
        try {html=decode(bytes,StandardCharsets.UTF_8);}
        catch(CharacterCodingException invalidUtf8) {
            if(!header.contains("euc-kr")&&!header.contains("ks_c_5601"))throw invalidUtf8;
            html=decode(bytes,Charset.forName("MS949"));
        }
        if(Pattern.compile("<!\\s*(?:DOCTYPE|ENTITY)",Pattern.CASE_INSENSITIVE).matcher(html).find())throw new IllegalArgumentException("HTML_DECLARATION_DISABLED");
        var tables=new ArrayDeque<HtmlTable>();var all=new ArrayList<HtmlTable>();
        var documentText=new StringBuilder();
        new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback() {
            @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
                if(tag==HTML.Tag.TABLE){if(tables.size()>=256)throw new IllegalArgumentException("HTML_DEPTH_LIMIT");var table=new HtmlTable();tables.push(table);all.add(table);}
                else if(!tables.isEmpty()) {
                    var table=tables.peek();
                    if(tag==HTML.Tag.TR)table.row=new ArrayList<>();
                    else if((tag==HTML.Tag.TD||tag==HTML.Tag.TH)&&table.row!=null)table.cell=new StringBuilder();
                }
            }
            @Override public void handleEndTag(HTML.Tag tag,int pos) {
                if(tables.isEmpty())return;var table=tables.peek();
                if(tag==HTML.Tag.TABLE)tables.pop();
                else if((tag==HTML.Tag.TD||tag==HTML.Tag.TH)&&table.row!=null&&table.cell!=null){table.row.add(clean(table.cell.toString()));table.cell=null;}
                else if(tag==HTML.Tag.TR&&table.row!=null){if(table.rows.size()>=8000)throw new IllegalArgumentException("HTML_ROW_LIMIT");table.rows.add(table.row);table.row=null;}
            }
            @Override public void handleText(char[] text,int pos) {documentText.append(text).append(' ');if(!tables.isEmpty()&&tables.peek().cell!=null)tables.peek().cell.append(text);}
            @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {if(tag==HTML.Tag.BR&&!tables.isEmpty()&&tables.peek().cell!=null)tables.peek().cell.append(' ');}
        },true);
        note(documentText.toString(),fields,evidence);
        for(HtmlTable table:all)collectRows(table.rows,fields,evidence);
    }
    private static String decode(byte[] bytes,Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void note(String text,Map<String,List<Value>> fields,StringBuilder evidence) {
        String compact=norm(text);
        if(compact.contains("변경계약")||compact.contains("기존계약변경")) add(fields,"changedContract","notes","AMENDED");
        if(COMPLETED_WITHDRAWAL.matcher(compact).find()||compact.contains("계약해지에따라"))add(fields,"withdrawnDocument","notes","WITHDRAWN");
    }
    private static void collectRows(List<List<String>> rows,Map<String,List<Value>> fields,StringBuilder evidence) {
        boolean correction=false,period=false;
        for(List<String> cells:rows) {
                if(cells.size()==1&&(cells.getFirst().contains("변경계약")||cells.getFirst().contains("부가가치세")
                    ||cells.getFirst().contains("해지")||cells.getFirst().contains("취소")||cells.getFirst().contains("철회")))evidence(evidence,cells.getFirst());
                if(cells.size()<2) continue;
                String joined=String.join(" | ",cells);
                String normalized=norm(joined);
                if(normalized.contains("정정전")&&normalized.contains("정정후")) { correction=true;add(fields,"amendedDocument","header","AMENDED");evidence(evidence,joined);continue; }
                if(correction) {
                    evidence(evidence,joined);
                    if(cells.size()==3&&norm(cells.getFirst()).contains("계약금액")) {
                        add(fields,"previousAmount",cells.getFirst(),cells.get(1));
                        add(fields,"correctedAmount",cells.getFirst(),cells.get(2));
                        evidence(evidence,joined);
                    }
                    continue;
                }
                for(int cellIndex=0;cellIndex+1<cells.size();cellIndex++) {
                    String label=cells.get(cellIndex), key=norm(label);
                    if(key.contains("계약기간")) period=true;
                    if(key.matches("[0-9]+\\..*")&&!key.contains("계약기간")) period=false;
                    String field=null;
                    if(key.contains("계약금액")&&!key.contains("대비")) field="amount";
                    else if(key.contains("최근매출액")) field="sales";
                    else if(key.matches("(?:[0-9]+\\.)?(?:계약상대방|계약상대|계약상대방명)")) field="customer";
                    else if((key.contains("판매")&&key.contains("공급계약")&&key.endsWith("내용"))||key.replaceFirst("^-","").equals("체결계약명")) field="product";
                    else if(period&&Set.of("시작일","계약시작일").contains(key)) field="start";
                    else if(period&&Set.of("종료일","계약종료일").contains(key)) field="end";
                    if(field!=null) {
                        add(fields,field,label,cellIndex+2==cells.size()?cells.get(cellIndex+1):"");
                        evidence(evidence,joined);
                    } else if(key.contains("계약기간")&&cellIndex+2==cells.size()) {
                        String[] dates=cells.get(cellIndex+1).split("\\s*[~∼]\\s*",-1);
                        if(dates.length==2) {add(fields,"start",label,dates[0]);add(fields,"end",label,dates[1]);evidence(evidence,joined);}
                    }
                }
        }
    }
    private static Node nearestTable(Node node) { for(Node p=node.getParentNode();p!=null;p=p.getParentNode()) if(p instanceof Element e&&"TABLE".equals(e.getTagName())) return p; return null; }
    private static void add(Map<String,List<Value>> fields,String field,String label,String value) { fields.computeIfAbsent(field,k->new ArrayList<>()).add(new Value(label,value)); }
    private static void evidence(StringBuilder text,String row) { if(text.length()+row.length()+1<=8000) text.append(row).append('\n'); }
    private static String norm(String value) { return value.replaceAll("[\\s\\u00a0]",""); }
    private static String clean(String value) { return value.replaceAll("[\\p{Cntrl}\\s\\u00a0]+"," ").strip(); }
    private static boolean unsafe(String name) { return name==null||name.isBlank()||name.startsWith("/")||name.contains("\\")||name.contains(":")||Arrays.stream(name.split("/",-1)).anyMatch(p->p.equals("..")||p.equals(".")); }
    private static BigDecimal money(Map<String,List<Value>> fields,String field,Set<String> issues,String tag) {
        List<Value> values=fields.getOrDefault(field,List.of()); if(values.isEmpty()) return null;
        BigDecimal result=null;
        for(Value value:values) {
            BigDecimal parsed=parseMoney(value);
            if(parsed==null) {issues.add("INVALID_"+tag);return null;}
            if(result!=null&&result.compareTo(parsed)!=0) {issues.add("AMBIGUOUS_"+tag);return null;}
            result=parsed;
        }
        return result;
    }
    private static BigDecimal parseMoney(Value value) {
        String unit=norm(value.label()), raw=value.text().strip(); BigDecimal multiplier;
        if(Pattern.compile("\\([^)]*(?:USD|EUR|JPY|CNY|달러|유로|위안|엔)[^)]*\\)",Pattern.CASE_INSENSITIVE).matcher(unit).find())return null;
        if(unit.contains("(백만)")||unit.contains("(천)")||unit.contains("(억)"))return null;
        if(unit.contains("(백만원)")) multiplier=new BigDecimal("1000000");
        else if(unit.contains("(천원)")) multiplier=new BigDecimal("1000");
        else if(unit.contains("(원)")||raw.endsWith("원")) multiplier=BigDecimal.ONE;
        else return null;
        raw=raw.replaceFirst("\\s*원$","");
        if(raw.length()>60||!raw.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) return null;
        try {BigDecimal amount=new BigDecimal(raw.replace(",","")).multiply(multiplier);return amount.precision()<=32&&amount.stripTrailingZeros().scale()<=0?amount:null;}
        catch(RuntimeException error){return null;}
    }
    private static String text(Map<String,List<Value>> fields,String field,Set<String> issues,String tag) {
        var unique=new LinkedHashSet<String>();
        for(Value value:fields.getOrDefault(field,List.of())) {if(value.text().isBlank()||value.text().equals("-")||value.text().length()>500) {issues.add("INVALID_"+tag);return null;} unique.add(value.text());}
        if(unique.size()>1) {issues.add("AMBIGUOUS_"+tag);return null;}
        return unique.isEmpty()?null:unique.iterator().next();
    }
    private static LocalDate date(Map<String,List<Value>> fields,String field,Set<String> issues) {
        var unique=new HashSet<LocalDate>();
        for(Value value:fields.getOrDefault(field,List.of())) {
            try {
                var match=Pattern.compile("([0-9]{4})[-.년]\\s*([0-9]{1,2})[-.월]\\s*([0-9]{1,2})(?:일|\\.)?").matcher(value.text());
                if(!match.matches()) throw new IllegalArgumentException();
                unique.add(LocalDate.of(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3))));
            } catch(RuntimeException invalid) {issues.add("INVALID_CONTRACT_PERIOD");return null;}
        }
        if(unique.size()>1) {issues.add("AMBIGUOUS_CONTRACT_PERIOD");return null;}
        return unique.isEmpty()?null:unique.iterator().next();
    }
}
