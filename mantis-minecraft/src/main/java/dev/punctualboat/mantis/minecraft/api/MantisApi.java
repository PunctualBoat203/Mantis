package dev.punctualboat.mantis.minecraft.api;

import dev.punctualboat.mantis.runtime.ScriptSession;
import dev.punctualboat.mantis.recipes.*;
import com.google.gson.*;
import dev.punctualboat.mantis.core.MantisExport;
import org.graalvm.polyglot.Value;
import java.util.function.UnaryOperator;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MantisApi {
    private static final Map<String, ScriptSession.Modules> EXTENSIONS = new LinkedHashMap<>();
    private static final Map<String, Class<?>> RHINO_TYPES = new LinkedHashMap<>();
    private static boolean frozen;
    private static final RecipeSchemas SCHEMAS = VanillaRecipes.schemas();
    private MantisApi() {}

    public static synchronized void registerExtension(String id, ScriptSession.Modules extension) {
        if (frozen) throw new IllegalStateException("Register Mantis extensions during mod construction or common setup");
        if (id == null || !id.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid extension id: " + id);
        if (extension == null || EXTENSIONS.putIfAbsent(id, extension) != null) throw new IllegalArgumentException("Duplicate or empty extension: " + id);
    }

    public static synchronized void registerModules(ScriptSession.Registrar registrar) {
        registerModules(registrar, true);
    }
    public static synchronized void registerModules(ScriptSession.Registrar registrar, boolean freeze) {
        if (freeze) frozen = true;
        EXTENSIONS.values().forEach(extension -> extension.register(registrar));
    }
    public static synchronized void freeze() { frozen = true; }
    public static synchronized void registerRecipeComponent(String name, UnaryOperator<JsonElement> converter) {
        if (frozen) throw new IllegalStateException("Register components during construction or common setup");
        SCHEMAS.component(name, converter);
    }
    public static synchronized void registerRecipeSchema(String type, JsonObject schema) {
        if (frozen) throw new IllegalStateException("Register schemas during construction, common setup, or startup scripts");
        SCHEMAS.register(type, schema);
    }
    public static synchronized RecipeSchemas recipeSchemas() { return SCHEMAS.copy(); }
    public static final class Schemas {
        @MantisExport public void register(String type, Value definition) {
            JsonElement value = JsonCodec.read(definition);
            if (!value.isJsonObject()) throw new IllegalArgumentException("Schema definition must be an object");
            registerRecipeSchema(type, value.getAsJsonObject());
        }
    }

    public static synchronized void registerRhinoType(String alias, Class<?> type) {
        if (frozen) throw new IllegalStateException("Register migration types during mod construction or common setup");
        if (alias == null || alias.isBlank() || type == null) throw new IllegalArgumentException("Migration types require an alias and class");
        if (RHINO_TYPES.putIfAbsent(alias, type) != null) throw new IllegalArgumentException("Duplicate migration type: " + alias);
    }
    public static synchronized Map<String, Class<?>> rhinoTypes() { return Map.copyOf(RHINO_TYPES); }
}
