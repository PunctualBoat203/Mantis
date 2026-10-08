package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import dev.punctualboat.mantis.core.*;
import org.graalvm.polyglot.Value;

import java.util.function.Supplier;

public final class RecipesApi {
    private final Supplier<MantisContext> context;
    private RecipeTransaction transaction;
    private final RecipeSchemas schemas;

    public RecipesApi(Supplier<MantisContext> context) { this(context, VanillaRecipes.schemas()); }
    public RecipesApi(Supplier<MantisContext> context, RecipeSchemas schemas) { this.context = context; this.schemas = schemas; }
    public RecipeSchemas schemas() { return schemas; }

    public void begin(RecipeTransaction transaction) {
        if (this.transaction != null) throw new IllegalStateException("Recipe event is already running");
        this.transaction = transaction;
    }
    public void end() { transaction = null; }
    private RecipeTransaction active() {
        if (transaction == null) throw new IllegalStateException("Recipe edits must run inside events.on('recipes', ...)");
        return transaction;
    }
    private JsonElement json(Value value) { return context.get().access("recipe:js-to-json", () -> JsonCodec.read(value)); }
    private JsonObject object(Value value) {
        JsonElement json = json(value);
        if (!json.isJsonObject()) throw new IllegalArgumentException("Expected a JSON object");
        return json.getAsJsonObject();
    }
    private RecipeFilter filter(Value filter) { return RecipeFilter.parse(object(filter)); }

    @MantisExport public int remove(Value filter) { return active().remove(filter(filter)); }
    @MantisExport public void custom(String id, Value recipe) { active().custom(id, object(recipe)); }
    @MantisExport public void replace(String id, Value recipe) { active().replace(id, object(recipe)); }
    @MantisExport public Value get(String id) { return JsonCodec.write(context.get(), active().get(id)); }
    @MantisExport public Value ids(Value filter) {
        JsonArray array = new JsonArray();
        active().matching(filter(filter)).forEach(array::add);
        return JsonCodec.write(context.get(), array);
    }
    @MantisExport public int set(Value filter, String pointer, Value value) { return active().set(filter(filter), pointer, json(value)); }
    @MantisExport public int patch(Value filter, Value callback) {
        if (!callback.canExecute()) throw new IllegalArgumentException("Recipe patch must be a function");
        return active().patch(filter(filter), (id, json) -> {
            Value copy = JsonCodec.write(context.get(), json);
            Value result = context.get().invoke(callback, copy, id);
            return object(result);
        });
    }
    @MantisExport public int count(Value filter) { return active().matching(filter(filter)).size(); }
    @MantisExport public boolean contains(Value filter) { return !active().matching(filter(filter)).isEmpty(); }
    @MantisExport public int replaceInput(Value filter, Value from, Value to) { return active().replaceValues(filter(filter), json(from), json(to), "input"); }
    @MantisExport public int replaceOutput(Value filter, Value from, Value to) { return active().replaceValues(filter(filter), json(from), json(to), "output"); }
    @MantisExport public Value item(String id) { return item(id, 1); }
    @MantisExport public Value item(String id, int count) { return JsonCodec.write(context.get(), RecipeValues.item(id, count)); }
    @MantisExport public Value tag(String id) { return JsonCodec.write(context.get(), RecipeValues.tag(id)); }
    @MantisExport public Value fluid(String id, int amount) { return JsonCodec.write(context.get(), RecipeValues.fluid(id, amount)); }
    @MantisExport public Value ingredient(Value input) { return JsonCodec.write(context.get(), RecipeValues.ingredient(json(input))); }
    @MantisExport public void schema(String type, Value definition) { active(); schemas.register(type, object(definition)); }
    @MantisExport public Value types() { return context.get().array(schemas.types().toArray()); }
    @MantisExport public RecipeBuilder create(String id, String type, Value fields) { return build(id, type, object(fields)); }
    private RecipeBuilder build(String id, String type, JsonObject fields) {
        id = RecipeValues.id(id); RecipeTransaction origin = active();
        origin.custom(id, schemas.build(type, fields)); return new RecipeBuilder(id, origin);
    }
    @MantisExport public RecipeBuilder shapeless(String id, Value output, Value inputs) {
        JsonObject fields = new JsonObject(); fields.add("result", json(output)); fields.add("ingredients", json(inputs)); return build(id, "minecraft:crafting_shapeless", fields);
    }
    @MantisExport public RecipeBuilder shaped(String id, Value output, Value pattern, Value key) {
        JsonObject fields = new JsonObject(); fields.add("result", json(output)); fields.add("pattern", json(pattern)); fields.add("key", json(key)); return build(id, "minecraft:crafting_shaped", fields);
    }
    private RecipeBuilder cooking(String id, String type, Value output, Value input) {
        JsonObject fields = new JsonObject(); fields.add("result", json(output)); fields.add("ingredient", json(input)); return build(id, "minecraft:" + type, fields);
    }
    @MantisExport public RecipeBuilder smelting(String id, Value output, Value input) { return cooking(id, "smelting", output, input); }
    @MantisExport public RecipeBuilder blasting(String id, Value output, Value input) { return cooking(id, "blasting", output, input); }
    @MantisExport public RecipeBuilder smoking(String id, Value output, Value input) { return cooking(id, "smoking", output, input); }
    @MantisExport public RecipeBuilder campfire(String id, Value output, Value input) { return cooking(id, "campfire_cooking", output, input); }
    @MantisExport public RecipeBuilder stonecutting(String id, Value output, Value input) {
        JsonObject fields = new JsonObject(), stack = RecipeValues.stack(json(output)).getAsJsonObject();
        fields.add("result", stack.get("item")); fields.add("count", stack.get("count")); fields.add("ingredient", json(input)); return build(id, "minecraft:stonecutting", fields);
    }
    @MantisExport public RecipeBuilder smithing(String id, Value output, Value template, Value base, Value addition) {
        JsonObject fields = new JsonObject(); fields.add("result", json(output)); fields.add("template", json(template)); fields.add("base", json(base)); fields.add("addition", json(addition));
        return build(id, "minecraft:smithing_transform", fields);
    }
    public final class RecipeBuilder {
        private final String id;
        private final RecipeTransaction origin;
        private RecipeBuilder(String id, RecipeTransaction origin) { this.id = id; this.origin = origin; }
        private RecipeTransaction edit() {
            if (active() != origin) throw new IllegalStateException("Recipe builders expire when their recipe event ends");
            if (origin.get(id) == null) throw new IllegalStateException("Recipe was removed: " + id);
            return origin;
        }
        @MantisExport public String id() { return id; }
        @MantisExport public RecipeBuilder group(String group) { edit().set(new RecipeFilter(id, null, null), "/group", new JsonPrimitive(group)); return this; }
        @MantisExport public RecipeBuilder experience(double experience) {
            if (!Double.isFinite(experience) || experience < 0) throw new IllegalArgumentException("Experience must be finite and nonnegative");
            edit().set(new RecipeFilter(id, null, null), "/experience", new JsonPrimitive(experience)); return this;
        }
        @MantisExport public RecipeBuilder cookingTime(int ticks) {
            if (ticks < 1) throw new IllegalArgumentException("Cooking time must be positive");
            edit().set(new RecipeFilter(id, null, null), "/cookingtime", new JsonPrimitive(ticks)); return this;
        }
        @MantisExport public RecipeBuilder set(String pointer, Value value) { edit().set(new RecipeFilter(id, null, null), pointer, json(value)); return this; }
    }
}
