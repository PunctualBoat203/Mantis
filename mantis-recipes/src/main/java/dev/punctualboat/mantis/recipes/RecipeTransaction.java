package dev.punctualboat.mantis.recipes;

import com.google.gson.*;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public final class RecipeTransaction {
    private final Map<String, JsonObject> staged = new TreeMap<>();
    private final Set<String> changed = new LinkedHashSet<>();
    private boolean committed;

    public RecipeTransaction(Map<String, JsonObject> original) {
        original.forEach((id, json) -> staged.put(id, json.deepCopy()));
    }

    private void checkOpen() { if (committed) throw new IllegalStateException("Recipe transaction is closed"); }

    public int remove(RecipeFilter filter) {
        checkOpen();
        List<String> ids = matching(filter);
        ids.forEach(id -> { staged.remove(id); changed.add(id); });
        return ids.size();
    }

    public void custom(String id, JsonObject recipe) {
        checkOpen();
        validateId(id);
        validateShape(recipe);
        staged.put(id, recipe.deepCopy());
        changed.add(id);
    }

    public void replace(String id, JsonObject recipe) {
        checkOpen();
        if (!staged.containsKey(id)) throw new IllegalArgumentException("Unknown recipe: " + id);
        custom(id, recipe);
    }

    public int patch(RecipeFilter filter, BiFunction<String, JsonObject, JsonObject> transform) {
        checkOpen();
        List<String> ids = matching(filter);
        Map<String, JsonObject> replacements = new LinkedHashMap<>();
        for (String id : ids) {
            JsonObject replacement = Objects.requireNonNull(transform.apply(id, staged.get(id).deepCopy()), "Recipe patch must return an object");
            validateShape(replacement);
            replacements.put(id, replacement.deepCopy());
        }
        replacements.forEach((id, json) -> { staged.put(id, json); changed.add(id); });
        return ids.size();
    }

    public int set(RecipeFilter filter, String pointer, JsonElement value) {
        return patch(filter, (id, json) -> { JsonPointer.set(json, pointer, value); return json; });
    }

    public List<String> matching(RecipeFilter filter) {
        checkOpen();
        return staged.entrySet().stream().filter(entry -> filter.matches(entry.getKey(), entry.getValue())).map(Map.Entry::getKey).toList();
    }

    public JsonObject get(String id) {
        checkOpen();
        JsonObject json = staged.get(id);
        if (json == null) throw new IllegalArgumentException("Unknown recipe: " + id);
        return json.deepCopy();
    }

    public Map<String, JsonObject> commit(BiConsumer<String, JsonObject> validator) {
        checkOpen();
        for (String id : changed) if (staged.containsKey(id)) validator.accept(id, staged.get(id).deepCopy());
        Map<String, JsonObject> result = new TreeMap<>();
        staged.forEach((id, json) -> result.put(id, json.deepCopy()));
        committed = true;
        return result;
    }

    public Set<String> changedIds() { return Set.copyOf(changed); }

    private static void validateShape(JsonObject recipe) {
        JsonElement type = recipe.get("type");
        if (type == null || !type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Recipe needs a serializer type string");
        validateId(type.getAsString());
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid resource id: " + id);
    }
}
