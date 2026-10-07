package dev.punctualboat.mantis.benchmarks;

import dev.punctualboat.mantis.core.*;
import dev.punctualboat.mantis.interop.*;
import dev.punctualboat.mantis.runtime.EventBus;
import org.graalvm.polyglot.Value;
import org.mozilla.javascript.*;
import org.openjdk.jmh.annotations.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EngineBenchmarks {
    static final String FIXTURE = """
            function simple(x) { return x * 2 + 1; }
            function loop(x) { var n=0; for(var i=0;i<128;i++) n += (x+i)%7; return n; }
            function method(x) { return host.doubleValue(x); }
            function staticMethod(x) { return Host.twice(x); }
            function field(x) { return host.number + x; }
            function property(x) { return String(host.label).length + x; }
            function construct(x) { return new Host(x).number; }
            function overload(x) { return host.choose(x); }
            function array(values) { var sum=0; for(var i=0;i<values.length;i++) sum+=values[i]; return sum; }
            function map(value) { return value.count + value.name.length; }
            function recipes(count) {
              var out=[]; for(var i=0;i<count;i++) out.push({type:'example:machine',energy:1000,result:{count:1}});
              var total=0; for(var j=0;j<out.length;j++){out[j].energy*=2;out[j].result.count=4;total+=out[j].energy;}
              return total;
            }
            function event(x) { return x + 1; }
            """;
    private static final List<String> FUNCTIONS = List.of("simple","loop","method","staticMethod","field","property","construct","overload","array","map","recipes","event");

    public static final class Host {
        @MantisExport public int number;
        @MantisExport public Host(int number) { this.number = number; }
        @MantisProperty("label") public String getLabel() { return "mantis"; }
        @MantisExport public static int twice(int value) { return value * 2; }
        @MantisExport public int doubleValue(int value) { return value * 2; }
        @MantisExport public int choose(int value) { return value + 1; }
        @MantisExport public int choose(String value) { return value.length(); }
    }

    interface Backend extends AutoCloseable {
        int call(String name, Object... arguments);
        int evaluate(String name, String code);
        Object array(Object[] values);
        Object object(Map<String, Object> values);
        int cachedEvaluate();
        int loadCollection(int count);
        @Override void close();
    }
    static Backend create(String backend) { return backend.equals("mantis") ? new MantisBackend() : new RhinoBackend(backend.equals("rhino-interpreted") ? -1 : 9); }

    private static final class MantisBackend implements Backend {
        private final MantisEngine engine = new MantisEngine();
        private final MantisContext context;
        private final TypeConversions conversions;
        private final HostBindings bindings;
        private final Map<String, Value> functions = new HashMap<>();
        MantisBackend() {
            MantisContext[] ready = new MantisContext[1];
            conversions = new TypeConversions(() -> ready[0]); bindings = new HostBindings(conversions);
            context = engine.createContext(Map.of("hosts.js", "import {host,Host} from 'benchmark:host'; globalThis.host=host; globalThis.Host=Host;"),
                    Map.of("benchmark:host", Map.of("host", bindings.bind(new Host(7)), "Host", bindings.type(Host.class)))); ready[0] = context;
            context.evaluateModule("hosts.js"); context.evaluate("fixture.js", FIXTURE);
            FUNCTIONS.forEach(name -> functions.put(name, context.evaluate("select:" + name, name)));
        }
        @Override public int call(String name, Object... arguments) { return context.invoke(functions.get(name), arguments).asInt(); }
        @Override public int evaluate(String name, String code) { return context.evaluate(name, code).asInt(); }
        @Override public Object array(Object[] values) { return conversions.toScript(values); }
        @Override public Object object(Map<String, Object> values) { return conversions.toScript(values); }
        @Override public int cachedEvaluate() { return evaluate("cached.js", "simple(21)"); }
        @Override public int loadCollection(int count) {
            Map<String, String> sources = new TreeMap<>();
            for (int i=0;i<count;i++) sources.put("script-" + i + ".js", "globalThis.value" + i + "=" + i + ";");
            MantisContext next = engine.createContext(sources, Map.of());
            try { sources.keySet().forEach(next::evaluateModule); return next.evaluate("read.js", "value" + (count-1)).asInt(); }
            finally { engine.release(next); }
        }
        @Override public void close() { bindings.close(); engine.close(); }
    }

    private static final class RhinoBackend implements Backend {
        private final Context context;
        private final Scriptable scope;
        private final Script cached;
        private final Map<String, Function> functions = new HashMap<>();
        RhinoBackend(int optimization) {
            context = new ContextFactory().enterContext(); context.setLanguageVersion(Context.VERSION_ES6); context.setOptimizationLevel(optimization);
            scope = context.initStandardObjects();
            ScriptableObject.putProperty(scope, "host", Context.javaToJS(new Host(7), scope));
            ScriptableObject.putProperty(scope, "Host", new NativeJavaClass(scope, Host.class));
            context.evaluateString(scope, FIXTURE, "fixture.js", 1, null);
            FUNCTIONS.forEach(name -> functions.put(name, (Function) ScriptableObject.getProperty(scope, name)));
            cached = context.compileString("simple(21)", "cached.js", 1, null);
        }
        @Override public int call(String name, Object... arguments) { return ((Number) functions.get(name).call(context, scope, scope, arguments)).intValue(); }
        @Override public int evaluate(String name, String code) { return ((Number) context.evaluateString(scope, code, name, 1, null)).intValue(); }
        @Override public Object array(Object[] values) { return context.newArray(scope, values); }
        @Override public Object object(Map<String, Object> values) {
            Scriptable object = context.newObject(scope); values.forEach((key, value) -> ScriptableObject.putProperty(object, key, value)); return object;
        }
        @Override public int cachedEvaluate() { return ((Number) cached.exec(context, scope)).intValue(); }
        @Override public int loadCollection(int count) {
            Scriptable next = context.initStandardObjects();
            for (int i=0;i<count;i++) context.evaluateString(next, "globalThis.value" + i + "=" + i + ";", "script-" + i + ".js", 1, null);
            return ((Number) ScriptableObject.getProperty(next, "value" + (count-1))).intValue();
        }
        @Override public void close() { Context.exit(); }
    }

    @State(Scope.Thread)
    public static class Warm {
        @Param({"mantis", "rhino-interpreted", "rhino-compiled"}) public String backend;
        Backend runtime;
        EventBus events;
        Object[] array = {1,2,3,4,5,6,7,8};
        Map<String,Object> map = Map.of("count", 4, "name", "mantis");
        int sequence;
        @Setup public void setup() {
            runtime = create(backend); events = new EventBus(error -> { throw new AssertionError(error); });
            events.subscribe("tick", value -> runtime.call("event", value), false);
            if (runtime.call("simple", 21) != 43 || runtime.call("recipes", 256) != 512000) throw new AssertionError("Benchmark fixture failed");
        }
        @TearDown public void close() { runtime.close(); }
    }

    @State(Scope.Thread)
    public static class Fresh {
        @Param({"mantis", "rhino-interpreted", "rhino-compiled"}) public String backend;
    }

    @Benchmark public int createAndClose(Fresh fresh) {
        if (fresh.backend.equals("mantis")) {
            try (MantisEngine engine = new MantisEngine()) { return engine.createContext(Map.of(), Map.of()).evaluate("empty.js", "1").asInt(); }
        }
        Context context = new ContextFactory().enterContext();
        try { context.setLanguageVersion(Context.VERSION_ES6); context.setOptimizationLevel(fresh.backend.equals("rhino-interpreted") ? -1 : 9);
            return ((Number) context.evaluateString(context.initStandardObjects(), "1", "empty.js", 1, null)).intValue(); }
        finally { Context.exit(); }
    }
    @Benchmark public int parseAndEvaluate(Warm state) { int value = ++state.sequence; return state.runtime.evaluate("parse.js", value + "+1"); }
    @Benchmark public int cachedEvaluate(Warm state) { return state.runtime.cachedEvaluate(); }
    @Benchmark public int functionCall(Warm state) { return state.runtime.call("simple", state.sequence++ & 255); }
    @Benchmark public int hotLoop(Warm state) { return state.runtime.call("loop", state.sequence++ & 255); }
    @Benchmark public int javaMethod(Warm state) { return state.runtime.call("method", state.sequence++ & 255); }
    @Benchmark public int staticMethod(Warm state) { return state.runtime.call("staticMethod", state.sequence++ & 255); }
    @Benchmark public int fieldRead(Warm state) { return state.runtime.call("field", state.sequence++ & 255); }
    @Benchmark public int propertyRead(Warm state) { return state.runtime.call("property", state.sequence++ & 255); }
    @Benchmark public int constructor(Warm state) { return state.runtime.call("construct", state.sequence++ & 255); }
    @Benchmark public int overloadedMethod(Warm state) { return state.runtime.call("overload", state.sequence++ & 255); }
    @Benchmark public int arrayConversion(Warm state) { return state.runtime.call("array", state.runtime.array(state.array)); }
    @Benchmark public int mapConversion(Warm state) { return state.runtime.call("map", state.runtime.object(state.map)); }
    @Benchmark public int recipeEdits(Warm state) { return state.runtime.call("recipes", 256); }
    @Benchmark public int eventDispatch(Warm state) { state.events.emit("tick", state.sequence++ & 255); return state.events.listenerCount(); }
    @Benchmark public int load100Scripts(Warm state) { return state.runtime.loadCollection(100); }
}
