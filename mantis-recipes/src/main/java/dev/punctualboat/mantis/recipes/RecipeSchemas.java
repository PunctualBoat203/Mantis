package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import java.util.*;
import java.util.function.UnaryOperator;

public final class RecipeSchemas {
    private final Map<String, UnaryOperator<JsonElement>> components = new LinkedHashMap<>();
    private final Map<String, JsonObject> schemas = new LinkedHashMap<>();
    private boolean frozen;

    public RecipeSchemas() {
        component("json", JsonElement::deepCopy);
        component("ingredient", RecipeValues::ingredient);
        component("item", RecipeValues::stack);
        component("ingredients", value -> {
            if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) throw new IllegalArgumentException("Expected nonempty ingredient array");
            JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(RecipeValues.ingredient(v))); return result;
        });
        component("items", value -> {
            if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) throw new IllegalArgumentException("Expected nonempty item array");
            JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(RecipeValues.stack(v))); return result;
        });
        component("positive_int", value -> new JsonPrimitive(RecipeValues.positiveInt(value)));
        component("number", value -> {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw new IllegalArgumentException("Expected a finite number");
            return value.deepCopy();
        });
        component("string", value -> {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected a string"); return value.deepCopy();
        });
        component("fluid", RecipeValues::fluid);
    }
    public void component(String name, UnaryOperator<JsonElement> converter) {
        mutable(); if (name == null || !name.matches("[a-z0-9_:.-]+") || components.putIfAbsent(name, Objects.requireNonNull(converter)) != null) throw new IllegalArgumentException("Duplicate or invalid recipe component: " + name);
    }
    private void mutable() { if (frozen) throw new IllegalStateException("Recipe schemas are frozen"); }
    public void register(String type, JsonObject definition) {
        mutable(); type = RecipeValues.id(type);
        JsonObject copy = definition.deepCopy();
        if (!copy.has("fields") || !copy.get("fields").isJsonObject()) throw new IllegalArgumentException("Schema needs a fields object");
        if (copy.keySet().stream().anyMatch(k -> !Set.of("fields", "template").contains(k)) || copy.has("template") && !copy.get("template").isJsonObject())
            throw new IllegalArgumentException("Schema accepts fields and an optional template object");
        List<String> paths = new ArrayList<>();
        for (var e : copy.getAsJsonObject("fields").entrySet()) {
            if (e.getKey().isBlank() || !e.getValue().isJsonObject()) throw new IllegalArgumentException("Schema fields need names and object definitions");
            JsonObject field = e.getValue().getAsJsonObject();
            if (field.keySet().stream().anyMatch(k -> !Set.of("kind", "path", "role", "default", "optional").contains(k))) throw new IllegalArgumentException("Unknown schema field option: " + e.getKey());
            if (!field.has("kind") || !components.containsKey(field.get("kind").getAsString())) throw new IllegalArgumentException("Unknown component for " + e.getKey());
            if (!field.has("path") || !field.get("path").getAsString().matches("(/[a-zA-Z_~][a-zA-Z0-9_~.-]*)+")) throw new IllegalArgumentException("Field needs an object-field JSON pointer path");
            String path = field.get("path").getAsString();
            if (path.matches(".*~(?![01]).*")) throw new IllegalArgumentException("JSON pointer escapes must be ~0 or ~1");
            if (path.equals("/type") || path.startsWith("/type/")) throw new IllegalArgumentException("Schema cannot replace its serializer type");
            for (String existing : paths) if (path.equals(existing) || path.startsWith(existing + "/") || existing.startsWith(path + "/"))
                throw new IllegalArgumentException("Schema field paths overlap: " + path + " and " + existing);
            paths.add(path);
            if (field.has("role") && !Set.of("input", "output").contains(field.get("role").getAsString())) throw new IllegalArgumentException("Role must be input or output");
            if (field.has("optional") && (!field.get("optional").isJsonPrimitive() || !field.getAsJsonPrimitive("optional").isBoolean())) throw new IllegalArgumentException("optional must be boolean");
            if (field.has("default")) components.get(field.get("kind").getAsString()).apply(field.get("default").deepCopy());
        }
        if (schemas.putIfAbsent(type, copy) != null) throw new IllegalArgumentException("Duplicate recipe schema: " + type);
    }
    public RecipeSchemas snapshot() {
        RecipeSchemas copy = copy(); copy.frozen = true; return copy;
    }
    public RecipeSchemas copy() {
        RecipeSchemas copy = new RecipeSchemas(); copy.components.clear(); copy.components.putAll(components);
        schemas.forEach((id, definition) -> copy.schemas.put(id, definition.deepCopy())); return copy;
    }
    public List<String> types() { return List.copyOf(schemas.keySet()); }
    public JsonObject build(String type, JsonObject values) {
        type = RecipeValues.id(type); JsonObject schema = schemas.get(type);
        if (schema == null) throw new IllegalArgumentException("No recipe schema registered for " + type);
        JsonObject fields = schema.getAsJsonObject("fields");
        for (String key : values.keySet()) if (!fields.has(key)) throw new IllegalArgumentException("Unknown recipe field: " + key);
        JsonObject result = schema.has("template") ? schema.getAsJsonObject("template").deepCopy() : new JsonObject();
        result.addProperty("type", type);
        for (var e : fields.entrySet()) {
            JsonObject field = e.getValue().getAsJsonObject(); JsonElement value = values.get(e.getKey());
            if (value == null) value = field.get("default");
            if (value == null) {
                if (field.has("optional") && field.get("optional").getAsBoolean()) continue;
                throw new IllegalArgumentException("Missing recipe field: " + e.getKey());
            }
            put(result, field.get("path").getAsString(), Objects.requireNonNull(components.get(field.get("kind").getAsString()).apply(value.deepCopy())));
        }
        VanillaRecipes.validate(result); return result;
    }
    static void put(JsonObject root, String pointer, JsonElement value) {
        String[] keys = pointer.substring(1).split("/"); JsonObject parent = root;
        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i].replace("~1", "/").replace("~0", "~");
            if (!parent.has(key)) parent.add(key, new JsonObject());
            parent = parent.getAsJsonObject(key);
        }
        parent.add(keys[keys.length - 1].replace("~1", "/").replace("~0", "~"), value.deepCopy());
    }
    public List<String> paths(String type, String role) {
        JsonObject schema = schemas.get(type);
        if (schema == null) return role.equals("input") ? List.of("/ingredients", "/ingredient", "/key", "/input", "/inputs", "/base", "/addition", "/template", "/catalyst", "/activation_item") : List.of("/result", "/results", "/output", "/outputs");
        return schema.getAsJsonObject("fields").entrySet().stream().map(e -> e.getValue().getAsJsonObject()).filter(f -> f.has("role") && role.equals(f.get("role").getAsString())).map(f -> f.get("path").getAsString()).toList();
    }
}
