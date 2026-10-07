package dev.punctualboat.mantis.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.TreeMap;

public final class ScriptSources {
    private ScriptSources() {}

    public static Map<String, String> load(Path directory) throws IOException {
        Map<String, String> result = new TreeMap<>();
        Files.createDirectories(directory);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                String name = directory.relativize(path).toString().replace('\\', '/');
                if (name.endsWith(".js") || name.endsWith(".mjs")) {
                    if (Files.size(path) > 2 * 1024 * 1024) throw new IOException("Script exceeds 2 MiB: " + name);
                    result.put(name, Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }
}
