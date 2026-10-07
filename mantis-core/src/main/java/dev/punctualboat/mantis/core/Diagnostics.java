package dev.punctualboat.mantis.core;

import java.util.*;

public final class Diagnostics {
    public record Sample(String operation, long calls, long failures, long totalNanos, long maxNanos) {}
    private final Map<String, Counter> counters = new HashMap<>();

    public synchronized void record(String operation, long nanos, boolean failed) {
        String key = counters.containsKey(operation) || counters.size() < 2048 ? operation : "other";
        Counter counter = counters.computeIfAbsent(key, ignored -> new Counter());
        counter.calls++;
        if (failed) counter.failures++;
        counter.total += nanos;
        counter.max = Math.max(counter.max, nanos);
    }

    public synchronized List<Sample> snapshot() {
        return counters.entrySet().stream().map(entry -> new Sample(entry.getKey(), entry.getValue().calls,
                entry.getValue().failures, entry.getValue().total, entry.getValue().max))
                .sorted(Comparator.comparingLong(Sample::totalNanos).reversed().thenComparing(Sample::operation)).toList();
    }

    public synchronized void reset() { counters.clear(); }
    private static final class Counter { long calls, failures, total, max; }
}
