package com.bitman.justbuy.research.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.text.Normalizer;
import java.util.*;

/** Time-bounded propagation through disclosed, named legal entities only. */
public class EconomicLinkService {
    private static final ZoneId KOREA=ZoneId.of("Asia/Seoul");
    public record Link(String customerName,String supplierCode,String supplierName,String product,
        String receiptNo,String sourceUrl,Instant knownAt,LocalDate contractStartDate,LocalDate contractEndDate) {
        public Link(String customerName,String supplierCode,String supplierName,String product,String receiptNo,String sourceUrl,Instant knownAt) {
            this(customerName,supplierCode,supplierName,product,receiptNo,sourceUrl,knownAt,null,null);
        }
    }
    public record Propagation(Link link,DartSupplyContractSource.Filing event,String status,List<String> issues) {
        public Propagation {issues=List.copyOf(issues);}
    }
    public List<Link> relationships(List<DartSupplyContractSource.Contract> contracts,Instant cutoff) {
        return evaluate(contracts,cutoff).links();
    }
    public List<String> relationshipIssues(List<DartSupplyContractSource.Contract> contracts,Instant cutoff) {
        return evaluate(contracts,cutoff).issues();
    }
    private record Relationships(List<Link> links,List<String> issues) {}
    private Relationships evaluate(List<DartSupplyContractSource.Contract> contracts,Instant cutoff) {
        if(contracts==null||cutoff==null)return new Relationships(List.of(),List.of());
        var unique=new LinkedHashMap<String,DartSupplyContractSource.Contract>();int inspected=0;
        for(var contract:contracts) {
            if(++inspected>512)break;
            if(contract==null||!Set.of("ORIGINAL","AMENDED","WITHDRAWN").contains(contract.amendmentStatus()==null?"":contract.amendmentStatus())
                ||"ORIGINAL".equals(contract.amendmentStatus())&&!named(contract.customerName())||!stock(contract.supplierCode())||!named(contract.supplierName())
                ||contract.firstSeenAt()==null||contract.firstSeenAt().isAfter(cutoff)||!origin(contract.receiptNo(),contract.sourceUrl()))continue;
            String key=contract.receiptNo()+":"+exposure(contract);
            var prior=unique.get(key);
            if(prior==null||contract.firstSeenAt().isBefore(prior.firstSeenAt()))unique.put(key,contract);
        }
        var groups=new LinkedHashMap<String,List<DartSupplyContractSource.Contract>>();
        for(var contract:unique.values())groups.computeIfAbsent(exposure(contract),key->new ArrayList<>()).add(contract);
        var links=new ArrayList<Link>();var issues=new LinkedHashSet<String>();
        for(var group:groups.values()) {
            Instant blockedAt=null;
            for(var contract:group)if(!"ORIGINAL".equals(contract.amendmentStatus())&&(blockedAt==null||contract.firstSeenAt().isAfter(blockedAt)))blockedAt=contract.firstSeenAt();
            boolean recovered=false;
            for(var contract:group) {
                if(!"ORIGINAL".equals(contract.amendmentStatus())||blockedAt!=null&&!contract.firstSeenAt().isAfter(blockedAt))continue;
                // Correction text can rename the product or omit its customer. Without an
                // original-receipt link, retain ambiguity rather than reviving an older exposure.
                boolean unresolvedChange=unique.values().stream().anyMatch(change->!"ORIGINAL".equals(change.amendmentStatus())
                    &&change.supplierCode().equals(contract.supplierCode())
                    &&(!change.firstSeenAt().isBefore(contract.firstSeenAt())||contract.filingDate()==null||change.filingDate()==null||!contract.filingDate().isAfter(change.filingDate()))
                    &&(!named(change.customerName())||legalName(change.customerName()).equals(legalName(contract.customerName()))));
                if(unresolvedChange){issues.add("AMBIGUOUS_CURRENT_EXPOSURE");continue;}
                if(contract.startDate()!=null&&contract.endDate()!=null&&contract.endDate().isBefore(contract.startDate())) {issues.add("AMBIGUOUS_CURRENT_EXPOSURE");continue;}
                links.add(new Link(contract.customerName(),contract.supplierCode(),contract.supplierName(),contract.product(),
                    contract.receiptNo(),contract.sourceUrl(),contract.firstSeenAt(),contract.startDate(),contract.endDate()));
                recovered=true;
            }
            if(blockedAt!=null&&!recovered)issues.add("AMBIGUOUS_CURRENT_EXPOSURE");
        }
        return new Relationships(List.copyOf(links),List.copyOf(issues));
    }
    private static String exposure(DartSupplyContractSource.Contract contract) {
        return (named(contract.customerName())?legalName(contract.customerName()):"UNKNOWN")+":"+contract.supplierCode()+":"+(contract.product()==null?"UNKNOWN":Normalizer.normalize(contract.product(),Normalizer.Form.NFKC).replaceAll("[\\s\\u00a0]","").toUpperCase(Locale.ROOT));
    }
    public List<Propagation> propagate(List<Link> links,List<DartSupplyContractSource.Filing> events,Instant cutoff) {
        if(links==null||events==null||cutoff==null)return List.of();
        var observations=new LinkedHashMap<String,Propagation>();
        int eventCount=0;
        for(var event:events) {
            if(++eventCount>300)break;
            if(event==null||!stock(event.stockCode())||!named(event.corpName())||!origin(event.receiptNo(),event.sourceUrl())
                ||event.firstSeenAt()==null||event.firstSeenAt().isAfter(cutoff)||event.filingDate()==null
                ||event.filingDate().isAfter(cutoff.atZone(KOREA).toLocalDate())||!economicEvent(event.title()))continue;
            int linkCount=0;
            for(Link link:links) {
                if(++linkCount>512||observations.size()>=300)break;
                if(link==null||!named(link.customerName())||!named(link.supplierName())||!stock(link.supplierCode())
                    ||link.knownAt()==null||!link.knownAt().isBefore(event.firstSeenAt())||!origin(link.receiptNo(),link.sourceUrl())
                    ||!event.filingDate().isAfter(link.knownAt().atZone(KOREA).toLocalDate())
                    ||link.contractEndDate()!=null&&link.contractEndDate().isBefore(event.filingDate())
                    ||link.contractStartDate()!=null&&link.contractStartDate().isAfter(event.filingDate())
                    ||link.contractStartDate()!=null&&link.contractEndDate()!=null&&link.contractEndDate().isBefore(link.contractStartDate())
                    ||!legalName(link.customerName()).equals(legalName(event.corpName())))continue;
                String key=link.receiptNo()+":"+link.supplierCode()+":"+event.receiptNo();
                var issues=new ArrayList<String>();issues.add("CUSTOMER_EVENT_ALLOCATION_UNKNOWN");
                if(link.contractStartDate()==null||link.contractEndDate()==null)issues.add("RELATIONSHIP_VALIDITY_UNKNOWN");
                observations.putIfAbsent(key,new Propagation(link,event,"TRANSFER_UNQUANTIFIED",issues));
            }
        }
        return List.copyOf(observations.values());
    }
    private static boolean stock(String value){return value!=null&&value.matches("[0-9]{6}");}
    private static boolean named(String value) {
        if(value==null||value.isBlank()||value.length()>500)return false;
        String name=legalName(value);
        return !name.isBlank()&&!Set.of("-","미공개","비공개","국내주요고객사","국내고객사","고객사","주요고객사","국내업체","해외업체","해외고객사","미정").contains(name)
            &&!name.matches(".*(?:미공개|비공개|미확정|영업비밀|공시유보|CONFIDENTIAL|UNDISCLOSED).*?");
    }
    private static String legalName(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\s\\u00a0]","").replace("주식회사","").replace("(주)","").toUpperCase(Locale.ROOT);
    }
    private static boolean origin(String receipt,String sourceUrl) {
        return receipt!=null&&receipt.matches("[0-9]{14}")&&("https://dart.fss.or.kr/dsaf001/main.do?rcpNo="+receipt).equals(sourceUrl);
    }
    private static boolean economicEvent(String title) {
        if(title==null||title.isBlank()||title.length()>300)return false;
        String normalized=title.replaceAll("\\s+","");
        return normalized.contains("영업(잠정)실적")||normalized.contains("매출액또는손익구조")||normalized.contains("연결재무제표기준영업(잠정)실적")
            ||normalized.contains("단일판매")&&normalized.contains("공급계약")||normalized.contains("신규시설투자")||normalized.contains("타법인주식및출자증권취득결정");
    }
}
