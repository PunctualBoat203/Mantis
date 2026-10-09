package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.Engine;
import com.oracle.truffle.api.Truffle;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public final class MantisEngine implements AutoCloseable {
    private final Engine engine;
    private final ExecutionLimits limits;
    private final ExecutionWatchdog watchdog;
    private final Set<MantisContext> contexts = ConcurrentHashMap.newKeySet();
    private final SourceCache sources = new SourceCache(512, 8 * 1024 * 1024);
    private boolean closed;

    public MantisEngine() { this(ExecutionLimits.DEFAULT); }
    public MantisEngine(ExecutionLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits);
        engine = Engine.newBuilder("js").useSystemProperties(false).option("engine.WarnInterpreterOnly", "false").build();
        watchdog = new ExecutionWatchdog();
    }

    public ExecutionLimits limits() { return limits; }
    public boolean isExecutingOnCurrentThread() { return contexts.stream().anyMatch(MantisContext::isExecutingOnCurrentThread); }
    public String runtimeName() { return Truffle.getRuntime().getName(); }

    public record CacheStats(long hits, long misses, long evictions, int entries, long bytes) {}

    public synchronized MantisContext createContext(Map<String, String> sources, Map<String, Map<String, Object>> modules) {
        return createContext(sources, modules, (context, value) -> value);
    }

    public synchronized MantisContext createContext(Map<String, String> sources, Map<String, Map<String, Object>> modules,
                                                     BiFunction<MantisContext, Object, Object> exports) {
        if (closed) throw new IllegalStateException("Engine is closed");
        MantisContext context = new MantisContext(engine, watchdog, limits, this.sources, sources, modules, exports);
        contexts.add(context);
        return context;
    }

    public synchronized void release(MantisContext context) { context.close(); contexts.remove(context); }

    public CacheStats cacheStats() {
        SourceCache.Stats stats = sources.stats();
        return new CacheStats(stats.hits(), stats.misses(), stats.evictions(), stats.entries(), stats.bytes());
    }
    public void clearSourceCache() { sources.clear(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        contexts.forEach(MantisContext::close);
        contexts.clear();
        sources.clear();
        watchdog.close();
        engine.close();
    }
}
