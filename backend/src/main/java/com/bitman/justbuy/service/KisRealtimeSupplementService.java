package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.bitman.justbuy.util.KoreanMarketCalendar;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.*;

/** Supplementary venue data; it never replaces, combines, or totals KRX quotes. */
@Service
public class KisRealtimeSupplementService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final String HISTORICAL="/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
    private record Channel(int fields,String market,String kind) {}
    private static final Map<String,Channel> CHANNELS=Map.of(
        "H0NXCNT0",new Channel(47,"NXT","trade"), "H0UNCNT0",new Channel(47,"INTEGRATED","trade"),
        "H0STASP0",new Channel(63,"KRX","orderBook"), "H0UNASP0",new Channel(66,"INTEGRATED","orderBook"),
        "H0STPGM0",new Channel(11,"KRX","program"), "H0UNMKO0",new Channel(10,"INTEGRATED","marketStatus"));
    private record Event(String tr,String code,Instant anchor,Instant received,String signature,Map<String,Object> values) {}
    private final KisSourceGateway gateway;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final KisRealtimeFrameParser tradeParser=new KisRealtimeFrameParser();
    private Set<String> watched=Set.of();
    private final Set<String> confirmed=new HashSet<>();
    private final Map<String,Event> events=new HashMap<>();
    private WebSocket socket;
    private boolean stopped;

    @Autowired public KisRealtimeSupplementService(KisSourceGateway gateway,ObjectMapper mapper) {
        this(gateway,mapper,Clock.systemUTC());
    }
    KisRealtimeSupplementService(KisSourceGateway gateway,ObjectMapper mapper,Clock clock) {
        this.gateway=gateway;this.mapper=mapper;this.clock=clock;
    }
    public synchronized void watch(List<String> codes) {
        if(stopped)return;
        var selected=new LinkedHashSet<String>();
        if(codes!=null)for(String code:codes){if(code!=null&&code.matches("[0-9]{6}"))selected.add(code);if(selected.size()==2)break;}
        if(!selected.equals(watched)) {
            watched=Collections.unmodifiableSet(selected);events.clear();reset();
        }
    }
    public record Subscription(String trId,String code) {}
    public synchronized List<Subscription> subscriptions() {
        return requests().stream().map(key -> {var pair=key.split("\\|",2);return new Subscription(pair[0],pair[1]);}).toList();
    }
    synchronized void onConnected(WebSocket sharedSocket){if(socket!=sharedSocket){socket=sharedSocket;confirmed.clear();}}
    synchronized void reset(){socket=null;confirmed.clear();}
    private synchronized Set<String> requests() {
        var result=new LinkedHashSet<String>();
        for(String code:watched)for(String tr:CHANNELS.keySet())if(!tr.equals("H0UNMKO0"))result.add(tr+"|"+code);
        if(!watched.isEmpty())result.add("H0UNMKO0|"+watched.iterator().next());
        return result;
    }
    private boolean marketOpen() {
        var now=clock.instant().atZone(KST);var time=now.toLocalTime();
        return KoreanMarketCalendar.isTradingDay(now.toLocalDate())&&!time.isBefore(LocalTime.of(9,0))&&!time.isAfter(LocalTime.of(15,30));
    }
    public synchronized Map<String,Object> readiness() {
        boolean connected=socket!=null&&!socket.isInputClosed()&&!socket.isOutputClosed();
        return Map.of("status",!gateway.isEnabled()?"DISABLED":!marketOpen()?"MARKET_CLOSED":connected?"CONNECTED":"DISCONNECTED",
            "watchedCodes",watched.size(),"requestedSubscriptions",requests().size(),"confirmedSubscriptions",confirmed.size(),
            "combinedSubscriptionUpperBound",20+requests().size(),"connected",connected,
            "sessionScope","SHARED_KRX_READ_ONLY_CONNECTION","sessionWindow","09:00–15:30 KST; extended NXT sessions disabled","maxAgeSeconds",30);
    }
    public synchronized Map<String,Object> facts(String code) {
        if(!gateway.isEnabled())return Map.of("status","DISABLED","availableChannels",0);
        if(!marketOpen())return Map.of("status","MARKET_CLOSED","availableChannels",0);
        var markets=new LinkedHashMap<String,Map<String,Object>>();int count=0;
        for(var event:events.values()) {
            if(!event.code.equals(code)||!confirmed.contains(event.tr+"|"+code)||socket==null||socket.isInputClosed()||socket.isOutputClosed()
                ||clock.instant().isAfter(event.anchor.plusSeconds(30))||event.anchor.isAfter(clock.instant().plusSeconds(5)))continue;
            var channel=CHANNELS.get(event.tr);markets.computeIfAbsent(channel.market,k->new LinkedHashMap<>()).put(channel.kind,event.values);count++;
        }
        int requested=(int)requests().stream().filter(key->key.endsWith("|"+code)).count();
        return Map.of("status",count==0?"UNAVAILABLE":count==requested?"AVAILABLE":"PARTIAL","code",code,
            "availableChannels",count,"requestedChannels",requested,"markets",markets,"scope","VENUE_SEPARATE_NO_KRX_PRICE_REPLACEMENT");
    }
    synchronized void acceptFrame(String frame,WebSocket receivedSocket) {
        if(stopped||socket!=receivedSocket||frame==null||frame.length()>65536)return;
        if(frame.startsWith("{")){control(frame,receivedSocket);return;}
        String[] envelope=frame.split("\\|",4);
        if(envelope.length!=4||!"0".equals(envelope[0])||!envelope[2].matches("[0-9]{1,3}"))return;
        var channel=CHANNELS.get(envelope[1]);if(channel==null)return;
        int count=Integer.parseInt(envelope[2]);if(count<1||count>20||("H0UNMKO0".equals(envelope[1])&&count!=1))return;
        String[] values=envelope[3].split("\\^",-1);if(values.length!=count*channel.fields)return;
        for(int offset=0;offset<values.length;offset+=channel.fields) {
            String[] row=Arrays.copyOfRange(values,offset,offset+channel.fields);
            String code=envelope[1].equals("H0UNMKO0")?(watched.isEmpty()?"":watched.iterator().next()):row[0];
            if(!watched.contains(code)||!confirmed.contains(envelope[1]+"|"+code))continue;
            try {var event=parse(envelope[1],code,row);if(event==null)continue;
                var key=event.tr+"|"+event.code;var previous=events.get(key);
                if(previous!=null&&(event.anchor.isBefore(previous.anchor)||event.signature.equals(previous.signature)))continue;
                if(previous!=null&&channel.kind.equals("trade")&&event.anchor.atZone(KST).toLocalDate().equals(previous.anchor.atZone(KST).toLocalDate())
                    &&((long)event.values.get("volume")<=(long)previous.values.get("volume")
                        ||((BigDecimal)event.values.get("tradingValue")).compareTo((BigDecimal)previous.values.get("tradingValue"))<=0))continue;
                events.put(key,event);
            }catch(RuntimeException ignored){/* Malformed source facts never become evidence. */}
        }
    }
    private void control(String frame,WebSocket ws) {
        try {var root=mapper.readTree(frame);String tr=root.path("header").path("tr_id").asText();
            if(tr.equals("PINGPONG"))return;
            String key=tr+"|"+root.path("header").path("tr_key").asText();if(!requests().contains(key))return;
            if(root.path("body").path("rt_cd").isTextual()&&"0".equals(root.path("body").path("rt_cd").asText())
                &&"N".equals(root.path("header").path("encrypt").asText())&&root.path("body").path("msg1").isTextual()
                &&!root.path("body").path("msg1").asText().startsWith("UNSUB")){confirmed.add(key);}
            else confirmed.remove(key);
        }catch(Exception ignored){/* No secret-bearing control frame or exception text is logged. */}
    }
    private Event parse(String tr,String code,String[] row) {
        Instant received=clock.instant();var channel=CHANNELS.get(tr);var data=new LinkedHashMap<String,Object>();
        data.put("source","KIS "+tr);data.put("market",channel.market);data.put("receivedAt",received.toString());
        Instant anchor=received;
        if(channel.kind.equals("trade")) {
            var quotes=tradeParser.parse("0|H0STCNT0|001|"+String.join("^",row),Set.of(code),clock);if(quotes.size()!=1)return null;
            var q=quotes.getFirst();anchor=q.tradedAt();data.put("tradePrice",q.price());data.put("volume",q.accumulatedVolume());
            data.put("tradingValue",q.tradingValue());data.put("priceUnit","KRW");data.put("volumeUnit","SHARES");data.put("observedAt",anchor.toString());
        } else {
            data.put("sourceObservedDate","NOT_PROVIDED");
            if(!channel.kind.equals("marketStatus")) {
                if(!row[1].matches("[0-9]{6}"))return null;
                LocalTime time=LocalTime.parse(row[1],DateTimeFormatter.ofPattern("HHmmss"));
                if(time.isBefore(LocalTime.of(9,0))||time.isAfter(LocalTime.of(15,30)))return null;
                anchor=received.atZone(KST).toLocalDate().atTime(time).atZone(KST).toInstant();
                if(anchor.isAfter(received.plusSeconds(5)))return null;
                data.put("sourceObservedTime",row[1]);data.put("timestampBasis","SOURCE_TIME_RECEIVE_DATE");
            }
            if(channel.kind.equals("orderBook")) {
                long ask=unsigned(row[3],true),bid=unsigned(row[13],true);if(ask<bid)return null;
                long asks=0,bids=0,priorAsk=0,priorBid=Long.MAX_VALUE;
                for(int i=0;i<10;i++) {
                    long ap=unsigned(row[3+i],false),bp=unsigned(row[13+i],false),aq=unsigned(row[23+i],false),bq=unsigned(row[33+i],false);
                    if((ap==0&&aq>0)||(bp==0&&bq>0)||(ap>0&&ap<priorAsk)||(bp>0&&bp>priorBid))return null;
                    if(ap>0)priorAsk=ap;if(bp>0)priorBid=bp;asks=Math.addExact(asks,aq);bids=Math.addExact(bids,bq);
                }
                long totalAsk=unsigned(row[43],false),totalBid=unsigned(row[44],false);if(totalAsk<asks||totalBid<bids)return null;
                data.put("bestAsk",ask);data.put("bestBid",bid);data.put("bestAskQuantity",unsigned(row[23],false));data.put("bestBidQuantity",unsigned(row[33],false));
                data.put("totalAskQuantity",totalAsk);data.put("totalBidQuantity",totalBid);data.put("priceUnit","KRW");data.put("quantityUnit","SHARES");
            } else if(channel.kind.equals("program")) {
                long sell=unsigned(row[2],false),buy=unsigned(row[4],false),net=signed(row[6]);if(net!=Math.subtractExact(buy,sell))return null;
                data.put("sellQuantity",sell);data.put("buyQuantity",buy);data.put("netBuyQuantity",net);
                data.put("sellAmountRaw",decimal(row[3],false));data.put("buyAmountRaw",decimal(row[5],false));data.put("netAmountRaw",decimal(row[7],true));
                data.put("amountUnit","PROVIDER_UNSPECIFIED");data.put("sellOrderQuantity",unsigned(row[8],false));data.put("buyOrderQuantity",unsigned(row[9],false));
                data.put("netOrderQuantity",signed(row[10]));data.put("quantityUnit","SHARES");
            } else {
                if(!row[0].matches("[YN]")||row[1].length()>160||row[1].contains("\n")||row[1].contains("\r"))return null;
                for(int i=2;i<row.length;i++)if(!row[i].matches("[A-Z0-9]{0,4}"))return null;
                data.put("haltFlag",row[0]);data.put("haltReason",row[1]);data.put("operationClassRaw",row[2]);data.put("viClassRaw",row[7]);data.put("exchangeClassRaw",row[9]);
                data.put("identityBasis","SUBSCRIPTION_SCOPE_SINGLE_CODE");data.put("sourceObservedTime","NOT_PROVIDED");data.put("timestampBasis","RECEIVED_ONLY");
            }
        }
        return new Event(tr,code,anchor,received,String.join("^",row),Map.copyOf(data));
    }
    private static long unsigned(String value,boolean positive){if(!value.matches("[0-9]{1,18}"))throw new IllegalArgumentException();long n=Long.parseLong(value);if(positive&&n==0)throw new IllegalArgumentException();return n;}
    private static long signed(String value){if(!value.matches("-?[0-9]{1,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}
    private static BigDecimal decimal(String value,boolean signed){if(!value.matches((signed?"-?":"")+"[0-9]{1,18}(?:\\.[0-9]{1,4})?"))throw new IllegalArgumentException();return new BigDecimal(value);}

    public Map<String,Object> historicalEvidence(List<String> codes) {
        if(!gateway.isEnabled())return Map.of("status","DISABLED","market","KRX","series",List.of());
        var selected=new LinkedHashSet<String>();if(codes!=null)for(String code:codes){if(code!=null&&code.matches("[0-9]{6}"))selected.add(code);if(selected.size()==2)break;}
        LocalDate day=KoreanMarketCalendar.previousTradingDay(clock.instant().atZone(KST).toLocalDate());
        if(day==null||selected.isEmpty())return Map.of("status","UNAVAILABLE","market","KRX","series",List.of());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);var series=new ArrayList<Map<String,Object>>();
        for(String code:selected) {
            if(System.nanoTime()>=deadline)break;
            try {var response=gateway.fetch(HISTORICAL,"FHKST03010230",Map.of("FID_COND_MRKT_DIV_CODE","J","FID_INPUT_ISCD",code,
                "FID_INPUT_HOUR_1","153000","FID_INPUT_DATE_1",day.format(DateTimeFormatter.BASIC_ISO_DATE),"FID_PW_DATA_INCU_YN","N","FID_FAKE_TICK_INCU_YN","N"),deadline);
                if(System.nanoTime()>=deadline||!"AVAILABLE".equals(response.status())||response.fetchedAt()==null
                    ||response.fetchedAt().isAfter(clock.instant().plusSeconds(5))||response.fetchedAt().isBefore(clock.instant().minusSeconds(180))
                    ||!response.body().path("rt_cd").isTextual()
                    ||!"0".equals(response.body().path("rt_cd").asText()))continue;
                JsonNode rows=response.body().path("output2"),identity=response.body().path("output1");
                if(!rows.isArray()||rows.isEmpty()||rows.size()>120||!identity.path("hts_kor_isnm").isTextual()||identity.path("hts_kor_isnm").asText().isBlank())continue;
                if(identity.has("stck_shrn_iscd")&&!code.equals(identity.path("stck_shrn_iscd").asText()))continue;
                var bars=new TreeMap<Instant,Map<String,Object>>();boolean valid=true;
                for(var row:rows) {
                    if(!day.format(DateTimeFormatter.BASIC_ISO_DATE).equals(row.path("stck_bsop_date").asText())||!row.path("stck_cntg_hour").asText().matches("[0-9]{6}")){valid=false;break;}
                    var time=LocalTime.parse(row.path("stck_cntg_hour").asText(),DateTimeFormatter.ofPattern("HHmmss"));
                    if(time.isBefore(LocalTime.of(9,0))||time.isAfter(LocalTime.of(15,30))){valid=false;break;}
                    long close=unsigned(row.path("stck_prpr").asText(),true),open=unsigned(row.path("stck_oprc").asText(),true),high=unsigned(row.path("stck_hgpr").asText(),true),low=unsigned(row.path("stck_lwpr").asText(),true),volume=unsigned(row.path("cntg_vol").asText(),false);
                    if(low>Math.min(open,close)||high<Math.max(open,close)||high<low){valid=false;break;}
                    Instant at=day.atTime(time).atZone(KST).toInstant();
                    if(bars.put(at,Map.of("observedAt",at.toString(),"open",open,"high",high,"low",low,"close",close,"volume",volume))!=null){valid=false;break;}
                }
                if(valid&&!bars.isEmpty())series.add(Map.of("code",code,"market","KRX","source","KIS FHKST03010230","period",day.toString(),"fetchedAt",response.fetchedAt().toString(),
                    "bars",List.copyOf(bars.values()),"coverage","MAX_120_BARS_NOT_FULL_SESSION","priceUnit","KRW","volumeUnit","SHARES"));
            }catch(RuntimeException ignored){/* Credentials and exception messages never escape. */}
        }
        return Map.of("status",series.isEmpty()?"UNAVAILABLE":series.size()==selected.size()?"AVAILABLE":"PARTIAL","market","KRX","series",List.copyOf(series),"requestedCodes",selected.size());
    }
    @PreDestroy public synchronized void shutdown(){stopped=true;reset();events.clear();watched=Set.of();}
}
