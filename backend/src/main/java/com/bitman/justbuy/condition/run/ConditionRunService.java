package com.bitman.justbuy.condition.run;

import com.bitman.justbuy.dto.AnalysisResponse;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConditionRunService {

    private static final int MAX_RUNS = 500;
    private static final int MAX_EVENTS_PER_RUN = 200;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final Clock clock;
    private final Map<UUID, MutableRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, List<ConditionRunEvent>> events = new ConcurrentHashMap<>();

    public ConditionRunService() {
        this(Clock.systemUTC());
    }

    ConditionRunService(Clock clock) {
        this.clock = clock;
    }

    public synchronized ConditionRunSnapshot create(ConditionRunTrigger trigger, String mode, String query) {
        UUID runId = UUID.randomUUID();
        Instant now = clock.instant();
        MutableRun run = new MutableRun(
            runId,
            "cr-" + runId.toString().substring(0, 8),
            trigger != null ? trigger : ConditionRunTrigger.UNKNOWN,
            safeText(mode, 120),
            safeText(query, 240),
            ConditionRunStatus.QUEUED,
            1,
            now,
            now
        );
        runs.put(runId, run);
        append(run, ConditionRunEventType.RUN_CREATED, "condition run created", Map.of());
        pruneRuns();
        return run.snapshot();
    }

    public synchronized void markStarted(UUID runId, String message) {
        updateStatus(runId, ConditionRunStatus.COLLECTING, ConditionRunEventType.RUN_STARTED, message, Map.of());
    }

    public synchronized void recordEvent(UUID runId, ConditionRunEventType type, String message) {
        recordEvent(runId, type, message, Map.of());
    }

    public synchronized void recordEvent(UUID runId, ConditionRunEventType type, String message, Map<String, Object> metrics) {
        MutableRun run = runs.get(runId);
        if (run == null) return;
        append(run, type, message, metrics);
    }

    public synchronized void markComplete(UUID runId, AnalysisResponse response) {
        MutableRun run = runs.get(runId);
        if (run == null) return;

        var metadata = response != null ? response.metadata() : null;
        run.pickCount = response != null && response.stockPicks() != null ? response.stockPicks().size() : 0;
        run.agentsUsed = metadata != null ? metadata.agentsUsed() : null;
        run.agentsSucceeded = metadata != null ? metadata.agentsSucceeded() : null;
        run.status = ConditionRunStatus.COMPLETE;
        run.finishedAt = clock.instant();
        run.updatedAt = run.finishedAt;

        append(run, ConditionRunEventType.COMPLETED, "condition run completed", Map.of(
            "pickCount", run.pickCount,
            "agentsUsed", run.agentsUsed != null ? run.agentsUsed : 0,
            "agentsSucceeded", run.agentsSucceeded != null ? run.agentsSucceeded : 0
        ));
    }

    public synchronized void markFailed(UUID runId, String errorMessage) {
        MutableRun run = runs.get(runId);
        if (run == null) return;
        run.status = ConditionRunStatus.FAILED;
        run.errorCode = "EXECUTION_FAILED";
        run.errorMessage = safeText(errorMessage, MAX_MESSAGE_LENGTH);
        run.finishedAt = clock.instant();
        run.updatedAt = run.finishedAt;
        append(run, ConditionRunEventType.FAILED, run.errorMessage, Map.of("errorCode", run.errorCode));
    }

    public synchronized Optional<ConditionRunSnapshot> find(UUID runId) {
        MutableRun run = runs.get(runId);
        return run == null ? Optional.empty() : Optional.of(run.snapshot());
    }

    public synchronized List<ConditionRunSnapshot> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return runs.values().stream()
            .sorted(Comparator.comparing((MutableRun run) -> run.updatedAt).reversed())
            .limit(bounded)
            .map(MutableRun::snapshot)
            .toList();
    }

    public synchronized List<ConditionRunEvent> events(UUID runId) {
        return List.copyOf(events.getOrDefault(runId, List.of()));
    }

    private void updateStatus(UUID runId, ConditionRunStatus status, ConditionRunEventType type,
                              String message, Map<String, Object> metrics) {
        MutableRun run = runs.get(runId);
        if (run == null) return;
        run.status = status;
        run.updatedAt = clock.instant();
        append(run, type, message, metrics);
    }

    private void append(MutableRun run, ConditionRunEventType type, String message, Map<String, Object> metrics) {
        ConditionRunEvent event = new ConditionRunEvent(
            UUID.randomUUID(),
            run.runId,
            type,
            run.status,
            clock.instant(),
            safeText(message, MAX_MESSAGE_LENGTH),
            safeMetrics(metrics)
        );
        List<ConditionRunEvent> runEvents = events.computeIfAbsent(run.runId, ignored -> new ArrayList<>());
        runEvents.add(event);
        if (runEvents.size() > MAX_EVENTS_PER_RUN) {
            runEvents.remove(0);
        }
        run.updatedAt = event.createdAt();
    }

    private void pruneRuns() {
        if (runs.size() <= MAX_RUNS) return;
        List<UUID> oldest = runs.values().stream()
            .sorted(Comparator.comparing(run -> run.updatedAt))
            .limit(runs.size() - MAX_RUNS)
            .map(run -> run.runId)
            .toList();
        for (UUID runId : oldest) {
            runs.remove(runId);
            events.remove(runId);
        }
    }

    private static Map<String, Object> safeMetrics(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        metrics.forEach((key, value) -> {
            if (key == null || value == null) return;
            if (value instanceof Number || value instanceof Boolean) {
                safe.put(safeText(key, 80), value);
            } else {
                safe.put(safeText(key, 80), safeText(String.valueOf(value), 180));
            }
        });
        return Map.copyOf(safe);
    }

    private static String safeText(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.length() <= maxLength) return normalized;
        return normalized.substring(0, maxLength);
    }

    private static final class MutableRun {
        private final UUID runId;
        private final String traceId;
        private final ConditionRunTrigger trigger;
        private final String mode;
        private final String query;
        private ConditionRunStatus status;
        private final int attempt;
        private final Instant startedAt;
        private Instant updatedAt;
        private Instant finishedAt;
        private String errorCode;
        private String errorMessage;
        private Integer pickCount;
        private Integer agentsUsed;
        private Integer agentsSucceeded;

        private MutableRun(UUID runId, String traceId, ConditionRunTrigger trigger, String mode, String query,
                           ConditionRunStatus status, int attempt, Instant startedAt, Instant updatedAt) {
            this.runId = runId;
            this.traceId = traceId;
            this.trigger = trigger;
            this.mode = mode;
            this.query = query;
            this.status = status;
            this.attempt = attempt;
            this.startedAt = startedAt;
            this.updatedAt = updatedAt;
        }

        private ConditionRunSnapshot snapshot() {
            return new ConditionRunSnapshot(
                runId,
                traceId,
                trigger,
                mode,
                query,
                status,
                attempt,
                startedAt,
                updatedAt,
                finishedAt,
                errorCode,
                errorMessage,
                pickCount,
                agentsUsed,
                agentsSucceeded
            );
        }
    }
}
