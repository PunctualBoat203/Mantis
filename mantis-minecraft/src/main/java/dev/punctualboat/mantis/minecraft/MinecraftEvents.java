package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.core.MantisExport;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.*;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

@Mod.EventBusSubscriber(modid = "mantis")
public final class MinecraftEvents {
    private MinecraftEvents() {}
    private static boolean ready(Entity entity, String name) {
        return !entity.level().isClientSide && entity.level().getServer() != null && entity.level().getServer().isSameThread() && Mantis.listens(name);
    }
    private static Map<String, Object> entity(Entity entity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entity", new MinecraftBindings.EntityRef(entity));
        data.put("entityType", Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType())).toString());
        data.put("dimension", entity.level().dimension().location().toString());
        if (entity instanceof ServerPlayer player) data.put("player", new MinecraftBindings.Player(player));
        return data;
    }
    private static void send(String name, Event event, Map<String, Object> data) {
        try (Control control = new Control(event)) { data.put("control", control); Mantis.dispatch(name, data); }
    }
    public static final class Control implements AutoCloseable {
        private final Event event;
        private boolean active = true;
        private Control(Event event) { this.event = event; }
        private void check() { if (!active) throw new IllegalStateException("Event control is only valid during its synchronous handler"); }
        @MantisExport public boolean cancellable() { check(); return event.isCancelable(); }
        @MantisExport public boolean cancelled() { check(); return event.isCancelable() && event.isCanceled(); }
        @MantisExport public void cancel() { check(); if (!event.isCancelable()) throw new IllegalStateException("This event cannot be cancelled"); event.setCanceled(true); }
        @MantisExport public void damage(double amount) {
            check(); if (!(event instanceof LivingHurtEvent hurt) || !Double.isFinite(amount) || amount < 0 || amount > Float.MAX_VALUE) throw new IllegalArgumentException("Damage changes require entity.hurt and a finite nonnegative amount");
            hurt.setAmount((float) amount);
        }
        @Override public void close() { active = false; }
    }
    @SubscribeEvent public static void chat(ServerChatEvent event) {
        if (!ready(event.getPlayer(), "player.chat")) return;
        Map<String, Object> data = entity(event.getPlayer()); data.put("message", event.getRawText()); send("player.chat", event, data);
    }
    @SubscribeEvent public static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!ready(event.getEntity(), "player.respawned")) return; send("player.respawned", event, entity(event.getEntity()));
    }
    @SubscribeEvent public static void clone(PlayerEvent.Clone event) {
        if (event.getEntity().level().isClientSide) return;
        var original = event.getOriginal().getPersistentData();
        if (original.contains("mantis", net.minecraft.nbt.Tag.TAG_COMPOUND)) event.getEntity().getPersistentData().put("mantis", original.getCompound("mantis").copy());
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!ready(event.getEntity(), "player.changed_dimension")) return;
        Map<String, Object> data = entity(event.getEntity()); data.put("from", event.getFrom().location().toString()); data.put("to", event.getTo().location().toString()); send("player.changed_dimension", event, data);
    }
    @SubscribeEvent public static void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && ready(event.player, "player.tick")) send("player.tick", event, entity(event.player));
    }
    private static Map<String, Object> item(Entity entity, net.minecraft.world.item.ItemStack stack) {
        Map<String, Object> data = entity(entity); data.put("item", Objects.requireNonNull(ForgeRegistries.ITEMS.getKey(stack.getItem())).toString());
        data.put("stack", new MinecraftBindings.Stack(stack)); return data;
    }
    @SubscribeEvent public static void crafted(PlayerEvent.ItemCraftedEvent event) {
        if (ready(event.getEntity(), "item.crafted")) send("item.crafted", event, item(event.getEntity(), event.getCrafting()));
    }
    @SubscribeEvent public static void smelted(PlayerEvent.ItemSmeltedEvent event) {
        if (ready(event.getEntity(), "item.smelted")) send("item.smelted", event, item(event.getEntity(), event.getSmelting()));
    }
    @SubscribeEvent public static void pickup(PlayerEvent.ItemPickupEvent event) {
        if (ready(event.getEntity(), "item.picked_up")) send("item.picked_up", event, item(event.getEntity(), event.getStack()));
    }
    @SubscribeEvent public static void toss(ItemTossEvent event) {
        if (ready(event.getPlayer(), "item.dropped")) send("item.dropped", event, item(event.getPlayer(), event.getEntity().getItem()));
    }
    @SubscribeEvent public static void eaten(LivingEntityUseItemEvent.Finish event) {
        if (ready(event.getEntity(), "item.used")) send("item.used", event, item(event.getEntity(), event.getItem()));
    }
    @SubscribeEvent public static void rightItem(PlayerInteractEvent.RightClickItem event) {
        if (ready(event.getEntity(), "item.right_clicked")) send("item.right_clicked", event, item(event.getEntity(), event.getItemStack()));
    }
    private static Map<String, Object> block(Entity entity, BlockPos pos) {
        Map<String, Object> data = entity(entity); data.put("block", Objects.requireNonNull(ForgeRegistries.BLOCKS.getKey(entity.level().getBlockState(pos).getBlock())).toString());
        data.put("position", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ())); data.put("level", new MinecraftBindings.LevelRef((ServerLevel) entity.level())); return data;
    }
    @SubscribeEvent public static void rightBlock(PlayerInteractEvent.RightClickBlock event) {
        if (ready(event.getEntity(), "block.right_clicked")) send("block.right_clicked", event, block(event.getEntity(), event.getPos()));
    }
    @SubscribeEvent public static void leftBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (ready(event.getEntity(), "block.left_clicked")) send("block.left_clicked", event, block(event.getEntity(), event.getPos()));
    }
    @SubscribeEvent public static void broken(BlockEvent.BreakEvent event) {
        if (ready(event.getPlayer(), "block.broken")) send("block.broken", event, block(event.getPlayer(), event.getPos()));
    }
    @SubscribeEvent public static void placed(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() != null && ready(event.getEntity(), "block.placed")) send("block.placed", event, block(event.getEntity(), event.getPos()));
    }
    @SubscribeEvent public static void joined(EntityJoinLevelEvent event) {
        if (ready(event.getEntity(), "entity.spawned")) send("entity.spawned", event, entity(event.getEntity()));
    }
    @SubscribeEvent public static void death(LivingDeathEvent event) {
        if (!ready(event.getEntity(), "entity.death")) return;
        Map<String, Object> data = entity(event.getEntity()); data.put("source", event.getSource().getMsgId()); send("entity.death", event, data);
    }
    @SubscribeEvent public static void hurt(LivingHurtEvent event) {
        if (!ready(event.getEntity(), "entity.hurt")) return;
        Map<String, Object> data = entity(event.getEntity()); data.put("damage", event.getAmount()); data.put("source", event.getSource().getMsgId()); send("entity.hurt", event, data);
    }
    private static boolean ready(ServerLevel level, String name) { return level.getServer().isSameThread() && Mantis.listens(name); }
    private static Map<String, Object> level(ServerLevel level) {
        Map<String, Object> data = new LinkedHashMap<>(); data.put("dimension", level.dimension().location().toString()); data.put("level", new MinecraftBindings.LevelRef(level)); return data;
    }
    @SubscribeEvent public static void levelLoaded(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && ready(level, "level.loaded")) send("level.loaded", event, level(level));
    }
    @SubscribeEvent public static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && ready(level, "level.unloaded")) send("level.unloaded", event, level(level));
    }
    @SubscribeEvent public static void levelTick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level && ready(level, "level.tick")) send("level.tick", event, level(level));
    }
    @SubscribeEvent public static void explosion(ExplosionEvent.Start event) {
        if (event.getLevel() instanceof ServerLevel level && ready(level, "level.before_explosion")) send("level.before_explosion", event, level(level));
    }
    @SubscribeEvent public static void detonated(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level && ready(level, "level.after_explosion")) {
            Map<String, Object> data = level(level); data.put("blocks", event.getAffectedBlocks().size()); data.put("entities", event.getAffectedEntities().size()); send("level.after_explosion", event, data);
        }
    }
}
