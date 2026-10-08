package dev.punctualboat.mantis.minecraft;

import com.google.gson.*;
import dev.punctualboat.mantis.minecraft.api.MantisApi;
import dev.punctualboat.mantis.recipes.*;
import dev.punctualboat.mantis.runtime.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.IOException;
import java.util.*;

public final class RecipeReload {
    private RecipeReload() {}

    public static PreparedScripts prepare(Map<ResourceLocation, JsonElement> jsons, ICondition.IContext conditions) {
        return prepare(jsons, conditions, Mantis.config().lenientFirstLoad() && !Mantis.hasActive());
    }

    static PreparedScripts prepare(Map<ResourceLocation, JsonElement> jsons, ICondition.IContext conditions, boolean lenient) {
        Mantis.discardPending();
        if (lenient) {
            try { return build(jsons, conditions, false); }
            catch (RuntimeException error) {
                Mantis.report(error);
                Mantis.log("Server scripts failed to load and were skipped, so no scripted recipe changes were applied. Fix the error, then run /mantis reload.");
            }
            return build(jsons, conditions, true);
        }
        return build(jsons, conditions, false);
    }

    private static PreparedScripts build(Map<ResourceLocation, JsonElement> jsons, ICondition.IContext conditions, boolean skipScripts) {
        RecipesApi[] recipes = new RecipesApi[1];
        ScriptSession session;
        try {
            Map<String, String> sources = skipScripts ? Map.<String, String>of() : ScriptSources.load(ScriptDirectories.SERVER);
            session = new ScriptSession(Mantis.engine(), sources, null,
                    Mantis::log, Mantis::report, registrar -> {
                recipes[0] = new RecipesApi(registrar.context(), MantisApi.recipeSchemas());
                registrar.module("minecraft:recipes", Map.of("recipes", recipes[0]));
                registrar.module("minecraft:mods", Map.of("mods", new MinecraftBindings.Mods()));
                registrar.module("minecraft:server", Map.of("server", new MinecraftBindings.Server()));
                MinecraftBindings.register(registrar);
                if (!skipScripts) MantisApi.registerModules(registrar);
            });
        } catch (IOException error) { throw new IllegalStateException("Could not read server scripts", error); }

        PreparedScripts prepared = new PreparedScripts(session, recipes[0]);
        try {
            Map<String, JsonObject> originals = new TreeMap<>();
            jsons.forEach((id, json) -> { if (json.isJsonObject() && !id.getPath().startsWith("_")) originals.put(id.toString(), json.getAsJsonObject()); });
            Map<String, Set<String>> tagCache = new HashMap<>();
            RecipeMatcher.TagLookup tags = (tag, item) -> tagCache.computeIfAbsent(tag, key -> {
                Set<String> values = new HashSet<>();
                var holders = conditions.getAllTags(Registries.ITEM).get(new ResourceLocation(key));
                if (holders != null) holders.forEach(holder -> values.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString()));
                return values;
            }).contains(item);
            RecipeTransaction transaction = RecipeTransaction.borrowing(originals, recipes[0].schemas(), tags);
            recipes[0].begin(transaction);
            try { session.events().emitStrict("recipes", recipes[0]); }
            finally { recipes[0].end(); }

            RecipeTransaction.Changes changes = transaction.commitChanges((id, json) -> {
                ResourceLocation location = new ResourceLocation(id);
                try {
                    if (CraftingHelper.processConditions(json, "conditions", conditions)) {
                        RecipeManager.fromJson(location, json, conditions);
                    }
                } catch (RuntimeException error) { throw new IllegalArgumentException("Invalid scripted recipe " + id + ": " + error.getMessage(), error); }
            });

            changes.replacements().forEach((id, json) -> jsons.put(new ResourceLocation(id), json));
            changes.removed().forEach(id -> jsons.remove(new ResourceLocation(id)));
            Mantis.pending(prepared);
            return prepared;
        } catch (RuntimeException error) {
            try { prepared.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
}
