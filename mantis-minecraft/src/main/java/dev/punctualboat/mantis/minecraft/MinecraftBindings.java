package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.core.MantisExport;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;

public final class MinecraftBindings {
    private MinecraftBindings() {}
    public static final class Mods {
        @MantisExport public boolean isLoaded(String id) { return ModList.get().isLoaded(id); }
    }
    public static final class Server {
        private MinecraftServer ready() {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.isSameThread()) throw new IllegalStateException("Server operations require a running server on its main thread");
            return server;
        }
        @MantisExport public void broadcast(String message) { ready().getPlayerList().broadcastSystemMessage(Component.literal(message), false); }
        @MantisExport public int runCommand(String command) {
            MinecraftServer server = ready();
            return server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }
    }
    public static final class Player {
        private final ServerPlayer player;
        public Player(ServerPlayer player) { this.player = player; }
        @MantisExport public String uuid() { return player.getUUID().toString(); }
        @MantisExport public String name() { return player.getGameProfile().getName(); }
        @MantisExport public void tell(String message) {
            if (!player.server.isSameThread()) throw new IllegalStateException("Player operations require the server thread");
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
