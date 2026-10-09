package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.recipes.RecipesApi;
import dev.punctualboat.mantis.runtime.ScriptSession;
import java.util.List;

public record PreparedScripts(ScriptSession session, RecipesApi recipes, ScriptCommands commands, List<String> loadErrors) implements AutoCloseable {
    public PreparedScripts { loadErrors = List.copyOf(loadErrors); }
    public PreparedScripts(ScriptSession session, RecipesApi recipes, ScriptCommands commands) { this(session, recipes, commands, List.of()); }
    public boolean degraded() { return !loadErrors.isEmpty(); }
    @Override public void close() { session.close(); }
}
