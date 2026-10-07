package dev.punctualboat.mantis.recipes;

import com.google.gson.*;

public final class JsonPointer {
    private JsonPointer() {}

    public static void set(JsonObject root, String pointer, JsonElement value) {
        if (pointer == null || !pointer.startsWith("/") || pointer.length() < 2) {
            throw new IllegalArgumentException("Expected a non-root JSON pointer, such as /result/count");
        }
        String[] keys = pointer.substring(1).split("/", -1);
        JsonElement parent = root;
        for (int index = 0; index < keys.length - 1; index++) {
            parent = get(parent, unescape(keys[index]));
        }
        String key = unescape(keys[keys.length - 1]);
        if (parent.isJsonObject()) parent.getAsJsonObject().add(key, value.deepCopy());
        else if (parent.isJsonArray()) parent.getAsJsonArray().set(index(key, parent.getAsJsonArray().size()), value.deepCopy());
        else throw new IllegalArgumentException("JSON pointer parent is not an object or array: " + pointer);
    }

    private static JsonElement get(JsonElement parent, String key) {
        JsonElement value;
        if (parent.isJsonObject()) value = parent.getAsJsonObject().get(key);
        else if (parent.isJsonArray()) value = parent.getAsJsonArray().get(index(key, parent.getAsJsonArray().size()));
        else throw new IllegalArgumentException("JSON pointer traverses a primitive at " + key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("JSON pointer path does not exist: " + key);
        return value;
    }

    private static int index(String key, int size) {
        if (!key.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid array index: " + key);
        int index = Integer.parseInt(key);
        if (index >= size) throw new IllegalArgumentException("Array index is outside the recipe: " + index);
        return index;
    }

    private static String unescape(String key) {
        if (key.matches(".*~(?![01]).*")) throw new IllegalArgumentException("Invalid JSON pointer escape: " + key);
        return key.replace("~1", "/").replace("~0", "~");
    }
}
