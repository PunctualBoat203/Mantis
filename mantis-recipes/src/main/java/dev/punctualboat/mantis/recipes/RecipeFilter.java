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

    /** Equivalent to {@code recipeId.startsWith(mod + ":")} without allocating a string per recipe. */
    private static boolean inNamespace(String recipeId, String mod) {
        return recipeId.length() > mod.length() && recipeId.charAt(mod.length()) == ':' && recipeId.startsWith(mod);
    }

    public boolean matches(String recipeId, JsonObject json) {
        return (id == null || id.equals(recipeId))
                && (type == null || json.has("type") && type.equals(json.get("type").getAsString()))
                && (mod == null || inNamespace(recipeId, mod));
    }
}
