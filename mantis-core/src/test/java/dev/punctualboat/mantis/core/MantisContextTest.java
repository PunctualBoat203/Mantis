package dev.punctualboat.mantis.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MantisContextTest {
    public static final class ExecutionProbe {
        private final MantisEngine engine;
        ExecutionProbe(MantisEngine engine) { this.engine = engine; }
        @MantisExport public boolean inside() { return engine.isExecutingOnCurrentThread(); }
    }

    @Test void executionTrackingCoversGuestHostCallsAndClearsAfterFailures() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of("probe.js", "import {probe} from 'test:probe'; export const check = () => probe.inside();"),
                    Map.of("test:probe", Map.of("probe", new ExecutionProbe(engine))));
            var check = context.evaluateModule("probe.js").getMember("check");
            assertFalse(engine.isExecutingOnCurrentThread());
            assertTrue(context.invoke(check).asBoolean());
            assertFalse(engine.isExecutingOnCurrentThread());
            assertThrows(ScriptException.class, () -> context.evaluate("failure.js", "throw Error('failure')"));
            assertFalse(engine.isExecutingOnCurrentThread());
        }
    }
    @Test void importsSnapshotPathsContainingQuotesSpacesAndUnicode() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(
                    "it's a script.js", "import {value} from \"./lib/it's é.mjs\"; export const answer = value;",
                    "lib/it's é.mjs", "export const value = 42;"), Map.of());
            assertEquals(42, context.evaluateModule("it's a script.js").getMember("answer").asInt());
            assertEquals(42, context.evaluateModule("lib/it's é.mjs").getMember("value").asInt());
        }
    }

    @Test void virtualFilePathsRoundTripThroughFileUris() throws Exception {
        var files = new ModuleFiles(Map.of("main.js", "export const value = 1;"), Map.of());
        var path = ModuleFiles.scriptPath("main.js");
        assertTrue(path.isAbsolute());
        assertEquals(path, files.parsePath(path.toUri()));
        files.checkAccess(files.parsePath(path.toUri()), java.util.Set.of(java.nio.file.AccessMode.READ));
        assertEquals(path, files.toRealPath(files.parsePath(path.toUri())));
    }
    public static final class Host {
        @MantisExport public int twice(int value) { return value * 2; }
        public String secret() { return "hidden"; }
    }

    @Test void executesModernJavaScript() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(), Map.of());
            assertEquals(42, context.evaluate("modern.js", "const answer = [1,2,3].map(x => x * 7).reduce((a,b) => a+b); answer").asInt());
            assertEquals(7, context.evaluate("optional.js", "({nested:{value:7}})?.nested?.value ?? 0").asInt());
        }
    }

    @Test void importsVirtualHostModulesAndRelativeModules() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(
                    "main.js", "import {host} from 'test:api'; import {factor} from './lib/factor.mjs'; export const answer = host.twice(factor);",
                    "lib/factor.mjs", "export const factor = 21;"), Map.of("test:api", Map.of("host", new Host())));
            assertEquals(42, context.evaluateModule("main.js").getMember("answer").asInt());
        }
    }

    @Test void exposesOnlyAnnotatedHostMembers() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of("main.js", "import {host} from 'test:api'; export const secret = typeof host.secret; export const reflection = typeof host.getClass; export const result = host.twice(4);"),
                    Map.of("test:api", Map.of("host", new Host())));
            var exports = context.evaluateModule("main.js");
            assertEquals("undefined", exports.getMember("secret").asString());
            assertEquals("undefined", exports.getMember("reflection").asString());
            assertEquals(8, exports.getMember("result").asInt());
            assertThrows(ScriptException.class, () -> context.evaluate("classes.js", "Java.type('java.lang.System')"));
        }
    }

    @Test void keepsContextsIsolatedAndSupportsJsonRoundTrips() {
        try (MantisEngine engine = new MantisEngine()) {
            var first = engine.createContext(Map.of(), Map.of());
            var second = engine.createContext(Map.of(), Map.of());
            first.evaluate("state.js", "globalThis.privateState = 9");
            assertEquals("undefined", second.evaluate("other.js", "typeof privateState").asString());
            assertEquals("{\"value\":9}", first.toJson(first.parseJson("{\"value\":9}")));
        }
    }

    @Test void reportsSourceLocations() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(), Map.of());
            ScriptException error = assertThrows(ScriptException.class, () -> context.evaluate("broken.js", "\nthrow new Error('broken');"));
            assertEquals("broken.js", error.source());
            assertEquals(2, error.line());
            assertTrue(error.getMessage().contains("broken"));
        }
    }

    @Test void stopsRunawayCallbacks() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of(), Map.of());
            var callback = context.evaluate("loop.js", "() => { while (true) {} }");
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> assertThrows(ScriptException.class, () -> context.invoke(callback)));
            assertTrue(context.isClosed());
        }
    }

    @Test void cannotReadFilesOutsideTheSnapshot() {
        try (MantisEngine engine = new MantisEngine()) {
            var context = engine.createContext(Map.of("main.js", "import '/etc/passwd';"), Map.of());
            assertThrows(ScriptException.class, () -> context.evaluateModule("main.js"));
        }
    }

    @Test void rejectsPathsEscapingTheScriptRootAndClosedEngines() {
        try (MantisEngine engine = new MantisEngine()) {
            assertThrows(IllegalArgumentException.class, () -> engine.createContext(Map.of("../outside.js", "1"), Map.of()));
        }
        MantisEngine engine = new MantisEngine();
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.createContext(Map.of(), Map.of()));
        engine.close();
    }
}
