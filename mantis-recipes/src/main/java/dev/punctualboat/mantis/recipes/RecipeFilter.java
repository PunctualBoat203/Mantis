package dev.punctualboat.mantis.recipes;

import com.google.gson.JsonObject;

import java.util.Set;

public record RecipeFilter(String id, String type, String mod) {
    public static RecipeFilter parse(JsonObject filter) {
        for (String key : filter.keySet()) {
            if (!Set.of("id", "type", "mod").contains(key)) throw new IllegalArgumentException("Unknown recipe filter: " + key);
        }
        return new RecipeFilter(string(filter, "id"), string(filter, "type"), string(filter, "mod"));
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsString() : null;
    }

    public boolean matches(String recipeId, JsonObject json) {
        return (id == null || id.equals(recipeId))
                && (type == null || json.has("type") && type.equals(json.get("type").getAsString()))
                && (mod == null || recipeId.startsWith(mod + ":"));
    }
}
