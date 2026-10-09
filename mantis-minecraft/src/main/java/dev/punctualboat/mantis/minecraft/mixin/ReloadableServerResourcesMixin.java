package dev.punctualboat.mantis.minecraft.mixin;

import dev.punctualboat.mantis.minecraft.Mantis;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(ReloadableServerResources.class)
public abstract class ReloadableServerResourcesMixin {
    @Inject(method = "loadResources", at = @At("RETURN"), cancellable = true)
    private static void mantis$cleanup(ResourceManager resources, RegistryAccess.Frozen registries, FeatureFlagSet features,
                                       Commands.CommandSelection selection, int functionLevel, Executor background, Executor game,
                                       CallbackInfoReturnable<CompletableFuture<ReloadableServerResources>> callback) {
        callback.setReturnValue(callback.getReturnValue().whenCompleteAsync((loaded, error) -> {
            if (error != null) Mantis.discardPending(resources);
        }, game));
    }
}
