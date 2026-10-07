package dev.punctualboat.mantis.minecraft.api;

import dev.punctualboat.mantis.runtime.ScriptSession;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MantisApi {
    private static final Map<String, ScriptSession.Modules> EXTENSIONS = new LinkedHashMap<>();
    private static boolean frozen;
    private MantisApi() {}

    public static synchronized void registerExtension(String id, ScriptSession.Modules extension) {
        if (frozen) throw new IllegalStateException("Register Mantis extensions during mod construction or common setup");
        if (id == null || !id.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid extension id: " + id);
        if (extension == null || EXTENSIONS.putIfAbsent(id, extension) != null) throw new IllegalArgumentException("Duplicate or empty extension: " + id);
    }

    public static synchronized void registerModules(ScriptSession.Registrar registrar) {
        frozen = true;
        EXTENSIONS.values().forEach(extension -> extension.register(registrar));
    }
}
