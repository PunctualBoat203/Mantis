package dev.punctualboat.mantis.minecraft;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.common.SoundActions;
import net.minecraftforge.fluids.*;
import java.util.Map;
import java.util.function.Consumer;

final class StartupFluid {
    final ResourceLocation id, flowingId, bucketId, stillTexture, flowingTexture;
    final int tint;
    private final JsonObject properties;
    FluidType type;
    ForgeFlowingFluid.Source source;
    ForgeFlowingFluid.Flowing flowing;
    LiquidBlock block;
    BucketItem bucket;

    StartupFluid(ResourceLocation id, JsonObject p) {
        this.id = id; properties = p.deepCopy();
        flowingId = new ResourceLocation(id.getNamespace(), "flowing_" + id.getPath());
        bucketId = new ResourceLocation(id.getNamespace(), id.getPath() + "_bucket");
        stillTexture = new ResourceLocation(StartupRegistries.text(p, "stillTexture", "minecraft:block/water_still"));
        flowingTexture = new ResourceLocation(StartupRegistries.text(p, "flowingTexture", "minecraft:block/water_flow"));
        new ResourceLocation(StartupRegistries.text(p, "bucketTexture", "minecraft:item/water_bucket"));
        String color = StartupRegistries.text(p, "tint", "#FFFFFFFF");
        if (!color.matches("#[0-9a-fA-F]{8}")) throw new IllegalArgumentException("Fluid tint must be #AARRGGBB");
        tint = (int) Long.parseLong(color.substring(1), 16);
        typeProperties(p); flowProperties(p);
    }
    Map<String, String> ids() { return Map.of("source", id.toString(), "flowing", flowingId.toString(), "block", id.toString(), "bucket", bucketId.toString()); }
    private FluidType.Properties typeProperties(JsonObject p) {
        return FluidType.Properties.create().descriptionId("fluid." + id.getNamespace() + "." + id.getPath().replace('/', '.'))
                .density(StartupRegistries.integer(p, "density", 1000, -100000, 100000))
                .viscosity(StartupRegistries.integer(p, "viscosity", 1000, 0, 100000))
                .temperature(StartupRegistries.integer(p, "temperature", 300, 0, 100000))
                .lightLevel(StartupRegistries.integer(p, "light", 0, 0, 15))
                .canConvertToSource(StartupRegistries.bool(p, "canConvertToSource", false))
                .canExtinguish(StartupRegistries.bool(p, "canExtinguish", false))
                .canHydrate(StartupRegistries.bool(p, "canHydrate", false))
                .supportsBoating(StartupRegistries.bool(p, "supportsBoating", false))
                .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY);
    }
    private ForgeFlowingFluid.Properties flowProperties(JsonObject p) {
        return new ForgeFlowingFluid.Properties(() -> type, () -> source, () -> flowing).block(() -> block).bucket(() -> bucket)
                .tickRate(StartupRegistries.integer(p, "tickRate", 5, 1, 100000))
                .slopeFindDistance(StartupRegistries.integer(p, "slopeFindDistance", 4, 1, 16))
                .levelDecreasePerBlock(StartupRegistries.integer(p, "levelDecreasePerBlock", 1, 1, 8))
                .explosionResistance(StartupRegistries.number(p, "resistance", 100, 0, 100000));
    }
    void initialize() {
        if (source != null) return;
        type = new FluidType(typeProperties(properties)) {
            @Override public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                StartupClient.fluidVisuals(consumer, stillTexture, flowingTexture, tint);
            }
        };
        var flowProperties = flowProperties(properties);
        source = new ForgeFlowingFluid.Source(flowProperties);
        flowing = new ForgeFlowingFluid.Flowing(flowProperties);
        int light = StartupRegistries.integer(properties, "light", 0, 0, 15);
        block = new LiquidBlock(() -> source, BlockBehaviour.Properties.of().noCollission().strength(100).noLootTable().liquid().lightLevel(state -> light));
        bucket = new BucketItem(() -> source, new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));
    }
}
