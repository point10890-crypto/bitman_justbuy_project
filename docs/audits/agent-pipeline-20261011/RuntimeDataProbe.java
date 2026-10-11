package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.repository.TrackRecordRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class RuntimeDataProbe {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  public static void main(String[] args) throws Exception {
    List<AnalysisTrackRecord> saved = new ArrayList<>();
    TrackRecordRepository repo = mock(TrackRecordRepository.class);
    KisApiService kis = mock(KisApiService.class);
    when(repo.save(any(AnalysisTrackRecord.class))).thenAnswer(inv -> { AnalysisTrackRecord r = inv.getArgument(0); saved.add(r); return r; });
    TrackRecordService tracker = new TrackRecordService(repo, kis);
    ShortTermRealtimeScanner scanner = new ShortTermRealtimeScanner(null);
    ZonedDateTime capture = ZonedDateTime.of(2026,5,18,10,0,0,0,KST);
    var s1 = scanner.buildSignals(List.of(stock("10000","5.21")), List.of(stock("10000","5.21")), capture).getFirst();
    tracker.recordShortTermRealtimeSignals(List.of(s1), capture);
    AnalysisTrackRecord r1 = saved.getFirst();
    Field createdAt = AnalysisTrackRecord.class.getDeclaredField("createdAt");
    createdAt.setAccessible(true);
    createdAt.set(r1, capture.toInstant());
    when(repo.findByModeAndAnalysisDateOrderByCreatedAtDesc("BREAKOUT",capture.toLocalDate())).thenReturn(List.of(r1));
    when(kis.fetchCurrentPrice("005930")).thenReturn(Map.of("currentPrice","9700","현재가","9700"));
    Method verify = TrackRecordService.class.getDeclaredMethod("verifyShortTermClose",LocalDate.class);
    verify.setAccessible(true);
    verify.invoke(tracker,capture.toLocalDate());
    System.out.printf("P1_PERCENT_AS_PRICE entry=%d signal.highPrice=%s stored.targetPrice=%d actual.close=%d closeReturn=%s hitTarget=%s%n",
      r1.getPriceAtAnalysis(),s1.highPrice(),r1.getTargetPrice(),r1.getClosePrice(),r1.getCloseReturn(),r1.isHitTarget());
    require(r1.getTargetPrice() == 521 && r1.isHitTarget() && r1.getCloseReturn() == -3.0,"percent target reproducer");
    var s2 = scanner.buildSignals(List.of(stock("12000","7.50")),List.of(stock("12000","7.50")),capture.plusMinutes(3)).getFirst();
    System.out.printf("P2_CAPTURE_DRIFT first.capturedAt=%s first.capturePrice=%s second.capturedAt=%s second.capturePrice=%s second.currentPrice=%s%n",
      s1.capturedAt(),s1.capturePrice(),s2.capturedAt(),s2.capturePrice(),s2.currentPrice());
    require(s1.capturedAt().equals(s2.capturedAt()) && !s1.capturePrice().equals(s2.capturePrice()),"capture drift reproducer");
    AnalysisTrackRecord horizon = new AnalysisTrackRecord();
    horizon.setMode("REVERSAL_EDGE");
    horizon.setStockCode("005930");
    horizon.setAnalysisDate(LocalDate.now(KST).minusDays(5));
    horizon.setPriceAtAnalysis(10000L);
    when(repo.findByStatus(AnalysisTrackRecord.TrackStatus.TRACKING)).thenReturn(List.of(horizon));
    when(kis.fetchCurrentPrice("005930")).thenReturn(Map.of("현재가","11000"));
    tracker.updatePrices();
    System.out.printf("P2_HORIZON_ALIAS currentDate=%s analysisDate=%s quote=11000 price1d=%s price3d=%s price5d=%s status=%s%n",
      LocalDate.now(KST),horizon.getAnalysisDate(),horizon.getPrice1d(),horizon.getPrice3d(),horizon.getPrice5d(),horizon.getStatus());
    require(horizon.getPrice1d() == 11000 && horizon.getPrice3d() == 11000 && horizon.getPrice5d() == 11000,"horizon alias reproducer");
    ConditionSearchPipeline pipeline = mock(ConditionSearchPipeline.class);
    var unverifiedResponse = new com.bitman.justbuy.dto.AnalysisResponse(
      "REVERSAL_EDGE", "swing", List.of(), null, "",
      List.of(new com.bitman.justbuy.dto.StockPick("삼성전자","005930","10000","","","주의","no outcome evidence")),
      null, capture.toInstant().toString(), true, new com.bitman.justbuy.dto.AnalysisResponse.Metadata(1,1,1));
    when(pipeline.getPrecomputed("REVERSAL_EDGE")).thenReturn(unverifiedResponse);
    var main = new MainConditionService(pipeline).getMain();
    var summary = main.trackRecord();
    System.out.printf("P1_UNVERIFIED_AS_PERFORMANCE stock=current10000 targetMissing outcomeEvidence=NONE generated.maxReturn=%s trackRecord.total=%d avg=%s winRate=%s%n",
      main.sections().swing().signals().getFirst().maxReturnPct(), summary.totalSignals(), summary.avgMaxReturnPct(), summary.winRate5d());
    require(summary.totalSignals()==1 && summary.winRate5d().equals("100%") && summary.avgMaxReturnPct().equals("+6.00%"), "unverified performance reproducer");
    System.out.println("OBSERVED=4 reproduced; EXTERNAL_NETWORK_CALLS=0; REAL_DB_CALLS=0");
  }
  private static KisApiService.RankedStock stock(String price,String change) {
    return new KisApiService.RankedStock("삼성전자","005930",price,change,"2000000",0L);
  }
  private static void require(boolean v,String label) { if(!v)throw new AssertionError(label); }
}
