package dev.punctualboat.mantis.minecraft;

import dev.punctualboat.mantis.runtime.TickClock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

public final class ClockData extends SavedData {
    private TickClock.Snapshot saved = new TickClock.Snapshot(0, Map.of());
    private TickClock active;

    public static ClockData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ClockData::load, ClockData::new, "mantis_clock");
    }

    public TickClock clock() {
        if (active == null) active = new TickClock(saved, this::setDirty, Mantis::report, 256);
        return active;
    }

    private static ClockData load(CompoundTag tag) {
        ClockData data = new ClockData();
        Map<String, Long> cooldowns = new HashMap<>();
        CompoundTag values = tag.getCompound("cooldowns");
        for (String key : values.getAllKeys()) cooldowns.put(key, values.getLong(key));
        data.saved = new TickClock.Snapshot(Math.max(0, tag.getLong("ticks")), cooldowns);
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        TickClock.Snapshot snapshot = active == null ? saved : active.snapshot();
        tag.putLong("ticks", snapshot.ticks());
        CompoundTag cooldowns = new CompoundTag();
        snapshot.cooldowns().forEach(cooldowns::putLong);
        tag.put("cooldowns", cooldowns);
        return tag;
    }

    public void stop() {
        if (active != null) { saved = active.snapshot(); active.close(); active = null; setDirty(); }
    }
}
