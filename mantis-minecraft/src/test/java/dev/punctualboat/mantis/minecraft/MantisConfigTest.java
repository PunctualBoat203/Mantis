package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.core.ExecutionLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class MantisConfigTest {
    @TempDir Path directory;

    @Test void createsDefaultsAndReadsEditedSettingsWithoutReplacingTheFile() throws Exception {
        Path file = directory.resolve("mantis/mantis.properties");
        var warnings = new ArrayList<String>();
        assertEquals(MantisConfig.DEFAULT, MantisConfig.load(file, warnings::add));
        assertTrue(Files.readString(file).contains("lenient_first_load=true"));
        String settings = "callback_timeout_ms=500\nload_timeout_ms=20000\nstatement_limit=2000000\nlenient_first_load=FALSE\n";
        Files.writeString(file, settings);
        var config = MantisConfig.load(file, warnings::add);
        assertEquals(new ExecutionLimits(Duration.ofMillis(500), Duration.ofSeconds(20), 2_000_000), config.limits());
        assertFalse(config.lenientFirstLoad());
        assertEquals(settings, Files.readString(file));
        assertTrue(warnings.isEmpty());
    }

    @Test void invalidExecutionLimitsAndBooleanWarnAndFallBack() throws Exception {
        Path file = directory.resolve("mantis.properties");
        Files.writeString(file, "callback_timeout_ms=500\nload_timeout_ms=9223372036854775807\nstatement_limit=-1\nlenient_first_load=maybe\n");
        var warnings = new ArrayList<String>();
        var config = MantisConfig.load(file, warnings::add);
        assertEquals(ExecutionLimits.DEFAULT, config.limits());
        assertTrue(config.lenientFirstLoad());
        assertEquals(2, warnings.size());
    }
}
