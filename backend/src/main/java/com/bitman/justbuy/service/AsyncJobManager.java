package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
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

    public enum JobStatus { PENDING, RUNNING, COMPLETE, PARTIAL, ERROR }

    public record JobEntry(
        JobStatus status,
        AnalysisResponse result,
        String error,
        Instant createdAt,
        UUID ownerId,
        Instant updatedAt
    ) {
        public JobEntry(JobStatus status, AnalysisResponse result, String error, Instant createdAt, UUID ownerId) {
            this(status, result, error, createdAt, ownerId, createdAt);
        }
    }

    public synchronized String createJob(UUID ownerId) {
        Objects.requireNonNull(ownerId, "Job owner is required");
        cleanup();
        if (jobs.size() >= 256) throw new IllegalStateException("Analysis job capacity exceeded");
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new JobEntry(JobStatus.PENDING, null, null, Instant.now(), ownerId));
        return jobId;
    }

    public void markRunning(String jobId) {
        jobs.computeIfPresent(jobId, (key, existing) -> existing.status() == JobStatus.PENDING
            ? new JobEntry(JobStatus.RUNNING, null, null, existing.createdAt(), existing.ownerId(), Instant.now()) : existing);
    }

    public void markComplete(String jobId, AnalysisResponse result) {
        if (AnalysisOutcome.classify(result) == AnalysisOutcome.FAILED) {
            markError(jobId, "Analysis failed: no successful agents");
            return;
        }
        jobs.computeIfPresent(jobId, (key, existing) -> existing.status() == JobStatus.PENDING || existing.status() == JobStatus.RUNNING
            ? new JobEntry(AnalysisOutcome.classify(result) == AnalysisOutcome.COMPLETE ? JobStatus.COMPLETE : JobStatus.PARTIAL,
                result, null, existing.createdAt(), existing.ownerId(), Instant.now()) : existing);
        log.info("[AsyncJob] Job {} complete", jobId);
    }

    public void markError(String jobId, String error) {
        jobs.computeIfPresent(jobId, (key, existing) -> existing.status() == JobStatus.PENDING || existing.status() == JobStatus.RUNNING
            ? new JobEntry(JobStatus.ERROR, null, error, existing.createdAt(), existing.ownerId(), Instant.now()) : existing);
        log.error("[AsyncJob] Job {} error: {}", jobId, error);
    }

    public JobEntry getJob(String jobId) {
        cleanup();
        return jobs.get(jobId);
    }

    public JobEntry getJob(String jobId, UUID ownerId) {
        JobEntry job = getJob(jobId);
        return job != null && ownerId != null && ownerId.equals(job.ownerId()) ? job : null;
    }

    private void cleanup() {
        Instant cutoff = Instant.now().minusMillis(JOB_TTL_MS);
        jobs.entrySet().removeIf(e -> e.getValue().status() != JobStatus.PENDING && e.getValue().status() != JobStatus.RUNNING
            && e.getValue().updatedAt().isBefore(cutoff));
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
