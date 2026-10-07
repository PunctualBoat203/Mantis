package dev.punctualboat.mantis.compat;

import dev.punctualboat.mantis.core.*;
import dev.punctualboat.mantis.interop.*;
import dev.punctualboat.mantis.runtime.ScriptSession;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;

import java.lang.reflect.Array;
import java.util.*;

public final class RhinoCompatibility {
    private RhinoCompatibility() {}

    public static void register(ScriptSession.Registrar registrar, Map<String, Class<?>> allowedTypes) {
        registrar.module("mantis:rhino", Map.of("Java", new JavaApi(registrar.conversions(), registrar.bindings(), allowedTypes)));
    }

    public static final class JavaApi {
        private static final Map<String, Class<?>> ARRAY_TYPES = Map.ofEntries(
                Map.entry("boolean", boolean.class), Map.entry("byte", byte.class), Map.entry("short", short.class),
                Map.entry("int", int.class), Map.entry("long", long.class), Map.entry("float", float.class),
                Map.entry("double", double.class), Map.entry("char", char.class),
                Map.entry("String", String.class), Map.entry("java.lang.String", String.class),
                Map.entry("Object", Object.class), Map.entry("java.lang.Object", Object.class));
        private final TypeConversions conversions;
        private final HostBindings bindings;
        private final Map<String, Class<?>> types;
        public JavaApi(TypeConversions conversions, HostBindings bindings, Map<String, Class<?>> types) {
            this.conversions = conversions; this.bindings = bindings; this.types = Map.copyOf(types);
        }
        @MantisExport public Object type(String name) {
            Class<?> type = types.get(name);
            if (type == null) throw new IllegalArgumentException("Java type is not registered for migration: " + name);
            return bindings.type(type);
        }
        @MantisExport public Object to(Value values, String type) {
            if (!type.endsWith("[]") || type.endsWith("[][]")) throw new IllegalArgumentException("Java.to requires a one-dimensional array type");
            String name = type.substring(0, type.length() - 2);
            Class<?> component = ARRAY_TYPES.get(name);
            if (component == null) component = types.get(name);
            if (component == null) throw new IllegalArgumentException("Array component type is not registered: " + name);
            Class<?> arrayType = Array.newInstance(component, 0).getClass();
            return new JavaArray(conversions.fromScript(values, arrayType), component, conversions);
        }
        @MantisExport public Value from(Value value) { return conversions.toScript(conversions.fromScript(value, Object.class)); }
    }

    private static final class JavaArray implements ProxyArray, JavaBackedValue {
        private final Object array;
        private final Class<?> component;
        private final TypeConversions conversions;
        private JavaArray(Object array, Class<?> component, TypeConversions conversions) { this.array = array; this.component = component; this.conversions = conversions; }
        @Override public Object javaValue() { return array; }
        @Override public Object get(long index) { return conversions.toScript(Array.get(array, index(index))); }
        @Override public void set(long index, Value value) { Array.set(array, index(index), conversions.fromScript(value, component)); }
        @Override public long getSize() { return Array.getLength(array); }
        private int index(long index) {
            if (index < 0 || index >= Array.getLength(array)) throw new IndexOutOfBoundsException("Java array index: " + index);
            return (int) index;
        }
    }
}
