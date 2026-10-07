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
    @Inject(method = "reloadResources", at = @At("RETURN"), cancellable = true)
    private void mantis$reload(Collection<String> packs, CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        callback.setReturnValue(callback.getReturnValue().thenRunAsync(() -> Mantis.adopt(server, true), server)
                .whenComplete((result, error) -> { if (error != null) Mantis.discardPending(); }));
    }
}
