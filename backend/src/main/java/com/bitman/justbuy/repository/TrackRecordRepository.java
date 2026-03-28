package com.bitman.justbuy.repository;

import com.bitman.justbuy.entity.AnalysisTrackRecord;
import com.bitman.justbuy.entity.AnalysisTrackRecord.TrackStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrackRecordRepository extends JpaRepository<AnalysisTrackRecord, UUID> {

    List<AnalysisTrackRecord> findByStatus(TrackStatus status);

    List<AnalysisTrackRecord> findTop50ByOrderByCreatedAtDesc();
}
