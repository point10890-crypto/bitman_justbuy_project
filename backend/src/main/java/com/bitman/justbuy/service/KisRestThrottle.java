package com.bitman.justbuy.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Shared request-start spacing for both existing and optional KIS REST reads. */
final class KisRestThrottle {
    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static long nextStart;
    private static final long SPACING_NANOS = TimeUnit.MILLISECONDS.toNanos(120);

    private KisRestThrottle() {}

    static void await(long deadline) {
        boolean acquired = false;
        try {
            check(deadline);
            acquired = LOCK.tryLock(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!acquired) throw new IllegalStateException("KIS request budget exceeded");
            long delay = Math.max(0, nextStart - System.nanoTime());
            if (delay >= remaining(deadline)) throw new IllegalStateException("KIS request budget exceeded");
            if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay);
            check(deadline);
            nextStart = System.nanoTime() + SPACING_NANOS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("KIS request interrupted");
        } finally {
            if (acquired) LOCK.unlock();
        }
    }

    static long remaining(long deadline) {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, deadline - System.nanoTime());
    }

    static void check(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("KIS request interrupted");
        if (remaining(deadline) <= 0) throw new IllegalStateException("KIS request budget exceeded");
    }
}
