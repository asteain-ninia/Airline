package com.asteain.airline.ledger;

import com.asteain.airline.Airline;
import com.minecolonies.api.colony.IColony;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Who wants what: for every colony, the items it is asking the air network for and how many.
 * One ledger per world (stored with the overworld). There are two kinds of entries:
 * <ul>
 * <li>manual ones, written by commands;</li>
 * <li>automatic ones, which an airfield derives from the requests nobody in its colony can fulfil
 * (those waiting for the player). They are replaced as a whole whenever that airfield looks again.</li>
 * </ul>
 * Only plain data is saved (dimension, colony number, item id, count), so the world stays readable without this mod.
 */
public final class WantLedger extends SavedData {
    private static final String FILE = Airline.MOD_ID + "_wants";
    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_DIMENSION = "dimension";
    private static final String TAG_COLONY = "colony";
    private static final String TAG_ITEM = "item";
    private static final String TAG_COUNT = "count";
    private static final String TAG_AUTOMATIC = "automatic";

    /** A colony is identified by its dimension and its number in that dimension. */
    public record Key(String dimension, int colony) implements Comparable<Key> {
        public static Key of(IColony colony) {
            return new Key(colony.getDimension().location().toString(), colony.getID());
        }

        @Override
        public int compareTo(Key other) {
            int byDimension = dimension.compareTo(other.dimension);
            return byDimension != 0 ? byDimension : Integer.compare(colony, other.colony);
        }
    }

    public record Entry(Key key, Item item, int count, boolean automatic) {}

    private final Map<Key, Map<Item, Integer>> manual = new TreeMap<>();
    private final Map<Key, Map<Item, Integer>> automatic = new TreeMap<>();
    /**
     * Goods that are already on their way to a colony, or were delivered to its import store and have not been taken
     * up by the colony yet. They count against what the colony wants, so the same goods are not collected or flown twice.
     */
    private final Map<Key, Map<Item, Integer>> covered = new TreeMap<>();
    /** The last flights, one line each (newest last). */
    private final List<String> history = new ArrayList<>();
    private static final int HISTORY = 20;

    public static WantLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(WantLedger::new, WantLedger::load), FILE);
    }

    /** Sets how many of an item a colony wants (manual entry); zero or less removes the entry. */
    public void set(Key key, Item item, int count) {
        if (count <= 0) {
            Map<Item, Integer> of = manual.get(key);
            if (of != null && of.remove(item) != null && of.isEmpty()) manual.remove(key);
        } else {
            manual.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(item, count);
        }
        trim(key);
        setDirty();
    }

    /** Never more on the way to a colony than it wants: a want that shrank or went away takes what was counted against it along. */
    private void trim(Key key) {
        Map<Item, Integer> on = covered.get(key);
        if (on == null) return;
        on.replaceAll((item, count) -> Math.min(count, wanted(key, item)));
        on.values().removeIf(count -> count <= 0);
        if (on.isEmpty()) covered.remove(key);
    }

    /** Replaces all automatic entries of one colony (an empty map removes them). */
    public void setAutomatic(Key key, Map<Item, Integer> wants) {
        Map<Item, Integer> clean = new LinkedHashMap<>();
        wants.forEach((item, count) -> { if (item != Items.AIR && count > 0) clean.put(item, count); });
        Map<Item, Integer> before = automatic.get(key);
        if (clean.isEmpty() ? before == null : clean.equals(before)) return;
        if (clean.isEmpty()) automatic.remove(key); else automatic.put(key, clean);
        // A want that went away was met (or withdrawn): what was counted against it is no longer needed.
        trim(key);
        setDirty();
    }

    /** Every colony that wants something. */
    public java.util.Set<Key> keys() {
        java.util.Set<Key> all = new java.util.TreeSet<>(manual.keySet());
        all.addAll(automatic.keySet());
        return all;
    }

    public int covered(Key key, Item item) {
        return amount(covered, key, item);
    }

    /** What a colony still wants of the item once the goods on their way to it are counted. */
    public int net(Key key, Item item) {
        return Math.max(0, wanted(key, item) - covered(key, item));
    }

    /** Goods were loaded for a colony (positive) or will not arrive after all (negative). */
    public void cover(Key key, Item item, int delta) {
        if (delta == 0 || item == Items.AIR) return;
        Map<Item, Integer> on = covered.computeIfAbsent(key, k -> new LinkedHashMap<>());
        int now = on.getOrDefault(item, 0) + delta;
        if (now > 0) on.put(item, now); else on.remove(item);
        if (on.isEmpty()) covered.remove(key);
        setDirty();
    }

    /**
     * Goods arrived in a colony's import store. A want entered by command is met by that and shrinks. A want the colony
     * reported itself stays as it is, with the goods counted against it, until the colony stops reporting it.
     */
    public void delivered(Key key, Item item, int count) {
        if (count <= 0) return;
        int byHand = amount(manual, key, item), met = Math.min(byHand, count);
        if (met > 0) {
            // In this order: set() trims what is on the way to what is still wanted, so the arrived goods come off first.
            cover(key, item, -met);
            set(key, item, byHand - met);
        }
    }

    public void log(String line) {
        history.add(line);
        while (history.size() > HISTORY) history.remove(0);
        setDirty();
    }

    public List<String> history() {
        return List.copyOf(history);
    }

    /** Removes the manual entries of one colony, or of all colonies when key is null. @return the number of entries removed. */
    public int clear(Key key) {
        int removed = 0;
        if (key == null) {
            for (Map<Item, Integer> of : manual.values()) removed += of.size();
            manual.clear();
        } else {
            Map<Item, Integer> of = manual.remove(key);
            if (of != null) removed = of.size();
        }
        if (removed > 0) {
            for (Key each : key == null ? new ArrayList<>(covered.keySet()) : List.of(key)) trim(each);
            setDirty();
        }
        return removed;
    }

    /** Removes everything known about a colony (it was deleted). */
    public void forget(Key key) {
        boolean changed = manual.remove(key) != null;
        changed |= automatic.remove(key) != null;
        changed |= covered.remove(key) != null;
        if (changed) setDirty();
    }

    /** What one colony wants of the item: its manual entry plus its automatic one. */
    public int wanted(Key key, Item item) {
        return amount(manual, key, item) + amount(automatic, key, item);
    }

    public int wantedAutomatically(Key key, Item item) {
        return amount(automatic, key, item);
    }

    private static int amount(Map<Key, Map<Item, Integer>> wants, Key key, Item item) {
        Map<Item, Integer> of = wants.get(key);
        return of == null ? 0 : of.getOrDefault(item, 0);
    }

    /** The sum of what all colonies other than self want of the item. */
    public int wantedByOthers(Key self, Item item) {
        long sum = 0;
        for (Key key : keys()) if (!key.equals(self)) sum += net(key, item);
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    /** All entries, manual ones first. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        collect(manual, false, out);
        collect(automatic, true, out);
        return out;
    }

    private static void collect(Map<Key, Map<Item, Integer>> wants, boolean automatic, List<Entry> out) {
        for (Map.Entry<Key, Map<Item, Integer>> e : wants.entrySet())
            for (Map.Entry<Item, Integer> i : e.getValue().entrySet()) out.add(new Entry(e.getKey(), i.getKey(), i.getValue(), automatic));
    }

    @NotNull
    @Override
    public CompoundTag save(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Entry entry : entries()) {
            CompoundTag e = new CompoundTag();
            e.putString(TAG_DIMENSION, entry.key().dimension());
            e.putInt(TAG_COLONY, entry.key().colony());
            e.putString(TAG_ITEM, BuiltInRegistries.ITEM.getKey(entry.item()).toString());
            e.putInt(TAG_COUNT, entry.count());
            e.putBoolean(TAG_AUTOMATIC, entry.automatic());
            list.add(e);
        }
        tag.put(TAG_ENTRIES, list);
        ListTag onTheWay = new ListTag();
        for (Map.Entry<Key, Map<Item, Integer>> of : covered.entrySet()) {
            for (Map.Entry<Item, Integer> i : of.getValue().entrySet()) {
                CompoundTag e = new CompoundTag();
                e.putString(TAG_DIMENSION, of.getKey().dimension());
                e.putInt(TAG_COLONY, of.getKey().colony());
                e.putString(TAG_ITEM, BuiltInRegistries.ITEM.getKey(i.getKey()).toString());
                e.putInt(TAG_COUNT, i.getValue());
                onTheWay.add(e);
            }
        }
        tag.put("covered", onTheWay);
        ListTag lines = new ListTag();
        for (String line : history) lines.add(net.minecraft.nbt.StringTag.valueOf(line));
        tag.put("history", lines);
        return tag;
    }

    public static WantLedger load(CompoundTag tag, HolderLookup.Provider provider) {
        WantLedger ledger = new WantLedger();
        ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(e.getString(TAG_ITEM));
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            int count = e.getInt(TAG_COUNT);
            // An item of a mod that is gone reads as air: drop the entry instead of failing the load.
            if (item == Items.AIR || count <= 0) continue;
            Map<Key, Map<Item, Integer>> target = e.getBoolean(TAG_AUTOMATIC) ? ledger.automatic : ledger.manual;
            target.computeIfAbsent(new Key(e.getString(TAG_DIMENSION), e.getInt(TAG_COLONY)), k -> new LinkedHashMap<>()).put(item, count);
        }
        ListTag onTheWay = tag.getList("covered", Tag.TAG_COMPOUND);
        for (int i = 0; i < onTheWay.size(); i++) {
            CompoundTag e = onTheWay.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(e.getString(TAG_ITEM));
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR || e.getInt(TAG_COUNT) <= 0) continue;
            ledger.covered.computeIfAbsent(new Key(e.getString(TAG_DIMENSION), e.getInt(TAG_COLONY)), k -> new LinkedHashMap<>()).put(item, e.getInt(TAG_COUNT));
        }
        ListTag lines = tag.getList("history", Tag.TAG_STRING);
        for (int i = 0; i < lines.size(); i++) ledger.history.add(lines.getString(i));
        return ledger;
    }
}
