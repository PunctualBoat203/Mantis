package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.MantisEngine;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScriptSessionTest {
    private static final String SCRIPT = "import {events} from 'mantis:events'; import {clock} from 'mantis:clock'; import {console} from 'mantis:console'; events.on('ping', () => console.log('event')); clock.every(1, () => console.log('timer'));";

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
        assertEquals(1, errors.size());
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
