package com.bitman.justbuy.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "analysis_track_records")
public class AnalysisTrackRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String mode;
    private LocalDate analysisDate;

    @Column(length = 6)
    private String stockCode;
    private String stockName;
    private String action;
    private int consensusScore;

    private Long priceAtAnalysis;
    private Long targetPrice;
    private Long stopLoss;

    // 성과 추적 (스케줄러가 나중에 채움)
    private Long price1d;
    private Long price3d;
    private Long price5d;
    private Double return1d;
    private Double return3d;
    private Double return5d;

    private boolean hitTarget;
    private boolean hitStop;

    @Enumerated(EnumType.STRING)
    private TrackStatus status = TrackStatus.TRACKING;

    private Instant createdAt = Instant.now();

    public enum TrackStatus { TRACKING, COMPLETED }

    // --- Getters & Setters ---

    public UUID getId() { return id; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public LocalDate getAnalysisDate() { return analysisDate; }
    public void setAnalysisDate(LocalDate analysisDate) { this.analysisDate = analysisDate; }

    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public int getConsensusScore() { return consensusScore; }
    public void setConsensusScore(int consensusScore) { this.consensusScore = consensusScore; }

    public Long getPriceAtAnalysis() { return priceAtAnalysis; }
    public void setPriceAtAnalysis(Long priceAtAnalysis) { this.priceAtAnalysis = priceAtAnalysis; }

    public Long getTargetPrice() { return targetPrice; }
    public void setTargetPrice(Long targetPrice) { this.targetPrice = targetPrice; }

    public Long getStopLoss() { return stopLoss; }
    public void setStopLoss(Long stopLoss) { this.stopLoss = stopLoss; }

    public Long getPrice1d() { return price1d; }
    public void setPrice1d(Long price1d) { this.price1d = price1d; }

    public Long getPrice3d() { return price3d; }
    public void setPrice3d(Long price3d) { this.price3d = price3d; }

    public Long getPrice5d() { return price5d; }
    public void setPrice5d(Long price5d) { this.price5d = price5d; }

    public Double getReturn1d() { return return1d; }
    public void setReturn1d(Double return1d) { this.return1d = return1d; }

    public Double getReturn3d() { return return3d; }
    public void setReturn3d(Double return3d) { this.return3d = return3d; }

    public Double getReturn5d() { return return5d; }
    public void setReturn5d(Double return5d) { this.return5d = return5d; }

    public boolean isHitTarget() { return hitTarget; }
    public void setHitTarget(boolean hitTarget) { this.hitTarget = hitTarget; }

    public boolean isHitStop() { return hitStop; }
    public void setHitStop(boolean hitStop) { this.hitStop = hitStop; }

    public TrackStatus getStatus() { return status; }
    public void setStatus(TrackStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
}
