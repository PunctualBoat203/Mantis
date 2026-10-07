package dev.punctualboat.mantis.runtime;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TickClockTest {
    @Test void runsTimersInStableOrderWithoutSameTickRecursion() {
        try (TickClock clock = new TickClock()) {
            List<Integer> calls = new ArrayList<>();
            clock.after(0, () -> { calls.add(1); clock.after(0, () -> calls.add(3)); });
            clock.after(1, () -> calls.add(2));
            assertTrue(calls.isEmpty());
            clock.tick();
            assertEquals(List.of(1, 2), calls);
            clock.tick();
            assertEquals(List.of(1, 2, 3), calls);
            assertEquals(2, clock.ticks());
        }
    }

    @Test void cancelsRepeatingTasksAndRespectsCallbackBudget() {
        AtomicInteger calls = new AtomicInteger();
        try (TickClock clock = new TickClock(new TickClock.Snapshot(0, Map.of()), () -> {}, error -> fail(error), 2)) {
            var repeated = clock.every(1, calls::incrementAndGet);
            clock.after(1, calls::incrementAndGet);
            clock.after(1, calls::incrementAndGet);
            clock.tick();
            assertEquals(2, calls.get());
            repeated.cancel();
            clock.tick();
            assertEquals(3, calls.get());
            assertEquals(0, clock.scheduledTasks());
        }
    }

    @Test void aFailingTaskDoesNotStarveOtherTimers() {
        List<Throwable> errors = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        try (TickClock clock = new TickClock(new TickClock.Snapshot(0, Map.of()), () -> {}, errors::add, 256)) {
            clock.every(1, () -> { throw new IllegalStateException("failed timer"); });
            clock.after(1, calls::incrementAndGet);
            clock.tick(); clock.tick();
            assertEquals(1, errors.size());
            assertEquals(1, calls.get());
            assertEquals(0, clock.scheduledTasks());
        }
    }

    @Test void preservesCounterAndCooldownsAcrossRestarts() {
        TickClock.Snapshot snapshot;
        AtomicInteger dirty = new AtomicInteger();
        try (TickClock first = new TickClock(new TickClock.Snapshot(10, Map.of()), dirty::incrementAndGet, error -> fail(error), 256)) {
            assertTrue(first.cooldown("ability:player", 3));
            assertFalse(first.cooldown("ability:player", 3));
            first.tick();
            snapshot = first.snapshot();
            assertEquals(2, first.remaining("ability:player"));
        }
        try (TickClock restored = new TickClock(snapshot, () -> {}, error -> fail(error), 256)) {
            assertEquals(11, restored.ticks());
            assertEquals(2, restored.remaining("ability:player"));
            restored.tick(); restored.tick();
            assertEquals(0, restored.remaining("ability:player"));
            assertTrue(restored.cooldown("ability:player", 3));
            assertEquals(0, restored.scheduledTasks());
        }
        assertTrue(dirty.get() >= 2);
    }

    @Test void preventsForeignThreadAccessAndInvalidDurations() {
        try (TickClock clock = new TickClock()) {
            assertTrue(CompletableFuture.supplyAsync(() -> {
                try { clock.tick(); return false; } catch (IllegalStateException expected) { return true; }
            }).join());
            assertThrows(IllegalArgumentException.class, () -> clock.every(0, () -> {}));
            assertThrows(IllegalArgumentException.class, () -> clock.after(-1, () -> {}));
            assertThrows(IllegalArgumentException.class, () -> clock.cooldown("", 1));
            assertThrows(IllegalArgumentException.class, () -> clock.cooldown("key", 0));
        }
    }

    @Test void checksCounterOverflow() {
        try (TickClock clock = new TickClock(new TickClock.Snapshot(Long.MAX_VALUE, Map.of()), () -> {}, error -> fail(error), 256)) {
            assertThrows(ArithmeticException.class, clock::tick);
            assertEquals(Long.MAX_VALUE, clock.ticks());
        }
    }
}
