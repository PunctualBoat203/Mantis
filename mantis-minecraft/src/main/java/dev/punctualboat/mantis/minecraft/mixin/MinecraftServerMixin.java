package dev.punctualboat.mantis.minecraft.mixin;

import dev.punctualboat.mantis.minecraft.Mantis;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "reloadResources", at = @At("HEAD"))
    private void mantis$guardReload(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        if (Mantis.isScriptExecuting()) throw new IllegalStateException("Resource reloads cannot run inside a script callback; request /mantis reload outside scripts");
    }

    @Inject(method = "reloadResources", at = @At("RETURN"), cancellable = true)
    private void mantis$reload(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        callback.setReturnValue(callback.getReturnValue().thenRun(() -> Mantis.adopt(server, true)));
    }
}
