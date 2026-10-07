package dev.punctualboat.mantis.benchmarks;

import dev.punctualboat.mantis.core.*;
import dev.punctualboat.mantis.runtime.*;
import org.graalvm.polyglot.Value;
import org.openjdk.jmh.annotations.*;

import java.util.Map;
import java.util.concurrent.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class RuntimeBenchmarks {
    public static final class Host {
        @MantisExport public CompletableFuture<Integer> load() { return CompletableFuture.completedFuture(42); }
    }
    @State(Scope.Thread)
    public static class Runtime {
        MantisEngine engine;
        TickClock clock;
        ScriptSession session;
        Value promiseFunction;
        Map<String,String> sources = Map.of("main.js", """
                import {host} from 'benchmark:host'; import {events} from 'mantis:events'; import {clock} from 'mantis:clock';
                clock.every(20, () => {}); events.on('tick', () => {});
                export async function load() { return await host.load(); }
                """);
        @Setup public void setup() { engine = new MantisEngine(); clock = new TickClock(); reload(); }
        void reload() {
            ScriptSession next = new ScriptSession(engine, sources, null, message -> {}, error -> {throw new AssertionError(error);},
                    registrar -> registrar.module("benchmark:host", Map.of("host", new Host())));
            next.attachClock(clock);
            if (session != null) session.close("reload");
            session = next; session.start(ScriptScheduler.pumped(), true);
            promiseFunction = session.context().evaluateModule("main.js").getMember("load");
        }
        @TearDown public void close() { session.close(); clock.close(); engine.close(); }
    }
    @Benchmark public int futurePromiseRoundTrip(Runtime state) {
        CompletableFuture<Integer> future = state.session.async().future(state.session.context().invoke(state.promiseFunction), Integer.class);
        state.session.async().drain(); return future.join();
    }
    @Benchmark public int scriptReload(Runtime state) { state.reload(); return state.clock.scheduledTasks(); }
}
