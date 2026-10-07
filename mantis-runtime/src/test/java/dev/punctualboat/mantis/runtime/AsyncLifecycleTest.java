package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AsyncLifecycleTest {
    public static final class Host {
        private final CompletableFuture<List<Integer>> value = new CompletableFuture<>();
        @MantisExport public CompletableFuture<List<Integer>> load() { return value; }
    }

    @Test void futuresBecomeAwaitableNativePromisesAndDeliverOnOwnerAfterActivation() throws Exception {
        Host host = new Host(); List<String> calls = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(); ScriptSession session = new ScriptSession(engine, Map.of("main.js", """
                import {host} from 'test:api'; import {console} from 'mantis:console';
                export let answer = 0;
                export const promise = (async () => { const data = await host.load();
                  if (!Array.isArray(data)) throw new Error('not a native array');
                  answer = data.reduce((a,b) => a+b, 0); console.log('ready'); return answer; })();
                """), null, calls::add, error -> fail(error), registrar -> registrar.module("test:api", Map.of("host", host)))) {
            CompletableFuture<Integer> result = session.async().future(session.context().evaluateModule("main.js").getMember("promise"), Integer.class);
            Thread worker = new Thread(() -> host.value.complete(List.of(20, 22)), "test-worker"); worker.start(); worker.join();
            assertFalse(result.isDone()); assertTrue(calls.isEmpty());
            session.start(ScriptScheduler.pumped(), false);
            assertEquals(1, session.async().drain());
            assertEquals(42, result.join()); assertEquals(List.of("ready"), calls);
            assertEquals(0, session.async().pendingCount()); assertEquals(0, session.ownedResources());
        }
    }

    @Test void rejectionDetailsAndCancellationReleaseOwnedWork() {
        Host host = new Host();
        try (MantisEngine engine = new MantisEngine(); ScriptSession session = new ScriptSession(engine, Map.of("main.js", """
                import {host} from 'test:api'; export const promise = host.load();
                """), null, message -> {}, error -> fail(error), registrar -> registrar.module("test:api", Map.of("host", host)))) {
            CompletableFuture<Object> result = session.async().future(session.context().evaluateModule("main.js").getMember("promise"), Object.class);
            session.start(ScriptScheduler.pumped(), false);
            host.value.completeExceptionally(new IllegalArgumentException("no such item")); session.async().drain();
            CompletionException error = assertThrows(CompletionException.class, result::join);
            assertInstanceOf(PromiseRejectedException.class, error.getCause()); assertTrue(error.getCause().getMessage().contains("no such item"));
            assertEquals(0, session.async().pendingCount());
            CompletableFuture<String> pending = new CompletableFuture<>();
            session.async().promise(pending); assertEquals(1, session.async().pendingCount());
            session.close("reload"); assertTrue(pending.isCancelled()); assertEquals(0, session.async().pendingCount());
        }
    }

    @Test void completedAndLateResultsCannotRunAfterReload() throws Exception {
        List<String> calls = new ArrayList<>(); Host host = new Host();
        try (MantisEngine engine = new MantisEngine()) {
            ScriptSession old = new ScriptSession(engine, Map.of("main.js", """
                    import {host} from 'test:api'; import {console} from 'mantis:console';
                    host.load().then(() => console.log('stale'));
                    """), null, calls::add, error -> fail(error), registrar -> registrar.module("test:api", Map.of("host", host)));
            old.start(ScriptScheduler.pumped(), false);
            Thread worker = new Thread(() -> host.value.complete(List.of(1))); worker.start(); worker.join();
            assertEquals(1, old.async().queuedCount()); old.close("reload");
            assertEquals(0, old.async().drain()); assertTrue(calls.isEmpty()); assertEquals(0, old.ownedResources());
            CompletableFuture<String> shared = new CompletableFuture<>();
            try (ScriptSession next = new ScriptSession(engine, Map.of(), null, calls::add, error -> fail(error), registrar -> {})) {
                next.async().promise(shared, false); next.close(); assertFalse(shared.isCancelled());
                shared.complete("late"); assertEquals(0, next.async().queuedCount());
            }
        }
    }

    @Test void schedulersRejectWrongThreadAndMayUseAHostQueue() throws Exception {
        Queue<Runnable> scheduled = new ConcurrentLinkedQueue<>(); Thread owner = Thread.currentThread();
        try (MantisEngine engine = new MantisEngine(); ScriptSession session = new ScriptSession(engine, Map.of(), null, message -> {}, error -> fail(error), registrar -> {})) {
            session.start(ScriptScheduler.of(scheduled::add, () -> Thread.currentThread() == owner), false);
            CompletableFuture<String> source = new CompletableFuture<>(); Value promise = session.async().promise(source);
            CompletableFuture<String> result = session.async().future(promise, String.class);
            CompletableFuture<Throwable> wrongThread = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                source.complete("ok");
                try { session.async().drain(); } catch (Throwable error) { wrongThread.complete(error); }
            }); worker.start(); worker.join();
            assertInstanceOf(IllegalStateException.class, wrongThread.join()); assertFalse(result.isDone());
            scheduled.remove().run(); assertEquals("ok", result.join());
        }
    }

    @Test void lifecycleHooksRunInOrderAndCleanupRunsDespiteHookFailure() {
        List<String> calls = new ArrayList<>(); List<Throwable> errors = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine()) {
            ScriptSession session = new ScriptSession(engine, Map.of("main.js", """
                    import {lifecycle} from 'mantis:lifecycle'; import {console} from 'mantis:console';
                    for (const hook of ['load','init','start','reload','stop','unload'])
                      lifecycle.on(hook, () => console.log(hook + ':' + lifecycle.state()));
                    lifecycle.on('stop', () => {throw new Error('stop failed')});
                    """), null, calls::add, errors::add, registrar -> {});
            assertEquals(SessionState.LOADED, session.state());
            session.start(ScriptScheduler.pumped(), true); session.close("reload"); session.close();
            assertEquals(List.of("load:loaded","init:loaded","start:running","reload:running","stop:stopping","unload:stopping"), calls);
            assertEquals(List.of(SessionState.CREATED,SessionState.LOADING,SessionState.LOADED,SessionState.RUNNING,SessionState.STOPPING,SessionState.DISPOSED), session.history());
            assertEquals(1, errors.size()); assertEquals(0, session.ownedResources());
        }
    }

    @Test void pendingTopLevelAwaitRejectsTheCandidateAndCancelsItsFuture() {
        Host host = new Host();
        try (MantisEngine engine = new MantisEngine()) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> new ScriptSession(engine,
                    Map.of("main.js", "import {host} from 'test:api'; await host.load();"), null,
                    message -> {}, failure -> {}, registrar -> registrar.module("test:api", Map.of("host", host))));
            assertTrue(error.getMessage().contains("async"));
            assertTrue(host.value.isCancelled());
        }
    }

    @Test void conversionFailuresRejectThePromiseAndExecutionLimitsCancelAwaitingJavaWork() {
        try (MantisEngine engine = new MantisEngine(); ScriptSession session = new ScriptSession(engine, Map.of(), null, message -> {}, error -> {}, registrar -> {})) {
            session.start(ScriptScheduler.pumped(), false);
            List<Object> cyclic = new ArrayList<>(); cyclic.add(cyclic);
            CompletableFuture<Object> rejected = session.async().future(session.async().promise(CompletableFuture.completedFuture(cyclic)), Object.class);
            session.async().drain();
            assertTrue(assertThrows(CompletionException.class, rejected::join).getCause().getMessage().contains("Cyclic"));
            CompletableFuture<String> source = new CompletableFuture<>();
            Value runaway = session.context().invoke(session.context().evaluate("runaway.js", "p => p.then(() => {while(true){}})"), session.async().promise(source));
            CompletableFuture<Object> result = session.async().future(runaway, Object.class);
            source.complete("ready");
            assertTimeout(java.time.Duration.ofSeconds(5), session.async()::drain);
            assertTrue(session.context().isClosed()); assertTrue(result.isCancelled()); assertEquals(SessionState.FAILED, session.state());
            assertEquals(0, session.async().pendingCount());
        }
    }

    @Test void rejectedSchedulingReportsOnTheOwnerThreadAndStillAllowsManualRecovery() throws Exception {
        Thread owner = Thread.currentThread(); List<Thread> reported = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(); ScriptSession session = new ScriptSession(engine, Map.of(), null,
                message -> {}, error -> reported.add(Thread.currentThread()), registrar -> {})) {
            session.start(ScriptScheduler.of(task -> {throw new RejectedExecutionException("scheduler unavailable");}, () -> Thread.currentThread() == owner), false);
            CompletableFuture<String> source = new CompletableFuture<>();
            CompletableFuture<String> result = session.async().future(session.async().promise(source), String.class);
            Thread worker = new Thread(() -> source.complete("ready")); worker.start(); worker.join();
            assertTrue(reported.isEmpty()); assertFalse(result.isDone());
            session.async().drain(); assertEquals(List.of(owner), reported); assertEquals("ready", result.join());
        }
    }
}
