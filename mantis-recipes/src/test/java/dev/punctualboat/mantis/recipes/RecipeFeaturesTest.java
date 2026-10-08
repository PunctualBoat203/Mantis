package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import dev.punctualboat.mantis.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipeFeaturesTest {
    @Test void buildersCannotCrossRecipeEvents() {
        try (MantisEngine engine = new MantisEngine()) {
            MantisContext[] ref = new MantisContext[1]; RecipesApi api = new RecipesApi(() -> ref[0]);
            ref[0] = engine.createContext(Map.of("builder.js", "import {recipes as r} from 'test:recipes';let old;export const build=()=>{old=r.shapeless('test:one','stick',['stone'])};export const mutate=()=>old.group('wrong');"), Map.of("test:recipes", Map.of("recipes", api)));
            var module = ref[0].evaluateModule("builder.js");
            api.begin(new RecipeTransaction(Map.of())); ref[0].invokeLoad(module.getMember("build")); api.end();
            assertThrows(ScriptException.class, () -> ref[0].invoke(module.getMember("mutate")));
            var next = new RecipeTransaction(Map.of("test:one", json("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:dirt\"}],\"result\":{\"item\":\"minecraft:stick\"}}")));
            api.begin(next); assertThrows(ScriptException.class, () -> ref[0].invoke(module.getMember("mutate"))); api.end();
            assertTrue(next.changedIds().isEmpty());
        }
    }
    @Test void nativeJsonPreservesLargeIntegerFieldsAndRejectsOversizedWrites() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(), Map.of());
            JsonObject input = json("{\"energy\":9223372036854775807,\"fraction\":0.5,\"negative\":-9007199254740992}");
            var value = JsonCodec.write(context, input);
            assertEquals(input, JsonCodec.read(value));
            assertTrue(context.invoke(context.evaluate("type", "value => typeof value.energy === 'bigint' && typeof value.negative === 'bigint' && typeof value.fraction === 'number'"), value).asBoolean());
            JsonArray huge = new JsonArray(); for (int i = 0; i < 100_001; i++) huge.add(0);
            assertThrows(IllegalArgumentException.class, () -> JsonCodec.write(context, huge));
        }
    }

    @Test void invalidSchemaPathsAndFluidReplacementsLeaveRecipesUnchanged() {
        var schemas = new RecipeSchemas();
        assertThrows(IllegalArgumentException.class, () -> schemas.register("test:overlap", json("{\"fields\":{\"a\":{\"kind\":\"json\",\"path\":\"/result\"},\"b\":{\"kind\":\"json\",\"path\":\"/result/item\"}}}")));
        assertThrows(IllegalArgumentException.class, () -> schemas.register("test:escape", json("{\"fields\":{\"a\":{\"kind\":\"json\",\"path\":\"/bad~x\"}}}")));
        assertThrows(IllegalArgumentException.class, () -> schemas.register("test:type", json("{\"fields\":{\"a\":{\"kind\":\"json\",\"path\":\"/type/child\"}}}")));
        assertTrue(schemas.types().isEmpty());
        var tx = new RecipeTransaction(Map.of("test:fluid", json("{\"type\":\"test:machine\",\"input\":{\"fluid\":\"minecraft:water\",\"amount\":1000,\"chance\":0.25}}")));
        assertThrows(IllegalArgumentException.class, () -> tx.replaceValues(new RecipeFilter(null,null,null), json("{\"fluid\":\"water\"}"), json("{\"fluid\":\"lava\",\"amount\":-1}"), "input"));
        assertTrue(tx.changedIds().isEmpty());
        assertEquals(1, tx.replaceValues(new RecipeFilter(null,null,null), json("{\"fluid\":\"water\"}"), json("{\"fluid\":\"lava\",\"amount\":2000}"), "input"));
        assertEquals(json("{\"fluid\":\"minecraft:lava\",\"amount\":2000,\"chance\":0.25}"), tx.get("test:fluid").get("input"));
    }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    @Test void semanticFiltersUseRolesTagsAndBooleanCombinations() {
        RecipeSchemas schemas = new RecipeSchemas();
        schemas.register("test:machine", json("{\"fields\":{\"in\":{\"kind\":\"ingredient\",\"path\":\"/strange/feed\",\"role\":\"input\"},\"out\":{\"kind\":\"item\",\"path\":\"/strange/product\",\"role\":\"output\"}}}"));
        var tx = RecipeTransaction.borrowing(Map.of("test:one", json("{\"type\":\"test:machine\",\"strange\":{\"feed\":{\"tag\":\"forge:ingots/iron\"},\"product\":{\"item\":\"minecraft:diamond\",\"count\":4,\"chance\":0.5}},\"nbt\":{\"item\":\"minecraft:stone\"}}")), schemas,
                (tag, item) -> tag.equals("forge:ingots/iron") && item.equals("minecraft:iron_ingot"));
        assertEquals(List.of("test:one"), tx.matching(RecipeFilter.parse(json("{\"input\":\"iron_ingot\",\"output\":\"diamond\",\"any\":[{\"mod\":\"other\"},{\"mod\":\"test\"}],\"not\":{\"id\":\"test:missing\"}}"))));
        assertTrue(tx.matching(RecipeFilter.parse(json("{\"input\":\"stone\"}"))).isEmpty());
        assertEquals(1, tx.replaceValues(new RecipeFilter(null,null,null), new JsonPrimitive("diamond"), new JsonPrimitive("emerald"), "output"));
        JsonObject result = tx.commitChanges((id, value) -> {}).replacements().get("test:one");
        assertEquals(4, result.getAsJsonObject("strange").getAsJsonObject("product").get("count").getAsInt());
        assertEquals(0.5, result.getAsJsonObject("strange").getAsJsonObject("product").get("chance").getAsDouble());
        assertEquals("minecraft:stone", result.getAsJsonObject("nbt").get("item").getAsString());
    }
    @Test void failedBulkReplacementPublishesNothing() {
        var tx = new RecipeTransaction(Map.of("test:a", json("{\"type\":\"minecraft:crafting_shapeless\",\"result\":{\"item\":\"minecraft:diamond\"}}"),
                "test:b", json("{\"type\":\"minecraft:smelting\",\"result\":\"minecraft:diamond\"}")));
        assertThrows(IllegalArgumentException.class, () -> tx.replaceValues(new RecipeFilter(null,null,null), new JsonPrimitive("diamond"), RecipeValues.item("emerald", 2), "output"));
        assertTrue(tx.changedIds().isEmpty());
        assertEquals("minecraft:diamond", tx.get("test:a").getAsJsonObject("result").get("item").getAsString());
    }
    @Test void javascriptBuildersSchemasAndNativeJsonRoundTrip() {
        try (MantisEngine engine = new MantisEngine()) {
            MantisContext[] ref = new MantisContext[1]; RecipesApi api = new RecipesApi(() -> ref[0]);
            ref[0] = engine.createContext(Map.of("recipes.js", """
                    import {recipes as r} from 'test:recipes';
                    export function build() {
                      r.shaped('test:shaped', r.item('stick', 4), ['A A',' A '], {A:r.tag('planks')}).group('test');
                      r.shapeless('test:loose', 'diamond', ['stone', ['dirt', '#minecraft:planks']]);
                      r.smelting('test:smelt', 'iron_ingot', 'raw_iron').experience(0.7).cookingTime(100);
                      r.stonecutting('test:cut', r.item('stone_slab',2), 'stone');
                      r.smithing('test:smith', 'netherite_sword', 'netherite_upgrade_smithing_template', 'diamond_sword', 'netherite_ingot');
                      r.schema('test:machine', {fields:{feed:{path:'/nested/feed',kind:'ingredient',role:'input'},
                        product:{path:'/nested/product',kind:'items',role:'output'},power:{path:'/power',kind:'positive_int',default:20}}});
                      r.create('test:machine', 'test:machine', {feed:'stone',product:[r.item('diamond',3)]});
                      r.replaceOutput({type:'test:machine'}, 'diamond', 'emerald');
                      const json=r.get('test:machine');
                      if(json.nested.product[0].count!==3 || json.nested.product[0].item!=='minecraft:emerald') throw Error('bad replacement');
                      r.patch({id:'test:machine'}, j=>{j.power+=2;return j;});
                      return r.count({output:'emerald'})===1 && r.contains({input:'stone'}) && r.types().includes('test:machine');
                    }
                    """), Map.of("test:recipes", Map.of("recipes", api)));
            var tx = RecipeTransaction.borrowing(Map.of(), api.schemas(), (tag,item)->false); api.begin(tx);
            assertTrue(ref[0].invokeLoad(ref[0].evaluateModule("recipes.js").getMember("build")).asBoolean()); api.end();
            var out = tx.commitChanges((id,value)->{}).replacements();
            assertEquals(6, out.size());
            assertEquals("minecraft:iron_ingot", out.get("test:smelt").get("result").getAsString());
            assertEquals(2, out.get("test:cut").get("count").getAsInt());
            assertEquals(22, out.get("test:machine").get("power").getAsInt());
        }
    }
    @Test void convertersRejectCyclesNonFiniteNumbersAndInvalidBuilderInputs() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(), Map.of());
            assertThrows(IllegalArgumentException.class, () -> JsonCodec.read(context.evaluate("cycle", "let a={};a.a=a;a")));
            assertThrows(IllegalArgumentException.class, () -> JsonCodec.read(context.evaluate("nan", "NaN")));
            assertThrows(IllegalArgumentException.class, () -> RecipeValues.item("stone", 0));
            assertThrows(IllegalArgumentException.class, () -> RecipeValues.positiveInt(new JsonPrimitive(1.5)));
            var schemas = VanillaRecipes.schemas();
            assertThrows(IllegalArgumentException.class, () -> schemas.build("minecraft:crafting_shaped", json("{\"pattern\":[\"A\"],\"key\":{\"B\":\"stone\"},\"result\":\"stick\"}")));
            assertThrows(IllegalArgumentException.class, () -> schemas.build("minecraft:smelting", json("{\"ingredient\":\"stone\",\"result\":{\"item\":\"diamond\",\"count\":2}}")));
            assertThrows(IllegalStateException.class, () -> schemas.snapshot().component("new", v->v));
        }
    }
}
