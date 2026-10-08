package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.MantisContext;
import org.graalvm.polyglot.Value;
import java.util.function.*;

/** A callback owned by one generation, for host APIs that need a return value. */
public final class ScriptCallback implements AutoCloseable {
    private Supplier<MantisContext> context;
    private Function<Object, Object> export;
    private Consumer<Throwable> errors;
    private Runnable closed;
    private Value callback;
    private int failures;

    ScriptCallback(Value callback, Supplier<MantisContext> context, Function<Object, Object> export, Consumer<Throwable> errors, Runnable closed) {
        if (!callback.canExecute()) throw new IllegalArgumentException("Callback must be a function");
        this.callback = callback; this.context = context; this.export = export; this.errors = errors; this.closed = closed;
    }
    public boolean active() { return callback != null; }
    public Value call(Object... arguments) {
        return callValidated(Function.identity(), arguments);
    }
    public <T> T callValidated(Function<Value, T> validate, Object... arguments) {
        if (!active()) throw new IllegalStateException("Callback generation is closed or callback is disabled");
        MantisContext owner = context.get();
        Value callable = callback;
        Function<Object, Object> convert = export;
        Consumer<Throwable> report = errors;
        try {
            T result = owner.access("host-callback", () -> {
                Object[] values = new Object[arguments.length];
                for (int i = 0; i < values.length; i++) values[i] = convert.apply(arguments[i]);
                if (!active()) throw new IllegalStateException("Callback was closed while converting arguments");
                return validate.apply(owner.invoke(callable, values));
            });
            failures = 0; return result;
        } catch (RuntimeException error) {
            if (++failures >= EventBus.DEFAULT_MAX_FAILURES || owner.isClosed()) close();
            report.accept(error); throw error;
        }
    }
    @Override public void close() {
        if (callback == null) return;
        Runnable cleanup = closed;
        callback = null; context = null; export = null; errors = null; closed = null;
        cleanup.run();
    }
}
