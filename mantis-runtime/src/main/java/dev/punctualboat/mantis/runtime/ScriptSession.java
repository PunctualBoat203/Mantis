package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.*;
import org.graalvm.polyglot.Value;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ScriptSession implements AutoCloseable {
    @FunctionalInterface public interface Modules {
        void register(Registrar registrar);
    }

    public final class Registrar {
        public void module(String name, Map<String, Object> exports) {
            if (modules.putIfAbsent(name, Map.copyOf(exports)) != null) throw new IllegalArgumentException("Duplicate module: " + name);
        }
        public Supplier<MantisContext> context() { return () -> Objects.requireNonNull(context, "Script context is not ready"); }
        public ResourceScope resources() { return resources; }
        public EventBus events() { return events; }
    }

    private final ResourceScope resources = new ResourceScope();
    private final Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
    private final EventBus events;
    private final MantisEngine engine;
    private final int scriptCount;
    private final ClockApi clockApi = new ClockApi();
    private MantisContext context;

    public ScriptSession(MantisEngine engine, Map<String, String> sources, TickClock clock,
                         Consumer<String> log, Consumer<Throwable> errors, Modules extension) {
        this(engine, sources, clock, log, errors, extension, true);
    }

    public ScriptSession(MantisEngine engine, Map<String, String> sources, TickClock clock,
                         Consumer<String> log, Consumer<Throwable> errors, Modules extension, boolean clockModule) {
        this.engine = engine;
        this.events = new EventBus(errors);
        this.scriptCount = sources.size();
        Registrar registrar = new Registrar();
        registrar.module("mantis:events", Map.of("events", new EventsApi()));
        registrar.module("mantis:console", Map.of("console", new ConsoleApi(log)));
        if (clockModule) registrar.module("mantis:clock", Map.of("clock", clockApi));
        try {
            extension.register(registrar);
            context = engine.createContext(sources, modules);
            for (String name : new TreeSet<>(sources.keySet())) context.evaluateModule(name);
            if (clock != null) attachClock(clock);
        } catch (RuntimeException error) {
            try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    public MantisContext context() { return context; }
    public EventBus events() { return events; }
    public int scriptCount() { return scriptCount; }
    public void attachClock(TickClock clock) { clockApi.attach(clock); }
    public int ownedResources() { return resources.size(); }

    public final class EventsApi {
        @MantisExport public EventBus.Subscription on(String name, Value callback) { return subscribe(name, callback, false); }
        @MantisExport public EventBus.Subscription once(String name, Value callback) { return subscribe(name, callback, true); }
        private EventBus.Subscription subscribe(String name, Value callback, boolean once) {
            if (!callback.canExecute()) throw new IllegalArgumentException("Event callback must be a function");
            EventBus.Subscription subscription = events.subscribe(name, event -> context.invoke(callback, event), once);
            subscription.whenClosed(() -> resources.forget(subscription));
            return resources.own(subscription);
        }
    }

    public final class ClockApi {
        private TickClock clock;
        private final Set<TimerTicket> tickets = new LinkedHashSet<>();
        private void attach(TickClock clock) {
            if (this.clock != null) {
                if (this.clock == clock) return;
                throw new IllegalStateException("Script session already has a clock");
            }
            this.clock = Objects.requireNonNull(clock);
            for (TimerTicket ticket : List.copyOf(tickets)) ticket.start();
        }
        private TickClock ready() {
            if (clock == null) throw new IllegalStateException("Clock state is available after the server starts; timers may be registered during loading");
            return clock;
        }
        @MantisExport public long ticks() { return ready().ticks(); }
        @MantisExport public TimerTicket after(long ticks, Value callback) { return register(ticks, false, callback); }
        @MantisExport public TimerTicket every(long ticks, Value callback) { return register(ticks, true, callback); }
        private TimerTicket register(long ticks, boolean repeat, Value callback) {
            requireFunction(callback);
            if (ticks < (repeat ? 1 : 0)) throw new IllegalArgumentException("Invalid timer interval");
            TimerTicket ticket = resources.own(new TimerTicket(ticks, repeat, callback));
            tickets.add(ticket);
            if (clock != null) ticket.start();
            return ticket;
        }
        @MantisExport public boolean cooldown(String key, long ticks) { return ready().cooldown(key, ticks); }
        @MantisExport public long remaining(String key) { return ready().remaining(key); }
        private void requireFunction(Value callback) { if (!callback.canExecute()) throw new IllegalArgumentException("Timer callback must be a function"); }

        public final class TimerTicket implements AutoCloseable {
            private final long ticks;
            private final boolean repeat;
            private final Value callback;
            private TickClock.Timer scheduled;
            private boolean active = true;
            private TimerTicket(long ticks, boolean repeat, Value callback) { this.ticks = ticks; this.repeat = repeat; this.callback = callback; }
            private void start() {
                if (!active || scheduled != null) return;
                Runnable action = () -> {
                    if (!repeat) close();
                    try { context.invoke(callback); }
                    catch (RuntimeException error) { close(); throw error; }
                };
                scheduled = repeat ? ready().every(ticks, action) : ready().after(ticks, action);
            }
            @MantisExport public void cancel() { close(); }
            @MantisExport public boolean active() { return active; }
            @Override public void close() {
                if (!active) return;
                active = false;
                if (scheduled != null) scheduled.close();
                tickets.remove(this);
                resources.forget(this);
            }
        }
    }

    public static final class ConsoleApi {
        private final Consumer<String> log;
        private ConsoleApi(Consumer<String> log) { this.log = Objects.requireNonNull(log); }
        @MantisExport public void log(String message) { log.accept(message); }
        @MantisExport public void warn(String message) { log.accept("WARN: " + message); }
        @MantisExport public void error(String message) { log.accept("ERROR: " + message); }
    }

    @Override public void close() {
        try { resources.close(); }
        finally { if (context != null) { engine.release(context); context = null; } }
    }
}
