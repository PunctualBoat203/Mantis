package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.core.MantisExport;
import dev.punctualboat.mantis.minecraft.api.MantisApi;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;
import java.util.concurrent.*;

@Mod.EventBusSubscriber(modid = "mantis", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MantisTestExtension {
    private static final List<CompletableFuture<?>> HELD = new CopyOnWriteArrayList<>();
    @SubscribeEvent public static void setup(FMLCommonSetupEvent event) {
        MantisApi.registerExtension("mantis_test", registrar -> registrar.module("mantis_test:async", Map.of("test", new Host())));
    }
    public record Data(ResourceLocation id, Component component, List<Integer> values) {}
    public static final class Host {
        @MantisExport public CompletableFuture<Data> load() {
            return CompletableFuture.supplyAsync(() -> new Data(new ResourceLocation("mantis:async"), Component.literal("async"), List.of(20, 22)));
        }
        @MantisExport public CompletableFuture<String> hold() {
            CompletableFuture<String> future = new CompletableFuture<>(); HELD.add(future); return future;
        }
        @MantisExport public boolean onServerThread() {
            var server = ServerLifecycleHooks.getCurrentServer(); return server != null && server.isSameThread();
        }
    }
    public static long activeFutures() { return HELD.stream().filter(future -> !future.isDone()).count(); }
    public static long cancelledFutures() { return HELD.stream().filter(CompletableFuture::isCancelled).count(); }
}
