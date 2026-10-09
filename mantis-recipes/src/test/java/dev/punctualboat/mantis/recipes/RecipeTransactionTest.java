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

    @Test void recipesWithoutAUsableSerializerTypeAreSkippedInsteadOfFailingEveryRoleFilter() {
        Map<String, JsonObject> pack = new HashMap<>(recipes());
        pack.put("bad:no_type", json("{\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}]}"));
        pack.put("bad:null_type", json("{\"type\":null,\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}]}"));
        pack.put("bad:array_type", json("{\"type\":[],\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}]}"));
        pack.put("bad:number_type", json("{\"type\":5,\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}]}"));
        pack.put("bad:object_type", json("{\"type\":{},\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}]}"));
        pack.put("bad:boolean_type", json("{\"type\":true,\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}]}"));
        RecipeTransaction transaction = RecipeTransaction.borrowing(pack);
        RecipeFilter usesIron = new RecipeFilter(null, null, null, new JsonPrimitive("minecraft:iron_ingot"), null, List.of(), List.of(), null);
        assertEquals(List.of("create:mix"), transaction.matching(usesIron));
        assertEquals(List.of("create:mix"), transaction.matching(new RecipeFilter(null, "create:mixing", null)));
        assertEquals(1, transaction.replaceValues(new RecipeFilter(null, null, null), new JsonPrimitive("minecraft:iron_ingot"), new JsonPrimitive("minecraft:copper_ingot"), "input"));
        assertEquals(Set.of("create:mix"), transaction.changedIds());
    }

    @Test void borrowedTransactionsPublishOnlyDetachedChanges() {
        Map<String, JsonObject> original = recipes();
        JsonObject untouched = original.get("create:mix");
        JsonObject edited = original.get("example:fusion");
        RecipeTransaction transaction = RecipeTransaction.borrowing(original);
        transaction.set(new RecipeFilter("example:fusion", null, null), "/energy", new JsonPrimitive(5));
        Map<String, JsonObject> result = transaction.commitChanges((id, value) -> {}).replacements();
        assertFalse(result.containsKey("create:mix"));
        assertSame(untouched, original.get("create:mix"));
        assertNotSame(edited, result.get("example:fusion"));
        assertEquals(1000, edited.get("energy").getAsInt());
        assertEquals(5, result.get("example:fusion").get("energy").getAsInt());
        assertEquals(Set.of("example:fusion"), transaction.changedIds());
    }

    @Test void ordinaryTransactionsSnapshotInputsAndDetachTheirFullResult() {
        Map<String, JsonObject> original = recipes();
        RecipeTransaction transaction = new RecipeTransaction(original);
        original.get("create:mix").addProperty("heatRequirement", "changed_after_construction");
        assertEquals("heated", transaction.get("create:mix").get("heatRequirement").getAsString());
        var result = transaction.commit((id, value) -> {});
        result.get("example:fusion").addProperty("energy", 77);
        assertEquals(1000, original.get("example:fusion").get("energy").getAsInt());
    }

    @Test void borrowedChangesRetainAtomicValidationAndDoNotIncludeUntouchedRecipes() {
        Map<String, JsonObject> original = recipes();
        RecipeTransaction transaction = RecipeTransaction.borrowing(original);
        transaction.remove(new RecipeFilter("example:ritual", null, null));
        transaction.set(new RecipeFilter("example:fusion", null, null), "/energy", new JsonPrimitive(9));
        assertThrows(IllegalArgumentException.class, () -> transaction.commitChanges((id, value) -> { throw new IllegalArgumentException("bad serializer"); }));
        assertEquals(1000, original.get("example:fusion").get("energy").getAsInt());
        var changes = transaction.commitChanges((id, value) -> value.addProperty("energy", 999));
        assertEquals(Set.of("example:ritual"), changes.removed());
        assertEquals(Set.of("example:fusion"), changes.replacements().keySet());
        assertEquals(9, changes.replacements().get("example:fusion").get("energy").getAsInt());
        assertEquals("heated", original.get("create:mix").get("heatRequirement").getAsString());
        assertThrows(IllegalStateException.class, () -> transaction.get("example:fusion"));
    }

    @Test void readsNeverLetCallersMutateStagedOrOriginalRecipes() {
        Map<String, JsonObject> original = recipes();
        RecipeTransaction transaction = new RecipeTransaction(original);
        transaction.get("create:mix").addProperty("heatRequirement", "superheated");
        assertEquals("heated", transaction.get("create:mix").get("heatRequirement").getAsString());
        assertEquals("heated", original.get("create:mix").get("heatRequirement").getAsString());
    }

    @Test void exactIdFiltersAndNamespaceFiltersMatchTheSameRecipesAsAFullScan() {
        RecipeTransaction transaction = new RecipeTransaction(recipes());
        assertEquals(List.of("example:fusion"), transaction.matching(new RecipeFilter("example:fusion", null, null)));
        assertEquals(List.of(), transaction.matching(new RecipeFilter("example:fusion", "create:mixing", null)));
        assertEquals(List.of(), transaction.matching(new RecipeFilter("missing:recipe", null, null)));
        assertEquals(List.of("example:fusion", "example:ritual"), transaction.matching(new RecipeFilter(null, null, "example")));
        assertEquals(List.of(), transaction.matching(new RecipeFilter(null, null, "exampl")));
        assertEquals(List.of(), transaction.matching(new RecipeFilter(null, null, "example:fusion")));
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
