package dev.punctualboat.mantis.runtime;

import dev.punctualboat.mantis.core.MantisContext;
import dev.punctualboat.mantis.interop.TypeConversions;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

public final class AsyncBridge implements AutoCloseable {
    private final Supplier<MantisContext> context;
    private final TypeConversions conversions;
    private final ResourceScope resources;
    private final Consumer<Throwable> errors;
    private final Set<Pending> pending = ConcurrentHashMap.newKeySet();
    private final Queue<Runnable> deliveries = new ConcurrentLinkedQueue<>();
    private final Queue<Throwable> schedulingFailures = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Thread loadingThread = Thread.currentThread();
    private volatile ScriptScheduler scheduler;
    private volatile boolean closed;
    private boolean draining;

    public AsyncBridge(Supplier<MantisContext> context, TypeConversions conversions, ResourceScope resources, Consumer<Throwable> errors) {
        this.context = context; this.conversions = conversions; this.resources = resources; this.errors = errors;
        registerStageConverters();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerStageConverters() {
        TypeConversions.Converter converter = new TypeConversions.Converter<CompletionStage<?>>() {
            @Override public Object toScript(CompletionStage<?> value, TypeConversions conversions) { return promise(value); }
            @Override public CompletionStage<?> fromScript(Value value, Type target, TypeConversions conversions) {
                return future(value, TypeConversions.argument(target, 0));
            }
        };
        conversions.register(CompletionStage.class, converter);
        conversions.register(CompletableFuture.class, converter);
    }

    public void activate(ScriptScheduler scheduler) {
        activate(scheduler, () -> {});
    }
    public void activate(ScriptScheduler scheduler, Runnable beforeDelivery) {
        if (closed) throw new IllegalStateException("Async bridge is closed");
        if (!scheduler.isOnThread()) throw new IllegalStateException("Activate on the host scheduler thread");
        this.scheduler = Objects.requireNonNull(scheduler);
        beforeDelivery.run();
        requestDrain();
    }

    public boolean canInvoke() { return scheduler == null ? Thread.currentThread() == loadingThread : scheduler.isOnThread(); }
    public int pendingCount() { return pending.size(); }
    public int queuedCount() { return deliveries.size(); }

    public Value promise(CompletionStage<?> stage) { return promise(stage, true); }
    public Value promise(CompletionStage<?> stage, boolean cancelSource) {
        Objects.requireNonNull(stage); requireCallThread();
        Pending ticket = own(new Pending());
        if (cancelSource) ticket.source = stage.toCompletableFuture();
        Value promise;
        try {
            promise = context.get().promise(arguments -> {
                ticket.resolve = arguments[0]; ticket.reject = arguments[1]; return null;
            });
            WeakReference<Pending> weak = new WeakReference<>(ticket);
            stage.whenComplete((result, error) -> {
                Pending target = weak.get();
                if (target != null && target.active.get()) target.deliver(result, error);
            });
        } catch (RuntimeException error) { ticket.close(); throw error; }
        return promise;
    }

    public <T> CompletableFuture<T> future(Value promise, Class<T> target) {
        @SuppressWarnings("unchecked") CompletableFuture<T> result = (CompletableFuture<T>) (CompletableFuture<?>) future(promise, (Type) target);
        return result;
    }

    public CompletableFuture<Object> future(Value promise, Type target) {
        requireCallThread();
        if (!promise.canInvokeMember("then")) throw new IllegalArgumentException("Expected a JavaScript Promise or thenable");
        Pending ticket = own(new Pending());
        ticket.result = new CompletableFuture<>();
        WeakReference<Pending> weak = new WeakReference<>(ticket);
        ticket.result.whenComplete((value, error) -> {
            Pending task = weak.get();
            if (task != null && task.result.isCancelled()) enqueue(task::close);
        });
        try {
            context.get().invokeMember(promise, "then", (ProxyExecutable) arguments -> {
                if (ticket.active.get()) {
                    try { ticket.result.complete(conversions.fromScript(arguments.length == 0 ? null : arguments[0], target)); }
                    catch (RuntimeException error) { ticket.result.completeExceptionally(error); }
                    ticket.close();
                }
                return null;
            }, (ProxyExecutable) arguments -> {
                if (ticket.active.get()) {
                    ticket.result.completeExceptionally(new PromiseRejectedException(arguments.length == 0 ? null : arguments[0]));
                    ticket.close();
                }
                return null;
            });
        } catch (RuntimeException error) { ticket.close(); throw error; }
        return ticket.result;
    }

    public int drain() {
        ScriptScheduler owner = scheduler;
        if (closed || owner == null) return 0;
        if (!owner.isOnThread()) throw new IllegalStateException("Async delivery requires the host scheduler thread");
        if (draining) return 0;
        draining = true; scheduled.set(false);
        int calls = 0; long start = System.nanoTime();
        try {
            Throwable schedulingFailure;
            while ((schedulingFailure = schedulingFailures.poll()) != null) errors.accept(schedulingFailure);
            Runnable action;
            while (!closed && calls < 256 && (calls == 0 || System.nanoTime() - start < 10_000_000) && (action = deliveries.poll()) != null) {
                calls++;
                try { action.run(); } catch (RuntimeException error) {
                    if (context.get() != null && context.get().isClosed()) close();
                    errors.accept(error);
                }
            }
        } finally { draining = false; }
        return calls;
    }

    private void enqueue(Runnable action) {
        if (closed) return;
        deliveries.add(action);
        if (closed) deliveries.clear();
        else requestDrain();
    }

    private void requestDrain() {
        ScriptScheduler owner = scheduler;
        if (closed || owner == null || deliveries.isEmpty() || !scheduled.compareAndSet(false, true)) return;
        try {
            owner.execute(() -> {
                try { drain(); }
                catch (RuntimeException error) { scheduled.set(false); schedulingFailures.add(error); }
            });
        } catch (RuntimeException error) { scheduled.set(false); schedulingFailures.add(error); }
    }

    private Pending own(Pending ticket) {
        if (closed) throw new IllegalStateException("Async bridge is closed");
        if (pending.size() >= 4096) throw new IllegalStateException("Too many pending async operations");
        pending.add(ticket); resources.own(ticket); return ticket;
    }
    private void requireCallThread() {
        if (!canInvoke()) throw new IllegalStateException("Create script promises on the host scheduler thread");
        if (closed) throw new IllegalStateException("Async bridge is closed");
    }

    private final class Pending implements AutoCloseable {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Future<?> source;
        private CompletableFuture<Object> result;
        private Value resolve, reject;
        private void deliver(Object value, Throwable error) {
            enqueue(() -> {
                if (!active.get()) return;
                try {
                    Object converted = null;
                    Throwable failure = error;
                    if (failure == null) {
                        try { converted = conversions.toScript(value); }
                        catch (RuntimeException conversion) { failure = conversion; }
                    }
                    if (failure == null) context.get().invoke(resolve, converted);
                    else {
                        Throwable cause = failure;
                        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
                        Value reason = context.get().error(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                        reason.putMember("javaType", cause.getClass().getName());
                        context.get().invoke(reject, reason);
                    }
                } finally { close(); }
            });
        }
        @Override public void close() {
            if (!active.compareAndSet(true, false)) return;
            pending.remove(this); resources.forget(this);
            resolve = null; reject = null;
            if (source != null && !source.isDone()) source.cancel(true);
            if (result != null && !result.isDone()) result.cancel(false);
        }
    }

    public void suspend() {
        scheduler = null; scheduled.set(false);
        RuntimeException failure = null;
        for (Pending task : List.copyOf(pending)) {
            try { task.close(); }
            catch (RuntimeException error) {
                if (failure == null) failure = new RuntimeException("Async cancellation failed");
                failure.addSuppressed(error);
            }
        }
        deliveries.clear();
        schedulingFailures.clear();
        if (failure != null) throw failure;
    }
    @Override public void close() { closed = true; suspend(); }
}
