package dev.punctualboat.mantis.recipes;

import com.google.gson.*;
import dev.punctualboat.mantis.core.MantisContext;
import org.graalvm.polyglot.Value;

import java.util.*;

public final class JsonCodec {
    private static final int MAX_NUMBER_DIGITS = 10_000;
    private JsonCodec() {}
    public static JsonElement read(Value value) { return read(value, new HashSet<>(), 0, new int[]{0}); }
    private static JsonElement read(Value value, Set<Value> path, int depth, int[] nodes) {
        if (depth > 64 || ++nodes[0] > 100_000) throw new IllegalArgumentException("Recipe JSON exceeds conversion limits");
        if (value.isNull()) return JsonNull.INSTANCE;
        if (value.isString()) return new JsonPrimitive(value.asString());
        if (value.isBoolean()) return new JsonPrimitive(value.asBoolean());
        if (value.isNumber()) {
            if (value.fitsInLong()) return new JsonPrimitive(value.asLong());
            if (value.fitsInBigInteger()) return new JsonPrimitive(value.asBigInteger());
            if (!value.fitsInDouble()) throw new IllegalArgumentException("JSON number cannot be represented");
            if (!Double.isFinite(value.asDouble())) throw new IllegalArgumentException("JSON numbers must be finite");
            return new JsonPrimitive(value.asDouble());
        }
        if (value.canExecute() || value.isHostObject() || !path.add(value)) throw new IllegalArgumentException("Recipe JSON contains a function, host object, or cycle");
        try {
            if (value.hasArrayElements()) {
                if (value.getArraySize() > 100_000) throw new IllegalArgumentException("Recipe array is too large");
                JsonArray array = new JsonArray();
                for (long i = 0; i < value.getArraySize(); i++) array.add(read(value.getArrayElement(i), path, depth + 1, nodes));
                return array;
            }
            if (value.hasMembers()) {
                JsonObject object = new JsonObject();
                for (String key : value.getMemberKeys()) object.add(key, read(value.getMember(key), path, depth + 1, nodes));
                return object;
            }
            throw new IllegalArgumentException("Expected a JSON value");
        } finally { path.remove(value); }
    }
    public static Value write(MantisContext context, JsonElement json) {
        return context.access("recipe:json-to-js", () -> write(context, json, 0, new int[]{0}));
    }
    private static Value write(MantisContext context, JsonElement json, int depth, int[] nodes) {
        if (depth > 64 || ++nodes[0] > 100_000) throw new IllegalArgumentException("Recipe JSON exceeds conversion limits");
        if (json.isJsonNull()) return context.value(null);
        if (json.isJsonPrimitive()) {
            var p = json.getAsJsonPrimitive();
            if (p.isString()) return context.value(p.getAsString());
            if (p.isBoolean()) return context.value(p.getAsBoolean());
            if (p.getAsString().length() > MAX_NUMBER_DIGITS + 16) throw new IllegalArgumentException("JSON number exceeds conversion limits");
            var decimal = p.getAsBigDecimal();
            if (decimal.signum() != 0 && (decimal.precision() > MAX_NUMBER_DIGITS || Math.abs((long) decimal.scale()) > MAX_NUMBER_DIGITS
                    || (long) decimal.precision() - decimal.scale() > MAX_NUMBER_DIGITS)) {
                throw new IllegalArgumentException("JSON number exceeds conversion limits");
            }
            try {
                var integer = decimal.toBigIntegerExact();
                return integer.abs().bitLength() <= 53 ? context.value(integer.longValue()) : context.bigInteger(integer.toString());
            } catch (ArithmeticException ignored) {
                if (!Double.isFinite(p.getAsDouble())) throw new IllegalArgumentException("JSON numbers must be finite");
                return context.value(p.getAsDouble());
            }
        }
        if (json.isJsonArray()) {
            if (json.getAsJsonArray().size() > 100_000) throw new IllegalArgumentException("Recipe array is too large");
            Object[] values = new Object[json.getAsJsonArray().size()];
            for (int i = 0; i < values.length; i++) values[i] = write(context, json.getAsJsonArray().get(i), depth + 1, nodes);
            return context.array(values);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        json.getAsJsonObject().entrySet().forEach(e -> values.put(e.getKey(), write(context, e.getValue(), depth + 1, nodes)));
        return context.object(values);
    }
}
