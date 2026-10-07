package dev.punctualboat.mantis.minecraft;

import com.mojang.logging.LogUtils;
import dev.punctualboat.mantis.core.MantisEngine;
import dev.punctualboat.mantis.core.ScriptException;
import dev.punctualboat.mantis.minecraft.api.MantisApi;
import dev.punctualboat.mantis.runtime.*;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod("mantis")
public final class Mantis {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<PreparedScripts> PENDING = ConcurrentHashMap.newKeySet();
    private static Mantis instance;
    private MantisEngine engine;
    private ScriptSession startup;
    private PreparedScripts active;
    private ClockData clockData;
    private TickClock clock;

    public Mantis() {
        instance = this;
        try { ScriptDirectories.create(); }
        catch (IOException error) { throw new IllegalStateException("Could not create Mantis script directories", error); }
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.addListener(this::reloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::stopping);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener(this::loggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::loggedOut);
        MinecraftForge.EVENT_BUS.addListener(this::commands);
    }

    private void setup(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            engine = new MantisEngine();
            try {
                startup = new ScriptSession(engine, ScriptSources.load(ScriptDirectories.STARTUP), null,
                        Mantis::log, Mantis::report, registrar -> {
                    registrar.module("minecraft:mods", Map.of("mods", new MinecraftBindings.Mods()));
                    MinecraftBindings.register(registrar);
                    MantisApi.registerModules(registrar);
                }, false);
                startup.events().emitStrict("startup", ProxyObject.fromMap(Map.of("version", ModList.get().getModContainerById("mantis").orElseThrow().getModInfo().getVersion().toString())));
            } catch (IOException | RuntimeException error) {
                if (startup != null) { startup.close(); startup = null; }
                throw new IllegalStateException("Mantis startup scripts failed", error);
            }
        });
    }

    public static MantisEngine engine() {
        if (instance == null || instance.engine == null) throw new IllegalStateException("Mantis has not completed common setup");
        return instance.engine;
    }
    public static void log(String text) { LOG.info("[Mantis] {}", text); }
    public static void report(Throwable error) { LOG.error("[Mantis] {}", error instanceof ScriptException script ? script.format() : error.getMessage()); LOG.debug("Mantis script failure", error); }
    public static void pending(PreparedScripts scripts) { PENDING.add(scripts); }
    public static void discardPending() {
        for (PreparedScripts scripts : Set.copyOf(PENDING)) {
            if (PENDING.remove(scripts)) {
                try { scripts.close(); } catch (RuntimeException error) { report(error); }
            }
        }
    }

    private void reloadListeners(AddReloadListenerEvent event) {
        ((RecipeScripts) event.getServerResources().getRecipeManager()).mantis$conditionContext(event.getConditionContext());
    }

    private void started(ServerStartedEvent event) { adopt(event.getServer(), false); }

    public static void adopt(MinecraftServer server, boolean reload) {
        if (!server.isSameThread()) throw new IllegalStateException("Activate scripts on the server thread");
        PreparedScripts next = ((RecipeScripts) server.getRecipeManager()).mantis$prepared();
        if (next == null) throw new IllegalStateException("Recipe reload did not prepare Mantis scripts");
        if (next == instance.active) return;
        if (instance.clock == null) {
            instance.clockData = ClockData.get(server);
            instance.clock = instance.clockData.clock();
        }
        next.session().attachClock(instance.clock);
        PreparedScripts previous = instance.active;
        instance.active = next;
        PENDING.remove(next);
        if (previous != null) {
            try { previous.session().close("reload"); } catch (RuntimeException error) { report(error); }
        }
        ScriptScheduler scheduler = ScriptScheduler.of(server::execute, server::isSameThread);
        next.session().start(scheduler, reload);
        if (instance.startup != null) instance.startup.start(scheduler, false);
        instance.emit(reload ? "server.reloaded" : "server.started", Map.of("ticks", instance.clock.ticks()));
        log("Loaded " + next.session().scriptCount() + " server scripts");
    }

    private void stopping(ServerStoppingEvent event) {
        emit("server.stopping", Map.of());
        if (active != null) { active.close(); active = null; }
        if (startup != null) startup.async().suspend();
        discardPending();
        if (clockData != null) { clockData.stop(); clockData = null; clock = null; }
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null || clock == null) return;
        clock.tick();
        active.session().async().drain();
        if (startup != null) startup.async().drain();
        emit("server.tick", Map.of("ticks", clock.ticks()));
    }

    private void emit(String name, Map<String, Object> data) {
        if (active != null) active.session().events().emit(name, ProxyObject.fromMap(data));
        if (startup != null) startup.events().emit(name, ProxyObject.fromMap(data));
    }

    private void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) emit("player.logged_in", Map.of("player", new MinecraftBindings.Player(player)));
    }
    private void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) emit("player.logged_out", Map.of("player", new MinecraftBindings.Player(player)));
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mantis").requires(source -> source.hasPermission(2))
                .then(Commands.literal("clock").executes(command -> {
                    command.getSource().sendSuccess(() -> Component.literal("Mantis clock: " + clock.ticks() + " ticks"), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(command -> {
                    ScriptSession scripts = active.session();
                    String text = "Mantis " + scripts.state().name().toLowerCase() + ": " + scripts.scriptCount() + " scripts, " + scripts.events().listenerCount()
                            + " listeners, " + clock.scheduledTasks() + " timers, " + scripts.async().pendingCount() + " async, " + scripts.context().invocations() + " calls, "
                            + scripts.context().executionNanos() / 1_000_000 + " ms script time";
                    command.getSource().sendSuccess(() -> Component.literal(text), false);
                    return 1;
                }))
                .then(Commands.literal("scripts").executes(command -> {
                    command.getSource().sendSuccess(() -> Component.literal(String.join(", ", active.session().scriptNames())), false); return 1;
                }))
                .then(Commands.literal("modules").executes(command -> {
                    command.getSource().sendSuccess(() -> Component.literal(String.join(", ", active.session().moduleNames())), false); return 1;
                }))
                .then(Commands.literal("errors").executes(command -> {
                    var errors = active.session().recentErrors();
                    if (errors.isEmpty()) command.getSource().sendSuccess(() -> Component.literal("No script errors in this generation"), false);
                    else errors.stream().skip(Math.max(0, errors.size() - 5)).forEach(error -> command.getSource().sendFailure(Component.literal(error)));
                    return 1;
                }))
                .then(Commands.literal("profile").executes(command -> {
                    active.session().context().diagnostics().snapshot().stream().limit(8).forEach(sample -> command.getSource().sendSuccess(() ->
                            Component.literal(sample.operation() + ": " + sample.calls() + " calls, " + sample.totalNanos() / 1_000_000 + " ms total, "
                                    + sample.maxNanos() / 1_000_000 + " ms max, " + sample.failures() + " failures"), false));
                    var cache = engine.cacheStats();
                    command.getSource().sendSuccess(() -> Component.literal("Source cache: " + cache.hits() + " hits, " + cache.misses() + " misses, " + cache.entries() + " entries"), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(command -> {
                    var source = command.getSource();
                    MinecraftServer server = source.getServer();
                    server.reloadResources(server.getPackRepository().getSelectedIds()).whenComplete((result, error) -> server.execute(() -> {
                        if (error == null) source.sendSuccess(() -> Component.literal("Mantis server scripts and recipes reloaded"), false);
                        else { report(error); source.sendFailure(Component.literal("Mantis reload failed: " + error.getMessage())); }
                    }));
                    return 1;
                })));
    }
}
