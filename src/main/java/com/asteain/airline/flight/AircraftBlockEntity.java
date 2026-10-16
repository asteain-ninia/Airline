package com.asteain.airline.flight;

import com.asteain.airline.Airline;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the aircraft's own block remembers. It is one of the ship's blocks, so all of this is saved with the ship and is
 * the ship's tag: which design it is, which airfield is its home, and where it is in a flight.
 *
 * <p>This class holds data only and can be loaded without Sable; the work is done by {@link FlightController}, which is
 * only touched when the flying part is available.</p>
 */
public final class AircraftBlockEntity extends BlockEntity {
    /** What the aircraft is doing. Saved by name. */
    public enum Phase {
        PARKED, BOARDING, CLIMB, CRUISE, RETREAT, HOLD, ALIGN, DESCEND, DISEMBARK, WAIT,
        /**
         * In the air with nothing left to burn: fixed where it is, its pilot on board, its ticket kept, until somebody
         * brings fuel; then the flight goes on.
         */
        DISTRESS,
        /** Fixed on a pad, goods are being moved into the hold (at home) or out of it and into it (elsewhere, and back home). */
        LOAD, UNLOAD,
        /**
         * Given up in the air (no pilot any more, no pad to land on, or a failure there): fixed where it is, without a
         * ticket, until the clear command takes it away. A failure on a pad does not end here: the aircraft stays on its pad.
         */
        STRANDED,
        /** Worked by the test bench of aircraft designs ({@link Trial}; a development check): the controller leaves it alone. */
        TRIAL
    }

    public String type = AircraftType.DEFAULT.name;
    /** The tank: how many ticks one engine can still burn of what is in it (an aircraft has two engines). See {@link Fuel}. */
    /**
     * Fuel already taken out of the store for the engines and not burnt yet (ticks of one engine): what the engines give
     * back when the aircraft lands, and what was in the tank before there was a store. Everything the aircraft has to
     * burn is this and what the store holds ({@link Fuel#total}).
     */
    public long fuel;
    /** The fuel store (the user's ruling of 2026-10-14: fuel is kept in the inventory of the aircraft's own block). */
    public final FuelStore store = new FuelStore(this);
    /**
     * While the aircraft is being built: the landing pad it is built on (this block stands on its middle, in the world,
     * not in a ship) and the number of the work order the colony's builders have for it (0: none). See {@link Shipyard}.
     */
    @Nullable public BlockPos yard;
    /** Being changed by hand with the design tools ({@link DesignTools}): it stands fixed, is not put back and does not fly. */
    public boolean modifying;
    public int workOrder;
    /** The landing pad (network point) of the home airfield, and the pad it stands on now (null while in the air). */
    @Nullable public BlockPos home, at;
    /** Height of the ship's own position above the top of a pad's floor when it rests on it (measured when it was made). */
    public double restOffset;
    public Phase phase = Phase.PARKED;
    /** The flight: points still to fly over (route.get(leg) is the one behind, leg + 1 the one ahead), where it started, where it goes. */
    public List<BlockPos> route = new ArrayList<>();
    public int leg;
    @Nullable public BlockPos origin, destination;
    public boolean roundTrip, returning, cancelled;
    /**
     * Parked away from home after a flight that could not go on there (nobody got to the seat, or it was called off):
     * it flies home by itself as soon as it has a pilot and a way.
     */
    public boolean homeward;
    @Nullable public UUID pilot;
    /** The pilot as its colony knows it (colony number in this dimension, citizen number); the entity id above can change. */
    public int pilotColony, pilotCitizen;
    /**
     * A cargo flight: for which colony the goods are (and which is home), what was loaded for it and what for the way
     * back (item name to count), whether the ledger has been told what arrived. What is really in the hold is the truth.
     */
    public boolean cargo, settled;
    /** A cargo flight that leaves home with an empty hold, to fetch what home wants from the other airfield's racks. */
    public boolean fetch;
    public int freightColony, homeColony;
    public final java.util.Map<String, Integer> manifest = new java.util.LinkedHashMap<>(), returnManifest = new java.util.LinkedHashMap<>();
    /** Game time until when no flight is prepared, after one that was called off or delivered nothing. */
    public long quietUntil;
    /** Game time when it came to rest on the pad it stands on: the berth (a day, by the rules) is counted from here. */
    public long landed;
    public int timer;
    /**
     * What the pilot carries between the airfield and the hold right now (see {@link Handling}): for which flow of
     * goods, which item, how many, and whether it is being brought back to where it was taken ("" and 0: nothing).
     */
    public String carryFlow = "", carryItem = "";
    public int carryCount;
    public boolean carryReturning;
    /** The last thing worth telling an administrator (plain English; shown by the status command and written to the log). */
    public String note = "";
    /** Why the flight was cancelled, when it was not for a blocked line: the message to give when it is over ("" for the usual one). */
    public String cancelNote = "";
    /**
     * Not saved (looked at again while it stands at home): the parts of its design the aircraft lacks and the airfield's
     * racks do not have, block name to count. An aircraft that lacks a part does not fly. See {@link Shipyard#maintain}.
     */
    public final java.util.Map<String, Integer> lacks = new java.util.LinkedHashMap<>();
    /** Not saved: the controller's working state for this aircraft. */
    @Nullable Object runtime;

    public AircraftBlockEntity(BlockPos pos, BlockState state) {
        super(Airline.AIRCRAFT_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean flying() {
        return phase != Phase.PARKED && phase != Phase.BOARDING && phase != Phase.DISEMBARK && phase != Phase.WAIT && phase != Phase.STRANDED && phase != Phase.DISTRESS
                && phase != Phase.LOAD && phase != Phase.UNLOAD;
    }

    private static void putPos(CompoundTag tag, String key, @Nullable BlockPos pos) {
        if (pos != null) tag.putLong(key, pos.asLong());
    }

    @Nullable
    private static BlockPos getPos(CompoundTag tag, String key) {
        return tag.contains(key) ? BlockPos.of(tag.getLong(key)) : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("type", type);
        putPos(tag, "home", home);
        putPos(tag, "at", at);
        tag.putLong("fuel", fuel);
        tag.put("fuelStore", store.createTag(registries));
        tag.putBoolean("fetch", fetch);
        putPos(tag, "yard", yard);
        tag.putInt("workOrder", workOrder);
        tag.putDouble("restOffset", restOffset);
        tag.putString("phase", phase.name());
        if (modifying) tag.putBoolean("modifying", true);
        tag.put("route", new LongArrayTag(route.stream().mapToLong(BlockPos::asLong).toArray()));
        tag.putInt("leg", leg);
        putPos(tag, "origin", origin);
        putPos(tag, "destination", destination);
        tag.putBoolean("roundTrip", roundTrip);
        tag.putBoolean("returning", returning);
        tag.putBoolean("cancelled", cancelled);
        tag.putBoolean("homeward", homeward);
        if (pilot != null) tag.putUUID("pilot", pilot);
        tag.putInt("pilotColony", pilotColony);
        tag.putInt("pilotCitizen", pilotCitizen);
        tag.putInt("timer", timer);
        tag.putString("note", note);
        tag.putString("cancelNote", cancelNote);
        tag.putBoolean("cargo", cargo);
        tag.putBoolean("settled", settled);
        tag.putInt("freightColony", freightColony);
        tag.putInt("homeColony", homeColony);
        tag.put("manifest", list(manifest));
        tag.put("returnManifest", list(returnManifest));
        tag.putLong("quietUntil", quietUntil);
        tag.putLong("landed", landed);
        tag.putString("carryFlow", carryFlow);
        tag.putString("carryItem", carryItem);
        tag.putInt("carryCount", carryCount);
        tag.putBoolean("carryReturning", carryReturning);
    }

    private static CompoundTag list(java.util.Map<String, Integer> goods) {
        final CompoundTag tag = new CompoundTag();
        goods.forEach(tag::putInt);
        return tag;
    }

    private static void read(CompoundTag tag, java.util.Map<String, Integer> goods) {
        goods.clear();
        for (final String key : tag.getAllKeys()) if (tag.getInt(key) > 0) goods.put(key, tag.getInt(key));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        type = tag.getString("type");
        modifying = tag.getBoolean("modifying");
        home = getPos(tag, "home");
        at = getPos(tag, "at");
        fuel = tag.getLong("fuel");
        store.clearContent();
        if (tag.contains("fuelStore")) store.fromTag(tag.getList("fuelStore", net.minecraft.nbt.Tag.TAG_COMPOUND), registries);
        fetch = tag.getBoolean("fetch");
        yard = getPos(tag, "yard");
        workOrder = tag.getInt("workOrder");
        restOffset = tag.getDouble("restOffset");
        try {
            phase = Phase.valueOf(tag.getString("phase"));
        } catch (IllegalArgumentException unknown) {
            phase = Phase.PARKED;
        }
        route = new ArrayList<>();
        for (final long point : tag.getLongArray("route")) route.add(BlockPos.of(point));
        leg = tag.getInt("leg");
        origin = getPos(tag, "origin");
        destination = getPos(tag, "destination");
        roundTrip = tag.getBoolean("roundTrip");
        returning = tag.getBoolean("returning");
        cancelled = tag.getBoolean("cancelled");
        homeward = tag.getBoolean("homeward");
        pilot = tag.hasUUID("pilot") ? tag.getUUID("pilot") : null;
        pilotColony = tag.getInt("pilotColony");
        pilotCitizen = tag.getInt("pilotCitizen");
        timer = tag.getInt("timer");
        note = tag.getString("note");
        cancelNote = tag.getString("cancelNote");
        cargo = tag.getBoolean("cargo");
        settled = tag.getBoolean("settled");
        freightColony = tag.getInt("freightColony");
        homeColony = tag.getInt("homeColony");
        read(tag.getCompound("manifest"), manifest);
        read(tag.getCompound("returnManifest"), returnManifest);
        quietUntil = tag.getLong("quietUntil");
        landed = tag.getLong("landed");
        carryFlow = tag.getString("carryFlow");
        carryItem = tag.getString("carryItem");
        carryCount = tag.getInt("carryCount");
        carryReturning = tag.getBoolean("carryReturning");
    }
}
