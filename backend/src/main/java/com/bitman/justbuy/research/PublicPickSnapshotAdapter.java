package com.bitman.justbuy.research;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.math.*;
import java.security.MessageDigest;
import static com.bitman.justbuy.research.ResearchConditionEngine.*;

/** Read-only import of the allowlisted public pick archive; not a historical universe. */
public final class PublicPickSnapshotAdapter {
    public record AdaptedSnapshot(ResearchConditionEngine.Snapshot snapshot,int slotCount,int distinctStocks,
                                  int rewardRiskBelowOne,String inputDigest,List<String> limitations) {}
    public AdaptedSnapshot read(Path file,Instant importedAt,Duration maxAge) {
        Objects.requireNonNull(importedAt);Objects.requireNonNull(maxAge);
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>4*1024*1024)throw new IllegalArgumentException("Invalid public snapshot file");
            byte[] bytes=Files.readAllBytes(file);
            if(bytes.length>4*1024*1024)throw new IllegalArgumentException("Public snapshot too large");
            var factory=JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(4096).maxNumberLength(40).build()).build();
            var mapper=new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=mapper.readTree(bytes);
            if(root==null||!root.isArray()||root.size()>32)throw new IllegalArgumentException("Expected public mode array");
            var rows=new TreeMap<String,List<Row>>();int slots=0,warnings=0;
            for(JsonNode mode:root){
                JsonNode picks=mode.path("stockPicks");
                if(!mode.isObject()||!picks.isArray()||picks.size()>1000)throw new IllegalArgumentException("Invalid public picks");
                for(JsonNode pick:picks){
                    if(++slots>10000)throw new IllegalArgumentException("Too many public picks");
                    String code=text(pick,"code");
                    if(code==null||!code.matches("[0-9]{6}"))throw new IllegalArgumentException("Invalid stock identity");
                    BigDecimal price=price(pick,"currentPrice"),target=price(pick,"targetPrice"),stop=price(pick,"stopLoss");
                    BigDecimal ratio=ratio(price,target,stop);
                    if(ratio!=null&&ratio.compareTo(BigDecimal.ONE)<0)warnings++;
                    Instant at=instant(pick,"quoteCheckedAt");
                    String provider=provider(text(pick,"quoteSource"));
                    Environment environment=provider.endsWith("MOCK")?Environment.MOCK:Environment.REAL;
                    rows.computeIfAbsent(code,ignored->new ArrayList<>()).add(new Row(code,price,ratio,at,provider,environment));
                }
            }
            var candidates=new ArrayList<Candidate>();
            for(var entry:rows.entrySet()){
                List<Row> values=entry.getValue();
                // Newest quote selects a current archive view, not a historical trading universe.
                values.sort(Comparator.comparing(Row::observedAt,Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                    .thenComparing(Row::provider).thenComparing(r->r.ratio()==null?"":r.ratio().toPlainString()));
                Row latest=values.getFirst();
                boolean conflict=values.stream().filter(r->Objects.equals(r.observedAt(),latest.observedAt()))
                    .anyMatch(r->!decimalEqual(r.price(),latest.price())||r.environment()!=latest.environment()||!r.provider().equals(latest.provider()));
                boolean scenarioConflict=values.stream().filter(r->Objects.equals(r.observedAt(),latest.observedAt()))
                    .anyMatch(r->!decimalEqual(r.ratio(),latest.ratio()));
                FactStatus priceState=state(latest.price(),latest,conflict);
                FactStatus ratioState=state(latest.ratio(),latest,conflict||scenarioConflict);
                candidates.add(new Candidate(entry.getKey(),List.of(
                    new Fact(entry.getKey(),Metric.CURRENT_PRICE,latest.price(),Unit.KRW,latest.provider(),latest.environment(),latest.observedAt(),importedAt,priceState),
                    new Fact(entry.getKey(),Metric.REWARD_RISK,latest.ratio(),Unit.MULTIPLE,latest.provider(),latest.environment(),latest.observedAt(),importedAt,ratioState))));
            }
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new AdaptedSnapshot(new Snapshot(importedAt,maxAge,candidates,null,false),slots,candidates.size(),warnings,digest,
                List.of("PUBLIC_SAVED_PICKS_ONLY","LEGACY_IDENTITY_NOT_POINT_IN_TIME_MASTER","FIRST_AVAILABLE_AT_IMPORT","PRICE_DISTANCE_IS_NOT_EXPECTED_RETURN","NO_FUTURE_OUTCOMES_IN_PICK_SNAPSHOT"));
        } catch(IllegalArgumentException ex){throw ex;}
        catch(Exception ex){throw new IllegalArgumentException("Public snapshot could not be read");}
    }
    private record Row(String code,BigDecimal price,BigDecimal ratio,Instant observedAt,String provider,Environment environment){}
    private static FactStatus state(BigDecimal value,Row row,boolean conflict){
        if(conflict)return FactStatus.CONFLICT;
        return value==null||row.observedAt()==null||row.provider().equals("UNKNOWN")?FactStatus.UNKNOWN:FactStatus.VALID;
    }
    private static String text(JsonNode n,String key){JsonNode value=n.get(key);return value!=null&&value.isTextual()?value.textValue():null;}
    private static Instant instant(JsonNode n,String key){try{return Instant.parse(text(n,key));}catch(Exception ignored){return null;}}
    private static BigDecimal price(JsonNode n,String key){
        JsonNode node=n.get(key);String s=node!=null&&node.isIntegralNumber()?node.asText():text(n,key);
        if(s==null||s.length()>20||!s.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)"))return null;
        try{BigDecimal v=new BigDecimal(s.replace(",",""));return v.signum()>0?v:null;}catch(Exception ignored){return null;}
    }
    private static BigDecimal ratio(BigDecimal price,BigDecimal target,BigDecimal stop){
        if(price==null||target==null||stop==null||target.compareTo(price)<=0||price.compareTo(stop)<=0)return null;
        return target.subtract(price).divide(price.subtract(stop),12,RoundingMode.HALF_EVEN);
    }
    private static boolean decimalEqual(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
    private static String provider(String value){
        return value!=null&&Set.of("NAVER_FINANCE","KIS","KIS_OPENAPI","KIS_REAL","KIWOOM_REST_REAL","KIWOOM_REST_MOCK").contains(value)?value:"UNKNOWN";
    }
}
