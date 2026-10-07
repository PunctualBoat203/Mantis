package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class MantisContext implements AutoCloseable {
    private final Context context;
    private final Map<String, String> sources;
    private final ScheduledExecutorService watchdog;
    private boolean closed;
    private long invocations;
    private long executionNanos;
    private int depth;

    MantisContext(Engine engine, ScheduledExecutorService watchdog, Map<String, String> sources,
                  Map<String, Map<String, Object>> modules) {
        this.sources = Map.copyOf(sources);
        this.watchdog = watchdog;
        context = Context.newBuilder("js").engine(engine)
                .allowExperimentalOptions(true)
                .allowHostAccess(HostAccess.newBuilder(HostAccess.NONE).allowAccessAnnotatedBy(MantisExport.class).build())
                .allowHostClassLookup(name -> false).allowHostClassLoading(false)
                .allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
                .allowIO(IOAccess.newBuilder().fileSystem(new ModuleFiles(sources, modules)).build())
                .option("js.esm-eval-returns-exports", "true")
                .resourceLimits(ResourceLimits.newBuilder().statementLimit(1_000_000, source -> true).build())
                .build();
        Map<String, Object> bindings = new LinkedHashMap<>();
        modules.forEach((name, values) -> bindings.put(name, ProxyObject.fromMap(new LinkedHashMap<>(values))));
        context.getBindings("js").putMember("__mantis_bindings", ProxyObject.fromMap(bindings));
    }

    public Value evaluateModule(String name) {
        if (!sources.containsKey(name)) throw new IllegalArgumentException("Unknown script: " + name);
        Path path = ModuleFiles.scriptPath(name);
        String entry = "export * as namespace from '" + path.toUri().toASCIIString() + "';";
        Source source = Source.newBuilder("js", entry, name + " [entry]").uri(Path.of("/mantis/entries").resolve(name).toUri())
                .mimeType("application/javascript+module").buildLiteral();
        return run(Duration.ofSeconds(10), () -> context.eval(source).getMember("namespace"));
    }

    public Value evaluate(String name, String code) {
        return run(Duration.ofSeconds(10), () -> context.eval(Source.newBuilder("js", code, name).buildLiteral()));
    }

    public Value invoke(Value function, Object... arguments) {
        if (!function.canExecute()) throw new IllegalArgumentException("Expected a JavaScript function");
        return run(Duration.ofMillis(250), () -> function.execute(arguments));
    }

    public synchronized Value parseJson(String json) {
        return run(Duration.ofMillis(250), () -> context.getBindings("js").getMember("JSON").getMember("parse").execute(json));
    }

    public String toJson(Value value) {
        return run(Duration.ofMillis(250), () -> context.getBindings("js").getMember("JSON").getMember("stringify").execute(value)).asString();
    }

    private synchronized <T> T run(Duration limit, Supplier<T> action) {
        if (closed) throw new IllegalStateException("Script context is closed");
        if (depth > 0) return action.get();
        depth++;
        AtomicBoolean finished = new AtomicBoolean();
        ScheduledFuture<?> deadline = watchdog.schedule(() -> {
            if (finished.compareAndSet(false, true)) context.close(true);
        }, limit.toMillis(), TimeUnit.MILLISECONDS);
        long start = System.nanoTime();
        try {
            context.resetLimits();
            return action.get();
        } catch (PolyglotException error) {
            if (error.isCancelled() || error.isResourceExhausted()) closed = true;
            throw new ScriptException(error);
        } finally {
            finished.set(true);
            deadline.cancel(false);
            invocations++;
            executionNanos += System.nanoTime() - start;
            depth--;
        }
    }

    public synchronized long invocations() { return invocations; }
    public synchronized long executionNanos() { return executionNanos; }
    public synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (!closed) { closed = true; context.close(true); }
    }
}
