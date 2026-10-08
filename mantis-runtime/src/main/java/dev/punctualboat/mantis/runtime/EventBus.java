package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.MantisExport;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class EventBus {
    /** Consecutive failures after which a listener or timer is disabled so a broken script cannot flood the log forever. */
    public static final int DEFAULT_MAX_FAILURES = 10;

    private final Map<String, List<Subscription>> listeners = new HashMap<>();
    private final Consumer<Throwable> errors;
    private final int maxFailures;
    public EventBus(Consumer<Throwable> errors) { this(errors, DEFAULT_MAX_FAILURES); }
    public EventBus(Consumer<Throwable> errors, int maxFailures) {
        if (maxFailures < 1) throw new IllegalArgumentException("Failure budget must be at least one");
        this.errors = Objects.requireNonNull(errors);
        this.maxFailures = maxFailures;
    }

    public Subscription subscribe(String name, Consumer<Object> callback, boolean once) {
        return subscribe(name, event -> true, callback, once);
    }
    public Subscription subscribe(String name, Predicate<Object> filter, Consumer<Object> callback, boolean once) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Event name cannot be empty");
        Subscription subscription = new Subscription(name, Objects.requireNonNull(filter), Objects.requireNonNull(callback), once);
        listeners.computeIfAbsent(name, key -> new ArrayList<>()).add(subscription);
        return subscription;
    }

    public void emit(String name, Object event) {
        for (Subscription listener : List.copyOf(listeners.getOrDefault(name, List.of()))) {
            if (!listener.active) continue;
            try {
                if (!listener.filter.test(event)) continue;
                if (listener.once) listener.close();
                listener.callback.accept(event); listener.failures = 0;
            }
            catch (RuntimeException error) {
                if (++listener.failures >= maxFailures) listener.close();
                errors.accept(error);
            }
        }
    }

    /** True when at least one listener is subscribed, so hot emitters can skip building payloads nobody will read. */
    public boolean hasListeners(String name) {
        List<Subscription> list = listeners.get(name);
        return list != null && !list.isEmpty();
    }

    public int listenerCount() { return listeners.values().stream().mapToInt(List::size).sum(); }

    public void emitStrict(String name, Object event) {
        for (Subscription listener : List.copyOf(listeners.getOrDefault(name, List.of()))) {
            if (!listener.active) continue;
            try {
                if (!listener.filter.test(event)) continue;
                if (listener.once) listener.close();
                listener.callback.accept(event); listener.failures = 0;
            }
            catch (RuntimeException error) {
                if (++listener.failures >= maxFailures) listener.close();
                throw error;
            }
        }
    }

    public final class Subscription implements AutoCloseable {
        private final String name;
        private final Consumer<Object> callback;
        private final Predicate<Object> filter;
        private final boolean once;
        private boolean active = true;
        private int failures;
        private Runnable closed = () -> {};
        private Subscription(String name, Predicate<Object> filter, Consumer<Object> callback, boolean once) { this.name = name; this.filter = filter; this.callback = callback; this.once = once; }
        @MantisExport public void unsubscribe() { close(); }
        public Subscription whenClosed(Runnable callback) { closed = callback; return this; }
        @Override public void close() {
            if (!active) return;
            active = false;
            List<Subscription> list = listeners.get(name);
            if (list != null) { list.remove(this); if (list.isEmpty()) listeners.remove(name); }
            closed.run();
        }
    }
}
