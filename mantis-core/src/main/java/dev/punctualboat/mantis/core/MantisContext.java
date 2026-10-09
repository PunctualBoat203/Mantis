package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.BiFunction;

public final class MantisContext implements AutoCloseable {
    private final Context context;
    private final Map<String, String> sources;
    private final Map<String, Value> evaluatedModules = new LinkedHashMap<>();
    private final Map<Value, String> functionLabels = new WeakHashMap<>();
    private final SourceCache sourceCache;
    private final ExecutionWatchdog.Guard execution;
    private final ExecutionLimits limits;
    private final Diagnostics diagnostics = new Diagnostics();
    private final Value helpers;
    private boolean closed;
    private long invocations, executionNanos, moduleHits, moduleMisses;
    private int depth;
    private volatile Thread executingThread;

    public record ModuleStats(long hits, long misses, List<String> loaded) {}

    MantisContext(Engine engine, ExecutionWatchdog watchdog, ExecutionLimits limits, SourceCache sourceCache,
                  Map<String, String> sources, Map<String, Map<String, Object>> modules,
                  BiFunction<MantisContext, Object, Object> exports) {
        this.limits = limits;
        this.sources = Map.copyOf(sources);
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
                .resourceLimits(ResourceLimits.newBuilder().statementLimit(limits.statements(), source -> true).build())
                .build();
        execution = watchdog.register(() -> context.close(true));
        try {
            helpers = run("initialize", limits.load(), () -> context.eval(sourceCache.get("mantis:values", """
                (() => {
                  const parse = JSON.parse, stringify = JSON.stringify, PromiseType = Promise, ErrorType = Error;
                  const from = Array.from, assign = Object.assign, create = Object.create, BigIntType = BigInt, StringType = String;
                  return { parse, stringify, array: a => from(a), object: o => assign(create(null), o),
                    bigint: value => BigIntType(value), promise: executor => new PromiseType(executor), error: message => new ErrorType(message),
                    consoleText: value => {
                      if (typeof value === 'object' && value !== null) {
                        try { const text = stringify(value); if (text !== undefined) return text; } catch (_) {}
                      }
                      try { return StringType(value); } catch (_) { return '<unprintable>'; }
                    } };
                })()
                """, null, false)));
            Map<String, Object> bindings = new LinkedHashMap<>();
            modules.forEach((name, values) -> {
                Map<String, Object> mapped = new LinkedHashMap<>();
                values.forEach((key, value) -> mapped.put(key, exports.apply(this, value)));
                bindings.put(name, readOnly(mapped));
            });
            context.getBindings("js").putMember("__mantis_bindings", readOnly(bindings));
        } catch (RuntimeException | Error error) { execution.close(); context.close(true); throw error; }
    }

    public synchronized Value evaluateModule(String name) {
        if (closed) throw new IllegalStateException("Script context is closed");
        if (!sources.containsKey(name)) throw new IllegalArgumentException("Unknown script: " + name);
        Value cached = evaluatedModules.get(name);
        if (cached != null) { moduleHits++; return cached; }
        moduleMisses++;
        Path path = ModuleFiles.scriptPath(name);
        String entry = "import * as namespace from '" + path.toUri().toASCIIString().replace("'", "%27") + "'; export {namespace}; export const initialized = true;";
        Source source = sourceCache.get(name + " [entry]", entry,
                ModuleFiles.ROOT.resolve("entries").resolve(name).toUri(), true);
        Value value = run("module:" + name, limits.load(), () -> {
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
        return run("evaluate:" + name, limits.load(), () -> context.eval(sourceCache.get(name, code, null, false)));
    }

    public Value invoke(Value function, Object... arguments) { return invokeWithin(limits.callback(), function, arguments); }

    /** Like {@link #invoke} but with the longer load-phase budget, for handlers of events that run while the server is loading. */
    public Value invokeLoad(Value function, Object... arguments) { return invokeWithin(limits.load(), function, arguments); }

    private Value invokeWithin(Duration limit, Value function, Object... arguments) {
        return run("callback", limit, () -> {
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
    public String consoleText(Value value) { return access("console:format", () -> helpers.getMember("consoleText").execute(value).asString()); }
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

    public <T> T access(String operation, Supplier<T> action) { return run(operation, limits.callback(), action); }
    public <T> T loadAccess(String operation, Supplier<T> action) { return run(operation, limits.load(), action); }
    public <T> T measure(String operation, Supplier<T> action) {
        long start = System.nanoTime(); boolean failed = true;
        try { T result = action.get(); failed = false; return result; }
        finally { diagnostics.record(operation, System.nanoTime() - start, failed); }
    }

    private synchronized <T> T run(String operation, Duration limit, Supplier<T> action) {
        if (closed) throw new IllegalStateException("Script context is closed");
        if (depth > 0) return measure(operation, action);
        long start = System.nanoTime();
        long deadline = execution.begin(start, limit.toNanos());
        executingThread = Thread.currentThread();
        depth++;
        long end = 0;
        boolean failed = true;
        try {
            context.resetLimits();
            T result = action.get();
            end = System.nanoTime();
            if (!execution.finish(deadline, end)) throw new ScriptException(operation);
            failed = false;
            return result;
        } catch (PolyglotException error) {
            if (error.isCancelled() || error.isResourceExhausted()) closed = true;
            throw new ScriptException(error);
        } finally {
            if (end == 0) end = System.nanoTime();
            execution.finish(deadline, end);
            diagnostics.record(operation, end - start, failed);
            invocations++; executionNanos += end - start; depth--; executingThread = null;
            if (closed || execution.expired()) {
                closed = true; execution.close(); context.close(true);
            }
        }
    }

    public Diagnostics diagnostics() { return diagnostics; }
    public synchronized ModuleStats moduleStats() { return new ModuleStats(moduleHits, moduleMisses, List.copyOf(evaluatedModules.keySet())); }
    public synchronized long invocations() { return invocations; }
    public synchronized long executionNanos() { return executionNanos; }
    public synchronized boolean isClosed() { return closed; }
    public boolean isExecutingOnCurrentThread() { return executingThread == Thread.currentThread(); }

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
        closed = true; execution.close(); context.close(true);
    }
}
