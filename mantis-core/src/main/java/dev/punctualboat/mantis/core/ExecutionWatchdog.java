package dev.punctualboat.mantis.core;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class ExecutionWatchdog implements AutoCloseable {
    private static final long IDLE = 0, EXPIRED = Long.MIN_VALUE;
    private final Set<Guard> guards = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mantis-script-limits");
        thread.setDaemon(true);
        return thread;
    });

    ExecutionWatchdog() { executor.scheduleAtFixedRate(this::check, 5, 5, TimeUnit.MILLISECONDS); }

    Guard register(Runnable cancel) {
        Guard guard = new Guard(cancel);
        guards.add(guard);
        return guard;
    }

    private void check() {
        long now = System.nanoTime();
        for (Guard guard : guards) {
            long deadline = guard.deadline.get();
            if (deadline != IDLE && deadline != EXPIRED && now - deadline >= 0
                    && guard.deadline.compareAndSet(deadline, EXPIRED)) {
                guards.remove(guard);
                try { guard.cancel.run(); }
                catch (RuntimeException ignored) { /* The owner also closes an expired context when execution returns. */ }
            }
        }
    }

    final class Guard implements AutoCloseable {
        private final AtomicLong deadline = new AtomicLong();
        private final Runnable cancel;
        private Guard(Runnable cancel) { this.cancel = cancel; }

        long begin(long start, long limitNanos) {
            long expires = start + limitNanos;
            if (!deadline.compareAndSet(IDLE, expires)) throw new IllegalStateException("Script execution is closed or already active");
            return expires;
        }

        boolean finish(long expected, long now) {
            if (now - expected >= 0) deadline.compareAndSet(expected, EXPIRED);
            return deadline.compareAndSet(expected, IDLE);
        }

        boolean expired() { return deadline.get() == EXPIRED; }
        @Override public void close() { deadline.set(EXPIRED); guards.remove(this); }
    }

    @Override public void close() { guards.clear(); executor.shutdownNow(); }
}
