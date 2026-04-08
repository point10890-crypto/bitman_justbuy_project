package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 비동기 분석 작업 관리자.
 * Render 30초 HTTP 타임아웃 회피를 위해 작업을 비동기로 실행하고 폴링으로 결과 반환.
 */
@Component
public class AsyncJobManager {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobManager.class);
    private static final long JOB_TTL_MS = 30 * 60 * 1000; // 30분 후 정리

    private final Map<String, JobEntry> jobs = new ConcurrentHashMap<>();

    public enum JobStatus { PENDING, RUNNING, COMPLETE, ERROR }

    public record JobEntry(
        JobStatus status,
        AnalysisResponse result,
        String error,
        Instant createdAt
    ) {}

    public String createJob() {
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        jobs.put(jobId, new JobEntry(JobStatus.PENDING, null, null, Instant.now()));
        cleanup();
        return jobId;
    }

    public void markRunning(String jobId) {
        JobEntry existing = jobs.get(jobId);
        if (existing != null) {
            jobs.put(jobId, new JobEntry(JobStatus.RUNNING, null, null, existing.createdAt()));
        }
    }

    public void markComplete(String jobId, AnalysisResponse result) {
        JobEntry existing = jobs.get(jobId);
        Instant created = existing != null ? existing.createdAt() : Instant.now();
        jobs.put(jobId, new JobEntry(JobStatus.COMPLETE, result, null, created));
        log.info("[AsyncJob] Job {} complete", jobId);
    }

    public void markError(String jobId, String error) {
        JobEntry existing = jobs.get(jobId);
        Instant created = existing != null ? existing.createdAt() : Instant.now();
        jobs.put(jobId, new JobEntry(JobStatus.ERROR, null, error, created));
        log.error("[AsyncJob] Job {} error: {}", jobId, error);
    }

    public JobEntry getJob(String jobId) {
        return jobs.get(jobId);
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minusMillis(JOB_TTL_MS);
        jobs.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    /**
     * 주기적 정리. 기존에는 createJob() 호출 때만 cleanup() 이 돌아서,
     * 새 job 이 생성되지 않으면 orphan(만료된 RUNNING/COMPLETE) 이 영원히 남아 메모리가 새고 있었다.
     * 10분마다 강제 정리한다.
     */
    @Scheduled(fixedDelay = 600_000L, initialDelay = 600_000L)
    public void periodicCleanup() {
        int before = jobs.size();
        cleanup();
        int after = jobs.size();
        if (before != after) {
            log.debug("[AsyncJob] periodic cleanup removed {} expired jobs ({} -> {})", before - after, before, after);
        }
    }
}
