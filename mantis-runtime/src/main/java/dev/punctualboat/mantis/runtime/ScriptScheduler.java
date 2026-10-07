package dev.punctualboat.mantis.runtime;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

public interface ScriptScheduler {
    void execute(Runnable task);
    boolean isOnThread();

    static ScriptScheduler of(Executor executor, BooleanSupplier onThread) {
        Objects.requireNonNull(executor); Objects.requireNonNull(onThread);
        return new ScriptScheduler() {
            @Override public void execute(Runnable task) { executor.execute(task); }
            @Override public boolean isOnThread() { return onThread.getAsBoolean(); }
        };
    }

    static ScriptScheduler pumped() {
        Thread owner = Thread.currentThread();
        return of(task -> {}, () -> Thread.currentThread() == owner);
    }
}
