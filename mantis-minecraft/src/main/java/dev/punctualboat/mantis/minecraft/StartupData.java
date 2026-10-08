package dev.punctualboat.mantis.minecraft;

import com.google.gson.*;
import dev.punctualboat.mantis.core.MantisExport;
import dev.punctualboat.mantis.recipes.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.resources.IoSupplier;
import org.graalvm.polyglot.Value;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Startup data is an immutable pack after scripts finish; every resource reload reads that same snapshot. */
public final class StartupData {
    private final Map<ResourceLocation, byte[]> resources = new LinkedHashMap<>();
    private final Map<ResourceLocation, byte[]> assets = new LinkedHashMap<>();
    private int bytes;
    private boolean frozen;

    public void freeze() { frozen = true; }
    private static ResourceLocation path(String id) {
        ResourceLocation key = new ResourceLocation(RecipeValues.id(id));
        if (!key.getPath().endsWith(".json") || Arrays.stream(key.getPath().split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw new IllegalArgumentException("Data resource needs a relative JSON path, such as pack:loot_tables/chests/reward.json");
        return key;
    }
    private void put(ResourceLocation key, JsonObject json, boolean update) {
        put(resources, key, json, update);
    }
    private void put(Map<ResourceLocation, byte[]> target, ResourceLocation key, JsonObject json, boolean update) {
        if (frozen) throw new IllegalStateException("Data declarations are only available during startup loading");
        if (!update && target.containsKey(key)) throw new IllegalArgumentException("Duplicate generated resource: " + key);
        byte[] encoded = json.toString().getBytes(StandardCharsets.UTF_8);
        int total = bytes - (target.containsKey(key) ? target.get(key).length : 0) + encoded.length;
        if (encoded.length > 1_048_576 || total > 16_777_216 || !target.containsKey(key) && resources.size() + assets.size() >= 4096)
            throw new IllegalArgumentException("Generated data limit exceeded (4096 resources, 1 MiB each, 16 MiB total)");
        target.put(key, encoded); bytes = total;
    }
    private static JsonObject object(Value value) {
        JsonElement json = JsonCodec.read(value);
        if (!json.isJsonObject()) throw new IllegalArgumentException("Data resource must be a JSON object");
        return json.getAsJsonObject();
    }
    @MantisExport public void json(String path, Value value) { put(path(path), object(value), false); }
    @MantisExport public void assetJson(String path, Value value) { put(assets, path(path), object(value), true); }

    void contentAssets(ResourceLocation id, JsonObject properties, boolean block, boolean blockItem) {
        String texture = properties.has("texture") ? properties.get("texture").getAsString() : block ? "minecraft:block/stone" : "minecraft:item/paper";
        texture = RecipeValues.id(texture);
        String prefix = id.getNamespace() + ":";
        String modelId = prefix + "block/" + id.getPath();
        JsonObject model = new JsonObject(), textures = new JsonObject();
        model.addProperty("parent", block ? "minecraft:block/cube_all" : "minecraft:item/generated");
        textures.addProperty(block ? "all" : "layer0", texture); model.add("textures", textures);
        if (block) {
            put(assets, path(prefix + "models/block/" + id.getPath() + ".json"), model, false);
            JsonObject state = new JsonObject(), variants = new JsonObject(), value = new JsonObject(); value.addProperty("model", modelId); variants.add("", value); state.add("variants", variants);
            put(assets, path(prefix + "blockstates/" + id.getPath() + ".json"), state, false);
            if (blockItem) {
                JsonObject item = new JsonObject(); item.addProperty("parent", modelId);
                put(assets, path(prefix + "models/item/" + id.getPath() + ".json"), item, false);
            }
        } else put(assets, path(prefix + "models/item/" + id.getPath() + ".json"), model, false);
        if (properties.has("displayName")) {
            String name = properties.get("displayName").getAsString();
            if (name.isBlank() || name.length() > 256) throw new IllegalArgumentException("displayName must contain 1-256 characters");
            translation(id, (block ? "block." : "item.") + id.getNamespace() + "." + id.getPath().replace('/', '.'), name);
        }
    }
    void translation(ResourceLocation id, String key, String name) {
        ResourceLocation langPath = path(id.getNamespace() + ":lang/en_us.json");
        JsonObject lang = assets.containsKey(langPath) ? JsonParser.parseString(new String(assets.get(langPath), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        lang.addProperty(key, name); put(assets, langPath, lang, true);
    }
    void fluidAssets(StartupFluid fluid, JsonObject properties) {
        JsonObject bucket = new JsonObject();
        bucket.addProperty("texture", StartupRegistries.text(properties, "bucketTexture", "minecraft:item/water_bucket"));
        if (properties.has("displayName")) {
            String name = properties.get("displayName").getAsString();
            translation(fluid.id, "fluid." + fluid.id.getNamespace() + "." + fluid.id.getPath().replace('/', '.'), name);
            translation(fluid.bucketId, "item." + fluid.bucketId.getNamespace() + "." + fluid.bucketId.getPath().replace('/', '.'), name + " Bucket");
        }
        contentAssets(fluid.bucketId, bucket, false, false);
        JsonObject state = new JsonObject(); state.add("variants", new JsonObject());
        put(assets, path(fluid.id.getNamespace() + ":blockstates/" + fluid.id.getPath() + ".json"), state, false);
        ResourceLocation atlasPath = path("minecraft:atlases/blocks.json");
        JsonObject atlas = assets.containsKey(atlasPath) ? JsonParser.parseString(new String(assets.get(atlasPath), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        JsonArray sources = atlas.has("sources") ? atlas.getAsJsonArray("sources") : new JsonArray();
        for (ResourceLocation texture : List.of(fluid.stillTexture, fluid.flowingTexture)) {
            JsonObject sprite = new JsonObject(); sprite.addProperty("type", "minecraft:single"); sprite.addProperty("resource", texture.toString());
            if (!sources.contains(sprite)) sources.add(sprite);
        }
        atlas.add("sources", sources); put(assets, atlasPath, atlas, true);
    }
    @MantisExport public void lootTable(String id, Value value) {
        ResourceLocation key = new ResourceLocation(RecipeValues.id(id));
        put(path(key.getNamespace() + ":loot_tables/" + key.getPath() + ".json"), object(value), false);
    }
    @MantisExport public void tag(String folder, String id, Value entries) { tag(folder, id, entries, false); }
    @MantisExport public void tag(String folder, String id, Value entries, boolean replace) {
        if (!folder.matches("[a-z0-9_.-]+(/[a-z0-9_.-]+)*") || Arrays.stream(folder.split("/")).anyMatch(p -> p.equals(".") || p.equals("..")))
            throw new IllegalArgumentException("Tag folder must be relative, such as items, blocks, or worldgen/biome");
        ResourceLocation name = new ResourceLocation(RecipeValues.id(id));
        ResourceLocation key = path(name.getNamespace() + ":tags/" + folder + "/" + name.getPath() + ".json");
        JsonElement input = JsonCodec.read(entries);
        if (!input.isJsonArray()) throw new IllegalArgumentException("Tag values must be an array");
        JsonArray values = new JsonArray();
        for (JsonElement entry : input.getAsJsonArray()) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) values.add(tagId(entry.getAsString()));
            else if (entry.isJsonObject()) {
                JsonObject e = entry.getAsJsonObject();
                if (!e.has("id") || !e.get("id").isJsonPrimitive() || !e.getAsJsonPrimitive("id").isString()
                        || e.keySet().stream().anyMatch(k -> !Set.of("id", "required").contains(k))
                        || e.has("required") && (!e.get("required").isJsonPrimitive() || !e.getAsJsonPrimitive("required").isBoolean()))
                    throw new IllegalArgumentException("Tag entry needs id and an optional required boolean");
                JsonObject copy = e.deepCopy(); copy.addProperty("id", tagId(copy.get("id").getAsString())); values.add(copy);
            } else throw new IllegalArgumentException("Tag entry must be an ID or an object with id and required");
        }
        if (!replace && resources.containsKey(key)) {
            JsonObject previous = JsonParser.parseString(new String(resources.get(key), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray merged = previous.getAsJsonArray("values").deepCopy();
            for (JsonElement entry : values) if (!merged.contains(entry)) merged.add(entry);
            values = merged; replace = previous.has("replace") && previous.get("replace").getAsBoolean();
        }
        JsonObject result = new JsonObject(); result.addProperty("replace", replace); result.add("values", values);
        put(key, result, true);
    }
    private static String tagId(String id) { return id.startsWith("#") ? "#" + RecipeValues.id(id.substring(1)) : RecipeValues.id(id); }

    public PackResources open(String id, PackType type) {
        if (!frozen) throw new IllegalStateException("Startup data has not finished loading");
        return new Resources(id, type, Map.copyOf(type == PackType.SERVER_DATA ? resources : assets));
    }
    private static final class Resources extends AbstractPackResources {
        private static final byte[] META = "{\"pack\":{\"pack_format\":15,\"description\":\"Mantis startup data\"}}".getBytes(StandardCharsets.UTF_8);
        private final Map<ResourceLocation, byte[]> entries;
        private final Set<String> namespaces;
        private final PackType type;
        Resources(String id, PackType type, Map<ResourceLocation, byte[]> entries) {
            super(id, false); this.type = type; this.entries = entries;
            Set<String> names = new HashSet<>(); entries.keySet().forEach(key -> names.add(key.getNamespace())); namespaces = Set.copyOf(names);
        }
        @Override public IoSupplier<InputStream> getRootResource(String... path) {
            return path.length == 1 && path[0].equals("pack.mcmeta") ? () -> new ByteArrayInputStream(META) : null;
        }
        @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
            byte[] bytes = type == this.type ? entries.get(id) : null;
            return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
        }
        @Override public void listResources(PackType type, String namespace, String path, PackResources.ResourceOutput output) {
            if (type != this.type) return;
            String prefix = path.isEmpty() ? "" : path + "/";
            entries.forEach((key, bytes) -> {
                if (key.getNamespace().equals(namespace) && key.getPath().startsWith(prefix)) output.accept(key, () -> new ByteArrayInputStream(bytes));
            });
        }
        @Override public Set<String> getNamespaces(PackType type) { return type == this.type ? namespaces : Set.of(); }
        @Override public void close() {}
    }
}
