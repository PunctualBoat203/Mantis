package dev.punctualboat.mantis.benchmarks;

import dev.latvian.mods.rhino.*;

import java.util.*;

final class MinecraftRhinoBackend implements EngineBenchmarks.Backend {
    private final Context context = Context.enter();
    private final Scriptable scope = context.initStandardObjects();
    private final Map<String, Function> functions = new HashMap<>();
    private final Script cached;

    MinecraftRhinoBackend() {
        ScriptableObject.putProperty(scope, "host", Context.javaToJS(context, new EngineBenchmarks.Host(7), scope), context);
        ScriptableObject.putProperty(scope, "Host", new NativeJavaClass(context, scope, EngineBenchmarks.Host.class), context);
        context.evaluateString(scope, EngineBenchmarks.FIXTURE, "fixture.js", 1, null);
        EngineBenchmarks.FUNCTIONS.forEach(name -> functions.put(name, (Function) ScriptableObject.getProperty(scope, name, context)));
        cached = context.compileString("simple(21)", "cached.js", 1, null);
    }

    @Override public int call(String name, Object... arguments) {
        return ((Number) functions.get(name).call(context, scope, scope, arguments)).intValue();
    }
    @Override public int evaluate(String name, String code) {
        return ((Number) context.evaluateString(scope, code, name, 1, null)).intValue();
    }
    @Override public Object array(Object[] values) { return context.newArray(scope, values); }
    @Override public Object object(Map<String, Object> values) {
        Scriptable object = context.newObject(scope);
        values.forEach((key, value) -> ScriptableObject.putProperty(object, key, value, context));
        return object;
    }
    @Override public int cachedEvaluate() { return ((Number) cached.exec(context, scope)).intValue(); }
    @Override public int loadCollection(int count) {
        Scriptable next = context.initStandardObjects();
        ScriptableObject.putProperty(next, "globalThis", next, context);
        for (int i = 0; i < count; i++) context.evaluateString(next, "globalThis.value" + i + "=" + i + ";", "script-" + i + ".js", 1, null);
        return ((Number) ScriptableObject.getProperty(next, "value" + (count - 1), context)).intValue();
    }
    @Override public void close() { functions.clear(); }

    public static final class Factory implements EngineBenchmarks.ReferenceBackendFactory {
        public Factory() {}
        @Override public EngineBenchmarks.Backend create() { return new MinecraftRhinoBackend(); }
        @Override public int createAndClose() {
            Context context = Context.enter();
            return ((Number) context.evaluateString(context.initStandardObjects(), "1", "empty.js", 1, null)).intValue();
        }
    }
}
