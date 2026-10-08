package dev.punctualboat.mantis.benchmarks;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkFixtureTest {
    @Test void allBackendsExecuteTheSameWorkloadWithTheSameResults() {
        var backends = new ArrayList<>(List.of("mantis", "rhino-interpreted", "rhino-compiled"));
        try { Class.forName("dev.punctualboat.mantis.benchmarks.MinecraftRhinoBackend"); backends.add("rhino-minecraft"); }
        catch (ClassNotFoundException ignored) {}
        for (String backend : backends) {
            try (var runtime = EngineBenchmarks.create(backend)) {
                assertEquals(43, runtime.call("simple", 21), backend);
                assertEquals(42, runtime.call("method", 21), backend);
                assertEquals(42, runtime.call("staticMethod", 21), backend);
                assertEquals(28, runtime.call("field", 21), backend);
                assertEquals(27, runtime.call("property", 21), backend);
                assertEquals(21, runtime.call("construct", 21), backend);
                assertEquals(22, runtime.call("overload", 21), backend);
                assertEquals(6, runtime.call("array", runtime.array(new Object[]{1,2,3})), backend);
                assertEquals(10, runtime.call("map", runtime.object(Map.of("count", 4, "name", "mantis"))), backend);
                assertEquals(512000, runtime.call("recipes", 256), backend);
                assertEquals(43, runtime.cachedEvaluate(), backend);
                assertEquals(9, runtime.loadCollection(10), backend);
            }
        }
    }
}
