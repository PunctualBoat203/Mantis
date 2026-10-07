package dev.punctualboat.mantis.compat;

import dev.punctualboat.mantis.core.*;
import dev.punctualboat.mantis.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RhinoCompatibilityTest {
    public static final class LegacyHost {
        @MantisExport public int number;
        @MantisExport public LegacyHost(int number) { this.number = number; }
        @MantisExport public static int sum(int[] values) { return java.util.Arrays.stream(values).sum(); }
    }

    @Test void whitelistedJavaTypesConstructAndTypedArraysRoundTrip() {
        try (MantisEngine engine = new MantisEngine(); ScriptSession session = new ScriptSession(engine, Map.of("legacy.js", """
                import {Java} from 'mantis:rhino';
                var Host = Java.type('example.Host'); var host = new Host(7);
                var array = Java.to([1,2,3], 'int[]'); array[1] = 5;
                export const sum = Host.sum(array);
                export const native = Java.from(array);
                export const number = host.number;
                export const arrays = Array.isArray(native);
                """), null, message -> {}, error -> fail(error), registrar -> RhinoCompatibility.register(registrar, Map.of("example.Host", LegacyHost.class)))) {
            var exports = session.context().evaluateModule("legacy.js");
            assertEquals(9, exports.getMember("sum").asInt()); assertEquals(7, exports.getMember("number").asInt());
            assertEquals("[1,5,3]", session.context().toJson(exports.getMember("native"))); assertTrue(exports.getMember("arrays").asBoolean());
        }
    }

    @Test void migrationNeverEnablesUnregisteredClassesPackagesOrLossyArrayElements() {
        try (MantisEngine engine = new MantisEngine(); ScriptSession session = new ScriptSession(engine, Map.of("main.js", "import {Java} from 'mantis:rhino'; export {Java};"),
                null, message -> {}, error -> fail(error), registrar -> RhinoCompatibility.register(registrar, Map.of()))) {
            var java = session.context().evaluateModule("main.js").getMember("Java");
            assertThrows(ScriptException.class, () -> session.context().invokeMember(java, "type", "java.lang.System"));
            assertThrows(ScriptException.class, () -> session.context().invokeMember(java, "to", session.context().evaluate("bad.js", "[1.5]"), "int[]"));
            assertThrows(ScriptException.class, () -> session.context().invokeMember(java, "to", session.context().evaluate("types.js", "[]"), "java.lang.Class[]"));
            assertEquals("undefined", session.context().evaluate("packages.js", "typeof Packages").asString());
        }
    }
}
