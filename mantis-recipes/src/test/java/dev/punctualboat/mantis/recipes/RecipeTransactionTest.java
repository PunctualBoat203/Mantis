package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import dev.punctualboat.mantis.core.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RecipeTransactionTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static Map<String, JsonObject> recipes() {
        return Map.of(
                "create:mix", json("{\"type\":\"create:mixing\",\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}],\"results\":[{\"item\":\"minecraft:gold_ingot\"}],\"heatRequirement\":\"heated\"}"),
                "example:ritual", json("{\"type\":\"occultism:ritual\",\"ritual_type\":\"occultism:craft\",\"activation_item\":{\"item\":\"minecraft:stick\"},\"result\":{\"item\":\"minecraft:diamond\"},\"conditions\":[{\"type\":\"forge:mod_loaded\",\"modid\":\"occultism\"}]}"),
                "example:fusion", json("{\"type\":\"draconicevolution:fusion_crafting\",\"energy\":1000,\"tier\":1,\"catalyst\":{\"item\":\"minecraft:diamond\"},\"ingredients\":[{\"item\":\"minecraft:gold_ingot\"}],\"result\":{\"item\":\"minecraft:emerald\"}}"));
    }

    @Test void editsArbitrarySerializerSchemasWithoutCraftingAssumptions() {
        Map<String, JsonObject> original = recipes();
        RecipeTransaction transaction = new RecipeTransaction(original);
        assertEquals(1, transaction.set(new RecipeFilter(null, "draconicevolution:fusion_crafting", null), "/energy", new JsonPrimitive(2000)));
        assertEquals(1, transaction.set(new RecipeFilter("example:ritual", null, null), "/activation_item/item", new JsonPrimitive("minecraft:bone")));
        assertEquals(1, transaction.set(new RecipeFilter(null, "create:mixing", null), "/results/0/item", new JsonPrimitive("minecraft:copper_ingot")));
        Map<String, JsonObject> changed = transaction.commit((id, value) -> {});
        assertEquals(2000, changed.get("example:fusion").get("energy").getAsInt());
        assertEquals(original.get("example:ritual").get("conditions"), changed.get("example:ritual").get("conditions"));
        assertEquals("heated", changed.get("create:mix").get("heatRequirement").getAsString());
        assertEquals(1000, original.get("example:fusion").get("energy").getAsInt());
    }

    @Test void filtersByRecipeIdSerializerTypeAndNamespace() {
        RecipeTransaction transaction = new RecipeTransaction(recipes());
        assertEquals(2, transaction.remove(new RecipeFilter(null, null, "example")));
        assertEquals(List.of("create:mix"), transaction.matching(new RecipeFilter(null, null, null)));
        assertEquals(0, transaction.remove(new RecipeFilter("missing:recipe", null, null)));
    }

    @Test void replacementUsesTheSameIdAndCustomRecipesMayBeAdded() {
        RecipeTransaction transaction = new RecipeTransaction(recipes());
        JsonObject replacement = json("{\"type\":\"example:machine\",\"power\":15}");
        transaction.replace("example:fusion", replacement);
        transaction.custom("mantis:new_machine", replacement);
        replacement.addProperty("power", 99);
        Map<String, JsonObject> result = transaction.commit((id, value) -> {});
        assertEquals(15, result.get("example:fusion").get("power").getAsInt());
        assertTrue(result.containsKey("mantis:new_machine"));
        assertThrows(IllegalStateException.class, () -> transaction.custom("mantis:late", replacement));
    }

    @Test void validatesAllChangesBeforeCommitAndPreservesOriginalsOnFailure() {
        Map<String, JsonObject> original = recipes();
        RecipeTransaction transaction = new RecipeTransaction(original);
        transaction.set(new RecipeFilter(null, null, "example"), "/invalid", new JsonPrimitive(true));
        assertThrows(IllegalArgumentException.class, () -> transaction.commit((id, value) -> { throw new IllegalArgumentException("serializer rejected recipe"); }));
        assertFalse(original.get("example:ritual").has("invalid"));
        assertFalse(original.get("example:fusion").has("invalid"));
    }

    @Test void multiRecipePatchIsAtomicIfOneTransformationFails() {
        RecipeTransaction transaction = new RecipeTransaction(recipes());
        assertThrows(IllegalStateException.class, () -> transaction.patch(new RecipeFilter(null, null, "example"), (id, value) -> {
            if (id.endsWith("ritual")) throw new IllegalStateException("second recipe failed");
            value.addProperty("energy", 999);
            return value;
        }));
        assertEquals(1000, transaction.get("example:fusion").get("energy").getAsInt());
        assertTrue(transaction.changedIds().isEmpty());
    }

    @Test void jsonPointersSupportEscapesAndRejectMissingParents() {
        JsonObject value = json("{\"a/b\":{\"~key\":1},\"array\":[10]}");
        JsonPointer.set(value, "/a~1b/~0key", new JsonPrimitive(2));
        assertEquals(2, value.getAsJsonObject("a/b").get("~key").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> JsonPointer.set(value, "/missing/key", new JsonPrimitive(1)));
        assertThrows(IllegalArgumentException.class, () -> JsonPointer.set(value, "/array/1", new JsonPrimitive(1)));
        assertThrows(IllegalArgumentException.class, () -> JsonPointer.set(value, "/array/01", new JsonPrimitive(1)));
        assertThrows(IllegalArgumentException.class, () -> JsonPointer.set(value, "/bad~2", new JsonPrimitive(1)));
    }

    @Test void rejectsMisspelledFiltersMissingIdsAndInvalidRecipes() {
        assertThrows(IllegalArgumentException.class, () -> RecipeFilter.parse(json("{\"typ\":\"x:y\"}")));
        RecipeTransaction transaction = new RecipeTransaction(recipes());
        assertThrows(IllegalArgumentException.class, () -> transaction.replace("missing:id", json("{\"type\":\"example:machine\"}")));
        assertThrows(IllegalArgumentException.class, () -> transaction.custom("mantis:invalid", json("{}")));
        assertThrows(IllegalArgumentException.class, () -> transaction.custom("Bad Id", json("{\"type\":\"example:machine\"}")));
    }

    @Test void javascriptCallbacksCanPatchNestedRecipesAndCannotEditOutsideReload() {
        try (MantisEngine engine = new MantisEngine()) {
            RecipesApi[] api = new RecipesApi[1];
            MantisContext[] contextRef = new MantisContext[1];
            var context = engine.createContext(Map.of("main.js", "import {recipes} from 'minecraft:recipes'; export const change = () => recipes.patch({type:'create:mixing'}, (json,id) => { json.results[0].item='minecraft:diamond'; return json; });"),
                    Map.of("minecraft:recipes", Map.of("recipes", api[0] = new RecipesApi(() -> contextRef[0]))));
            contextRef[0] = context;
            var change = context.evaluateModule("main.js").getMember("change");
            assertThrows(ScriptException.class, () -> context.invoke(change));
            RecipeTransaction transaction = new RecipeTransaction(recipes());
            api[0].begin(transaction);
            assertEquals(1, context.invoke(change).asInt());
            api[0].end();
            assertEquals("minecraft:diamond", transaction.commit((id, value) -> {}).get("create:mix").getAsJsonArray("results").get(0).getAsJsonObject().get("item").getAsString());
        }
    }

}
