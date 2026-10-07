package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import dev.punctualboat.mantis.core.*;
import org.graalvm.polyglot.Value;

import java.util.function.Supplier;

public final class RecipesApi {
    private final Supplier<MantisContext> context;
    private RecipeTransaction transaction;

    public RecipesApi(Supplier<MantisContext> context) { this.context = context; }

    public void begin(RecipeTransaction transaction) {
        if (this.transaction != null) throw new IllegalStateException("Recipe event is already running");
        this.transaction = transaction;
    }
    public void end() { transaction = null; }
    private RecipeTransaction active() {
        if (transaction == null) throw new IllegalStateException("Recipe edits must run inside events.on('recipes', ...)");
        return transaction;
    }
    private JsonElement json(Value value) { return JsonParser.parseString(context.get().toJson(value)); }
    private JsonObject object(Value value) {
        JsonElement json = json(value);
        if (!json.isJsonObject()) throw new IllegalArgumentException("Expected a JSON object");
        return json.getAsJsonObject();
    }
    private RecipeFilter filter(Value filter) { return RecipeFilter.parse(object(filter)); }

    @MantisExport public int remove(Value filter) { return active().remove(filter(filter)); }
    @MantisExport public void custom(String id, Value recipe) { active().custom(id, object(recipe)); }
    @MantisExport public void replace(String id, Value recipe) { active().replace(id, object(recipe)); }
    @MantisExport public Value get(String id) { return context.get().parseJson(active().get(id).toString()); }
    @MantisExport public Value ids(Value filter) {
        JsonArray array = new JsonArray();
        active().matching(filter(filter)).forEach(array::add);
        return context.get().parseJson(array.toString());
    }
    @MantisExport public int set(Value filter, String pointer, Value value) { return active().set(filter(filter), pointer, json(value)); }
    @MantisExport public int patch(Value filter, Value callback) {
        if (!callback.canExecute()) throw new IllegalArgumentException("Recipe patch must be a function");
        return active().patch(filter(filter), (id, json) -> {
            Value copy = context.get().parseJson(json.toString());
            Value result = context.get().invoke(callback, copy, id);
            return object(result);
        });
    }
}
