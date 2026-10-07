package dev.punctualboat.mantis.interop;

import dev.punctualboat.mantis.core.MantisContext;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.Proxy;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.*;

public final class TypeConversions {
    public interface Converter<T> {
        Object toScript(T value, TypeConversions conversions);
        T fromScript(Value value, Type target, TypeConversions conversions);
    }
    private record Registered(Class<?> type, Converter<Object> converter) {}
    private record RecordPlan(RecordComponent[] components, MethodHandle[] getters, MethodHandle constructor) {}
    private static final ClassValue<RecordPlan> RECORDS = new ClassValue<>() {
        @Override protected RecordPlan computeValue(Class<?> type) {
            try {
                RecordComponent[] components = type.getRecordComponents();
                MethodHandle[] getters = new MethodHandle[components.length];
                Class<?>[] parameters = new Class<?>[components.length];
                for (int i = 0; i < components.length; i++) {
                    getters[i] = MethodHandles.publicLookup().unreflect(components[i].getAccessor());
                    parameters[i] = components[i].getType();
                }
                return new RecordPlan(components, getters, MethodHandles.publicLookup().unreflectConstructor(type.getConstructor(parameters)));
            } catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Record must have public accessors and constructor: " + type.getName(), error); }
        }
    };
    private final Supplier<MantisContext> context;
    private final Map<Class<?>, Registered> converters = new LinkedHashMap<>();
    private final Map<Class<?>, Optional<Registered>> resolved = new HashMap<>();
    private BiFunction<Value, Type, Object> interfaceAdapter;
    private boolean frozen;

    public TypeConversions(Supplier<MantisContext> context) { this.context = Objects.requireNonNull(context); }
    public MantisContext context() { return context.get(); }

    @SuppressWarnings("unchecked")
    public <T> void register(Class<T> type, Converter<T> converter) {
        if (frozen) throw new IllegalStateException("Type converters are frozen after script loading begins");
        if (converters.putIfAbsent(type, new Registered(type, (Converter<Object>) Objects.requireNonNull(converter))) != null)
            throw new IllegalArgumentException("Duplicate converter: " + type.getName());
        resolved.clear();
    }

    public <T> void register(Class<T> type, Function<T, Object> toScript, Function<Value, T> fromScript) {
        register(type, new Converter<>() {
            @Override public Object toScript(T value, TypeConversions conversions) { return toScript.apply(value); }
            @Override public T fromScript(Value value, Type target, TypeConversions conversions) { return fromScript.apply(value); }
        });
    }

    public void interfaces(BiFunction<Value, Type, Object> adapter) {
        if (frozen) throw new IllegalStateException("Converters are frozen");
        interfaceAdapter = Objects.requireNonNull(adapter);
    }
    public void freeze() { frozen = true; }

    public Value toScript(Object object) {
        return context().access("convert:java-to-js", () -> context().value(encode(object, new IdentityHashMap<>(), 0)));
    }

    private Object encode(Object value, IdentityHashMap<Object, Boolean> path, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Conversion exceeds 64 nested values");
        if (value instanceof BigInteger integer) return context().bigInteger(integer.toString());
        if (value instanceof Long integer && (integer > 9_007_199_254_740_991L || integer < -9_007_199_254_740_991L))
            return context().bigInteger(integer.toString());
        if (value == null || value instanceof Value || value instanceof Proxy || value instanceof String
                || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Character character) return character.toString();
        Registered converter = resolve(value.getClass());
        if (converter != null) {
            Object result = converter.converter().toScript(value, this);
            return result == value ? result : encode(result, path, depth + 1);
        }
        if (value instanceof Optional<?> optional) return encode(optional.orElse(null), path, depth + 1);
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        boolean structured = value instanceof Collection<?> || value instanceof Map<?, ?> || value.getClass().isArray() || value.getClass().isRecord();
        if (!structured) return value;
        if (path.put(value, true) != null) throw new IllegalArgumentException("Cyclic Java collection or record");
        try {
            if (value instanceof Collection<?> collection) {
                checkSize(collection.size());
                Object[] items = new Object[collection.size()]; int index = 0;
                for (Object item : collection) items[index++] = encode(item, path, depth + 1);
                return context().array(items);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value); checkSize(length);
                Object[] items = new Object[length];
                for (int i = 0; i < length; i++) items[i] = encode(Array.get(value, i), path, depth + 1);
                return context().array(items);
            }
            Map<String, Object> members = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> map) {
                checkSize(map.size());
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Java maps require string keys for JS objects");
                    members.put(key, encode(entry.getValue(), path, depth + 1));
                }
            } else {
                RecordPlan plan = RECORDS.get(value.getClass());
                for (int i = 0; i < plan.components().length; i++) {
                    try { members.put(plan.components()[i].getName(), encode(plan.getters()[i].invoke(value), path, depth + 1)); }
                    catch (Throwable error) { throw conversionError(value.getClass(), error); }
                }
            }
            return context().object(members);
        } finally { path.remove(value); }
    }

    public <T> T fromScript(Value value, Class<T> target) {
        @SuppressWarnings("unchecked") T converted = (T) fromScript(value, (Type) target);
        return converted;
    }
    public Object fromScript(Value value, Type target) {
        return context().access("convert:js-to-java", () -> decode(value, target, new HashSet<>(), 0));
    }

    private Object decode(Value value, Type target, Set<Value> path, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Conversion exceeds 64 nested values");
        Class<?> raw = rawType(target);
        if (raw == Value.class) return value;
        if (raw == void.class || raw == Void.class) return null;
        if (raw == Optional.class) return Optional.ofNullable(decode(value, argument(target, 0), path, depth + 1));
        if (value == null || value.isNull()) {
            if (raw.isPrimitive()) throw new IllegalArgumentException("Cannot convert null to " + raw.getName());
            return null;
        }
        if (value.isHostObject() && raw.isInstance(value.asHostObject())) return value.asHostObject();
        if (value.isProxyObject() && value.asProxyObject() instanceof JavaBackedValue backed && raw.isInstance(backed.javaValue())) return backed.javaValue();
        Registered converter = resolve(raw);
        if (converter != null) return converter.converter().fromScript(value, target, this);
        if (raw == String.class && value.isString()) return value.asString();
        if ((raw == char.class || raw == Character.class) && value.isString() && value.asString().length() == 1) return value.asString().charAt(0);
        if ((raw == boolean.class || raw == Boolean.class) && value.isBoolean()) return value.asBoolean();
        if ((raw == byte.class || raw == Byte.class) && value.fitsInByte()) return value.asByte();
        if ((raw == short.class || raw == Short.class) && value.fitsInShort()) return value.asShort();
        if ((raw == int.class || raw == Integer.class) && value.fitsInInt()) return value.asInt();
        if ((raw == long.class || raw == Long.class) && value.fitsInLong()) return value.asLong();
        if ((raw == float.class || raw == Float.class) && value.fitsInFloat()) return value.asFloat();
        if ((raw == double.class || raw == Double.class) && value.fitsInDouble()) return value.asDouble();
        if (raw == BigInteger.class && value.fitsInBigInteger()) return value.asBigInteger();
        if (raw.isEnum() && value.isString()) {
            @SuppressWarnings({"rawtypes", "unchecked"}) Object result = Enum.valueOf((Class) raw, value.asString()); return result;
        }
        if (raw.isInterface() && interfaceAdapter != null && raw != Map.class && raw != List.class && raw != Set.class && raw != Collection.class)
            return interfaceAdapter.apply(value, target);
        if (raw == Object.class) {
            if (value.isString()) return value.asString();
            if (value.isBoolean()) return value.asBoolean();
            if (value.fitsInInt()) return value.asInt();
            if (value.fitsInLong()) return value.asLong();
            if (value.fitsInBigInteger() && !value.fitsInDouble()) return value.asBigInteger();
            if (value.isNumber()) return value.asDouble();
            if (value.isHostObject()) return value.asHostObject();
        }
        if (!path.add(value)) throw new IllegalArgumentException("Cyclic JavaScript object");
        try {
            if ((raw.isArray() || raw == List.class || raw == Collection.class || raw == Set.class || raw == Object.class) && value.hasArrayElements()) {
                long size = value.getArraySize(); checkSize(size);
                Type element = raw.isArray() ? raw.getComponentType() : argument(target, 0);
                if (raw.isArray()) {
                    Object array = Array.newInstance(raw.getComponentType(), (int) size);
                    for (int i = 0; i < size; i++) Array.set(array, i, decode(value.getArrayElement(i), element, path, depth + 1));
                    return array;
                }
                Collection<Object> collection = raw == Set.class ? new LinkedHashSet<>() : new ArrayList<>();
                for (int i = 0; i < size; i++) collection.add(decode(value.getArrayElement(i), element, path, depth + 1));
                return collection;
            }
            if ((raw == Map.class || raw == Object.class) && value.hasMembers() && !value.canExecute()) {
                if (rawType(argument(target, 0)) != Object.class && rawType(argument(target, 0)) != String.class)
                    throw new IllegalArgumentException("Object maps require String keys");
                Set<String> keys = value.getMemberKeys(); checkSize(keys.size());
                Map<String, Object> members = new LinkedHashMap<>();
                for (String key : keys) members.put(key, decode(value.getMember(key), argument(target, 1), path, depth + 1));
                return members;
            }
            if (raw.isRecord() && value.hasMembers()) {
                RecordPlan plan = RECORDS.get(raw); Object[] arguments = new Object[plan.components().length];
                Set<String> expected = new HashSet<>();
                for (int i = 0; i < arguments.length; i++) {
                    RecordComponent component = plan.components()[i]; expected.add(component.getName());
                    if (!value.hasMember(component.getName())) throw new IllegalArgumentException("Missing record field: " + component.getName());
                    arguments[i] = decode(value.getMember(component.getName()), component.getGenericType(), path, depth + 1);
                }
                if (!expected.containsAll(value.getMemberKeys())) throw new IllegalArgumentException("Unknown record fields for " + raw.getName());
                try { return plan.constructor().invokeWithArguments(arguments); }
                catch (Throwable error) { throw conversionError(raw, error); }
            }
        } finally { path.remove(value); }
        throw new IllegalArgumentException("Cannot convert script value to " + target.getTypeName());
    }

    public boolean hasConverter(Class<?> type) { return resolve(type) != null; }
    private Registered resolve(Class<?> type) {
        return resolved.computeIfAbsent(type, key -> {
            Registered exact = converters.get(key); if (exact != null) return Optional.of(exact);
            List<Registered> matches = converters.values().stream().filter(candidate -> candidate.type().isAssignableFrom(key)).toList();
            List<Registered> best = matches.stream().filter(candidate -> matches.stream().noneMatch(other -> other != candidate && candidate.type().isAssignableFrom(other.type()))).toList();
            if (best.size() > 1) throw new IllegalArgumentException("Ambiguous converters for " + type.getName());
            return best.stream().findFirst();
        }).orElse(null);
    }
    public static Class<?> rawType(Type type) {
        if (type instanceof Class<?> raw) return raw;
        if (type instanceof ParameterizedType parameterized) return rawType(parameterized.getRawType());
        if (type instanceof WildcardType wildcard) return rawType(wildcard.getUpperBounds()[0]);
        if (type instanceof TypeVariable<?>) return Object.class;
        throw new IllegalArgumentException("Unsupported Java type: " + type);
    }
    public static Type argument(Type type, int index) {
        if (type instanceof ParameterizedType parameterized && parameterized.getActualTypeArguments().length > index)
            return parameterized.getActualTypeArguments()[index];
        return Object.class;
    }
    private static void checkSize(long size) { if (size > 100_000) throw new IllegalArgumentException("Conversion exceeds 100,000 entries"); }
    private static IllegalArgumentException conversionError(Class<?> type, Throwable error) { return new IllegalArgumentException("Could not convert " + type.getName() + ": " + error.getMessage(), error); }
}
