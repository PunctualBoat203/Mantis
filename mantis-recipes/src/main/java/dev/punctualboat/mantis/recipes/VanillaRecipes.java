package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import java.util.*;

public final class VanillaRecipes {
    private VanillaRecipes() {}
    public static RecipeSchemas schemas() {
        RecipeSchemas schemas = new RecipeSchemas();
        schemas.component("item_id", value -> {
            JsonObject stack = RecipeValues.stack(value).getAsJsonObject();
            if (stack.get("count").getAsInt() != 1) throw new IllegalArgumentException("Cooking output count must be one");
            return stack.get("item").deepCopy();
        });
        schemas.component("ingredient_key", value -> {
            if (!value.isJsonObject()) throw new IllegalArgumentException("Shaped key needs an object");
            JsonObject result = new JsonObject();
            for (var e : value.getAsJsonObject().entrySet()) {
                if (e.getKey().length() != 1 || e.getKey().equals(" ")) throw new IllegalArgumentException("Recipe symbols must be single non-space characters");
                result.add(e.getKey(), RecipeValues.ingredient(e.getValue()));
            }
            return result;
        });
        schemas.component("pattern", value -> {
            if (!value.isJsonArray() || value.getAsJsonArray().isEmpty() || value.getAsJsonArray().size() > 3) throw new IllegalArgumentException("Pattern needs one to three rows");
            int width = -1;
            for (JsonElement row : value.getAsJsonArray()) {
                if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Pattern rows must be strings");
                int length = row.getAsString().length();
                if (length < 1 || length > 3 || width != -1 && width != length) throw new IllegalArgumentException("Pattern must be rectangular and at most 3x3");
                width = length;
            }
            return value.deepCopy();
        });
        schemas.register("minecraft:crafting_shaped", definition("pattern", "pattern", null, "key", "ingredient_key", "input", "result", "item", "output"));
        schemas.register("minecraft:crafting_shapeless", definition("ingredients", "ingredients", "input", "result", "item", "output"));
        for (String type : List.of("smelting", "blasting", "smoking", "campfire_cooking")) {
            JsonObject def = definition("ingredient", "ingredient", "input", "result", "item_id", "output");
            JsonObject fields = def.getAsJsonObject("fields");
            fields.add("experience", field("/experience", "number", null)); fields.getAsJsonObject("experience").addProperty("default", 0);
            fields.add("cookingtime", field("/cookingtime", "positive_int", null)); fields.getAsJsonObject("cookingtime").addProperty("default", type.equals("campfire_cooking") ? 600 : type.equals("smelting") ? 200 : 100);
            schemas.register("minecraft:" + type, def);
        }
        JsonObject stone = definition("ingredient", "ingredient", "input", "result", "item_id", "output", "count", "positive_int", null);
        stone.getAsJsonObject("fields").getAsJsonObject("count").addProperty("default", 1); schemas.register("minecraft:stonecutting", stone);
        schemas.register("minecraft:smithing_transform", definition("template", "ingredient", "input", "base", "ingredient", "input", "addition", "ingredient", "input", "result", "item", "output"));
        schemas.register("minecraft:smithing_trim", definition("template", "ingredient", "input", "base", "ingredient", "input", "addition", "ingredient", "input"));
        return schemas;
    }
    private static JsonObject definition(String... fields) {
        JsonObject result = new JsonObject(), values = new JsonObject();
        for (int i = 0; i < fields.length; i += 3) values.add(fields[i], field("/" + fields[i], fields[i+1], fields[i+2]));
        JsonObject group = field("/group", "string", null); group.addProperty("optional", true); values.add("group", group);
        result.add("fields", values); return result;
    }
    private static JsonObject field(String path, String kind, String role) {
        JsonObject result = new JsonObject(); result.addProperty("path", path); result.addProperty("kind", kind);
        if (role != null) result.addProperty("role", role); return result;
    }
    public static void validate(JsonObject json) {
        String type = json.get("type").getAsString();
        if (type.equals("minecraft:crafting_shaped")) {
            Set<String> used = new HashSet<>();
            for (JsonElement row : json.getAsJsonArray("pattern")) for (char c : row.getAsString().toCharArray()) if (c != ' ') used.add(String.valueOf(c));
            if (used.isEmpty() || !used.equals(json.getAsJsonObject("key").keySet())) throw new IllegalArgumentException("Pattern symbols must exactly match the recipe key");
        }
        if (type.equals("minecraft:crafting_shapeless") && json.getAsJsonArray("ingredients").size() > 9) throw new IllegalArgumentException("Shapeless recipes allow at most nine ingredients");
        if (json.has("experience") && json.get("experience").getAsDouble() < 0) throw new IllegalArgumentException("Experience cannot be negative");
    }
}
