package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

class ScriptCallbackTest {
    @Test void returnValuesConvertArgumentsAndCloseWithTheirGeneration() {
        ScriptSession.Registrar[] registrar = new ScriptSession.Registrar[1];
        ScriptCallback callback;
        try (MantisEngine engine = new MantisEngine();
             ScriptSession session = new ScriptSession(engine, Map.of(), null, text -> {}, error -> fail(error), value -> registrar[0] = value)) {
            callback = registrar[0].callback(session.context().evaluate("sum", "event => event.values.reduce((a,b) => a+b, 0)"));
            assertEquals(42, callback.<Integer>callValidated(value -> value.asInt(), Map.of("values", List.of(20, 22))).intValue());
            assertEquals(1, session.ownedResources());
        }
        assertFalse(callback.active());
        assertThrows(IllegalStateException.class, () -> callback.call());
    }

    @Test void invalidReturnsUseTheConsecutiveFailureBudgetAndSuccessResetsIt() {
        ScriptSession.Registrar[] registrar = new ScriptSession.Registrar[1]; List<Throwable> errors = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine();
             ScriptSession session = new ScriptSession(engine, Map.of(), null, text -> {}, errors::add, value -> registrar[0] = value)) {
            ScriptCallback callback = registrar[0].callback(session.context().evaluate("value", "value => value"));
            for (int i = 0; i < 9; i++) assertThrows(RuntimeException.class, () -> callback.callValidated(value -> value.asInt(), "bad"));
            assertEquals(1, callback.<Integer>callValidated(value -> value.asInt(), 1).intValue());
            for (int i = 0; i < 10; i++) assertThrows(RuntimeException.class, () -> callback.callValidated(value -> value.asInt(), "bad"));
            assertFalse(callback.active()); assertEquals(19, errors.size()); assertEquals(0, session.ownedResources());
        }
    }

    @Test void executionLimitsCloseOtherGenerationWork() {
        ScriptSession.Registrar[] registrar = new ScriptSession.Registrar[1]; List<Throwable> errors = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(); TickClock clock = new TickClock();
             ScriptSession session = new ScriptSession(engine, Map.of("main.js", "import {clock} from 'mantis:clock'; clock.after(100000, () => {});"), clock, text -> {}, errors::add, value -> registrar[0] = value)) {
            ScriptCallback callback = registrar[0].callback(session.context().evaluate("loop", "() => {while(true){}}"));
            CompletableFuture<String> future = new CompletableFuture<>(); session.async().promise(future);
            assertThrows(RuntimeException.class, callback::call);
            assertEquals(SessionState.FAILED, session.state()); assertTrue(future.isCancelled());
            assertFalse(callback.active()); assertEquals(0, clock.scheduledTasks()); assertEquals(0, session.ownedResources()); assertEquals(1, errors.size());
        }
    }

    public static final class SlowValue {}
    @Test void argumentConversionIsInsideTheCallbackTimeBudget() {
        ScriptSession.Registrar[] registrar = new ScriptSession.Registrar[1]; List<Throwable> errors = new ArrayList<>();
        try (MantisEngine engine = new MantisEngine(new ExecutionLimits(Duration.ofMillis(100), Duration.ofSeconds(2), 1_000_000));
             ScriptSession session = new ScriptSession(engine, Map.of(), null, text -> {}, errors::add, value -> {
                 registrar[0] = value;
                 value.conversions().register(SlowValue.class, slow -> { LockSupport.parkNanos(Duration.ofMillis(300).toNanos()); return "converted"; }, guest -> new SlowValue());
             })) {
            session.start(ScriptScheduler.pumped(), false);
            ScriptCallback callback = registrar[0].callback(session.context().evaluate("identity", "value => value"));
            assertThrows(RuntimeException.class, () -> callback.call(new SlowValue()));
            assertEquals(SessionState.FAILED, session.state()); assertFalse(callback.active()); assertEquals(1, errors.size());
        }
    }
}
