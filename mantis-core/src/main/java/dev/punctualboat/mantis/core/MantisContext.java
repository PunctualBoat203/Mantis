package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.BiFunction;

public final class MantisContext implements AutoCloseable {
    private final Context context;
    private final Map<String, String> sources;
    private final Map<String, Value> evaluatedModules = new LinkedHashMap<>();
    private final Map<Value, String> functionLabels = new WeakHashMap<>();
    private final SourceCache sourceCache;
    private final ScheduledExecutorService watchdog;
    private final Diagnostics diagnostics = new Diagnostics();
    private final Value helpers;
    private boolean closed;
    private long invocations, executionNanos, moduleHits, moduleMisses;
    private int depth;

    public record ModuleStats(long hits, long misses, List<String> loaded) {}

    MantisContext(Engine engine, ScheduledExecutorService watchdog, SourceCache sourceCache,
                  Map<String, String> sources, Map<String, Map<String, Object>> modules,
                  BiFunction<MantisContext, Object, Object> exports) {
        this.sources = Map.copyOf(sources);
        this.watchdog = watchdog;
        this.sourceCache = sourceCache;
        context = Context.newBuilder("js").engine(engine)
                .allowExperimentalOptions(true)
                .allowHostAccess(HostAccess.newBuilder(HostAccess.NONE).allowAccessAnnotatedBy(MantisExport.class).build())
                .allowHostClassLookup(name -> false).allowHostClassLoading(false)
                .allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
                .allowIO(IOAccess.newBuilder().fileSystem(new ModuleFiles(sources, modules)).build())
                .option("js.esm-eval-returns-exports", "true")
                .option("js.java-package-globals", "false")
                .resourceLimits(ResourceLimits.newBuilder().statementLimit(1_000_000, source -> true).build())
                .build();
        helpers = run("initialize", Duration.ofSeconds(10), () -> context.eval(sourceCache.get("mantis:values", """
                (() => {
                  const parse = JSON.parse, stringify = JSON.stringify, PromiseType = Promise, ErrorType = Error;
                  const from = Array.from, assign = Object.assign, create = Object.create, BigIntType = BigInt;
                  return { parse, stringify, array: a => from(a), object: o => assign(create(null), o),
                    bigint: value => BigIntType(value), promise: executor => new PromiseType(executor), error: message => new ErrorType(message) };
                })()
                """, null, false)));
        try {
            Map<String, Object> bindings = new LinkedHashMap<>();
            modules.forEach((name, values) -> {
                Map<String, Object> mapped = new LinkedHashMap<>();
                values.forEach((key, value) -> mapped.put(key, exports.apply(this, value)));
                bindings.put(name, readOnly(mapped));
            });
            context.getBindings("js").putMember("__mantis_bindings", readOnly(bindings));
        } catch (RuntimeException error) { context.close(true); throw error; }
    }

    public synchronized Value evaluateModule(String name) {
        if (closed) throw new IllegalStateException("Script context is closed");
        if (!sources.containsKey(name)) throw new IllegalArgumentException("Unknown script: " + name);
        Value cached = evaluatedModules.get(name);
        if (cached != null) { moduleHits++; return cached; }
        moduleMisses++;
        Path path = ModuleFiles.scriptPath(name);
        String entry = "import * as namespace from '" + path.toUri().toASCIIString() + "'; export {namespace}; export const initialized = true;";
        Source source = sourceCache.get(name + " [entry]", entry,
                Path.of("/mantis/entries").resolve(name).toUri(), true);
        Value value = run("module:" + name, Duration.ofSeconds(10), () -> {
            Value exports = context.eval(source);
            try {
                if (!exports.getMember("initialized").asBoolean()) throw new IllegalStateException("Module has not finished loading: " + name);
            } catch (PolyglotException error) {
                throw new IllegalStateException("Module initialization cannot wait for pending async work: " + name + "; await inside an event or function instead", error);
            }
            return exports.getMember("namespace");
        });
        evaluatedModules.put(name, value);
        return value;
    }

    public Value evaluate(String name, String code) {
        return run("evaluate:" + name, Duration.ofSeconds(10), () -> context.eval(sourceCache.get(name, code, null, false)));
    }

    public Value invoke(Value function, Object... arguments) {
        return access("callback", () -> {
            if (!function.canExecute()) throw new IllegalArgumentException("Expected a JavaScript function");
            String label = functionLabels.computeIfAbsent(function, value -> {
                SourceSection source = value.getSourceLocation();
                return source == null ? "function:<host>" : "function:" + source.getSource().getName() + ":" + source.getStartLine();
            });
            return measure(label, () -> function.execute(arguments));
        });
    }

    public Value invokeMember(Value receiver, String member, Object... arguments) {
        return access("member:" + member, () -> receiver.invokeMember(member, arguments));
    }

    public Value parseJson(String json) { return access("json:parse", () -> helpers.getMember("parse").execute(json)); }
    public String toJson(Value value) { return access("json:stringify", () -> helpers.getMember("stringify").execute(value).asString()); }
    public Value value(Object object) { return access("convert:value", () -> context.asValue(object)); }
    public Value array(Object... items) {
        return access("convert:array", () -> helpers.getMember("array").execute(ProxyArray.fromArray(items)));
    }
    public Value object(Map<String, Object> members) {
        return access("convert:object", () -> helpers.getMember("object").execute(readOnly(members)));
    }
    public Value promise(ProxyExecutable executor) { return access("async:promise", () -> helpers.getMember("promise").execute(executor)); }
    public Value error(String message) { return access("async:error", () -> helpers.getMember("error").execute(message)); }
    public Value bigInteger(String value) { return access("convert:bigint", () -> helpers.getMember("bigint").execute(value)); }

    public <T> T access(String operation, Supplier<T> action) { return run(operation, Duration.ofMillis(250), action); }
    public <T> T measure(String operation, Supplier<T> action) {
        long start = System.nanoTime(); boolean failed = true;
        try { T result = action.get(); failed = false; return result; }
        finally { diagnostics.record(operation, System.nanoTime() - start, failed); }
    }

    private synchronized <T> T run(String operation, Duration limit, Supplier<T> action) {
        if (closed) throw new IllegalStateException("Script context is closed");
        if (depth > 0) return measure(operation, action);
        depth++;
        AtomicBoolean finished = new AtomicBoolean();
        ScheduledFuture<?> deadline = watchdog.schedule(() -> {
            if (finished.compareAndSet(false, true)) context.close(true);
        }, limit.toMillis(), TimeUnit.MILLISECONDS);
        long start = System.nanoTime();
        try {
            context.resetLimits();
            return measure(operation, action);
        } catch (PolyglotException error) {
            if (error.isCancelled() || error.isResourceExhausted()) closed = true;
            throw new ScriptException(error);
        } finally {
            finished.set(true); deadline.cancel(false);
            invocations++; executionNanos += System.nanoTime() - start; depth--;
        }
    }

    public Diagnostics diagnostics() { return diagnostics; }
    public synchronized ModuleStats moduleStats() { return new ModuleStats(moduleHits, moduleMisses, List.copyOf(evaluatedModules.keySet())); }
    public synchronized long invocations() { return invocations; }
    public synchronized long executionNanos() { return executionNanos; }
    public synchronized boolean isClosed() { return closed; }

    public static ProxyObject readOnly(Map<String, Object> values) {
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        return new ProxyObject() {
            @Override public Object getMember(String key) { return copy.get(key); }
            @Override public Object getMemberKeys() { return ProxyArray.fromArray(copy.keySet().toArray()); }
            @Override public boolean hasMember(String key) { return copy.containsKey(key); }
            @Override public void putMember(String key, Value value) { throw new UnsupportedOperationException("Host exports are read-only"); }
        };
    }

    @Override public synchronized void close() {
        evaluatedModules.clear();
        functionLabels.clear();
        if (!closed) { closed = true; context.close(true); }
    }
}
