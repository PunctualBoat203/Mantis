package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.Engine;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class MantisEngine implements AutoCloseable {
    private final Engine engine = Engine.newBuilder("js").useSystemProperties(false)
            .option("engine.WarnInterpreterOnly", "false").build();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mantis-script-limits");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<MantisContext> contexts = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public synchronized MantisContext createContext(Map<String, String> sources, Map<String, Map<String, Object>> modules) {
        if (closed) throw new IllegalStateException("Engine is closed");
        MantisContext context = new MantisContext(engine, watchdog, sources, modules);
        contexts.add(context);
        return context;
    }

    public synchronized void release(MantisContext context) { context.close(); contexts.remove(context); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        contexts.forEach(MantisContext::close);
        contexts.clear();
        watchdog.shutdownNow();
        engine.close();
    }
}
