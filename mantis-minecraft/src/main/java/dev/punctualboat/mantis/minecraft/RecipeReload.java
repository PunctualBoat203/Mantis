package dev.punctualboat.mantis.minecraft;

import com.google.gson.*;
import dev.punctualboat.mantis.minecraft.api.MantisApi;
import dev.punctualboat.mantis.recipes.*;
import dev.punctualboat.mantis.runtime.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.common.crafting.conditions.ICondition;

import java.io.IOException;
import java.util.*;

public final class RecipeReload {
    private RecipeReload() {}

    public static PreparedScripts prepare(Map<ResourceLocation, JsonElement> jsons, ICondition.IContext conditions) {
        Mantis.discardPending();
        RecipesApi[] recipes = new RecipesApi[1];
        ScriptSession session;
        try {
            session = new ScriptSession(Mantis.engine(), ScriptSources.load(ScriptDirectories.SERVER), null,
                    Mantis::log, Mantis::report, registrar -> {
                recipes[0] = new RecipesApi(registrar.context());
                registrar.module("minecraft:recipes", Map.of("recipes", recipes[0]));
                registrar.module("minecraft:mods", Map.of("mods", new MinecraftBindings.Mods()));
                registrar.module("minecraft:server", Map.of("server", new MinecraftBindings.Server()));
                MinecraftBindings.register(registrar);
                MantisApi.registerModules(registrar);
            });
        } catch (IOException error) { throw new IllegalStateException("Could not read server scripts", error); }

        PreparedScripts prepared = new PreparedScripts(session, recipes[0]);
        try {
            Map<String, JsonObject> originals = new TreeMap<>();
            jsons.forEach((id, json) -> { if (json.isJsonObject() && !id.getPath().startsWith("_")) originals.put(id.toString(), json.getAsJsonObject()); });
            RecipeTransaction transaction = new RecipeTransaction(originals);
            recipes[0].begin(transaction);
            try { session.events().emitStrict("recipes", recipes[0]); }
            finally { recipes[0].end(); }

            Map<String, JsonObject> staged = transaction.commit((id, json) -> {
                ResourceLocation location = new ResourceLocation(id);
                try {
                    if (CraftingHelper.processConditions(json, "conditions", conditions)) {
                        RecipeManager.fromJson(location, json, conditions);
                    }
                } catch (RuntimeException error) { throw new IllegalArgumentException("Invalid scripted recipe " + id + ": " + error.getMessage(), error); }
            });

            for (String id : transaction.changedIds()) {
                ResourceLocation location = new ResourceLocation(id);
                if (staged.containsKey(id)) jsons.put(location, staged.get(id));
                else jsons.remove(location);
            }
            Mantis.pending(prepared);
            return prepared;
        } catch (RuntimeException error) {
            try { prepared.close(); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
}
