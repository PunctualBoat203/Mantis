package dev.punctualboat.mantis.minecraft;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import java.util.function.Consumer;

@Mod.EventBusSubscriber(modid = "mantis", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class StartupClient {
    private StartupClient() {}
    static void fluidVisuals(Consumer<IClientFluidTypeExtensions> consumer, ResourceLocation still, ResourceLocation flowing, int tint) {
        consumer.accept(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture() { return still; }
            @Override public ResourceLocation getFlowingTexture() { return flowing; }
            @Override public int getTintColor() { return tint; }
        });
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Mantis.startupRegistries().fluids().forEach(fluid -> {
            ItemBlockRenderTypes.setRenderLayer(fluid.source, RenderType.translucent());
            ItemBlockRenderTypes.setRenderLayer(fluid.flowing, RenderType.translucent());
        }));
    }
}
