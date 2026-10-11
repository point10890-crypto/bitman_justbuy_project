package com.bitman.justbuy.ai.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** A timed-out caller cannot release capacity while its synchronous provider request is still running. */
final class ProviderCallLimiter {
    private static final Semaphore TOTAL = new Semaphore(8);
    private static final ConcurrentHashMap<String, Semaphore> PROVIDERS = new ConcurrentHashMap<>();
    private ProviderCallLimiter() {}

    static Lease tryAcquire(String provider) {
        if (Thread.currentThread().isInterrupted() || !TOTAL.tryAcquire()) return null;
        Semaphore slots = PROVIDERS.computeIfAbsent(provider, ignored -> new Semaphore(4));
        if (!slots.tryAcquire()) {
            TOTAL.release();
            return null;
        }
        return new Lease(slots);
    }

    static final class Lease implements AutoCloseable {
        private final Semaphore slots;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(Semaphore slots) { this.slots = slots; }
        public void close() {
            if (closed.compareAndSet(false, true)) {
                slots.release();
                TOTAL.release();
            }
        }
    }
}
