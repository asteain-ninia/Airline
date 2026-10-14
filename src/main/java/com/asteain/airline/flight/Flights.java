package com.asteain.airline.flight;

import com.asteain.airline.Airline;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The door to the flying part. Aircraft need Sable, Create Aeronautics (with Simulated) and ColonyPatchServer; when one
 * of them is missing everything here answers "not available" and nothing else of the mod is affected. This class can be
 * loaded without any of them; {@link FlightController} is only touched after {@link #available()} said yes.
 */
public final class Flights {
    private Flights() {}

    private static Boolean available;

    /** An answer for a command: whether it worked, and a translation key with its arguments. */
    public record Answer(boolean ok, String key, Object... args) {
        public Component text() {
            return Component.translatable("commands." + Airline.MOD_ID + "." + key, args);
        }
    }

    private static final Answer OFF = new Answer(false, "flight.off");

    public static boolean available() {
        if (available == null) {
            final ModList mods = ModList.get();
            available = mods.isLoaded(Airline.SABLE_ID) && mods.isLoaded(Airline.AERONAUTICS_ID) && mods.isLoaded(Airline.SIMULATED_ID)
                    && mods.isLoaded(Airline.COLONY_PATCH_SERVER_ID);
        }
        return available;
    }

    /** Called once when the mod is made: things that have to exist before a world is loaded (Sable's ticket type). */
    public static void bootstrap() {
        if (available()) FlightController.bootstrap();
    }

    static void abandon(ServerLevel level, BlockPos pos, @Nullable AircraftBlockEntity aircraft) {
        FlightController.abandon(level, pos, aircraft);
    }

    /** For the development checks only: the controller of the next aircraft in the air throws once. */
    public static void failOnceForChecks() {
        if (available()) FlightController.failNext = true;
    }

    /** For the development checks only: the controller of the next aircraft that is busy on a pad throws once. */
    public static void failOnPadOnceForChecks() {
        if (available()) FlightController.failNextOnPad = true;
    }

    /** Calls off the flight that is under way: back to where it started (in the air), or not leaving at all (before take-off). */
    public static Answer cancel(ServerLevel level) {
        return cancel(level, null);
    }

    /** The same for the aircraft whose home is this pad (with several flights under way it has to be said which). */
    public static Answer cancel(ServerLevel level, @Nullable BlockPos home) {
        return available() ? FlightController.cancel(level, home) : OFF;
    }

    /** Drops the timetable's lines of the aircraft of this home that are not loaded now (for an aircraft that is gone for good). */
    public static Answer forget(ServerLevel level, BlockPos home) {
        return available() ? FlightController.forget(level, home) : OFF;
    }

    /** Whether an aircraft may come to stand on this pad: nobody stands there or is expected, and it is no other aircraft's home. */
    public static boolean padFree(ServerLevel level, BlockPos pad) {
        return available() && FlightController.free(level, pad, null);
    }

    /** How many flights have a plan in the air right now (for checks: none may be left when all have landed). */
    public static int plans(ServerLevel level) {
        return available() ? FlightController.airspace(level).size() : 0;
    }

    /**
     * Takes away the aircraft that were given up in the air. What is in their holds goes to {@code receiver}, or falls
     * where the aircraft was when there is none.
     */
    public static Answer clear(ServerLevel level, @Nullable net.minecraft.server.level.ServerPlayer receiver) {
        return available() ? FlightController.clear(level, receiver) : OFF;
    }

    /** What is in the aircraft's hold right now, by item. */
    public static java.util.Map<net.minecraft.world.item.Item, Integer> holdContents(AircraftBlockEntity aircraft) {
        return available() ? FlightController.holdContents(aircraft) : java.util.Map.of();
    }

    /** The pilot's cargo handling since the aircraft was loaded: walks that ended with goods put down, and walks that were given up. */
    @Nullable
    public static int[] handling(AircraftBlockEntity aircraft) {
        return available() ? FlightController.handling(aircraft) : null;
    }

    /** The blocks of the aircraft's hold, in the ship's own coordinates (they outlast the aircraft's own block). */
    public static List<BlockPos> holdBlocks(AircraftBlockEntity aircraft) {
        return available() ? FlightController.holdBlocks(aircraft) : List.of();
    }

    /** Whether Sable holds any loading ticket for the ship with this id (for checks: none may be left when a flight is over). */
    public static boolean ticketed(ServerLevel level, java.util.UUID ship) {
        return available() && FlightController.ticketed(level, ship);
    }

    static void tick(ServerLevel level, AircraftBlockEntity aircraft) {
        FlightController.tick(level, aircraft);
    }

    /** Makes a new aircraft on a landing pad (a point of the network) and fixes it there. */
    public static Answer spawn(ServerLevel level, BlockPos pad) {
        return spawn(level, pad, AircraftType.DEFAULT.name);
    }

    /** A new aircraft of the named design ({@link #designs()}) on a landing pad. */
    public static Answer spawn(ServerLevel level, BlockPos pad, String design) {
        if (!AircraftType.ALL.containsKey(design)) return new Answer(false, "flight.no_design", design, String.join(", ", AircraftType.ALL.keySet()));
        return available() ? FlightController.spawn(level, pad, AircraftType.of(design)) : OFF;
    }

    /**
     * The aircraft's own block stands in the world at {@code pos}: {@code again} false when it was just set down (on the
     * middle of an airfield's own pad that begins the building of an aircraft), true when it was used with an empty hand
     * (the next design is built instead). False when the block is part of a ship, or nothing was done.
     */
    public static boolean building(ServerLevel level, BlockPos pos, AircraftBlockEntity aircraft, @Nullable net.minecraft.server.level.ServerPlayer player, boolean again) {
        if (!available() || Craft.shipAt(level, pos) != null) return false;
        lastBuilding = again ? Shipyard.next(level, aircraft, player) : Shipyard.placed(level, pos, aircraft, player);
        return true;
    }

    /** For the checks: the key of what the last call of {@link #building} said. */
    public static String lastBuilding = "";

    /** For the test bench: why the last aircraft could not be made by {@link #spawn}; empty when it was made. */
    public static String lastSpawnFault() {
        return available() ? FlightController.lastFault : "";
    }

    static void buildingStopped(ServerLevel level, AircraftBlockEntity aircraft) {
        if (available()) Shipyard.removed(level, aircraft);
    }

    /** How many blocks of its design an aircraft that is being built still lacks (-1: the design cannot be read). */
    public static int buildingLacks(ServerLevel level, AircraftBlockEntity aircraft) {
        return available() ? Shipyard.missing(level, aircraft.getBlockPos(), AircraftType.of(aircraft.type)) : -1;
    }

    /** Whether an airfield should ask its colony for fuel now: its own aircraft stands at home, not well filled, and the racks hold little. */
    public static boolean wantsFuel(ServerLevel level, com.asteain.airline.colony.BuildingAirfield airfield) {
        if (!available() || Fuel.stock(Cargo.racks(airfield)) >= Cargo.fuelStock()) return false;
        for (final AircraftBlockEntity aircraft : FlightController.loaded(level)) {
            if (airfield.pad().equals(aircraft.home) && airfield.pad().equals(aircraft.at) && Fuel.low(aircraft)) return true;
        }
        return false;
    }

    /** The items an airfield asks for as fuel. */
    public static java.util.List<net.minecraft.world.item.ItemStack> fuelAsked() {
        return Fuel.asked();
    }

    /** Whether the aircraft block at this place is part of a ship (a finished aircraft), not a block set down in the world. */
    public static boolean afloat(ServerLevel level, BlockPos pos) {
        return available() && Craft.shipAt(level, pos) != null;
    }

    public static boolean isFuel(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && Fuel.burn(stack.getItem()) > 0;
    }

    /** Fuel is put into an aircraft's tank by hand: as many of the stack as there is room for. @return how many were taken */
    public static int refuel(ServerLevel level, AircraftBlockEntity aircraft, net.minecraft.world.item.ItemStack stack, @Nullable net.minecraft.server.level.ServerPlayer player) {
        final net.minecraft.world.item.ItemStack rest = Fuel.tank(aircraft).insertItem(0, stack.copy(), false);
        final int taken = stack.getCount() - rest.getCount();
        if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message." + com.asteain.airline.Airline.MOD_ID + (taken > 0 ? ".fuel.put" : ".fuel.full"), taken, Fuel.minutes(aircraft)));
        return taken;
    }

    /**
     * For an airfield's window: its own aircraft. {state, number, kinds of parts lacking, in distress}, where state is 0
     * (none), 1 (being built on the pad: number is the blocks still to be put up) or 2 (there is one: number is the minutes
     * of fuel in its tank, -1 when it is not loaded now). The translation key of the design's name is put into {@code design}.
     */
    public static int[] ownAircraft(ServerLevel level, BlockPos pad, String[] design) {
        design[0] = "";
        if (!available()) return new int[4];
        for (final AircraftBlockEntity aircraft : FlightController.loaded(level)) {
            if (!pad.equals(aircraft.home)) continue;
            design[0] = AircraftType.of(aircraft.type).title();
            return new int[] {2, (int) Fuel.minutes(aircraft), aircraft.lacks.size(), aircraft.phase == AircraftBlockEntity.Phase.DISTRESS ? 1 : 0};
        }
        if (level.isLoaded(pad) && level.getBlockEntity(pad.above()) instanceof AircraftBlockEntity site && site.yard != null) {
            design[0] = AircraftType.of(site.type).title();
            return new int[] {1, Math.max(0, Shipyard.missing(level, site.getBlockPos(), AircraftType.of(site.type))), 0, 0};
        }
        // Not loaded now, but the timetable knows an aircraft whose home this pad is.
        for (final Timetable.Entry entry : Timetable.get(level.getServer()).entries()) {
            if (pad.equals(entry.home()) && entry.dimension().equals(level.dimension().location().toString())) return new int[] {2, -1, 0, 0};
        }
        return new int[4];
    }

    /** For the checks: what the engines still burn of what they have lit (ticks; -1: not loaded). */
    public static int[] engineBurn(AircraftBlockEntity aircraft) {
        return available() ? FlightController.engineBurn(aircraft) : new int[] {-1, -1};
    }

    /** Minutes of flight the aircraft's tank holds. */
    public static long fuelMinutes(AircraftBlockEntity aircraft) {
        return Fuel.minutes(aircraft);
    }

    /** What a flight of so many ticks needs in the tank of that aircraft before it is begun (ticks of one engine). */
    public static long fuelNeed(AircraftBlockEntity aircraft, long flightTicks) {
        return Fuel.need(AircraftType.of(aircraft.type), flightTicks);
    }

    /** Looks an aircraft that stands at home over now (it is done every ten seconds anyway): {blocks put right, blocks taken out}. */
    public static int[] maintain(ServerLevel level, AircraftBlockEntity aircraft) {
        final FlightController.Runtime rt = available() ? FlightController.runtime(aircraft) : null;
        return rt == null ? new int[2] : Shipyard.maintain(level, aircraft, rt.craft);
    }

    /** Where a block of an aircraft's design is now (the place in the ship's own space). */
    @Nullable
    public static BlockPos partOf(AircraftBlockEntity aircraft, BlockPos part) {
        final FlightController.Runtime rt = available() ? FlightController.runtime(aircraft) : null;
        return rt == null ? null : rt.craft.inShip(part);
    }

    /** How many blocks a design has, its own block not counted (what a building site lacks before anything is put up). */
    public static int blocksOf(String design) {
        return AircraftType.of(design).parts.size();
    }

    /** Where a design's blueprint is in the mod's structure pack. */
    public static String blueprintOf(String design) {
        return "aircraft/" + AircraftType.of(design).name + ".blueprint";
    }

    /** Mass of an aircraft and its centre of mass seen from its own block (nose, up, right); null when it is not loaded. */
    @Nullable
    public static double[] weight(ServerLevel level, AircraftBlockEntity aircraft) {
        return available() ? FlightController.weight(level, aircraft) : null;
    }

    /** The names of the aircraft designs, in the order they are offered; the first is the one used when none is named. */
    public static java.util.List<String> designs() {
        return java.util.List.copyOf(AircraftType.ALL.keySet());
    }

    /** The translation key of a design's name as players see it. */
    public static String designTitle(String design) {
        return AircraftType.of(design).title();
    }

    /** Sends the aircraft that stands on the pad {@code from} to the pad {@code to}, flown by the given pilot; back again when {@code round}. */
    public static Answer send(ServerLevel level, BlockPos from, BlockPos to, boolean round, @Nullable Mob pilot) {
        return available() ? FlightController.send(level, from, to, round, pilot) : OFF;
    }

    /**
     * What an aircraft is doing right now, as numbers: x, y, z of the ship, heading (degrees), fixed to the world (1 or 0),
     * nearest its box has come to a buoy block, how far from the middle of the pad it last landed (or from the last point
     * it stopped at), how far off the pad's heading it last landed, lever writes so far, speed (blocks per second), steps
     * the controller has run since the aircraft was loaded. Null when the aircraft is not on a loaded ship.
     */
    public static double[] readings(AircraftBlockEntity aircraft) {
        return available() ? FlightController.readings(aircraft) : null;
    }

    /** The id of the ship the aircraft block is part of, and its seat block in the ship's own coordinates (for boarding). */
    public static java.util.UUID shipId(AircraftBlockEntity aircraft) {
        final FlightController.Runtime rt = available() ? FlightController.runtime(aircraft) : null;
        return rt == null ? null : rt.craft.id();
    }

    /** The aircraft that are loaded in this dimension right now. */
    public static List<AircraftBlockEntity> aircraft(ServerLevel level) {
        return available() ? FlightController.loaded(level) : List.of();
    }
}
