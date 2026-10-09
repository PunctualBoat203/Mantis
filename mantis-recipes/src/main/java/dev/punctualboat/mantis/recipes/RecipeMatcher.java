package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import java.util.*;

public final class RecipeMatcher {
    @FunctionalInterface public interface TagLookup { boolean contains(String tag, String item); }
    private RecipeMatcher() {}
    public static JsonElement selector(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            if (text.startsWith("#")) return RecipeValues.tag(text);
            JsonObject result = new JsonObject(); result.addProperty("item", RecipeValues.id(text)); return result;
        }
        if (!value.isJsonObject() || value.getAsJsonObject().size() != 1) throw new IllegalArgumentException("Selector needs one item, tag, or fluid");
        String key = value.getAsJsonObject().keySet().iterator().next();
        if (!Set.of("item", "tag", "fluid").contains(key)) throw new IllegalArgumentException("Unknown selector: " + key);
        JsonObject result = new JsonObject(); result.addProperty(key, RecipeValues.id(value.getAsJsonObject().get(key).getAsString())); return result;
    }
    static JsonElement at(JsonObject root, String pointer) {
        JsonElement current = root;
        for (String token : pointer.substring(1).split("/")) {
            String key = token.replace("~1", "/").replace("~0", "~");
            if (current == null) return null;
            if (current.isJsonObject()) current = current.getAsJsonObject().get(key);
            else if (current.isJsonArray() && key.matches("0|[1-9][0-9]*")) {
                try { int index = Integer.parseInt(key); if (index >= current.getAsJsonArray().size()) return null; current = current.getAsJsonArray().get(index); }
                catch (NumberFormatException error) { return null; }
            } else return null;
        }
        return current;
    }
    /** The recipe's serializer id, or null when "type" is missing or not a string (such recipes are skipped by vanilla too). */
    static String serializer(JsonObject json) {
        JsonElement type = json.get("type");
        return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString() ? type.getAsString() : null;
    }
    public static boolean matchesRole(JsonObject json, String role, JsonElement selector, RecipeSchemas schemas, TagLookup tags) {
        String type = serializer(json);
        if (type == null) return false;
        for (String path : schemas.paths(type, role)) if (matches(at(json, path), selector.getAsJsonObject(), tags, 0)) return true;
        return false;
    }
    private static boolean atom(JsonElement candidate, JsonObject selector, TagLookup tags) {
        if (candidate == null || candidate.isJsonNull()) return false;
        if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()) {
            String text = candidate.getAsString();
            return selector.has("item") && selector.get("item").getAsString().equals(text)
                    || selector.has("tag") && (text.equals("#" + selector.get("tag").getAsString()) || tags.contains(selector.get("tag").getAsString(), text));
        }
        if (!candidate.isJsonObject()) return false;
        JsonObject c = candidate.getAsJsonObject();
        for (String key : List.of("item", "tag", "fluid")) if (c.has(key) && selector.has(key) && c.get(key).equals(selector.get(key))) return true;
        return c.has("item") && selector.has("tag") && tags.contains(selector.get("tag").getAsString(), c.get("item").getAsString())
                || c.has("tag") && selector.has("item") && tags.contains(c.get("tag").getAsString(), selector.get("item").getAsString());
    }
    private static boolean leaf(JsonObject object) { return object.has("item") || object.has("tag") || object.has("fluid"); }
    private static boolean matches(JsonElement value, JsonObject selector, TagLookup tags, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Recipe ingredient nesting exceeds 64");
        if (atom(value, selector, tags)) return true;
        if (value == null) return false;
        if (value.isJsonArray()) { for (JsonElement child : value.getAsJsonArray()) if (matches(child, selector, tags, depth + 1)) return true; }
        else if (value.isJsonObject() && !leaf(value.getAsJsonObject())) for (var e : value.getAsJsonObject().entrySet()) if (!Set.of("nbt", "conditions", "type").contains(e.getKey()) && matches(e.getValue(), selector, tags, depth + 1)) return true;
        return false;
    }
    public static void replaceRole(JsonObject json, String role, JsonElement from, JsonElement replacement, RecipeSchemas schemas, TagLookup tags) {
        String type = serializer(json);
        if (type == null) return;
        for (String path : schemas.paths(type, role)) {
            JsonElement original = at(json, path);
            if (original != null) JsonPointer.set(json, path, replace(original, from.getAsJsonObject(), replacement, tags, 0));
        }
    }
    private static JsonElement replace(JsonElement value, JsonObject from, JsonElement replacement, TagLookup tags, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Recipe ingredient nesting exceeds 64");
        if (atom(value, from, tags)) {
            JsonObject target = replacement.getAsJsonObject();
            if (value.isJsonPrimitive()) {
                if (target.has("count") && target.get("count").getAsInt() != 1) throw new IllegalArgumentException("String outputs cannot represent multiple items");
                String key = target.has("item") ? "item" : target.has("tag") ? "tag" : "fluid";
                return new JsonPrimitive((key.equals("tag") ? "#" : "") + target.get(key).getAsString());
            }
            JsonObject result = value.deepCopy().getAsJsonObject();
            for (String key : List.of("item", "tag", "fluid")) result.remove(key);
            target.entrySet().forEach(e -> result.add(e.getKey(), e.getValue().deepCopy())); return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(replace(v, from, replacement, tags, depth + 1))); return result;
        }
        if (value.isJsonObject() && !leaf(value.getAsJsonObject())) {
            JsonObject result = value.deepCopy().getAsJsonObject();
            for (var e : value.getAsJsonObject().entrySet()) if (!Set.of("nbt", "conditions", "type").contains(e.getKey())) result.add(e.getKey(), replace(e.getValue(), from, replacement, tags, depth + 1));
            return result;
        }
        return value.deepCopy();
    }
}
