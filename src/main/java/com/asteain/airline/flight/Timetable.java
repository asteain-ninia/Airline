package com.asteain.airline.flight;

import com.asteain.airline.Airline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where every aircraft is and where it goes next, kept with the world: an airfield can say when the next flight leaves
 * and when the next one comes in, also while the aircraft itself is not loaded. Written by the aircraft's controller,
 * read by the airfield's window and the status command. Needs neither Sable nor a loaded aircraft.
 *
 * <p>Times are game ticks. An entry is as old as the last time its aircraft was loaded: an airfield nobody is near does
 * not send its aircraft, so a time that has passed means "late", not "gone".</p>
 */
public final class Timetable extends SavedData {
    private static final String NAME = Airline.MOD_ID + "_timetable";

    /**
     * One aircraft. {@code at}: the pad it stands on (null in the air). {@code to}: the pad it flies to, or will fly to
     * when it leaves (null when nothing is planned). {@code leaves}: when its berth is over. {@code arrives}: when it is
     * expected on {@code to}. {@code state}: what it is doing, as the aircraft's phase is named.
     */
    public record Entry(UUID ship, String dimension, BlockPos home, @Nullable BlockPos at, @Nullable BlockPos to, long leaves, long arrives, String state) {
        public boolean flying() {
            return at == null;
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    /** A span of game ticks as minutes and seconds of real time ("3:05"); nothing below zero. */
    public static String clock(long ticks) {
        final long seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    public static Timetable get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Timetable::new, Timetable::load), NAME);
    }

    /** Writes an aircraft's line. A pad holds one aircraft: an older line that claims the same pad is dropped. */
    public void put(Entry entry) {
        if (entry.equals(entries.get(entry.ship()))) return;
        if (entry.at() != null) entries.values().removeIf(other -> !other.ship().equals(entry.ship()) && other.dimension().equals(entry.dimension()) && entry.at().equals(other.at()));
        entries.put(entry.ship(), entry);
        setDirty();
    }

    public void remove(UUID ship) {
        if (entries.remove(ship) != null) setDirty();
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    /** The aircraft that stands on this pad, if any. */
    @Nullable
    public Entry standing(String dimension, BlockPos pad) {
        for (final Entry entry : entries.values()) if (entry.dimension().equals(dimension) && pad.equals(entry.at())) return entry;
        return null;
    }

    /** The aircraft that is on its way to this pad, or will leave for it: the one expected first. */
    @Nullable
    public Entry coming(String dimension, BlockPos pad) {
        Entry first = null;
        for (final Entry entry : entries.values()) {
            if (!entry.dimension().equals(dimension) || !pad.equals(entry.to()) || pad.equals(entry.at())) continue;
            if (first == null || entry.arrives() < first.arrives()) first = entry;
        }
        return first;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider provider) {
        final ListTag list = new ListTag();
        for (final Entry entry : entries.values()) {
            final CompoundTag line = new CompoundTag();
            line.putUUID("ship", entry.ship());
            line.putString("dimension", entry.dimension());
            line.putLong("home", entry.home().asLong());
            if (entry.at() != null) line.putLong("at", entry.at().asLong());
            if (entry.to() != null) line.putLong("to", entry.to().asLong());
            line.putLong("leaves", entry.leaves());
            line.putLong("arrives", entry.arrives());
            line.putString("state", entry.state());
            list.add(line);
        }
        tag.put("aircraft", list);
        return tag;
    }

    private static Timetable load(CompoundTag tag, HolderLookup.Provider provider) {
        final Timetable table = new Timetable();
        for (final Tag each : tag.getList("aircraft", Tag.TAG_COMPOUND)) {
            final CompoundTag line = (CompoundTag) each;
            if (!line.hasUUID("ship")) continue;
            final Entry entry = new Entry(line.getUUID("ship"), line.getString("dimension"), BlockPos.of(line.getLong("home")),
                    line.contains("at") ? BlockPos.of(line.getLong("at")) : null, line.contains("to") ? BlockPos.of(line.getLong("to")) : null,
                    line.getLong("leaves"), line.getLong("arrives"), line.getString("state"));
            table.entries.put(entry.ship(), entry);
        }
        return table;
    }
}
