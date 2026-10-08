package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.MantisExport;

import java.util.*;
import java.util.function.Consumer;

public final class TickClock implements AutoCloseable {
    public record Snapshot(long ticks, Map<String, Long> cooldowns) {
        public Snapshot { cooldowns = Map.copyOf(cooldowns); }
    }

    private final Thread owner = Thread.currentThread();
    private final PriorityQueue<Timer> timers = new PriorityQueue<>(Comparator
            .comparingLong((Timer timer) -> timer.due).thenComparingLong(timer -> timer.id));
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Consumer<Throwable> errors;
    private final Runnable dirty;
    private final int callbacksPerTick;
    private final int maxFailures;
    private long ticks;
    private long nextId;
    private boolean closed;

    public TickClock(Snapshot saved, Runnable dirty, Consumer<Throwable> errors, int callbacksPerTick) {
        this(saved, dirty, errors, callbacksPerTick, EventBus.DEFAULT_MAX_FAILURES);
    }

    public TickClock(Snapshot saved, Runnable dirty, Consumer<Throwable> errors, int callbacksPerTick, int maxFailures) {
        if (saved.ticks() < 0 || callbacksPerTick < 1 || maxFailures < 1) throw new IllegalArgumentException("Invalid clock state or callback budget");
        this.maxFailures = maxFailures;
        this.ticks = saved.ticks();
        saved.cooldowns().forEach((key, due) -> { validateKey(key); if (due > ticks) cooldowns.put(key, due); });
        this.dirty = Objects.requireNonNull(dirty);
        this.errors = Objects.requireNonNull(errors);
        this.callbacksPerTick = callbacksPerTick;
    }

    public TickClock() { this(new Snapshot(0, Map.of()), () -> {}, error -> {}, 256); }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Clock access must run on its host thread");
    }

    private void checkThread() {
        checkOwner();
        if (closed) throw new IllegalStateException("Clock is closed");
    }

    public long ticks() { checkThread(); return ticks; }
    public Snapshot snapshot() { checkThread(); return new Snapshot(ticks, cooldowns); }
    public int scheduledTasks() { checkThread(); return timers.size(); }

    public void tick() {
        checkThread();
        ticks = Math.addExact(ticks, 1);
        if (ticks % 200 == 0) cooldowns.values().removeIf(due -> due <= ticks);
        dirty.run();
        long budgetStarted = System.nanoTime();
        for (int count = 0; count < callbacksPerTick && !timers.isEmpty() && timers.peek().due <= ticks; count++) {
            if (count > 0 && System.nanoTime() - budgetStarted > 10_000_000) break;
            Timer timer = timers.remove();
            if (!timer.active) continue;
            if (timer.period == 0) timer.active = false;
            try { timer.callback.run(); timer.failures = 0; }
            catch (RuntimeException error) {
                if (++timer.failures >= maxFailures) timer.close();
                timer.report.accept(error);
            }
            finally { if (timer.period == 0) timer.close(); }
            if (closed) timer.close();
            else if (timer.active && timer.period > 0) {
                timer.due = Math.addExact(ticks, timer.period);
                timers.add(timer);
            }
        }
    }

    public Timer after(long delay, Runnable callback) { return schedule(delay, 0, callback); }
    public Timer every(long period, Runnable callback) {
        if (period < 1) throw new IllegalArgumentException("Repeat interval must be at least one tick");
        return schedule(period, period, callback);
    }

    private Timer schedule(long delay, long period, Runnable callback) {
        checkThread();
        if (delay < 0) throw new IllegalArgumentException("Delay cannot be negative");
        Timer timer = new Timer(nextId++, Math.addExact(ticks, Math.max(1, delay)), period, Objects.requireNonNull(callback));
        timers.add(timer);
        return timer;
    }

    public boolean cooldown(String key, long duration) {
        checkThread();
        validateKey(key);
        if (duration < 1) throw new IllegalArgumentException("Cooldown must be at least one tick");
        if (remaining(key) > 0) return false;
        if (cooldowns.size() >= 100_000) throw new IllegalStateException("Too many active cooldown keys");
        cooldowns.put(key, Math.addExact(ticks, duration));
        dirty.run();
        return true;
    }

    public long remaining(String key) {
        checkThread();
        validateKey(key);
        Long due = cooldowns.get(key);
        if (due == null) return 0;
        if (due <= ticks) { cooldowns.remove(key); dirty.run(); return 0; }
        return due - ticks;
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 256) throw new IllegalArgumentException("Cooldown key must contain 1-256 characters");
    }

    public final class Timer implements AutoCloseable {
        private final long id;
        private long due;
        private final long period;
        private final Runnable callback;
        private boolean active = true;
        private int failures;
        private Consumer<Throwable> report = errors;
        private Runnable closedHook = () -> {};
        private boolean notified;
        private Timer(long id, long due, long period, Runnable callback) { this.id = id; this.due = due; this.period = period; this.callback = callback; }
        public Timer onError(Consumer<Throwable> report) { checkThread(); this.report = Objects.requireNonNull(report); return this; }
        public Timer whenClosed(Runnable hook) { checkThread(); closedHook = Objects.requireNonNull(hook); return this; }
        @MantisExport public void cancel() { close(); }
        @MantisExport public boolean active() { checkThread(); return active; }
        @Override public void close() {
            checkOwner(); active = false; timers.remove(this);
            if (!notified) { notified = true; closedHook.run(); }
        }
    }

    @Override public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Timer timer : List.copyOf(timers)) {
            try { timer.close(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
