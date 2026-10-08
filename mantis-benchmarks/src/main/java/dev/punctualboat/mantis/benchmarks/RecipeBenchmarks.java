package dev.punctualboat.mantis.benchmarks;

import com.google.gson.*;
import dev.punctualboat.mantis.recipes.*;
import org.openjdk.jmh.annotations.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class RecipeBenchmarks {
    @State(Scope.Thread)
    public static class Recipes {
        @Param({"10000", "20000"}) public int count;
        @Param({"snapshot", "borrowed-delta"}) public String strategy;
        Map<String, JsonObject> originals;

        @Setup public void setup() {
            originals = new TreeMap<>();
            JsonObject template = JsonParser.parseString("""
                    {"type":"example:machine","ingredients":[{"item":"minecraft:iron_ingot"},{"tag":"forge:ingots/gold"}],
                     "outputs":[{"item":"minecraft:diamond","count":1}],"fusion":{"energy":1000,"tier":"high"},
                     "ritual":{"duration":200,"extra":{"preserved":true}}}
                    """).getAsJsonObject();
            for (int i = 0; i < count; i++) originals.put("example:recipe/" + i, template.deepCopy());
        }
        RecipeTransaction begin() {
            return strategy.equals("snapshot") ? new RecipeTransaction(originals) : RecipeTransaction.borrowing(originals);
        }
        int commit(RecipeTransaction transaction) {
            if (strategy.equals("snapshot")) return transaction.commit((id, json) -> {}).size();
            var changes = transaction.commitChanges((id, json) -> {});
            return count - changes.removed().size();
        }
    }

    @Benchmark public int unchanged(Recipes recipes) { return recipes.commit(recipes.begin()); }
    @Benchmark public int patch100ById(Recipes recipes) {
        RecipeTransaction transaction = recipes.begin();
        for (int i = 0; i < 100; i++) transaction.set(new RecipeFilter("example:recipe/" + i, null, null), "/fusion/energy", new JsonPrimitive(32000));
        return recipes.commit(transaction);
    }
    @Benchmark public int patchAllByType(Recipes recipes) {
        RecipeTransaction transaction = recipes.begin();
        transaction.set(new RecipeFilter(null, "example:machine", null), "/fusion/energy", new JsonPrimitive(32000));
        return recipes.commit(transaction);
    }
}
