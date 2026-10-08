package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import java.util.*;

public record RecipeFilter(String id, String type, String mod, JsonElement input, JsonElement output,
                           List<RecipeFilter> any, List<RecipeFilter> all, RecipeFilter not) {
    private static final RecipeSchemas DEFAULT = new RecipeSchemas().snapshot();
    public RecipeFilter(String id, String type, String mod) { this(id, type, mod, null, null, List.of(), List.of(), null); }
    public RecipeFilter {
        input = input == null ? null : RecipeMatcher.selector(input);
        output = output == null ? null : RecipeMatcher.selector(output);
        any = List.copyOf(any); all = List.copyOf(all);
    }
    public static RecipeFilter parse(JsonObject filter) { return parse(filter, 0); }
    private static RecipeFilter parse(JsonObject filter, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Recipe filter nesting exceeds 16");
        for (String key : filter.keySet()) if (!Set.of("id", "type", "mod", "input", "output", "any", "all", "not").contains(key)) throw new IllegalArgumentException("Unknown recipe filter: " + key);
        return new RecipeFilter(string(filter, "id"), string(filter, "type"), string(filter, "mod"), filter.get("input"), filter.get("output"),
                children(filter, "any", depth), children(filter, "all", depth), filter.has("not") ? parse(filter.getAsJsonObject("not"), depth + 1) : null);
    }
    private static List<RecipeFilter> children(JsonObject value, String key, int depth) {
        if (!value.has(key)) return List.of();
        if (!value.get(key).isJsonArray() || value.getAsJsonArray(key).isEmpty()) throw new IllegalArgumentException(key + " needs a nonempty array of filters");
        return value.getAsJsonArray(key).asList().stream().map(v -> parse(v.getAsJsonObject(), depth + 1)).toList();
    }
    private static String string(JsonObject object, String name) {
        if (!object.has(name)) return null;
        if (!object.get(name).isJsonPrimitive() || !object.getAsJsonPrimitive(name).isString()) throw new IllegalArgumentException("Filter " + name + " needs a string");
        return object.get(name).getAsString();
    }
    public boolean matches(String recipeId, JsonObject json) { return matches(recipeId, json, DEFAULT, (tag, item) -> false); }
    public boolean matches(String recipeId, JsonObject json, RecipeSchemas schemas, RecipeMatcher.TagLookup tags) {
        return (id == null || id.equals(recipeId))
                && (type == null || json.has("type") && type.equals(json.get("type").getAsString()))
                && (mod == null || recipeId.length() > mod.length() && recipeId.charAt(mod.length()) == ':' && recipeId.startsWith(mod))
                && (input == null || RecipeMatcher.matchesRole(json, "input", input, schemas, tags))
                && (output == null || RecipeMatcher.matchesRole(json, "output", output, schemas, tags))
                && (any.isEmpty() || any.stream().anyMatch(f -> f.matches(recipeId, json, schemas, tags)))
                && all.stream().allMatch(f -> f.matches(recipeId, json, schemas, tags))
                && (not == null || !not.matches(recipeId, json, schemas, tags));
    }
}
