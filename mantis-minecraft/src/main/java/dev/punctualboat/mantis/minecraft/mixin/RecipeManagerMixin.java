package dev.punctualboat.mantis.minecraft.mixin;

import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import dev.punctualboat.mantis.minecraft.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin implements RecipeScripts {
    @Unique private ICondition.IContext mantis$conditions = ICondition.IContext.EMPTY;
    @Unique private PreparedScripts mantis$prepared;
    @Unique private CommandDispatcher<CommandSourceStack> mantis$dispatcher;
    @Override public void mantis$conditionContext(ICondition.IContext context) { mantis$conditions = context; }
    @Override public PreparedScripts mantis$prepared() { return mantis$prepared; }
    @Override public void mantis$commandDispatcher(CommandDispatcher<CommandSourceStack> dispatcher) { mantis$dispatcher = dispatcher; }

    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void mantis$recipes(Map<ResourceLocation, JsonElement> jsons, ResourceManager resources, ProfilerFiller profiler, CallbackInfo callback) {
        mantis$prepared = RecipeReload.prepare(jsons, mantis$conditions, mantis$dispatcher);
        Mantis.pending(resources, mantis$prepared);
    }
}
