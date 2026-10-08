package dev.punctualboat.mantis.recipes;

import com.google.gson.*;

public final class RecipeValues {
    private RecipeValues() {}
    public static String id(String text) {
        String id = text.contains(":") ? text : "minecraft:" + text;
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid resource id: " + text);
        return id;
    }
    public static JsonObject item(String id, int count) {
        if (count < 1) throw new IllegalArgumentException("Item count must be positive");
        JsonObject json = new JsonObject(); json.addProperty("item", id(id)); json.addProperty("count", count); return json;
    }
    public static JsonObject tag(String id) {
        JsonObject json = new JsonObject(); json.addProperty("tag", id(id.startsWith("#") ? id.substring(1) : id)); return json;
    }
    public static JsonObject fluid(String id, int amount) {
        if (amount < 1) throw new IllegalArgumentException("Fluid amount must be positive");
        JsonObject json = new JsonObject(); json.addProperty("fluid", id(id)); json.addProperty("amount", amount); return json;
    }
    public static JsonElement fluid(JsonElement value) {
        if (!value.isJsonObject() || !value.getAsJsonObject().has("fluid") || !value.getAsJsonObject().has("amount")) throw new IllegalArgumentException("Fluid needs fluid and amount");
        JsonObject result = value.deepCopy().getAsJsonObject();
        if (result.has("item") || result.has("tag") || !result.get("fluid").isJsonPrimitive() || !result.getAsJsonPrimitive("fluid").isString()) throw new IllegalArgumentException("Fluid needs one fluid ID");
        result.addProperty("fluid", id(result.get("fluid").getAsString())); result.addProperty("amount", positiveInt(result.get("amount"))); return result;
    }
    public static JsonElement ingredient(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString();
            if (text.startsWith("#")) return tag(text);
            JsonObject json = new JsonObject(); json.addProperty("item", id(text)); return json;
        }
        if (value.isJsonArray()) {
            if (value.getAsJsonArray().isEmpty()) throw new IllegalArgumentException("Ingredient alternatives cannot be empty");
            JsonArray result = new JsonArray(); value.getAsJsonArray().forEach(v -> result.add(ingredient(v))); return result;
        }
        if (!value.isJsonObject()) throw new IllegalArgumentException("Ingredient needs an item, tag, alternatives, or custom object");
        JsonObject result = value.deepCopy().getAsJsonObject();
        if (result.has("item") && result.has("tag")) throw new IllegalArgumentException("Ingredient cannot contain both item and tag");
        if (!result.has("item") && !result.has("tag") && !result.has("type")) throw new IllegalArgumentException("Custom ingredient needs a type");
        for (String key : new String[]{"item", "tag", "type"}) if (result.has(key)) result.addProperty(key, id(result.get(key).getAsString()));
        return result;
    }
    public static JsonElement stack(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return item(value.getAsString(), 1);
        if (!value.isJsonObject() || !value.getAsJsonObject().has("item")) throw new IllegalArgumentException("Output needs an item stack");
        JsonObject result = value.deepCopy().getAsJsonObject();
        result.addProperty("item", id(result.get("item").getAsString()));
        int count = result.has("count") ? positiveInt(result.get("count")) : 1;
        result.addProperty("count", count); return result;
    }
    public static int positiveInt(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected a positive integer");
        try { int number = value.getAsBigDecimal().intValueExact(); if (number > 0) return number; }
        catch (ArithmeticException ignored) {}
        throw new IllegalArgumentException("Expected a positive integer within the int range");
    }
}
