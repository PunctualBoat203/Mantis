package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ScriptSessionTest {
    private static final String SCRIPT = "import {events} from 'mantis:events'; import {clock} from 'mantis:clock'; import {console} from 'mantis:console'; events.on('ping', () => console.log('event')); clock.every(1, () => console.log('timer'));";

    public static final class SlowValue {}

    @Test void loadEventsIncludePayloadConversionInTheLoadBudget() {
        List<String> calls = new ArrayList<>(); List<Throwable> errors = new ArrayList<>();
        var limits = new ExecutionLimits(Duration.ofMillis(250), Duration.ofSeconds(2), 1_000_000);
        try (MantisEngine engine = new MantisEngine(limits);
             ScriptSession session = new ScriptSession(engine, Map.of("main.js", "import {events} from 'mantis:events'; import {console} from 'mantis:console'; events.on('recipes', value => console.log(value)); events.on('ping', () => {});"),
                     null, calls::add, errors::add, registrar -> registrar.conversions().register(SlowValue.class, value -> {
                         LockSupport.parkNanos(Duration.ofMillis(400).toNanos()); return "converted";
                     }, value -> new SlowValue()))) {
            session.start(ScriptScheduler.pumped(), false);
            session.events().emitStrict("recipes", new SlowValue());
            assertEquals(List.of("converted"), calls);
            session.events().emit("ping", new SlowValue());
            assertEquals(SessionState.FAILED, session.state());
            assertEquals(1, errors.size()); assertEquals(0, session.ownedResources());
        }
    }

    @Test void limitFailureImmediatelyClosesAllOwnedWorkInTheGeneration() {
        List<Throwable> errors = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(); TickClock clock = new TickClock();
             ScriptSession session = new ScriptSession(engine, Map.of("main.js", "import {events} from 'mantis:events'; import {clock} from 'mantis:clock'; events.on('bad', () => { while(true){} }); events.on('idle', () => {}); clock.after(100000, () => {});"),
                     clock, value -> {}, errors::add, registrar -> {})) {
            CompletableFuture<String> source = new CompletableFuture<>();
            session.async().promise(source);
            session.events().emit("bad", null);
            assertEquals(SessionState.FAILED, session.state());
            assertTrue(source.isCancelled());
            assertEquals(0, session.events().listenerCount());
            assertEquals(0, clock.scheduledTasks()); assertEquals(0, session.ownedResources());
            assertEquals(1, errors.size());
            session.events().emit("bad", null); assertEquals(1, errors.size());
        }
    }

    @Test void timerFailuresUseTheClockBudgetAndReleaseSessionResources() {
        List<Throwable> scriptErrors = new ArrayList<>(); List<Throwable> clockErrors = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine();
             TickClock clock = new TickClock(new TickClock.Snapshot(0, Map.of()), () -> {}, clockErrors::add, 256, 3);
             ScriptSession session = new ScriptSession(engine, Map.of("main.js", "import {clock} from 'mantis:clock'; clock.every(1, () => {throw Error('flaky')});"),
                     clock, value -> {}, scriptErrors::add, registrar -> {})) {
            for (int i = 0; i < 6; i++) clock.tick();
            assertEquals(3, scriptErrors.size()); assertTrue(clockErrors.isEmpty());
            assertEquals(0, clock.scheduledTasks()); assertEquals(0, session.ownedResources());
            assertEquals(SessionState.RUNNING, session.state());
            assertEquals(3, session.recentErrors().size());
        }
    }

    @Test void aTimerExecutionLimitMarksTheGenerationFailedAndCancelsOtherWork() {
        List<Throwable> errors = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(); TickClock clock = new TickClock();
             ScriptSession session = new ScriptSession(engine, Map.of("main.js", "import {clock} from 'mantis:clock'; clock.every(1, () => {while(true){}}); clock.after(100000, () => {});"),
                     clock, value -> {}, errors::add, registrar -> {})) {
            var source = new CompletableFuture<String>(); session.async().promise(source);
            clock.tick();
            assertEquals(SessionState.FAILED, session.state()); assertTrue(source.isCancelled());
            assertEquals(0, clock.scheduledTasks()); assertEquals(0, session.ownedResources()); assertEquals(1, errors.size());
        }
    }

    @Test void aSuccessfulStrictDispatchResetsTheConsecutiveFailureCount() {
        List<Throwable> errors = new ArrayList<>(); EventBus events = new EventBus(errors::add, 2);
        AtomicInteger attempts = new AtomicInteger();
        events.subscribe("ping", value -> { if (attempts.getAndIncrement() != 1) throw new IllegalArgumentException("broken"); }, false);
        events.emit("ping", null); events.emitStrict("ping", null); events.emit("ping", null);
        assertTrue(events.hasListeners("ping"));
        events.emit("ping", null); assertFalse(events.hasListeners("ping"));
    }

    @Test void reloadDisposesOldListenersAndTimers() {
        List<String> calls = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(); TickClock clock = new TickClock()) {
            ScriptSession first = new ScriptSession(engine, Map.of("main.js", SCRIPT), clock, calls::add, error -> fail(error), registrar -> {});
            first.events().emit("ping", null); clock.tick();
            assertEquals(List.of("event", "timer"), calls);
            ScriptSession next = new ScriptSession(engine, Map.of("main.js", SCRIPT), null, calls::add, error -> fail(error), registrar -> {});
            next.attachClock(clock);
            first.close();
            first.events().emit("ping", null);
            next.events().emit("ping", null); clock.tick();
            assertEquals(List.of("event", "timer", "event", "timer"), calls);
            assertEquals(0, first.events().listenerCount());
            assertEquals(1, clock.scheduledTasks());
            next.close();
            assertEquals(0, clock.scheduledTasks());
        }
    }

    @Test void aFailedCandidateLeavesOldScriptsRunning() {
        List<String> calls = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(); TickClock clock = new TickClock()) {
            try (ScriptSession old = new ScriptSession(engine, Map.of("main.js", SCRIPT), clock, calls::add, error -> fail(error), registrar -> {})) {
                assertThrows(RuntimeException.class, () -> new ScriptSession(engine, Map.of("main.js", SCRIPT + " throw new Error('bad reload');"), null,
                        calls::add, error -> fail(error), registrar -> {}));
                clock.tick(); old.events().emit("ping", null);
                assertEquals(List.of("timer", "event"), calls);
                assertEquals(1, clock.scheduledTasks());
            }
        }
    }

    @Test void completedTimersAndOnceListenersReleaseTheirResources() {
        String source = "import {events} from 'mantis:events'; import {clock} from 'mantis:clock'; events.once('ping', () => {}); for(let i=0;i<20;i++) clock.after(1, () => {});";
        try (MantisEngine engine = new MantisEngine(); TickClock clock = new TickClock();
             ScriptSession session = new ScriptSession(engine, Map.of("main.js", source), clock, message -> {}, error -> fail(error), registrar -> {})) {
            assertEquals(21, session.ownedResources());
            session.events().emit("ping", null);
            while (clock.scheduledTasks() > 0) clock.tick();
            assertEquals(0, session.ownedResources());
            assertEquals(0, session.events().listenerCount());
        }
    }

    @Test void deferredTimersWaitForServerActivation() {
        List<String> calls = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(); TickClock clock = new TickClock();
             ScriptSession session = new ScriptSession(engine, Map.of("main.js", SCRIPT), null, calls::add, error -> fail(error), registrar -> {})) {
            clock.tick();
            assertTrue(calls.isEmpty());
            session.attachClock(clock);
            clock.tick();
            assertEquals(List.of("timer"), calls);
        }
    }

    @Test void importingADiscoveredScriptDoesNotRunItTwice() {
        Map<String, String> sources = Map.of(
                "main.js", "import {value} from './lib/value.mjs';",
                "lib/value.mjs", "import {events} from 'mantis:events'; events.on('ping', () => {}); export const value = 1;");
        try (MantisEngine engine = new MantisEngine(); TickClock clock = new TickClock();
             ScriptSession session = new ScriptSession(engine, sources, clock, message -> {}, error -> fail(error), registrar -> {})) {
            assertEquals(1, session.events().listenerCount());
        }
    }

    @Test void onceUnsubscribesBeforeRecursiveDispatchAndFailuresAreIsolated() {
        List<Throwable> errors = new ArrayList<>();
        EventBus events = new EventBus(errors::add);
        AtomicInteger calls = new AtomicInteger();
        events.subscribe("ping", value -> { calls.incrementAndGet(); events.emit("ping", null); }, true);
        events.emit("ping", null);
        assertEquals(1, calls.get());
        events.subscribe("ping", value -> { throw new IllegalStateException("broken listener"); }, false);
        events.subscribe("ping", value -> calls.incrementAndGet(), false);
        events.emit("ping", null); events.emit("ping", null);
        assertEquals(3, calls.get());
        assertEquals(2, errors.size());
        assertEquals(2, events.listenerCount());
    }

    @Test void aListenerSurvivesOccasionalFailuresButIsDisabledAfterRepeatedOnes() {
        List<Throwable> errors = new ArrayList<>();
        EventBus events = new EventBus(errors::add);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        events.subscribe("ping", value -> { if (attempts.incrementAndGet() % 2 == 1) throw new IllegalStateException("flaky"); calls.incrementAndGet(); }, false);
        for (int i = 0; i < 40; i++) events.emit("ping", null);
        assertEquals(20, calls.get());
        assertEquals(20, errors.size());
        assertEquals(1, events.listenerCount());

        EventBus broken = new EventBus(errors::add);
        broken.subscribe("ping", value -> { throw new IllegalStateException("always"); }, false);
        errors.clear();
        for (int i = 0; i < 25; i++) broken.emit("ping", null);
        assertEquals(EventBus.DEFAULT_MAX_FAILURES, errors.size());
        assertEquals(0, broken.listenerCount());
        assertFalse(broken.hasListeners("ping"));
        assertTrue(events.hasListeners("ping"));
    }

    @Test void cleanupContinuesAfterAFailingResource() {
        List<Integer> closed = new ArrayList<>();
        ResourceScope scope = new ResourceScope();
        scope.own(() -> closed.add(1));
        scope.own(() -> { closed.add(2); throw new Exception("cleanup"); });
        scope.own(() -> closed.add(3));
        RuntimeException error = assertThrows(RuntimeException.class, scope::close);
        assertEquals(List.of(3, 2, 1), closed);
        assertEquals(1, error.getSuppressed().length);
        scope.close();
    }
}
