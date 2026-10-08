package dev.punctualboat.mantis.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionLimitsConfigTest {
    private static Properties properties(String... keyValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) properties.setProperty(keyValues[i], keyValues[i + 1]);
        return properties;
    }

    @Test void emptyConfigurationUsesTheDefaults() {
        List<String> warnings = new ArrayList<>();
        assertEquals(ExecutionLimits.DEFAULT, ExecutionLimits.fromProperties(new Properties(), warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test void readsConfiguredBudgets() {
        ExecutionLimits limits = ExecutionLimits.fromProperties(properties(
                "callback_timeout_ms", " 500 ", "load_timeout_ms", "30000", "statement_limit", "5000000"), message -> fail(message));
        assertEquals(Duration.ofMillis(500), limits.callback());
        assertEquals(Duration.ofSeconds(30), limits.load());
        assertEquals(5_000_000, limits.statements());
    }

    @Test void invalidValuesWarnAndFallBackInsteadOfFailingStartup() {
        List<String> warnings = new ArrayList<>();
        assertEquals(ExecutionLimits.DEFAULT, ExecutionLimits.fromProperties(properties("callback_timeout_ms", "fast"), warnings::add));
        assertEquals(1, warnings.size());
        warnings.clear();
        assertEquals(ExecutionLimits.DEFAULT, ExecutionLimits.fromProperties(properties("callback_timeout_ms", "0"), warnings::add));
        assertEquals(ExecutionLimits.DEFAULT, ExecutionLimits.fromProperties(properties("callback_timeout_ms", "5000", "load_timeout_ms", "100"), warnings::add));
        assertEquals(ExecutionLimits.DEFAULT, ExecutionLimits.fromProperties(properties("statement_limit", "-1"), warnings::add));
        assertEquals(3, warnings.size());
    }

    @Test void directConstructionRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(Duration.ZERO, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(Duration.ofSeconds(2), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(Duration.ofSeconds(1), Duration.ofSeconds(1), 0));
    }

    @Test void timeoutsThatOverflowNanosecondsWarnAndFallBack() {
        List<String> warnings = new ArrayList<>();
        assertEquals(ExecutionLimits.DEFAULT, ExecutionLimits.fromProperties(properties("load_timeout_ms", Long.toString(Long.MAX_VALUE)), warnings::add));
        assertEquals(1, warnings.size());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionLimits(Duration.ofMillis(Long.MAX_VALUE), Duration.ofMillis(Long.MAX_VALUE), 1));
        assertThrows(NullPointerException.class, () -> new MantisEngine(null));
    }
}
