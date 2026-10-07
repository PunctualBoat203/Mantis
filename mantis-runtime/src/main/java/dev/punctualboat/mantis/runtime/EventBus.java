package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.MantisExport;

import java.util.*;
import java.util.function.Consumer;

public final class EventBus {
    private final Map<String, List<Subscription>> listeners = new HashMap<>();
    private final Consumer<Throwable> errors;
    public EventBus(Consumer<Throwable> errors) { this.errors = Objects.requireNonNull(errors); }

    public Subscription subscribe(String name, Consumer<Object> callback, boolean once) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Event name cannot be empty");
        Subscription subscription = new Subscription(name, Objects.requireNonNull(callback), once);
        listeners.computeIfAbsent(name, key -> new ArrayList<>()).add(subscription);
        return subscription;
    }

    public void emit(String name, Object event) {
        for (Subscription listener : List.copyOf(listeners.getOrDefault(name, List.of()))) {
            if (!listener.active) continue;
            if (listener.once) listener.close();
            try { listener.callback.accept(event); }
            catch (RuntimeException error) { listener.close(); errors.accept(error); }
        }
    }

    public int listenerCount() { return listeners.values().stream().mapToInt(List::size).sum(); }

    public void emitStrict(String name, Object event) {
        for (Subscription listener : List.copyOf(listeners.getOrDefault(name, List.of()))) {
            if (!listener.active) continue;
            if (listener.once) listener.close();
            listener.callback.accept(event);
        }
    }

    public final class Subscription implements AutoCloseable {
        private final String name;
        private final Consumer<Object> callback;
        private final boolean once;
        private boolean active = true;
        private Runnable closed = () -> {};
        private Subscription(String name, Consumer<Object> callback, boolean once) { this.name = name; this.callback = callback; this.once = once; }
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
