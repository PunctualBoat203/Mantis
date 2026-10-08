package dev.punctualboat.mantis.interop;

import dev.punctualboat.mantis.core.*;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.*;
import org.graalvm.polyglot.proxy.Proxy;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.lang.ref.*;
import java.util.*;
import java.util.function.*;

public final class HostBindings implements AutoCloseable {
    private record Call(String name, String operation, MethodHandle handle, Type[] types, Class<?>[] raw, boolean varargs, boolean instance) {
        private Call(String name, MethodHandle handle, Type[] types, Class<?>[] raw, boolean varargs, boolean instance) {
            this(name, "bridge:" + name, handle.asSpreader(Object[].class, types.length + (instance ? 1 : 0))
                    .asType(MethodType.methodType(Object.class, Object[].class)), types, raw, varargs, instance);
        }
    }
    private static final class Property { Call getter, setter; }
    private record Plan(Map<String, List<Call>> methods, Map<String, Property> properties, List<Call> constructors) {}
    private static final ClassValue<Plan> PLANS = new ClassValue<>() {
        @Override protected Plan computeValue(Class<?> type) { return describe(type); }
    };
    private final TypeConversions conversions;
    private final BooleanSupplier onThread;
    private final Map<Class<?>, Plan> plans = new HashMap<>();
    private final Map<IdentityReference, WeakReference<Instance>> instances = new HashMap<>();
    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private long metadataHits, metadataMisses, resolutionHits, resolutionMisses;
    private boolean closed;

    public record CacheStats(long metadataHits, long metadataMisses, long resolutionHits, long resolutionMisses) {}

    public HostBindings(TypeConversions conversions, BooleanSupplier onThread) {
        this.conversions = Objects.requireNonNull(conversions);
        this.onThread = Objects.requireNonNull(onThread);
        conversions.interfaces(this::interfaceValue);
        conversions.objects(value -> {
            Plan plan = plan(value.getClass());
            return plan.methods().isEmpty() && plan.properties().isEmpty() ? value : bind(value);
        });
    }
    public HostBindings(TypeConversions conversions) { this(conversions, () -> true); }

    public Instance bind(Object target) {
        checkOpen(); Objects.requireNonNull(target);
        IdentityReference expired;
        while ((expired = (IdentityReference) collected.poll()) != null) instances.remove(expired);
        IdentityReference key = new IdentityReference(target, collected);
        WeakReference<Instance> reference = instances.get(key);
        Instance cached = reference == null ? null : reference.get();
        if (cached != null) return cached;
        Instance binding = new Instance(target, plan(target.getClass()));
        instances.put(key, new WeakReference<>(binding));
        return binding;
    }
    public TypeBinding type(Class<?> type) { checkOpen(); return new TypeBinding(type, plan(type)); }
    public CacheStats cacheStats() { return new CacheStats(metadataHits, metadataMisses, resolutionHits, resolutionMisses); }

    public Object export(Object value) {
        if (value == null || value instanceof Value || value instanceof Proxy || value instanceof Map<?, ?> || value instanceof Collection<?>
                || value.getClass().isRecord() || value.getClass().isArray() || value instanceof Optional<?> || value instanceof Enum<?>
                || value instanceof Number || value instanceof String || value instanceof Boolean || value instanceof Character || conversions.hasConverter(value.getClass()))
            return conversions.export(value);
        Plan plan = plan(value.getClass());
        return plan.methods().isEmpty() && plan.properties().isEmpty() ? conversions.export(value) : bind(value);
    }

    public final class Instance implements ProxyObject, JavaBackedValue {
        private final Object target;
        private final Plan plan;
        private final Map<String, Group> groups = new HashMap<>();
        private Instance(Object target, Plan plan) {
            this.target = target; this.plan = plan;
            plan.methods().forEach((name, methods) -> {
                List<Call> selected = methods.stream().filter(Call::instance).toList();
                if (!selected.isEmpty()) groups.put(name, new Group(target, selected));
            });
        }
        public Object target() { return target; }
        @Override public Object javaValue() { return target; }
        @Override public Object getMember(String name) {
            checkOpen();
            if (groups.containsKey(name)) return groups.get(name);
            Property property = plan.properties().get(name);
            return property != null && property.getter != null && property.getter.instance() ? call(property.getter, target, new Value[0]) : null;
        }
        @Override public Object getMemberKeys() {
            Set<String> names = new TreeSet<>(groups.keySet());
            plan.properties().forEach((name, property) -> { if (property.getter != null && property.getter.instance()) names.add(name); });
            return ProxyArray.fromArray(names.toArray());
        }
        @Override public boolean hasMember(String name) {
            Property property = plan.properties().get(name);
            return groups.containsKey(name) || property != null && property.getter != null && property.getter.instance();
        }
        @Override public void putMember(String name, Value value) {
            Property property = plan.properties().get(name);
            if (property == null || property.setter == null || !property.setter.instance()) throw new UnsupportedOperationException("Read-only host member: " + name);
            call(property.setter, target, new Value[]{value});
        }
    }

    public final class TypeBinding implements ProxyObject, ProxyInstantiable {
        private final Class<?> type;
        private final Plan plan;
        private final Map<String, Group> groups = new HashMap<>();
        private final Group constructors;
        private TypeBinding(Class<?> type, Plan plan) {
            this.type = type; this.plan = plan;
            plan.methods().forEach((name, methods) -> {
                List<Call> selected = methods.stream().filter(method -> !method.instance()).toList();
                if (!selected.isEmpty()) groups.put(name, new Group(null, selected));
            });
            constructors = new Group(null, plan.constructors());
        }
        @Override public Object newInstance(Value... arguments) {
            checkOpen();
            if (plan.constructors().isEmpty()) throw new IllegalArgumentException("No exported constructor: " + type.getName());
            return constructors.execute(arguments);
        }
        @Override public Object getMember(String name) {
            checkOpen();
            if (groups.containsKey(name)) return groups.get(name);
            Property property = plan.properties().get(name);
            return property != null && property.getter != null && !property.getter.instance() ? call(property.getter, null, new Value[0]) : null;
        }
        @Override public Object getMemberKeys() {
            Set<String> names = new TreeSet<>(groups.keySet());
            plan.properties().forEach((name, property) -> { if (property.getter != null && !property.getter.instance()) names.add(name); });
            return ProxyArray.fromArray(names.toArray());
        }
        @Override public boolean hasMember(String name) {
            Property property = plan.properties().get(name);
            return groups.containsKey(name) || property != null && property.getter != null && !property.getter.instance();
        }
        @Override public void putMember(String name, Value value) {
            Property property = plan.properties().get(name);
            if (property == null || property.setter == null || property.setter.instance()) throw new UnsupportedOperationException("Read-only static member: " + name);
            call(property.setter, null, new Value[]{value});
        }
    }

    private final class Group implements ProxyExecutable {
        private final Object receiver;
        private final List<Call> methods;
        private final Map<List<Object>, Call> selected = new LinkedHashMap<>();
        private Object[] lastShape;
        private Call lastMethod;
        private Group(Object receiver, List<Call> methods) { this.receiver = receiver; this.methods = methods; }
        @Override public Object execute(Value... arguments) {
            checkOpen();
            if (methods.size() == 1) {
                Call method = methods.get(0);
                int fixed = method.varargs() ? method.types().length - 1 : method.types().length;
                if (arguments.length < fixed || !method.varargs() && arguments.length != fixed)
                    throw new IllegalArgumentException("No exported overload accepts these arguments");
                resolutionHits++;
                return call(method, receiver, arguments);
            }
            if (lastShape != null && lastShape.length == arguments.length) {
                int index = 0;
                while (index < arguments.length && Objects.equals(lastShape[index], shape(arguments[index]))) index++;
                if (index == arguments.length) { resolutionHits++; return call(lastMethod, receiver, arguments); }
            }
            Object[] shapes = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) shapes[i] = shape(arguments[i]);
            List<Object> key = Arrays.asList(shapes);
            Call method = selected.get(key);
            if (method == null) {
                resolutionMisses++; method = select(methods, arguments);
                if (selected.size() < 256) selected.put(key, method);
            } else resolutionHits++;
            lastShape = shapes; lastMethod = method;
            return call(method, receiver, arguments);
        }
    }

    private Object call(Call call, Object receiver, Value[] arguments) {
        checkOpen();
        return conversions.context().access(call.operation(), () -> {
            try {
                Object[] converted = new Object[call.types().length + (call.instance() ? 1 : 0)];
                int offset = call.instance() ? 1 : 0;
                if (call.instance()) converted[0] = receiver;
                int fixed = call.varargs() ? call.types().length - 1 : call.types().length;
                for (int i = 0; i < fixed; i++) converted[i + offset] = conversions.fromScript(arguments[i], call.types()[i]);
                if (call.varargs()) {
                    if (arguments.length == call.types().length && arguments[fixed].hasArrayElements())
                        converted[fixed + offset] = conversions.fromScript(arguments[fixed], call.types()[fixed]);
                    else {
                        Class<?> component = call.raw()[fixed].getComponentType();
                        Object array = Array.newInstance(component, arguments.length - fixed);
                        for (int i = fixed; i < arguments.length; i++) Array.set(array, i - fixed, conversions.fromScript(arguments[i], component));
                        converted[fixed + offset] = array;
                    }
                }
                Object result = (Object) call.handle().invokeExact(converted);
                return export(result);
            } catch (VirtualMachineError error) { throw error; }
            catch (Throwable error) { throw new HostCallException(call.name(), error); }
        });
    }

    private Call select(List<Call> methods, Value[] arguments) {
        int bestScore = Integer.MAX_VALUE; List<Call> best = new ArrayList<>();
        for (Call method : methods) {
            int fixed = method.varargs() ? method.types().length - 1 : method.types().length;
            if (arguments.length < fixed || !method.varargs() && arguments.length != fixed) continue;
            int score = method.varargs() ? 20 : 0;
            for (int i = 0; i < arguments.length; i++) {
                Type target = i < fixed ? method.types()[i] : arguments.length == method.types().length && arguments[i].hasArrayElements()
                        ? method.types()[fixed] : method.raw()[fixed].getComponentType();
                int cost = cost(arguments[i], target);
                if (cost == Integer.MAX_VALUE) { score = cost; break; }
                score += cost;
            }
            if (score < bestScore) { bestScore = score; best.clear(); best.add(method); }
            else if (score == bestScore && score != Integer.MAX_VALUE) best.add(method);
        }
        if (best.isEmpty()) throw new IllegalArgumentException("No exported overload accepts these arguments");
        if (best.size() == 1) return best.get(0);
        List<Call> specific = best.stream().filter(candidate -> best.stream().noneMatch(other -> other != candidate && moreSpecific(other, candidate))).toList();
        if (specific.size() != 1) throw new IllegalArgumentException("Ambiguous exported overload: " + best.stream().map(Call::name).toList());
        return specific.get(0);
    }

    private int cost(Value value, Type target) {
        Class<?> raw = TypeConversions.rawType(target);
        if (raw == Value.class) return 50;
        if (value.isNull()) return raw.isPrimitive() ? Integer.MAX_VALUE : 1;
        if (value.isHostObject() && raw.isInstance(value.asHostObject())) return 0;
        if (value.isProxyObject() && value.asProxyObject() instanceof Instance instance && raw.isInstance(instance.target())) return 0;
        if (raw == Object.class) return 100;
        if (conversions.hasConverter(raw)) return 30;
        if (raw == String.class && value.isString() || (raw == boolean.class || raw == Boolean.class) && value.isBoolean()) return 0;
        if ((raw == char.class || raw == Character.class) && value.isString() && value.asString().length() == 1) return 1;
        if ((raw == int.class || raw == Integer.class) && value.fitsInInt()) return 0;
        if ((raw == long.class || raw == Long.class) && value.fitsInLong()) return 1;
        if ((raw == short.class || raw == Short.class) && value.fitsInShort()) return 2;
        if ((raw == byte.class || raw == Byte.class) && value.fitsInByte()) return 3;
        if ((raw == double.class || raw == Double.class) && value.fitsInDouble()) return value.fitsInLong() ? 4 : 0;
        if ((raw == float.class || raw == Float.class) && value.fitsInFloat()) return 5;
        if (raw == BigInteger.class && value.fitsInBigInteger()) return value.fitsInLong() ? 6 : 0;
        if (raw.isEnum() && value.isString()) return 3;
        if (raw == Optional.class) { int inner = cost(value, TypeConversions.argument(target, 0)); return inner == Integer.MAX_VALUE ? inner : inner + 2; }
        if (raw.isArray() && value.hasArrayElements()) return 4;
        if ((raw == List.class || raw == Set.class || raw == Collection.class) && value.hasArrayElements()) return 5;
        if ((raw == Map.class || raw.isRecord()) && value.hasMembers() && !value.canExecute()) return 5;
        if (raw.isInterface() && (value.canExecute() || value.hasMembers())) return 10;
        return Integer.MAX_VALUE;
    }

    public ProxyExecutable function(Class<?> functionalType, Object function) {
        Method method = sam(functionalType);
        if (method == null || !functionalType.isInstance(function)) throw new IllegalArgumentException("Expected an instance of a functional interface");
        try {
            Call call = methodCall(method);
            return arguments -> call(call, function, arguments);
        } catch (IllegalAccessException error) { throw new IllegalArgumentException(error); }
    }

    private Object interfaceValue(Value value, Type target) {
        Class<?> raw = TypeConversions.rawType(target);
        if (!raw.isAnnotationPresent(MantisExport.class) && !raw.getPackageName().equals("java.util.function") && raw != Runnable.class)
            throw new IllegalArgumentException("Script implementation requires an approved interface: " + raw.getName());
        Method functional = sam(raw);
        if (value.canExecute() && functional == null) throw new IllegalArgumentException("Interface is not functional: " + raw.getName());
        if (!value.canExecute()) {
            for (Method method : raw.getMethods()) if (Modifier.isAbstract(method.getModifiers()) && !value.canInvokeMember(method.getName()))
                throw new IllegalArgumentException("Script implementation is missing interface method: " + method.getName());
        }
        return java.lang.reflect.Proxy.newProxyInstance(raw.getClassLoader(), new Class<?>[]{raw}, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == arguments[0]; case "hashCode" -> System.identityHashCode(proxy); default -> "Mantis implementation of " + raw.getName();
            };
            checkOpen();
            if (!onThread.getAsBoolean()) throw new IllegalStateException("Script callbacks require the host scheduler thread");
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments == null ? new Object[0] : arguments);
            Object[] passed = arguments == null ? new Object[0] : Arrays.stream(arguments).map(conversions::toScript).toArray();
            Value result = value.canExecute() ? conversions.context().invoke(value, passed) : conversions.context().invokeMember(value, method.getName(), passed);
            return conversions.fromScript(result, resolveParameter(method.getGenericReturnType(), target, raw));
        });
    }

    private static Type resolveParameter(Type parameter, Type target, Class<?> raw) {
        if (parameter instanceof TypeVariable<?> variable && target instanceof ParameterizedType typed) {
            TypeVariable<?>[] variables = raw.getTypeParameters();
            for (int i = 0; i < variables.length; i++) if (variables[i].equals(variable)) return typed.getActualTypeArguments()[i];
        }
        return parameter;
    }
    private static Method sam(Class<?> type) {
        List<Method> methods = Arrays.stream(type.getMethods()).filter(method -> Modifier.isAbstract(method.getModifiers()) && method.getDeclaringClass() != Object.class && !method.isBridge()).toList();
        return methods.size() == 1 ? methods.get(0) : null;
    }
    private Plan plan(Class<?> type) {
        Plan cached = plans.get(type);
        if (cached != null) { metadataHits++; return cached; }
        metadataMisses++; Plan described = PLANS.get(type); plans.put(type, described); return described;
    }
    private static Plan describe(Class<?> type) {
        Map<String, List<Call>> methods = new TreeMap<>(); Map<String, Property> properties = new TreeMap<>(); List<Call> constructors = new ArrayList<>();
        try {
            for (Method method : type.getMethods()) {
                if (method.isBridge() || method.isSynthetic()) continue;
                MantisProperty property = method.getAnnotation(MantisProperty.class);
                if (property != null) {
                    if (property.value().isBlank()) throw new IllegalArgumentException("Empty property name");
                    Property member = properties.computeIfAbsent(property.value(), ignored -> new Property());
                    if (method.getParameterCount() == 0 && method.getReturnType() != void.class && member.getter == null) member.getter = methodCall(method);
                    else if (method.getParameterCount() == 1 && method.getReturnType() == void.class && member.setter == null) member.setter = methodCall(method);
                    else throw new IllegalArgumentException("Invalid or duplicate property: " + method);
                } else if (method.isAnnotationPresent(MantisExport.class)) methods.computeIfAbsent(method.getName(), ignored -> new ArrayList<>()).add(methodCall(method));
            }
            for (Field field : type.getFields()) {
                if (!field.isAnnotationPresent(MantisExport.class)) continue;
                boolean instance = !Modifier.isStatic(field.getModifiers()); Property property = properties.computeIfAbsent(field.getName(), ignored -> new Property());
                if (property.getter != null) throw new IllegalArgumentException("Duplicate field/property: " + field.getName());
                property.getter = new Call(type.getName() + "." + field.getName(), MethodHandles.publicLookup().unreflectGetter(field), new Type[0], new Class<?>[0], false, instance);
                if (!Modifier.isFinal(field.getModifiers())) property.setter = new Call(type.getName() + "." + field.getName(), MethodHandles.publicLookup().unreflectSetter(field),
                        new Type[]{field.getGenericType()}, new Class<?>[]{field.getType()}, false, instance);
            }
            for (Constructor<?> constructor : type.getConstructors()) if (constructor.isAnnotationPresent(MantisExport.class))
                constructors.add(new Call(constructor.toGenericString(), MethodHandles.publicLookup().unreflectConstructor(constructor).asFixedArity(), constructor.getGenericParameterTypes(), constructor.getParameterTypes(), constructor.isVarArgs(), false));
            for (String property : properties.keySet()) if (methods.containsKey(property)) throw new IllegalArgumentException("Method/property name collision: " + property);
            return new Plan(methods, properties, constructors);
        } catch (IllegalAccessException error) { throw new IllegalArgumentException("Exported host members require a public class: " + type.getName(), error); }
    }
    private static Call methodCall(Method method) throws IllegalAccessException {
        return new Call(method.toGenericString(), MethodHandles.publicLookup().unreflect(method).asFixedArity(), method.getGenericParameterTypes(), method.getParameterTypes(), method.isVarArgs(), !Modifier.isStatic(method.getModifiers()));
    }
    private static boolean moreSpecific(Call left, Call right) {
        if (left.raw().length != right.raw().length) return false;
        boolean strict = false;
        for (int i = 0; i < left.raw().length; i++) {
            if (!right.raw()[i].isAssignableFrom(left.raw()[i])) return false;
            strict |= left.raw()[i] != right.raw()[i];
        }
        return strict;
    }
    private static Object shape(Value value) {
        if (value.isNull()) return "null";
        if (value.isHostObject()) return value.asHostObject().getClass();
        if (value.isProxyObject() && value.asProxyObject() instanceof Instance instance) return instance.target().getClass();
        if (value.isString()) return value.asString().length() == 1 ? "char" : "string";
        if (value.isBoolean()) return "boolean";
        if (value.fitsInByte()) return "byte"; if (value.fitsInShort()) return "short"; if (value.fitsInInt()) return "int";
        if (value.fitsInLong()) return "long"; if (value.fitsInFloat()) return "float"; if (value.fitsInDouble()) return "double";
        if (value.fitsInBigInteger()) return "bigint";
        if (value.canExecute()) return "function"; if (value.hasArrayElements()) return "array";
        return "object";
    }
    private void checkOpen() { if (closed) throw new IllegalStateException("Host bindings are closed"); }
    @Override public void close() { closed = true; instances.clear(); plans.clear(); }
    private static final class IdentityReference extends WeakReference<Object> {
        private final int hash;
        private IdentityReference(Object object, ReferenceQueue<Object> queue) { super(object, queue); hash = System.identityHashCode(object); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) { return this == other || other instanceof IdentityReference reference && get() != null && get() == reference.get(); }
    }
}
