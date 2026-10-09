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
import org.slf4j.Logger;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.packs.resources.ResourceManager;

@Mod("mantis")
public final class Mantis {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<ResourceManager, PreparedScripts> PENDING = new ConcurrentHashMap<>();
    private static Mantis instance;
    private final MantisConfig config;
    private MantisEngine engine;
    private ScriptSession startup;
    private PreparedScripts active;
    private ClockData clockData;
    private TickClock clock;
    private final StartupData data = new StartupData();
    private final StartupRegistries registries = new StartupRegistries(data);

    public Mantis() {
        instance = this;
        try { ScriptDirectories.create(); }
        catch (IOException error) { throw new IllegalStateException("Could not create Mantis script directories", error); }
        config = MantisConfig.load(ScriptDirectories.ROOT.resolve("mantis.properties"), Mantis::log);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::register);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::packs);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(registries::tabContents);
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
            prepareStartup();
            registries.validateReferences();
            MantisApi.freeze();
        });
    }

    private void register(RegisterEvent event) { prepareStartup(); registries.register(event); }
    private void packs(AddPackFindersEvent event) {
        prepareStartup();
        PackType type = event.getPackType();
        event.addRepositorySource(accept -> {
            Pack pack = Pack.readMetaAndCreate("mantis:generated", Component.literal("Mantis startup resources"), true,
                    id -> data.open(id, type), type, Pack.Position.TOP, PackSource.BUILT_IN);
            if (pack == null) throw new IllegalStateException("Could not load Mantis generated pack metadata");
            accept.accept(pack);
        });
    }

    private synchronized void prepareStartup() {
        if (startup != null) return;
        if (engine == null) { engine = new MantisEngine(config.limits()); log("JavaScript runtime: " + engine.runtimeName()); }
        try {
            startup = new ScriptSession(engine, ScriptSources.load(ScriptDirectories.STARTUP), null,
                    Mantis::scriptLog, Mantis::report, registrar -> {
                registrar.module("minecraft:mods", Map.of("mods", new MinecraftBindings.Mods()));
                registrar.module("minecraft:server", Map.of("server", new MinecraftBindings.Server()));
                registrar.module("minecraft:registries", Map.of("registries", registries));
                registrar.module("minecraft:schemas", Map.of("schemas", new MantisApi.Schemas()));
                registrar.module("minecraft:data", Map.of("data", data));
                MinecraftBindings.register(registrar);
                MantisApi.registerModules(registrar, false);
            }, false);
            startup.events().emitStrict("startup", Map.of("version", ModList.get().getModContainerById("mantis").orElseThrow().getModInfo().getVersion().toString()));
        } catch (IOException | RuntimeException error) {
            if (startup != null) { startup.close(); startup = null; }
            throw new IllegalStateException("Mantis startup scripts failed", error);
        }
        registries.freeze();
        data.freeze();
    }

    public static MantisEngine engine() {
        if (instance == null || instance.engine == null) throw new IllegalStateException("Mantis has not initialized its script engine");
        return instance.engine;
    }
    public static boolean isScriptExecuting() { return instance != null && instance.engine != null && instance.engine.isExecutingOnCurrentThread(); }
    static StartupRegistries startupRegistries() { return instance.registries; }
    public static MantisConfig config() { return instance == null || instance.config == null ? MantisConfig.DEFAULT : instance.config; }
    /** True once a script generation has been activated, meaning a failed reload has something to fall back to. */
    public static boolean hasActive() { return instance != null && instance.active != null; }
    public static boolean degraded() { return hasActive() && instance.active.degraded(); }
    public static SessionState startupState() { return instance == null || instance.startup == null ? SessionState.CREATED : instance.startup.state(); }
    public static void log(String text) { LOG.info("[Mantis] {}", text); }
    static void scriptLog(ScriptSession.LogLevel level, String text) {
        switch (level) {
            case INFO -> LOG.info("[Mantis] {}", text);
            case WARN -> LOG.warn("[Mantis] {}", text);
            case ERROR -> LOG.error("[Mantis] {}", text);
        }
    }
    static String formatError(Throwable error) { return error instanceof ScriptException script ? script.format() : error.toString(); }
    public static void report(Throwable error) { LOG.error("[Mantis] {}", formatError(error)); LOG.debug("Mantis script failure", error); }
    public static void pending(ResourceManager resources, PreparedScripts scripts) {
        if (PENDING.putIfAbsent(resources, scripts) != null) {
            var error = new IllegalStateException("Scripts already prepared for these resources");
            try { scripts.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    public static void discardPending(ResourceManager resources) {
        PreparedScripts scripts = PENDING.remove(resources);
        if (scripts != null) {
            try { scripts.close(); } catch (RuntimeException error) { report(error); }
        }
    }
    public static void discardPending() {
        for (ResourceManager resources : java.util.List.copyOf(PENDING.keySet())) discardPending(resources);
    }

    private void reloadListeners(AddReloadListenerEvent event) {
        RecipeScripts recipes = (RecipeScripts) event.getServerResources().getRecipeManager();
        recipes.mantis$conditionContext(event.getConditionContext());
        recipes.mantis$commandDispatcher(event.getServerResources().getCommands().getDispatcher());
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
        PENDING.values().remove(next);
        if (previous != null) {
            try { previous.session().close("reload"); } catch (RuntimeException error) { report(error); }
        }
        ScriptScheduler scheduler = ScriptScheduler.of(server::execute, server::isSameThread);
        next.session().start(scheduler, reload);
        server.getPlayerList().getPlayers().forEach(server.getCommands()::sendCommands);
        if (instance.startup != null && (instance.startup.state() == SessionState.LOADED || instance.startup.state() == SessionState.RUNNING)) {
            instance.startup.start(scheduler, false);
        }
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
        // Runs every tick: skip building the payload entirely when no script listens.
        if (hasListeners("server.tick")) emit("server.tick", Map.of("ticks", clock.ticks()));
    }

    private boolean hasListeners(String name) {
        return active != null && active.session().events().hasListeners(name) || startup != null && startup.events().hasListeners(name);
    }
    public static boolean listens(String name) { return instance != null && instance.hasListeners(name); }
    public static void dispatch(String name, Map<String, Object> data) {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Minecraft events must dispatch on the server thread");
        instance.emit(name, data);
    }

    private void emit(String name, Map<String, Object> data) {
        if (active != null && active.session().events().hasListeners(name)) active.session().events().emit(name, data);
        if (startup != null && startup.events().hasListeners(name)) startup.events().emit(name, data);
    }

    private void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (degraded() && player.hasPermissions(2)) {
                player.sendSystemMessage(Component.literal("Mantis is degraded: server scripts failed on first load and scripted recipe changes were skipped. Run /mantis errors, fix the scripts, then /mantis reload."));
            }
            emit("player.logged_in", Map.of("player", new MinecraftBindings.Player(player)));
        }
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
                    String text = "Mantis " + (active.degraded() ? "degraded" : scripts.state().name().toLowerCase()) + ": " + scripts.scriptCount() + " scripts, " + scripts.events().listenerCount()
                            + " listeners, " + clock.scheduledTasks() + " timers, " + scripts.async().pendingCount() + " async, " + scripts.context().invocations() + " calls, "
                            + scripts.context().executionNanos() / 1_000_000 + " ms script time, startup " + startupState().name().toLowerCase();
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
                    var errors = java.util.stream.Stream.concat(active.loadErrors().stream(), active.session().recentErrors().stream());
                    var combined = java.util.stream.Stream.concat(errors, startup == null ? java.util.stream.Stream.<String>empty()
                            : startup.recentErrors().stream().map(error -> "Startup: " + error)).toList();
                    if (combined.isEmpty()) command.getSource().sendSuccess(() -> Component.literal("No script errors in this generation"), false);
                    else combined.stream().skip(Math.max(0, combined.size() - 5)).forEach(error -> command.getSource().sendFailure(Component.literal(error)));
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
