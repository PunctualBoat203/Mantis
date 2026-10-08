package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.core.MantisExport;
import dev.punctualboat.mantis.compat.RhinoCompatibility;
import dev.punctualboat.mantis.runtime.ScriptSession;
import dev.punctualboat.mantis.minecraft.api.MantisApi;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import java.util.Map;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;
import dev.punctualboat.mantis.recipes.RecipeValues;

public final class MinecraftBindings {
    private MinecraftBindings() {}
    public static void register(ScriptSession.Registrar registrar) {
        registrar.conversions().register(ResourceLocation.class, ResourceLocation::toString, value -> new ResourceLocation(value.asString()));
        registrar.conversions().register(Component.class, component -> Map.of("text", component.getString(), "json", Component.Serializer.toJson(component)), value -> {
            if (value.isString()) return Component.literal(value.asString());
            if (value.hasMember("json")) {
                Component parsed = Component.Serializer.fromJson(value.getMember("json").asString());
                if (parsed != null) return parsed;
            }
            if (value.hasMember("text")) return Component.literal(value.getMember("text").asString());
            throw new IllegalArgumentException("Component requires a string, text, or JSON");
        });
        RhinoCompatibility.register(registrar, MantisApi.rhinoTypes());
    }
    public static final class Mods {
        @MantisExport public boolean isLoaded(String id) { return ModList.get().isLoaded(id); }
    }
    public static final class Server {
        private MinecraftServer ready() {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null || !server.isSameThread()) throw new IllegalStateException("Server operations require a running server on its main thread");
            return server;
        }
        @MantisExport public void broadcast(String message) { ready().getPlayerList().broadcastSystemMessage(Component.literal(message), false); }
        @MantisExport public int runCommand(String command) {
            MinecraftServer server = ready();
            return server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }
        @MantisExport public List<Player> players() { return ready().getPlayerList().getPlayers().stream().map(Player::new).toList(); }
        @MantisExport public LevelRef level(String dimension) {
            MinecraftServer server = ready();
            ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, new ResourceLocation(RecipeValues.id(dimension))));
            if (level == null) throw new IllegalArgumentException("Unknown dimension: " + dimension); return new LevelRef(level);
        }
    }
    private static void check(Entity entity) {
        if (entity.level().isClientSide || entity.level().getServer() == null || !entity.level().getServer().isSameThread()) throw new IllegalStateException("Entity operations require the server thread");
    }
    public static final class Stack {
        private final ItemStack stack;
        public Stack(ItemStack stack) { this.stack = stack.copy(); }
        @MantisExport public String id() { return java.util.Objects.requireNonNull(ForgeRegistries.ITEMS.getKey(stack.getItem())).toString(); }
        @MantisExport public int count() { return stack.getCount(); }
        @MantisExport public String nbt() { return stack.hasTag() ? stack.getTag().toString() : "{}"; }
    }
    public static final class EntityRef {
        private final Entity entity;
        public EntityRef(Entity entity) { this.entity = entity; }
        @MantisExport public String uuid() { check(entity); return entity.getUUID().toString(); }
        @MantisExport public String type() { check(entity); return java.util.Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())).toString(); }
        @MantisExport public Map<String, Double> position() { check(entity); return Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ()); }
        @MantisExport public double health() { check(entity); if (!(entity instanceof LivingEntity living)) throw new IllegalStateException("Entity has no health"); return living.getHealth(); }
        @MantisExport public String data(String key) { check(entity); return entity.getPersistentData().getCompound("mantis").getString(key); }
        @MantisExport public void data(String key, String value) {
            check(entity); validateData(key, value); var root = entity.getPersistentData();
            var data = root.getCompound("mantis"); data.putString(key, value); root.put("mantis", data);
        }
    }
    private static void validateData(String key, String value) {
        if (key == null || key.isBlank() || key.length() > 256 || value.length() > 65536) throw new IllegalArgumentException("Persistent data needs a 1-256 character key and at most 65536 characters");
    }
    public static final class LevelRef {
        private final ServerLevel level;
        public LevelRef(ServerLevel level) { this.level = level; }
        private void check() { if (!level.getServer().isSameThread()) throw new IllegalStateException("Level operations require the server thread"); }
        private static BlockPos pos(int x, int y, int z) { if (Math.abs((long)x) > 30000000 || Math.abs((long)z) > 30000000) throw new IllegalArgumentException("Position outside world bounds"); return new BlockPos(x,y,z); }
        @MantisExport public String dimension() { check(); return level.dimension().location().toString(); }
        @MantisExport public String block(int x,int y,int z) { check(); return java.util.Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos(x,y,z)).getBlock())).toString(); }
        @MantisExport public boolean setBlock(int x,int y,int z,String id) {
            check(); ResourceLocation key = new ResourceLocation(RecipeValues.id(id));
            if (!ForgeRegistries.BLOCKS.containsKey(key)) throw new IllegalArgumentException("Unknown block: " + id);
            return level.setBlock(pos(x,y,z), ForgeRegistries.BLOCKS.getValue(key).defaultBlockState(), 3);
        }
        @MantisExport public long dayTime() { check(); return level.getDayTime(); }
    }
    public static final class Player {
        private final ServerPlayer player;
        public Player(ServerPlayer player) { this.player = player; }
        @MantisExport public String uuid() { return player.getUUID().toString(); }
        @MantisExport public String name() { return player.getGameProfile().getName(); }
        @MantisExport public Map<String, Double> position() { check(player); return Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()); }
        @MantisExport public LevelRef level() { check(player); return new LevelRef(player.serverLevel()); }
        @MantisExport public Stack heldItem() { check(player); return new Stack(player.getMainHandItem()); }
        @MantisExport public String data(String key) { return new EntityRef(player).data(key); }
        @MantisExport public void data(String key, String value) { new EntityRef(player).data(key, value); }
        @MantisExport public void give(String id, int count) {
            check(player); if (count < 1 || count > 4096) throw new IllegalArgumentException("Give count must be 1-4096");
            ResourceLocation key = new ResourceLocation(RecipeValues.id(id));
            if (!ForgeRegistries.ITEMS.containsKey(key)) throw new IllegalArgumentException("Unknown item: " + id);
            var item = ForgeRegistries.ITEMS.getValue(key);
            while (count > 0) { int n = Math.min(count, item.getMaxStackSize()); ItemStack stack = new ItemStack(item,n); if (!player.getInventory().add(stack)) player.drop(stack,false); count -= n; }
        }
        @MantisExport public void teleport(double x, double y, double z) {
            check(player); if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x)>30000000 || Math.abs(z)>30000000) throw new IllegalArgumentException("Invalid teleport position");
            player.connection.teleport(x,y,z,player.getYRot(),player.getXRot());
        }
        @MantisExport public void tell(String message) {
            if (!player.server.isSameThread()) throw new IllegalStateException("Player operations require the server thread");
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
