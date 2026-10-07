package dev.punctualboat.mantis.runtime;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ResourceScope implements AutoCloseable {
    private final Deque<AutoCloseable> owned = new ArrayDeque<>();
    private boolean closed;

    public <T extends AutoCloseable> T own(T resource) {
        if (closed) throw new IllegalStateException("Script scope is closed");
        owned.push(resource);
        return resource;
    }

    public boolean isClosed() { return closed; }
    public void forget(AutoCloseable resource) { owned.remove(resource); }
    public int size() { return owned.size(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        while (!owned.isEmpty()) {
            try { owned.pop().close(); }
            catch (Exception error) {
                if (failure == null) failure = new RuntimeException("Script resource cleanup failed");
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
