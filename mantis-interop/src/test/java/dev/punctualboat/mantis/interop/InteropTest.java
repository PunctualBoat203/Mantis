package dev.punctualboat.mantis.interop;

import dev.punctualboat.mantis.core.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class InteropTest {
    public enum Kind { STONE, WOOD }
    public record Data(String name, List<Integer> counts, Optional<Kind> kind) {}
    public record Location(String id) {}
    public static final class Host {
        @MantisExport public int number = 2;
        @MantisExport public static final int CONSTANT = 7;
        private String label;
        @MantisExport public Host(String label) { this.label = label; }
        @MantisProperty("label") public String label() { return label; }
        @MantisProperty("label") public void label(String label) { this.label = label; }
        @MantisExport public String choose(int value) { return "int"; }
        @MantisExport public String choose(long value) { return "long"; }
        @MantisExport public String choose(double value) { return "double"; }
        @MantisExport public String choose(String value) { return "string"; }
        @MantisExport public String choose(Object value) { return "object"; }
        @MantisExport public String ambiguous(String value) { return "string"; }
        @MantisExport public String ambiguous(List<?> value) { return "list"; }
        @MantisExport public int add(int... values) { return Arrays.stream(values).sum(); }
        @MantisExport public static int twice(int value) { return value * 2; }
        @MantisExport public Data echo(Data data) { return data; }
        @MantisExport public String location(Location location) { return location.id(); }
        @MantisExport public int callback(Function<Integer, Integer> function) { return function.andThen(result -> result + 1).apply(21); }
        @MantisExport public void fail() { throw new IllegalStateException("host failure"); }
        public String secret() { return "secret"; }
    }

    @Test void nativeCollectionsRecordsEnumsAndOptionalsRoundTrip() {
        try (MantisEngine engine = new MantisEngine()) {
            MantisContext context = engine.createContext(Map.of(), Map.of());
            TypeConversions conversions = new TypeConversions(() -> context);
            Data input = new Data("tools", List.of(1, 2), Optional.of(Kind.STONE));
            Value data = conversions.toScript(input);
            assertTrue(context.invoke(context.evaluate("array.js", "x => Array.isArray(x.counts)"), data).asBoolean());
            assertEquals(input, conversions.fromScript(data, Data.class));
            Value primitiveArray = conversions.toScript(new int[]{3, 4});
            assertArrayEquals(new int[]{3, 4}, conversions.fromScript(primitiveArray, int[].class));
            Value map = conversions.toScript(Map.of("a", Optional.empty(), "b", new String[]{"value"}));
            assertEquals("{\"a\":null,\"b\":[\"value\"]}", context.toJson(context.invoke(context.evaluate("order.js", "x => ({a:x.a,b:x.b})"), map)));
            assertEquals(Map.of("value", List.of(1, 2)), conversions.fromScript(context.evaluate("object.js", "({value:[1,2]})"), Object.class));
            var integer = new java.math.BigInteger("123456789012345678901234567890");
            assertEquals(integer, conversions.fromScript(conversions.toScript(integer), java.math.BigInteger.class));
            assertEquals(Long.MAX_VALUE, conversions.fromScript(conversions.toScript(Long.MAX_VALUE), long.class));
            assertEquals("bigint", context.invoke(context.evaluate("bigint.js", "value => typeof value"), conversions.toScript(Long.MAX_VALUE)).asString());
        }
    }

    @Test void rejectsLossyNumbersMalformedRecordsAndCycles() {
        try (MantisEngine engine = new MantisEngine()) {
            MantisContext context = engine.createContext(Map.of(), Map.of());
            TypeConversions conversions = new TypeConversions(() -> context);
            assertThrows(IllegalArgumentException.class, () -> conversions.fromScript(context.evaluate("fraction.js", "1.5"), int.class));
            assertThrows(IllegalArgumentException.class, () -> conversions.fromScript(context.evaluate("overflow.js", "2147483648"), int.class));
            assertThrows(IllegalArgumentException.class, () -> conversions.fromScript(context.evaluate("missing.js", "({name:'a',counts:[]})"), Data.class));
            List<Object> cyclic = new ArrayList<>(); cyclic.add(cyclic);
            assertThrows(IllegalArgumentException.class, () -> conversions.toScript(cyclic));
            assertThrows(IllegalArgumentException.class, () -> conversions.fromScript(context.evaluate("cycle.js", "(() => {const a={};a.self=a;return a;})()"), Object.class));
            assertThrows(IllegalArgumentException.class, () -> conversions.toScript(Map.of(1, "bad")));
        }
    }

    @Test void explicitBindingsCoverStaticsConstructorsFieldsPropertiesAndOverloads() {
        try (MantisEngine engine = new MantisEngine()) {
            MantisContext[] context = new MantisContext[1];
            TypeConversions conversions = new TypeConversions(() -> context[0]);
            try (HostBindings bindings = new HostBindings(conversions)) {
                context[0] = engine.createContext(Map.of("main.js", """
                        import {Host} from 'test:api';
                        const host = new Host('first'); host.number = 9; host.label = 'second';
                        export const values = [Host.CONSTANT, Host.twice(3), host.number, host.label,
                          host.choose(1), host.choose(2147483648), host.choose(1.25), host.choose('text'),
                          host.add(1,2,3), host.add([4,5]), typeof host.secret, typeof host.getClass];
                        export const hostObject = host;
                        export const HostType = Host;
                        """), Map.of("test:api", Map.of("Host", bindings.type(Host.class))));
                Value exports = context[0].evaluateModule("main.js");
                assertEquals("[7,6,9,\"second\",\"int\",\"long\",\"double\",\"string\",6,9,\"undefined\",\"undefined\"]", context[0].toJson(exports.getMember("values")));
                assertEquals("second", conversions.fromScript(exports.getMember("hostObject"), Host.class).label());
                assertThrows(ScriptException.class, () -> context[0].invokeMember(exports.getMember("hostObject"), "ambiguous", (Object) null));
                assertThrows(ScriptException.class, () -> context[0].evaluate("classes.js", "Java.type('java.lang.System')"));
                assertThrows(ScriptException.class, () -> context[0].invoke(context[0].evaluate("bad-constructor.js", "type => new type()"), exports.getMember("HostType")));
            }
        }
    }

    @Test void customConvertersGenericCallbacksCacheAndUsefulHostErrors() {
        try (MantisEngine engine = new MantisEngine()) {
            MantisContext[] context = new MantisContext[1];
            TypeConversions conversions = new TypeConversions(() -> context[0]);
            conversions.register(Location.class, Location::id, value -> new Location(value.asString()));
            try (HostBindings bindings = new HostBindings(conversions)) {
                context[0] = engine.createContext(Map.of("main.js", """
                        import {host, fn} from 'test:api';
                        export {host};
                        export const data = host.echo({name:'x', counts:[1,2], kind:'WOOD'});
                        export const result = host.callback(x => x * 2) + fn(4);
                        export const location = host.location('test:item');
                        export const call = x => host.choose(x);
                        export function broken() { host.fail(); }
                        """), Map.of("test:api", Map.of("host", bindings.bind(new Host("x")),
                        "fn", bindings.function(Function.class, (Function<Integer, Integer>) value -> value + 1))));
                Value exports = context[0].evaluateModule("main.js");
                assertEquals(48, exports.getMember("result").asInt());
                assertThrows(ScriptException.class, () -> context[0].invokeMember(exports.getMember("host"), "callback", context[0].parseJson("{}")));
                assertEquals("test:item", exports.getMember("location").asString());
                assertEquals(new Data("x", List.of(1,2), Optional.of(Kind.WOOD)), conversions.fromScript(exports.getMember("data"), Data.class));
                for (int i = 0; i < 10; i++) context[0].invoke(exports.getMember("call"), 20);
                assertTrue(bindings.cacheStats().resolutionHits() >= 9);
                ScriptException error = assertThrows(ScriptException.class, () -> context[0].invoke(exports.getMember("broken")));
                assertEquals("host failure", error.javaCause().getMessage());
                assertTrue(error.hostCall().contains("Host.fail"));
                assertTrue(error.scriptStack().stream().anyMatch(frame -> frame.function().equals("broken")));
                assertTrue(error.format().contains("main.js"));
                conversions.freeze();
                assertThrows(IllegalStateException.class, () -> conversions.register(String.class, value -> value, Value::asString));
            }
        }
    }
}
