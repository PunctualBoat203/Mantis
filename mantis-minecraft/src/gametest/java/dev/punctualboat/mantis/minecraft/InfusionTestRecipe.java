package dev.punctualboat.mantis.minecraft;

import com.google.gson.*;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;

@Mod.EventBusSubscriber(modid = "mantis", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class InfusionTestRecipe {
    private static RecipeType<Infusion> type;
    private static Serializer serializer;
    @SubscribeEvent public static void register(RegisterEvent event) {
        ResourceLocation id = new ResourceLocation("mantis:test_infusion");
        event.register(Registries.RECIPE_TYPE, id, () -> type = new RecipeType<>() {
            @Override public String toString() { return id.toString(); }
        });
        event.register(Registries.RECIPE_SERIALIZER, id, () -> serializer = new Serializer());
    }

    public record Infusion(ResourceLocation id, JsonObject json, long energy) implements Recipe<Container> {
        @Override public boolean matches(Container container, Level level) { return false; }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public boolean isSpecial() { return true; }
        @Override public ItemStack assemble(Container container, RegistryAccess registries) { return getResultItem(registries).copy(); }
        @Override public ItemStack getResultItem(RegistryAccess registries) {
            JsonObject result = json.getAsJsonArray("outputs").get(0).getAsJsonObject();
            return new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(result.get("item").getAsString())), result.get("count").getAsInt());
        }
        @Override public NonNullList<Ingredient> getIngredients() {
            NonNullList<Ingredient> ingredients = NonNullList.create();
            json.getAsJsonArray("catalysts").forEach(value -> ingredients.add(Ingredient.fromJson(value)));
            return ingredients;
        }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return serializer; }
        @Override public RecipeType<?> getType() { return type; }
    }
    private static final class Serializer implements RecipeSerializer<Infusion> {
        @Override public Infusion fromJson(ResourceLocation id, JsonObject json) {
            long energy = json.getAsJsonObject("fusion").get("energy").getAsLong();
            if (energy < 0 || json.getAsJsonArray("catalysts").isEmpty() || json.getAsJsonArray("outputs").isEmpty())
                throw new JsonSyntaxException("Invalid infusion energy/catalysts/outputs");
            json.getAsJsonArray("catalysts").forEach(Ingredient::fromJson);
            return new Infusion(id, json.deepCopy(), energy);
        }
        @Override public Infusion fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return fromJson(id, JsonParser.parseString(buffer.readUtf()).getAsJsonObject());
        }
        @Override public void toNetwork(FriendlyByteBuf buffer, Infusion recipe) { buffer.writeUtf(recipe.json().toString()); }
    }
}
