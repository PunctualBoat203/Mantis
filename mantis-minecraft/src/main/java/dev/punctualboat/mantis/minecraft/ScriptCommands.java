package dev.punctualboat.mantis.minecraft;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import dev.punctualboat.mantis.core.MantisExport;
import dev.punctualboat.mantis.recipes.*;
import dev.punctualboat.mantis.runtime.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.graalvm.polyglot.Value;
import java.util.*;

public final class ScriptCommands {
    private record Argument(String name, String type, ArgumentType<?> parser, boolean optional, List<String> suggestions) {}
    private record Definition(String name, int permission, List<Argument> arguments, ScriptCallback callback) {}
    private final ScriptSession.Registrar registrar;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private boolean frozen;
    public ScriptCommands(ScriptSession.Registrar registrar) { this.registrar = registrar; }

    @MantisExport public void register(String name, Value options, Value handler) {
        if (frozen) throw new IllegalStateException("Commands must be declared while server scripts load");
        if (!name.matches("[a-z][a-z0-9_.-]*(?::[a-z][a-z0-9_.-]*)?") || name.equals("mantis")) throw new IllegalArgumentException("Invalid or reserved command name: " + name);
        if (definitions.containsKey(name) || definitions.size() >= 512) throw new IllegalArgumentException("Duplicate command or 512-command limit exceeded: " + name);
        JsonElement parsed = JsonCodec.read(options);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Command options must be an object");
        JsonObject json = parsed.getAsJsonObject();
        keys(json, Set.of("permission", "arguments"));
        int permission = json.has("permission") ? integer(json.get("permission")) : 2;
        if (permission < 0 || permission > 4) throw new IllegalArgumentException("Command permission must be 0-4");
        List<Argument> arguments = new ArrayList<>(); Set<String> names = new HashSet<>(); boolean optional = false;
        if (json.has("arguments")) {
            if (!json.get("arguments").isJsonArray() || json.getAsJsonArray("arguments").size() > 16) throw new IllegalArgumentException("Command arguments must be an array of at most 16 definitions");
            for (JsonElement entry : json.getAsJsonArray("arguments")) {
                JsonObject argument = entry.getAsJsonObject(); keys(argument, Set.of("name", "type", "min", "max", "optional", "suggestions"));
                String key = string(argument, "name"), type = string(argument, "type");
                if (!key.matches("[a-zA-Z_][a-zA-Z0-9_]*") || !names.add(key)) throw new IllegalArgumentException("Duplicate or invalid argument name: " + key);
                boolean isOptional = argument.has("optional") && bool(argument.get("optional"));
                if (optional && !isOptional) throw new IllegalArgumentException("Optional arguments must follow required arguments"); optional |= isOptional;
                if (!arguments.isEmpty() && arguments.get(arguments.size()-1).type().equals("greedy")) throw new IllegalArgumentException("Greedy argument must be last");
                if (!Set.of("integer", "double").contains(type) && (argument.has("min") || argument.has("max"))) throw new IllegalArgumentException("Only numeric arguments accept min/max");
                ArgumentType<?> parser = switch (type) {
                    case "integer" -> {
                        int min = argument.has("min") ? integer(argument.get("min")) : Integer.MIN_VALUE;
                        int max = argument.has("max") ? integer(argument.get("max")) : Integer.MAX_VALUE;
                        if (min > max) throw new IllegalArgumentException("Argument min exceeds max"); yield IntegerArgumentType.integer(min, max);
                    }
                    case "double" -> {
                        double min = argument.has("min") ? number(argument.get("min")) : -Double.MAX_VALUE;
                        double max = argument.has("max") ? number(argument.get("max")) : Double.MAX_VALUE;
                        if (min > max) throw new IllegalArgumentException("Argument min exceeds max"); yield DoubleArgumentType.doubleArg(min, max);
                    }
                    case "boolean" -> BoolArgumentType.bool();
                    case "word" -> StringArgumentType.word();
                    case "string" -> StringArgumentType.string();
                    case "greedy" -> StringArgumentType.greedyString();
                    default -> throw new IllegalArgumentException("Unknown command argument type: " + type);
                };
                List<String> suggestions = new ArrayList<>();
                if (argument.has("suggestions")) {
                    if (!Set.of("word", "string", "greedy").contains(type) || !argument.get("suggestions").isJsonArray() || argument.getAsJsonArray("suggestions").size() > 256) throw new IllegalArgumentException("Suggestions require a string argument and at most 256 values");
                    for (JsonElement suggestion : argument.getAsJsonArray("suggestions")) {
                        if (!suggestion.isJsonPrimitive() || !suggestion.getAsJsonPrimitive().isString() || suggestion.getAsString().length() > 256) throw new IllegalArgumentException("Suggestion must be a string of at most 256 characters");
                        suggestions.add(suggestion.getAsString());
                    }
                }
                arguments.add(new Argument(key, type, parser, isOptional, List.copyOf(suggestions)));
            }
        }
        definitions.put(name, new Definition(name, permission, List.copyOf(arguments), registrar.callback(handler)));
    }
    public void freeze() { frozen = true; }
    public void validate(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : definitions.keySet()) if (dispatcher.getRoot().getChild(name) != null) throw new IllegalArgumentException("Script command conflicts with an existing command: " + name);
    }
    public void install(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!frozen) throw new IllegalStateException("Command declarations have not finished");
        validate(dispatcher);
        definitions.values().forEach(definition -> dispatcher.register(node(definition)));
    }
    private LiteralArgumentBuilder<CommandSourceStack> node(Definition definition) {
        var root = LiteralArgumentBuilder.<CommandSourceStack>literal(definition.name()).requires(source -> source.hasPermission(definition.permission()) && definition.callback().active());
        ArgumentBuilder<CommandSourceStack, ?> cursor = null;
        for (int i = definition.arguments().size() - 1; i >= 0; i--) {
            Argument argument = definition.arguments().get(i);
            var next = RequiredArgumentBuilder.<CommandSourceStack, Object>argument(argument.name(), cast(argument.parser()));
            if (!argument.suggestions().isEmpty()) next.suggests((context, builder) -> {
                String remaining = builder.getRemainingLowerCase();
                argument.suggestions().stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(remaining)).forEach(builder::suggest);
                return builder.buildFuture();
            });
            if (i == definition.arguments().size()-1 || definition.arguments().get(i+1).optional()) {
                int count = i+1; next.executes(context -> execute(definition, context, count));
            }
            if (cursor != null) next.then(cursor); cursor = next;
        }
        if (definition.arguments().isEmpty() || definition.arguments().get(0).optional()) root.executes(context -> execute(definition, context, 0));
        if (cursor != null) root.then(cursor); return root;
    }
    @SuppressWarnings("unchecked") private static ArgumentType<Object> cast(ArgumentType<?> parser) { return (ArgumentType<Object>) parser; }
    private int execute(Definition definition, CommandContext<CommandSourceStack> command, int count) {
        CommandSourceStack source = command.getSource();
        if (!source.getServer().isSameThread()) throw new IllegalStateException("Commands execute on the server thread");
        if (!definition.callback().active()) return 0;
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < definition.arguments().size(); i++) {
            Argument argument = definition.arguments().get(i); Object value = null;
            if (i < count) value = switch (argument.type()) {
                case "integer" -> IntegerArgumentType.getInteger(command, argument.name());
                case "double" -> DoubleArgumentType.getDouble(command, argument.name());
                case "boolean" -> BoolArgumentType.getBool(command, argument.name());
                default -> StringArgumentType.getString(command, argument.name());
            };
            args.put(argument.name(), value);
        }
        try {
            return definition.callback().callValidated(result -> {
                if (result.isNull()) return 1;
                if (!result.fitsInInt()) throw new IllegalArgumentException("Command handler must return an integer or undefined");
                return result.asInt();
            }, Map.of("source", new Source(source), "args", args, "input", command.getInput()));
        } catch (RuntimeException error) { source.sendFailure(Component.literal("Mantis command failed: " + definition.name())); return 0; }
    }
    public static final class Source {
        private final CommandSourceStack source;
        private Source(CommandSourceStack source) { this.source = source; }
        private void check() { if (!source.getServer().isSameThread()) throw new IllegalStateException("Command source operations require the server thread"); }
        @MantisExport public String name() { check(); return source.getTextName(); }
        @MantisExport public void reply(String message) { check(); source.sendSuccess(() -> Component.literal(message), false); }
        @MantisExport public void error(String message) { check(); source.sendFailure(Component.literal(message)); }
        @MantisExport public boolean hasPermission(int level) { check(); return source.hasPermission(level); }
        @MantisExport public MinecraftBindings.Player player() { check(); return source.getPlayer() == null ? null : new MinecraftBindings.Player(source.getPlayer()); }
        @MantisExport public String dimension() { check(); return source.getLevel().dimension().location().toString(); }
    }
    private static void keys(JsonObject object, Set<String> allowed) { for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown command option: " + key); }
    private static String string(JsonObject object, String key) { JsonElement value = object.get(key); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be a string"); return value.getAsString(); }
    private static boolean bool(JsonElement value) { if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("optional must be boolean"); return value.getAsBoolean(); }
    private static int integer(JsonElement value) { if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected an integer"); try { return value.getAsBigDecimal().intValueExact(); } catch (ArithmeticException error) { throw new IllegalArgumentException("Expected an integer in range", error); } }
    private static double number(JsonElement value) { if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw new IllegalArgumentException("Expected a finite number"); return value.getAsDouble(); }
}
