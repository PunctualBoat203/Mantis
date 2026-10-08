package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.core.ExecutionLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.Objects;
import java.util.function.Consumer;

/** Settings read from {@code config/mantis/mantis.properties}; a commented default file is created on first launch. */
public record MantisConfig(ExecutionLimits limits, boolean lenientFirstLoad) {
    public static final MantisConfig DEFAULT = new MantisConfig(ExecutionLimits.DEFAULT, true);

    public MantisConfig { Objects.requireNonNull(limits); }

    private static final String TEMPLATE = """
            # Mantis settings. Restart the game after editing.

            # Wall-clock budget for one event handler, timer, or host call. Exceeding it closes the script context
            # (all scripts in that generation stop until /mantis reload), so avoid setting it tighter than needed.
            callback_timeout_ms=250

            # Budget for loading scripts and for handlers of load-phase events (startup, recipes, lifecycle load/init).
            # Raise this if large packs patch thousands of recipes in one pass.
            load_timeout_ms=10000

            # Statements one operation may execute before the context is closed.
            statement_limit=1000000

            # If server scripts fail the first time they load (nothing to fall back to), continue without them and log
            # the error instead of aborting the datapack load. A failed /reload always keeps the previous scripts.
            lenient_first_load=true
            """;

    public static MantisConfig load(Path file, Consumer<String> warnings) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                try { Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
                catch (FileAlreadyExistsException ignored) { /* another launcher created it first */ }
            }
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            String lenient = properties.getProperty("lenient_first_load", "true").trim();
            if (!"true".equalsIgnoreCase(lenient) && !"false".equalsIgnoreCase(lenient))
                warnings.accept("Ignoring invalid lenient_first_load=" + lenient + "; using true");
            return new MantisConfig(ExecutionLimits.fromProperties(properties, warnings), !"false".equalsIgnoreCase(lenient));
        } catch (IOException | RuntimeException error) {
            warnings.accept("Could not read " + file.getFileName() + " (" + error.getMessage() + "); using defaults");
            return DEFAULT;
        }
    }
}
