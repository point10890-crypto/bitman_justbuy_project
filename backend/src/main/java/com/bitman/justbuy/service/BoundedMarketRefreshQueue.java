package com.bitman.justbuy.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** A single short-lived worker, deduplicated bounded demand, and no automatic retry loop. */
final class BoundedMarketRefreshQueue implements AutoCloseable {
    private record Job(Runnable task,Instant expiresAt) {}
    private final int capacity;
    private final Clock clock;
    private final ExecutorService executor;
    private final LinkedHashMap<String,Job> pending=new LinkedHashMap<>();
    private String active;
    private boolean draining,closed;
    private long enqueued,completed,failed,expired,rejected;

    BoundedMarketRefreshQueue(String threadName,int capacity) {
        this(capacity,Clock.systemUTC(),Executors.newSingleThreadExecutor(task->{
            Thread worker=new Thread(task,threadName);worker.setDaemon(true);return worker;
        }));
    }
    BoundedMarketRefreshQueue(int capacity,Clock clock,ExecutorService executor) {
        if(capacity<1||capacity>128)throw new IllegalArgumentException("Invalid queue capacity");
        this.capacity=capacity;this.clock=Objects.requireNonNull(clock);this.executor=Objects.requireNonNull(executor);
    }
    synchronized boolean offer(String key,Runnable task) {
        if(closed||key==null||key.isBlank()||task==null){rejected++;return false;}
        if(key.equals(active)||pending.containsKey(key))return true;
        if(pending.size()+(active==null?0:1)>=capacity){rejected++;return false;}
        pending.put(key,new Job(task,clock.instant().plusSeconds(120)));enqueued++;
        if(!draining){
            draining=true;
            try{executor.execute(this::drain);}
            catch(RejectedExecutionException ignored){pending.clear();draining=false;rejected++;return false;}
        }
        return true;
    }
    private void drain() {
        while(!Thread.currentThread().isInterrupted()){
            Job job;
            synchronized(this){
                if(closed||pending.isEmpty()){draining=false;active=null;return;}
                var next=pending.entrySet().iterator().next();active=next.getKey();job=next.getValue();pending.remove(active);
            }
            boolean success=false,outdated=!clock.instant().isBefore(job.expiresAt());
            try{if(!outdated){job.task().run();success=true;}}
            catch(RuntimeException ignored){/* Provider details must never escape the worker. */}
            finally{synchronized(this){if(outdated)expired++;else if(success)completed++;else failed++;active=null;}}
        }
        synchronized(this){pending.clear();active=null;draining=false;}
    }
    synchronized Set<String> keys() {
        Set<String> keys=new LinkedHashSet<>();if(active!=null)keys.add(active);keys.addAll(pending.keySet());return Collections.unmodifiableSet(keys);
    }
    synchronized Map<String,Object> snapshot() {
        return Map.ofEntries(Map.entry("queueDepth",pending.size()),Map.entry("pendingJobs",pending.size()+(active==null?0:1)),
            Map.entry("workerActive",active!=null),Map.entry("capacity",capacity),Map.entry("enqueued",enqueued),
            Map.entry("completed",completed),Map.entry("failed",failed),Map.entry("expired",expired),Map.entry("rejected",rejected));
    }
    @Override public synchronized void close(){closed=true;pending.clear();executor.shutdownNow();}
}
