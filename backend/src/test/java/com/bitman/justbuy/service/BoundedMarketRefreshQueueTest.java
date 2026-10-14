package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class BoundedMarketRefreshQueueTest {
    static class ManualExecutor extends AbstractExecutorService {
        final Deque<Runnable> tasks=new ArrayDeque<>();boolean stopped;
        @Override public void execute(Runnable task){if(stopped)throw new RejectedExecutionException();tasks.add(task);}
        void drain(){while(!tasks.isEmpty())tasks.remove().run();}
        @Override public void shutdown(){stopped=true;}
        @Override public List<Runnable> shutdownNow(){stopped=true;tasks.clear();return List.of();}
        @Override public boolean isShutdown(){return stopped;}
        @Override public boolean isTerminated(){return stopped;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return stopped;}
    }
    static class MutableClock extends Clock {
        Instant value=Instant.parse("2026-10-12T04:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return value;}
    }
    @Test void duplicateJobsShareOneWorkerAndRunOnlyOnce() {
        var executor=new ManualExecutor();var queue=new BoundedMarketRefreshQueue(2,Clock.systemUTC(),executor);var calls=new AtomicInteger();
        assertThat(queue.offer("technical/005930",calls::incrementAndGet)).isTrue();
        assertThat(queue.offer("technical/005930",calls::incrementAndGet)).isTrue();
        assertThat(queue.keys()).containsExactly("technical/005930");assertThat(executor.tasks).hasSize(1);
        executor.drain();assertThat(calls).hasValue(1);assertThat(queue.keys()).isEmpty();
        assertThat(queue.snapshot()).containsEntry("completed",1L).containsEntry("enqueued",1L);
    }
    @Test void CapacityIncludesActiveWorkAndNeverGrowsWithoutABound() {
        var executor=new ManualExecutor();var queue=new BoundedMarketRefreshQueue(2,Clock.systemUTC(),executor);
        queue.offer("first",()->assertThat(queue.offer("third",()->{})).isFalse());queue.offer("second",()->{});
        assertThat(queue.offer("fourth",()->{})).isFalse();executor.drain();
        assertThat(queue.snapshot()).containsEntry("rejected",2L).containsEntry("completed",2L);
    }
    @Test void OneFailedJobDoesNotKillTheWorkerOrRetryForever() {
        var executor=new ManualExecutor();var queue=new BoundedMarketRefreshQueue(3,Clock.systemUTC(),executor);var calls=new AtomicInteger();
        queue.offer("failure",()->{calls.incrementAndGet();throw new IllegalStateException("provider failure");});
        queue.offer("success",calls::incrementAndGet);executor.drain();
        assertThat(calls).hasValue(2);assertThat(queue.snapshot()).containsEntry("failed",1L).containsEntry("completed",1L);
        assertThat(executor.tasks).isEmpty();
    }
    @Test void ExpiredQueuedJobsAreDroppedRatherThanUsingOldDemand() {
        var executor=new ManualExecutor();var clock=new MutableClock();var queue=new BoundedMarketRefreshQueue(2,clock,executor);var calls=new AtomicInteger();
        queue.offer("old",calls::incrementAndGet);clock.value=clock.value.plusSeconds(121);executor.drain();
        assertThat(calls).hasValue(0);assertThat(queue.snapshot()).containsEntry("expired",1L);assertThat(queue.keys()).isEmpty();
    }
    @Test void observingTheQueueDropsExpiredWaitingKeysEvenBeforeTheWorkerReachesThem() {
        var executor=new ManualExecutor();var clock=new MutableClock();var queue=new BoundedMarketRefreshQueue(2,clock,executor);var calls=new AtomicInteger();
        queue.offer("investorDaily/005930/2026-10-08",calls::incrementAndGet);clock.value=clock.value.plusSeconds(120);
        assertThat(queue.keys()).isEmpty();assertThat(queue.snapshot()).containsEntry("expired",1L).containsEntry("pendingJobs",0);
        executor.drain();assertThat(calls).hasValue(0);assertThat(queue.snapshot()).containsEntry("expired",1L);
    }
    @Test void ShutdownCancelsQueuedWorkAndRejectsNewJobs() {
        var executor=new ManualExecutor();var queue=new BoundedMarketRefreshQueue(2,Clock.systemUTC(),executor);var calls=new AtomicInteger();
        queue.offer("cancelled",calls::incrementAndGet);queue.close();executor.drain();
        assertThat(calls).hasValue(0);assertThat(queue.offer("new",calls::incrementAndGet)).isFalse();assertThat(queue.keys()).isEmpty();
    }
    @Test void ActiveKeysAreVisibleAndCanRefreshAgainOnlyAfterCompletion() {
        var executor=new ManualExecutor();var queue=new BoundedMarketRefreshQueue(2,Clock.systemUTC(),executor);var calls=new AtomicInteger();
        queue.offer("quote/005930",()->{assertThat(queue.keys()).contains("quote/005930");assertThat(queue.offer("quote/005930",calls::incrementAndGet)).isTrue();calls.incrementAndGet();});
        executor.drain();assertThat(calls).hasValue(1);queue.offer("quote/005930",calls::incrementAndGet);executor.drain();assertThat(calls).hasValue(2);
    }
}
