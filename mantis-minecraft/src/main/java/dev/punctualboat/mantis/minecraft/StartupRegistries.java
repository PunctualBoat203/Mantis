package dev.punctualboat.mantis.minecraft;

import com.google.gson.*;
import dev.punctualboat.mantis.core.MantisExport;
import dev.punctualboat.mantis.recipes.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.*;
import org.graalvm.polyglot.Value;
import java.util.*;

public final class StartupRegistries {
    private final Map<ResourceLocation, JsonObject> items = new LinkedHashMap<>(), blocks = new LinkedHashMap<>();
    private final Map<ResourceLocation, Block> registeredBlocks = new LinkedHashMap<>();
    private boolean frozen;
    private final StartupData data;
    public StartupRegistries(StartupData data) { this.data = Objects.requireNonNull(data); }
    public void freeze() { frozen = true; }
    private ResourceLocation declare(String id) {
        if (frozen) throw new IllegalStateException("Registry declarations are only available during startup loading");
        ResourceLocation key = new ResourceLocation(RecipeValues.id(id));
        if (key.getNamespace().equals("minecraft")) throw new IllegalArgumentException("Use a custom namespace for new registry entries");
        if (items.size() + blocks.size() >= 4096) throw new IllegalStateException("Too many registry declarations");
        return key;
    }
    private static JsonObject properties(Value value, Set<String> allowed) {
        JsonElement json = JsonCodec.read(value);
        if (!json.isJsonObject()) throw new IllegalArgumentException("Registry properties must be an object");
        JsonObject result = json.getAsJsonObject();
        for (String key : result.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown registry property: " + key);
        for (String key : List.of("texture", "displayName")) if (result.has(key) && (!result.get(key).isJsonPrimitive() || !result.getAsJsonPrimitive(key).isString()))
            throw new IllegalArgumentException(key + " must be a string");
        if (result.has("displayName") && (result.get("displayName").getAsString().isBlank() || result.get("displayName").getAsString().length() > 256)) throw new IllegalArgumentException("displayName must contain 1-256 characters");
        return result;
    }
    @MantisExport public void item(String id, Value properties) {
        ResourceLocation key = declare(id);
        JsonObject json = properties(properties, Set.of("maxStackSize", "durability", "fireResistant", "food", "texture", "displayName"));
        itemProperties(json);
        if (items.containsKey(key) || blocks.containsKey(key) && bool(blocks.get(key), "item", true)) throw new IllegalArgumentException("Duplicate item: " + id);
        data.contentAssets(key, json, false, false);
        items.put(key, json.deepCopy());
    }
    @MantisExport public void block(String id, Value properties) {
        ResourceLocation key = declare(id);
        JsonObject json = properties(properties, Set.of("hardness", "resistance", "light", "noOcclusion", "requiresTool", "item", "texture", "displayName"));
        blockProperties(json);
        if (blocks.containsKey(key) || bool(json, "item", true) && items.containsKey(key)) throw new IllegalArgumentException("Duplicate block or block item: " + id);
        data.contentAssets(key, json, true, bool(json, "item", true));
        blocks.put(key, json.deepCopy());
    }
    private static boolean bool(JsonObject p, String key, boolean fallback) {
        if (!p.has(key)) return fallback;
        if (!p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
        return p.get(key).getAsBoolean();
    }
    private static float number(JsonObject p, String key, float fallback, float min, float max) {
        if (!p.has(key)) return fallback;
        if (!p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException(key + " must be numeric");
        float value = p.get(key).getAsFloat();
        if (!Float.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(key + " is outside its supported range"); return value;
    }
    private static Item.Properties itemProperties(JsonObject p) {
        int stack = p.has("maxStackSize") ? RecipeValues.positiveInt(p.get("maxStackSize")) : 64;
        int durability = p.has("durability") ? RecipeValues.positiveInt(p.get("durability")) : 0;
        if (stack > 64 || durability > 0 && p.has("maxStackSize") && stack != 1) throw new IllegalArgumentException("Durable items stack to one; max stack size is 64");
        Item.Properties properties = new Item.Properties();
        if (durability > 0) properties.durability(durability); else properties.stacksTo(stack);
        if (bool(p, "fireResistant", false)) properties.fireResistant();
        if (p.has("food")) {
            JsonObject food = p.getAsJsonObject("food");
            for (String key : food.keySet()) if (!Set.of("nutrition", "saturation", "alwaysEat").contains(key)) throw new IllegalArgumentException("Unknown food property: " + key);
            int nutrition = food.has("nutrition") ? RecipeValues.positiveInt(food.get("nutrition")) : 1;
            if (nutrition > 100) throw new IllegalArgumentException("Food nutrition cannot exceed 100");
            FoodProperties.Builder builder = new FoodProperties.Builder().nutrition(nutrition).saturationMod(number(food, "saturation", 0.6f, 0, 10));
            if (bool(food, "alwaysEat", false)) builder.alwaysEat(); properties.food(builder.build());
        }
        return properties;
    }
    private static BlockBehaviour.Properties blockProperties(JsonObject p) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of().strength(number(p, "hardness", 1.5f, -1, 100000), number(p, "resistance", 3, 0, 100000));
        int light = p.has("light") ? p.get("light").getAsBigDecimal().intValueExact() : 0;
        if (light < 0 || light > 15) throw new IllegalArgumentException("Block light must be 0-15");
        properties.lightLevel(state -> light);
        if (bool(p, "noOcclusion", false)) properties.noOcclusion();
        if (bool(p, "requiresTool", false)) properties.requiresCorrectToolForDrops();
        bool(p, "item", true); return properties;
    }
    public void register(RegisterEvent event) {
        if (!frozen) throw new IllegalStateException("Registry declarations have not finished");
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> blocks.forEach((id, properties) -> {
            if (ForgeRegistries.BLOCKS.containsKey(id)) throw new IllegalArgumentException("Block already registered: " + id);
            Block block = new Block(blockProperties(properties)); helper.register(id, block); registeredBlocks.put(id, block);
        }));
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            items.forEach((id, properties) -> {
                if (ForgeRegistries.ITEMS.containsKey(id)) throw new IllegalArgumentException("Item already registered: " + id);
                helper.register(id, new Item(itemProperties(properties)));
            });
            blocks.forEach((id, properties) -> {
                if (!bool(properties, "item", true)) return;
                if (ForgeRegistries.ITEMS.containsKey(id)) throw new IllegalArgumentException("Block item already registered: " + id);
                helper.register(id, new BlockItem(Objects.requireNonNull(registeredBlocks.get(id), "Blocks must register before block items"), new Item.Properties()));
            });
        });
    }
}
