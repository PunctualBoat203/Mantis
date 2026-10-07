package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.recipes.RecipesApi;
import dev.punctualboat.mantis.runtime.ScriptSession;

public record PreparedScripts(ScriptSession session, RecipesApi recipes) implements AutoCloseable {
    @Override public void close() { session.close(); }
}
