package dev.punctualboat.mantis.core;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CacheDiagnosticsTest {
    @Test void sharedSourcesReuseContentAndChangedContentGetsAFreshResult() {
        try (MantisEngine engine = new MantisEngine()) {
            var first = engine.createContext(Map.of(), Map.of());
            assertEquals(1, first.evaluate("value.js", "1").asInt());
            long hits = engine.cacheStats().hits();
            var second = engine.createContext(Map.of(), Map.of());
            assertEquals(1, second.evaluate("value.js", "1").asInt());
            assertTrue(engine.cacheStats().hits() > hits);
            assertEquals(2, second.evaluate("value.js", "2").asInt());
            engine.clearSourceCache(); assertEquals(0, engine.cacheStats().entries());
        }
    }

    @Test void aChangedImportedModuleIsNotReusedAcrossGenerations() {
        try (MantisEngine engine = new MantisEngine()) {
            String main = "import {value} from './lib.js'; export const result=value;";
            var first = engine.createContext(Map.of("main.js", main, "lib.js", "export const value=1;"), Map.of());
            assertEquals(1, first.evaluateModule("main.js").getMember("result").asInt());
            first.evaluateModule("main.js"); assertEquals(1, first.moduleStats().hits());
            var second = engine.createContext(Map.of("main.js", main, "lib.js", "export const value=2;"), Map.of());
            assertEquals(2, second.evaluateModule("main.js").getMember("result").asInt());
        }
    }

    @Test void cachesAreBoundedAndProfilingIncludesFailuresAndFunctionSources() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(), Map.of());
            for (int i=0;i<520;i++) context.evaluate("value-" + i + ".js", Integer.toString(i));
            assertTrue(engine.cacheStats().entries() <= 512); assertTrue(engine.cacheStats().evictions() > 0);
            var callback = context.evaluate("callback.js", "function broken(){throw new Error('failure')} broken");
            assertThrows(ScriptException.class, () -> context.invoke(callback));
            assertTrue(context.diagnostics().snapshot().stream().anyMatch(sample -> sample.operation().contains("callback.js") && sample.failures() > 0 && sample.maxNanos() > 0));
            context.diagnostics().reset(); assertTrue(context.diagnostics().snapshot().isEmpty());
        }
    }
}
