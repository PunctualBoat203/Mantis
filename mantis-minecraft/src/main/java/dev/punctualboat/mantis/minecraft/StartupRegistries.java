package dev.punctualboat.mantis.minecraft;

import com.google.gson.*;
import dev.punctualboat.mantis.core.MantisExport;
import dev.punctualboat.mantis.recipes.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.*;
import org.graalvm.polyglot.Value;
import java.util.*;

public final class StartupRegistries {
    private final Map<ResourceLocation, JsonObject> items = new LinkedHashMap<>(), blocks = new LinkedHashMap<>();
    private final Map<ResourceLocation, Block> registeredBlocks = new LinkedHashMap<>();
    private final Map<ResourceLocation, StartupFluid> fluids = new LinkedHashMap<>();
    private record Tab(String name, ResourceLocation icon, List<ResourceLocation> items, List<ResourceLocation> before, List<ResourceLocation> after) {}
    private final Map<ResourceLocation, Tab> tabs = new LinkedHashMap<>();
    private final Map<ResourceLocation, LinkedHashSet<ResourceLocation>> tabItems = new LinkedHashMap<>();
    private boolean frozen;
    private final StartupData data;
    public StartupRegistries(StartupData data) { this.data = Objects.requireNonNull(data); }
    public void freeze() { frozen = true; }
    private ResourceLocation declare(String id) {
        if (frozen) throw new IllegalStateException("Registry declarations are only available during startup loading");
        ResourceLocation key = new ResourceLocation(RecipeValues.id(id));
        if (key.getNamespace().equals("minecraft")) throw new IllegalArgumentException("Use a custom namespace for new registry entries");
        if (items.size() + blocks.size() + fluids.size() * 4 + tabs.size() >= 4096) throw new IllegalStateException("Too many registry declarations");
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
        if (items.containsKey(key) || fluidItem(key) || blocks.containsKey(key) && bool(blocks.get(key), "item", true)) throw new IllegalArgumentException("Duplicate item: " + id);
        data.contentAssets(key, json, false, false);
        items.put(key, json.deepCopy());
    }
    @MantisExport public void block(String id, Value properties) {
        ResourceLocation key = declare(id);
        JsonObject json = properties(properties, Set.of("hardness", "resistance", "light", "noOcclusion", "requiresTool", "item", "texture", "displayName"));
        blockProperties(json);
        if (blocks.containsKey(key) || fluids.containsKey(key) || bool(json, "item", true) && (items.containsKey(key) || fluidItem(key))) throw new IllegalArgumentException("Duplicate block or block item: " + id);
        data.contentAssets(key, json, true, bool(json, "item", true));
        blocks.put(key, json.deepCopy());
    }
    @MantisExport public Map<String, String> fluid(String id, Value properties) {
        ResourceLocation key = declare(id);
        if (items.size() + blocks.size() + fluids.size() * 4 + tabs.size() + 4 > 4096) throw new IllegalStateException("Too many registry declarations");
        JsonObject p = properties(properties, Set.of("displayName", "stillTexture", "flowingTexture", "bucketTexture", "tint", "density", "viscosity", "temperature", "light", "tickRate", "slopeFindDistance", "levelDecreasePerBlock", "resistance", "canConvertToSource", "canExtinguish", "canHydrate", "supportsBoating"));
        StartupFluid fluid = new StartupFluid(key, p);
        if (blocks.containsKey(key) || fluids.containsKey(key) || items.containsKey(fluid.bucketId) || fluidItem(fluid.bucketId)
                || blocks.containsKey(fluid.bucketId) && bool(blocks.get(fluid.bucketId), "item", true)
                || fluids.values().stream().anyMatch(other -> other.flowingId.equals(key) || other.id.equals(fluid.flowingId))) throw new IllegalArgumentException("Duplicate fluid family: " + id);
        data.fluidAssets(fluid, p); fluids.put(key, fluid); return fluid.ids();
    }
    private boolean fluidItem(ResourceLocation key) { return fluids.values().stream().anyMatch(fluid -> fluid.bucketId.equals(key)); }
    Collection<StartupFluid> fluids() { return Collections.unmodifiableCollection(fluids.values()); }
    @MantisExport public void creativeTab(String id, Value properties) {
        ResourceLocation key = declare(id);
        JsonObject p = properties(properties, Set.of("displayName", "icon", "items", "before", "after"));
        String name = text(p, "displayName", key.toString());
        ResourceLocation icon = new ResourceLocation(text(p, "icon", "minecraft:paper"));
        Tab tab = new Tab(name, icon, ids(p.get("items")), ids(p.get("before")), ids(p.get("after")));
        if (tabs.containsKey(key) || tab.before().contains(key) || tab.after().contains(key)) throw new IllegalArgumentException("Duplicate tab or self ordering: " + id);
        data.translation(key, tabKey(key), name); tabs.put(key, tab);
    }
    @MantisExport public void tabItems(String tab, Value values) {
        if (frozen) throw new IllegalStateException("Creative entries must be declared during startup loading");
        ResourceLocation key = new ResourceLocation(RecipeValues.id(tab));
        List<ResourceLocation> items = ids(JsonCodec.read(values));
        if (!tabItems.containsKey(key) && tabItems.size() >= 512) throw new IllegalArgumentException("Too many creative tab targets");
        var entries = new LinkedHashSet<>(tabItems.getOrDefault(key, new LinkedHashSet<>())); entries.addAll(items);
        if (entries.size() > 4096) throw new IllegalArgumentException("Creative tab accepts at most 4096 items");
        tabItems.put(key, entries);
    }
    private static List<ResourceLocation> ids(JsonElement json) {
        if (json == null) return List.of();
        if (!json.isJsonArray() || json.getAsJsonArray().size() > 4096) throw new IllegalArgumentException("Creative IDs must be an array of at most 4096 strings");
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (JsonElement value : json.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Creative IDs must be strings");
            result.add(new ResourceLocation(RecipeValues.id(value.getAsString())));
        }
        return List.copyOf(result);
    }
    static String text(JsonObject p, String key, String fallback) {
        if (!p.has(key)) return fallback;
        if (!p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException(key + " must be a string");
        return p.get(key).getAsString();
    }
    private static String tabKey(ResourceLocation id) { return "itemGroup." + id.getNamespace() + "." + id.getPath().replace('/', '.'); }
    static int integer(JsonObject p, String key, int fallback, int min, int max) {
        if (!p.has(key)) return fallback;
        if (!p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException(key + " must be an integer");
        int value;
        try { value = p.get(key).getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(key + " must be an integer", error); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " is outside its supported range"); return value;
    }
    static boolean bool(JsonObject p, String key, boolean fallback) {
        if (!p.has(key)) return fallback;
        if (!p.get(key).isJsonPrimitive() || !p.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
        return p.get(key).getAsBoolean();
    }
    static float number(JsonObject p, String key, float fallback, float min, float max) {
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
        // Suppliers connect the family even when Forge registers blocks before fluids.
        fluids.values().forEach(StartupFluid::initialize);
        event.register(ForgeRegistries.Keys.FLUID_TYPES, helper -> fluids.forEach((id, fluid) -> {
            if (ForgeRegistries.FLUID_TYPES.get().containsKey(id)) throw new IllegalArgumentException("Fluid type already registered: " + id);
            helper.register(id, fluid.type);
        }));
        event.register(ForgeRegistries.Keys.FLUIDS, helper -> fluids.forEach((id, fluid) -> {
            if (ForgeRegistries.FLUIDS.containsKey(id) || ForgeRegistries.FLUIDS.containsKey(fluid.flowingId)) throw new IllegalArgumentException("Fluid already registered: " + id);
            helper.register(id, fluid.source); helper.register(fluid.flowingId, fluid.flowing);
        }));
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> fluids.forEach((id, fluid) -> {
            if (ForgeRegistries.BLOCKS.containsKey(id)) throw new IllegalArgumentException("Fluid block already registered: " + id);
            helper.register(id, fluid.block);
        }));
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> blocks.forEach((id, properties) -> {
            if (ForgeRegistries.BLOCKS.containsKey(id)) throw new IllegalArgumentException("Block already registered: " + id);
            Block block = new Block(blockProperties(properties)); helper.register(id, block); registeredBlocks.put(id, block);
        }));
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            fluids.values().forEach(fluid -> {
                if (ForgeRegistries.ITEMS.containsKey(fluid.bucketId)) throw new IllegalArgumentException("Fluid bucket already registered: " + fluid.bucketId);
                helper.register(fluid.bucketId, fluid.bucket);
            });
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
        event.register(Registries.CREATIVE_MODE_TAB, helper -> tabs.forEach((id, tab) -> {
            if (BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(id)) throw new IllegalArgumentException("Creative tab already registered: " + id);
            var builder = CreativeModeTab.builder().title(Component.translatable(tabKey(id))).icon(() -> new ItemStack(requireItem(tab.icon())))
                    .displayItems((parameters, output) -> tab.items().forEach(item -> output.accept(requireItem(item))));
            builder.withTabsAfter(tab.before().toArray(ResourceLocation[]::new));
            builder.withTabsBefore(tab.after().toArray(ResourceLocation[]::new));
            helper.register(id, builder.build());
        }));
    }
    private static Item requireItem(ResourceLocation id) {
        if (!ForgeRegistries.ITEMS.containsKey(id) || ForgeRegistries.ITEMS.getValue(id) == Items.AIR) throw new IllegalArgumentException("Unknown creative item: " + id);
        return ForgeRegistries.ITEMS.getValue(id);
    }
    public void validateReferences() {
        tabs.forEach((id, tab) -> {
            requireItem(tab.icon()); tab.items().forEach(StartupRegistries::requireItem);
            tab.before().forEach(StartupRegistries::requireTab); tab.after().forEach(StartupRegistries::requireTab);
        });
        tabItems.forEach((id, items) -> { requireTab(id); items.forEach(StartupRegistries::requireItem); });
    }
    private static void requireTab(ResourceLocation id) { if (!BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(id)) throw new IllegalArgumentException("Unknown creative tab: " + id); }
    public void tabContents(BuildCreativeModeTabContentsEvent event) {
        var entries = tabItems.get(event.getTabKey().location());
        if (entries != null) entries.forEach(id -> event.accept(requireItem(id)));
    }
}
