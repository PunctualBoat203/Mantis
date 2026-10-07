package dev.punctualboat.mantis.minecraft;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.*;

public final class ScriptDirectories {
    public static final Path ROOT = FMLPaths.CONFIGDIR.get().resolve("mantis");
    public static final Path STARTUP = ROOT.resolve("startup_scripts");
    public static final Path SERVER = ROOT.resolve("server_scripts");
    private ScriptDirectories() {}

    public static void create() throws IOException {
        Files.createDirectories(STARTUP);
        Files.createDirectories(SERVER);
        for (String name : new String[]{"startup_scripts/startup.js.example", "server_scripts/clock.js.example", "server_scripts/recipes.js.example"}) {
            Path target = ROOT.resolve("examples").resolve(name);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) continue;
            try (var source = ScriptDirectories.class.getResourceAsStream("/examples/" + name)) {
                if (source == null) throw new IOException("Missing bundled example: " + name);
                try { Files.copy(source, target); }
                catch (FileAlreadyExistsException ignored) {}
            }
        }
    }
}
