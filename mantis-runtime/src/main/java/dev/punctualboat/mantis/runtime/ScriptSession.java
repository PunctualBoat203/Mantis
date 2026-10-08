package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.*;
import dev.punctualboat.mantis.interop.*;
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
            if (modules.putIfAbsent(name, Collections.unmodifiableMap(new LinkedHashMap<>(exports))) != null) throw new IllegalArgumentException("Duplicate module: " + name);
        }
        public Supplier<MantisContext> context() { return () -> Objects.requireNonNull(context, "Script context is not ready"); }
        public ResourceScope resources() { return resources; }
        public EventBus events() { return events; }
        public TypeConversions conversions() { return conversions; }
        public HostBindings bindings() { return bindings; }
        public AsyncBridge async() { return async; }
        public Object bind(Object object) { return bindings.bind(object); }
    }

    /** Events dispatched while the server is loading; their handlers get the longer load-phase execution budget. */
    private static final Set<String> LOAD_EVENTS = Set.of("startup", "recipes", "lifecycle.load", "lifecycle.init");

    private final ResourceScope resources = new ResourceScope();
    private final Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
    private final EventBus events;
    private final MantisEngine engine;
    private final int scriptCount;
    private final List<String> scriptNames;
    private final ClockApi clockApi = new ClockApi();
    private MantisContext context;
    private final TypeConversions conversions = new TypeConversions(() -> Objects.requireNonNull(context, "Script context is not ready"));
    private final AsyncBridge async;
    private final HostBindings bindings;
    private final Consumer<Throwable> errors;
    private final List<SessionState> history = new ArrayList<>(List.of(SessionState.CREATED));
    private SessionState state = SessionState.CREATED;
    private Throwable failure;
    private final Deque<String> recentErrors = new ArrayDeque<>();

    public ScriptSession(MantisEngine engine, Map<String, String> sources, TickClock clock,
                         Consumer<String> log, Consumer<Throwable> errors, Modules extension) {
        this(engine, sources, clock, log, errors, extension, true);
    }

    public ScriptSession(MantisEngine engine, Map<String, String> sources, TickClock clock,
                         Consumer<String> log, Consumer<Throwable> errors, Modules extension, boolean clockModule) {
        this.engine = engine;
        this.errors = Objects.requireNonNull(errors);
        this.events = new EventBus(this::report);
        this.async = new AsyncBridge(() -> context, conversions, resources, this::report);
        this.bindings = new HostBindings(conversions, async::canInvoke);
        this.scriptCount = sources.size();
        this.scriptNames = sources.keySet().stream().sorted().toList();
        Registrar registrar = new Registrar();
        registrar.module("mantis:events", Map.of("events", new EventsApi()));
        registrar.module("mantis:console", Map.of("console", new ConsoleApi(log)));
        registrar.module("mantis:lifecycle", Map.of("lifecycle", new LifecycleApi()));
        if (clockModule) registrar.module("mantis:clock", Map.of("clock", clockApi));
        try {
            transition(SessionState.LOADING);
            extension.register(registrar);
            conversions.freeze();
            context = engine.createContext(sources, modules, (ready, value) -> { context = ready; return bindings.export(value); });
            for (String name : new TreeSet<>(sources.keySet())) context.evaluateModule(name);
            transition(SessionState.LOADED);
            lifecycle("load", Map.of());
            lifecycle("init", Map.of());
            if (clock != null) attachClock(clock);
            if (clock != null) start(ScriptScheduler.pumped(), false);
        } catch (RuntimeException error) {
            failure = error;
            transition(SessionState.FAILED);
            if (context != null && !context.isClosed()) events.emit("lifecycle.error", MantisContext.readOnly(Map.of("message", error.getMessage() == null ? error.toString() : error.getMessage())));
            try { close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    public MantisContext context() { return context; }
    public EventBus events() { return events; }
    public int scriptCount() { return scriptCount; }
    public List<String> scriptNames() { return scriptNames; }
    public List<String> moduleNames() { return modules.keySet().stream().sorted().toList(); }
    public List<String> recentErrors() { return List.copyOf(recentErrors); }
    public void attachClock(TickClock clock) { clockApi.attach(clock); }
    public int ownedResources() { return resources.size(); }
    public SessionState state() { return state; }
    public List<SessionState> history() { return List.copyOf(history); }
    public Throwable failure() { return failure; }
    public AsyncBridge async() { return async; }
    public TypeConversions conversions() { return conversions; }
    public HostBindings bindings() { return bindings; }

    public void start(ScriptScheduler scheduler, boolean reload) {
        if (!scheduler.isOnThread()) throw new IllegalStateException("Start scripts on the host scheduler thread");
        if (state != SessionState.LOADED && state != SessionState.RUNNING) throw new IllegalStateException("Cannot start scripts in state " + state);
        if (state == SessionState.RUNNING) { async.activate(scheduler); return; }
        transition(SessionState.RUNNING);
        async.activate(scheduler, () -> {
            events.emit("lifecycle.start", MantisContext.readOnly(Map.of("reloaded", reload)));
            if (reload) events.emit("lifecycle.reload", MantisContext.readOnly(Map.of()));
        });
    }

    public final class LifecycleApi {
        @MantisExport public String state() { return ScriptSession.this.state.name().toLowerCase(Locale.ROOT); }
        @MantisExport public EventBus.Subscription on(String hook, Value callback) {
            if (!Set.of("load", "init", "start", "reload", "stop", "unload", "error").contains(hook)) throw new IllegalArgumentException("Unknown lifecycle hook: " + hook);
            return new EventsApi().subscribe("lifecycle." + hook, callback, false);
        }
    }

    private void transition(SessionState next) { state = next; history.add(next); }
    private void lifecycle(String hook, Map<String, Object> data) { events.emitStrict("lifecycle." + hook, MantisContext.readOnly(data)); }
    private boolean reporting;
    private void report(Throwable error) {
        if (recentErrors.size() == 32) recentErrors.removeFirst();
        recentErrors.addLast(error instanceof ScriptException script ? script.format() : error.toString());
        if (context != null && context.isClosed() && state == SessionState.RUNNING) {
            failure = error; transition(SessionState.FAILED);
            try { async.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            try { resources.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            bindings.close();
            try { engine.release(context); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
        }
        if (!reporting && context != null && !context.isClosed() && state != SessionState.DISPOSED) {
            reporting = true;
            try { events.emit("lifecycle.error", MantisContext.readOnly(Map.of("message", error.getMessage() == null ? error.toString() : error.getMessage()))); }
            finally { reporting = false; }
        }
        errors.accept(error);
    }

    public final class EventsApi {
        @MantisExport public EventBus.Subscription on(String name, Value callback) { return subscribe(name, callback, false); }
        @MantisExport public EventBus.Subscription once(String name, Value callback) { return subscribe(name, callback, true); }
        private EventBus.Subscription subscribe(String name, Value callback, boolean once) {
            if (!callback.canExecute()) throw new IllegalArgumentException("Event callback must be a function");
            boolean loadPhase = LOAD_EVENTS.contains(name);
            EventBus.Subscription subscription = events.subscribe(name, event -> {
                if (context == null || context.isClosed()) return;
                Supplier<Value> dispatch = () -> context.invoke(callback, bindings.export(event));
                if (loadPhase) context.loadAccess("event:" + name, dispatch);
                else context.access("event:" + name, dispatch);
            }, once);
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
                    if (context == null || context.isClosed()) { close(); return; }
                    try { context.invoke(callback); }
                    catch (RuntimeException error) {
                        if (context.isClosed()) close();
                        throw error;
                    }
                };
                scheduled = repeat ? ready().every(ticks, action) : ready().after(ticks, action);
                scheduled.onError(ScriptSession.this::report).whenClosed(this::close);
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

    @Override public void close() { close("stop"); }
    public void close(String reason) {
        if (state == SessionState.DISPOSED || state == SessionState.STOPPING) return;
        boolean failed = state == SessionState.FAILED;
        transition(SessionState.STOPPING);
        try {
            if (context != null && !context.isClosed()) {
                if (!failed) events.emit("lifecycle.stop", MantisContext.readOnly(Map.of("reason", reason)));
                events.emit("lifecycle.unload", MantisContext.readOnly(Map.of("reason", reason)));
            }
        } finally {
            try { async.close(); }
            finally {
                try { resources.close(); }
                finally {
                    bindings.close();
                    try { if (context != null) engine.release(context); }
                    finally { context = null; transition(SessionState.DISPOSED); }
                }
            }
        }
    }
}
