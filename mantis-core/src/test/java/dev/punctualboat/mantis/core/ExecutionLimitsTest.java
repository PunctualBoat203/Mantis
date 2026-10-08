package dev.punctualboat.mantis.core;

import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Value;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionLimitsTest {
    public static final class SlowHost {
        private MantisContext context;
        @MantisExport public void work() {
            context.access("host:nested", () -> { LockSupport.parkNanos(Duration.ofMillis(400).toNanos()); return null; });
        }
    }

    public static final class ReentrantHost {
        private MantisContext context;
        private Value callback;
        @MantisExport public void repeat() { for (int i = 0; i < 2000; i++) context.invoke(callback); }
    }

    @Test void completedAndFailedCallbacksDoNotLeaveIdleDeadlines() throws InterruptedException {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(), Map.of());
            var callback = context.evaluate("callback.js", "x => x + 1");
            for (int i = 0; i < 500; i++) assertEquals(i + 1, context.invoke(callback, i).asInt());
            assertThrows(ScriptException.class, () -> context.evaluate("failure.js", "throw Error('failure')"));
            Thread.sleep(350);
            assertEquals(43, context.invoke(callback, 42).asInt());
            assertFalse(context.isClosed());
        }
    }

    @Test void nestedHostWorkKeepsTheOuterDeadlineAndOtherContextsRemainUsable() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (MantisEngine engine = new MantisEngine()) {
                SlowHost host = new SlowHost();
                var context = engine.createContext(Map.of("slow.js", "import {host} from 'test:api'; export const work = () => host.work();"),
                        Map.of("test:api", Map.of("host", host)));
                host.context = context;
                var callback = context.evaluateModule("slow.js").getMember("work");
                var other = engine.createContext(Map.of(), Map.of());
                assertThrows(ScriptException.class, () -> context.invoke(callback));
                assertTrue(context.isClosed());
                assertEquals(42, other.evaluate("other.js", "21 * 2").asInt());
            }
        });
    }

    @Test void loadPhaseInvocationsGetTheLongerBudgetThanOrdinaryCallbacks() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (MantisEngine engine = new MantisEngine()) {
                SlowHost host = new SlowHost();
                var context = engine.createContext(Map.of("slow.js", "import {host} from 'test:api'; export const work = () => host.work();"),
                        Map.of("test:api", Map.of("host", host)));
                host.context = context;
                var callback = context.evaluateModule("slow.js").getMember("work");
                context.invokeLoad(callback);
                assertFalse(context.isClosed());
                assertThrows(ScriptException.class, () -> context.invoke(callback));
                assertTrue(context.isClosed());
            }
        });
    }

    @Test void configuredLimitsReplaceTheDefaultCallbackBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (MantisEngine engine = new MantisEngine(new ExecutionLimits(Duration.ofSeconds(2), Duration.ofSeconds(10), 1_000_000))) {
                SlowHost host = new SlowHost();
                var context = engine.createContext(Map.of("slow.js", "import {host} from 'test:api'; export const work = () => host.work();"),
                        Map.of("test:api", Map.of("host", host)));
                host.context = context;
                context.invoke(context.evaluateModule("slow.js").getMember("work"));
                assertFalse(context.isClosed());
            }
        });
    }

    @Test void hostOnlyAccessCannotReturnSuccessfullyAfterItsDeadline() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (MantisEngine engine = new MantisEngine()) {
                var context = engine.createContext(Map.of(), Map.of());
                assertThrows(ScriptException.class, () -> context.access("slow:conversion", () -> {
                    LockSupport.parkNanos(Duration.ofMillis(400).toNanos()); return 42;
                }));
                assertTrue(context.isClosed());
                assertTrue(context.diagnostics().snapshot().stream().anyMatch(sample -> sample.operation().equals("slow:conversion") && sample.failures() == 1));
            }
        });
    }

    @Test void reentrantCallbacksCannotResetTheOuterStatementBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (MantisEngine engine = new MantisEngine()) {
                ReentrantHost host = new ReentrantHost();
                var context = engine.createContext(Map.of("host.js", "import {host} from 'test:api'; globalThis.repeatHost = host;"),
                        Map.of("test:api", Map.of("host", host)));
                host.context = context;
                context.evaluateModule("host.js");
                host.callback = context.evaluate("step.js", "() => { let n=0; for (let i=0;i<1000;i++) n+=i; return n; }");
                assertThrows(ScriptException.class, () -> context.evaluate("outer.js", "repeatHost.repeat()"));
                assertTrue(context.isClosed());
            }
        });
    }
}
