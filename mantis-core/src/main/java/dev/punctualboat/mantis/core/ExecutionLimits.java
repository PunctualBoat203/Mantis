package dev.punctualboat.mantis.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

public record ExecutionLimits(Duration callback, Duration load, long statements) {
    public static final ExecutionLimits DEFAULT = new ExecutionLimits(Duration.ofMillis(250), Duration.ofSeconds(10), 1_000_000);

    public ExecutionLimits {
        Objects.requireNonNull(callback);
        Objects.requireNonNull(load);
        if (callback.isNegative() || callback.isZero()) throw new IllegalArgumentException("Callback timeout must be positive");
        if (load.compareTo(callback) < 0) throw new IllegalArgumentException("Load timeout cannot be shorter than the callback timeout");
        try { callback.toNanos(); load.toNanos(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("Timeout exceeds the supported nanosecond range", error); }
        if (statements < 1) throw new IllegalArgumentException("Statement limit must be positive");
    }

    /** Reads {@code callback_timeout_ms}, {@code load_timeout_ms}, and {@code statement_limit}; bad values fall back to defaults. */
    public static ExecutionLimits fromProperties(Properties properties, Consumer<String> warnings) {
        Objects.requireNonNull(properties); Objects.requireNonNull(warnings);
        long callback = number(properties, "callback_timeout_ms", DEFAULT.callback().toMillis(), warnings);
        long load = number(properties, "load_timeout_ms", DEFAULT.load().toMillis(), warnings);
        long statements = number(properties, "statement_limit", DEFAULT.statements(), warnings);
        try {
            return new ExecutionLimits(Duration.ofMillis(callback), Duration.ofMillis(load), statements);
        } catch (IllegalArgumentException | NullPointerException error) {
            warnings.accept("Ignoring invalid execution limits (" + error.getMessage() + "); using defaults");
            return DEFAULT;
        }
    }

    private static long number(Properties properties, String key, long fallback, Consumer<String> warnings) {
        String text = properties.getProperty(key);
        if (text == null || text.isBlank()) return fallback;
        try { return Long.parseLong(text.trim()); }
        catch (NumberFormatException error) {
            warnings.accept("Ignoring non-numeric " + key + "=" + text + "; using " + fallback);
            return fallback;
        }
    }
}
