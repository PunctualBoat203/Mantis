package dev.punctualboat.mantis.recipes;

import com.google.gson.*;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public final class RecipeTransaction {
    private final Map<String, JsonObject> staged = new TreeMap<>();
    private final Set<String> changed = new LinkedHashSet<>();
    private boolean committed;
    private final RecipeSchemas schemas;
    private final RecipeMatcher.TagLookup tags;

    public RecipeTransaction(Map<String, JsonObject> original) { this(original, false); }

    private RecipeTransaction(Map<String, JsonObject> original, boolean borrow) { this(original, borrow, new RecipeSchemas(), (tag, item) -> false); }
    private RecipeTransaction(Map<String, JsonObject> original, boolean borrow, RecipeSchemas schemas, RecipeMatcher.TagLookup tags) {
        this.schemas = Objects.requireNonNull(schemas); this.tags = Objects.requireNonNull(tags);
        original.forEach((id, json) -> staged.put(id, borrow ? json : json.deepCopy()));
    }

    /** The caller owns the source JSON and must keep it unchanged until commit. Writes and reads remain detached. */
    public static RecipeTransaction borrowing(Map<String, JsonObject> original) { return new RecipeTransaction(original, true); }
    public static RecipeTransaction borrowing(Map<String, JsonObject> original, RecipeSchemas schemas, RecipeMatcher.TagLookup tags) { return new RecipeTransaction(original, true, schemas, tags); }

    public int replaceValues(RecipeFilter filter, JsonElement from, JsonElement to, String role) {
        checkOpen();
        if (!Set.of("input", "output").contains(role)) throw new IllegalArgumentException("Invalid recipe role");
        JsonElement selector = RecipeMatcher.selector(from);
        JsonElement replacement;
        if (to.isJsonObject() && to.getAsJsonObject().has("fluid")) replacement = RecipeValues.fluid(to);
        else replacement = role.equals("input") ? RecipeValues.ingredient(to) : RecipeValues.stack(to);
        if (!replacement.isJsonObject()) throw new IllegalArgumentException("Replacement must be one item, tag, or fluid");
        if (to.isJsonPrimitive() && replacement.getAsJsonObject().has("count")) replacement.getAsJsonObject().remove("count");
        Map<String, JsonObject> updates = new LinkedHashMap<>();
        for (String id : matching(filter)) if (RecipeMatcher.matchesRole(staged.get(id), role, selector, schemas, tags)) {
            JsonObject copy = staged.get(id).deepCopy(); RecipeMatcher.replaceRole(copy, role, selector, replacement, schemas, tags); validateShape(copy); updates.put(id, copy);
        }
        updates.forEach((id, json) -> { staged.put(id, json); changed.add(id); });
        return updates.size();
    }

    public record Changes(Map<String, JsonObject> replacements, Set<String> removed) {
        public Changes { replacements = Collections.unmodifiableMap(new TreeMap<>(replacements)); removed = Set.copyOf(removed); }
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
        if (filter.id() != null) {
            JsonObject json = staged.get(filter.id());
            return json != null && filter.matches(filter.id(), json, schemas, tags) ? List.of(filter.id()) : List.of();
        }
        return staged.entrySet().stream().filter(entry -> filter.matches(entry.getKey(), entry.getValue(), schemas, tags)).map(Map.Entry::getKey).toList();
    }

    public JsonObject get(String id) {
        checkOpen();
        JsonObject json = staged.get(id);
        if (json == null) throw new IllegalArgumentException("Unknown recipe: " + id);
        return json.deepCopy();
    }

    public Map<String, JsonObject> commit(BiConsumer<String, JsonObject> validator) {
        checkOpen();
        validateChanges(validator);
        Map<String, JsonObject> result = new TreeMap<>();
        staged.forEach((id, json) -> result.put(id, json.deepCopy()));
        committed = true;
        return result;
    }

    public Changes commitChanges(BiConsumer<String, JsonObject> validator) {
        checkOpen();
        validateChanges(validator);
        Map<String, JsonObject> replacements = new TreeMap<>();
        Set<String> removed = new LinkedHashSet<>();
        for (String id : changed) {
            JsonObject json = staged.get(id);
            if (json == null) removed.add(id);
            else replacements.put(id, json.deepCopy());
        }
        committed = true;
        return new Changes(replacements, removed);
    }

    private void validateChanges(BiConsumer<String, JsonObject> validator) {
        for (String id : changed) if (staged.containsKey(id)) validator.accept(id, staged.get(id).deepCopy());
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
