package com.asteain.airline.flight;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One aircraft design as data: every block, where the devices are that the autopilot works (the same ones a player can
 * work by hand), where the pilot sits, how much room it needs, and the numbers its handling was measured with.
 * Positions are relative to the aircraft's own block (0, 0, 0), which is the middle block of the deck; +x is the nose,
 * -z the left side.
 *
 * <p>The autopilot knows nothing about how a design is driven. It asks for one of a few manoeuvres ({@link Drive}), and
 * the design says which value each of its drive levers gets for that manoeuvre; it holds the height with the levers of
 * the burners, all set alike. So a design is free in how many engines, propellers and levers it has and where they are.</p>
 *
 * <p>More designs can be added to {@link #ALL}; nothing else in the mod knows the shape of an aircraft. Every design has
 * to fit the outline the air network is verified for and the airfields are built for ({@link #fits}).</p>
 */
public final class AircraftType {
    /**
     * What the autopilot can ask of the drives. Left and right are turns on the spot, seen from above with the nose ahead.
     * The slow ones are for the last block and the last degrees: a design that is fast has to be able to be gentle as
     * well. A design that names none gets its ordinary manoeuvres in their place.
     */
    public enum Drive {
        OFF, AHEAD, ASTERN, LEFT, RIGHT, AHEAD_LEFT, AHEAD_RIGHT, AHEAD_SLOW, ASTERN_SLOW, LEFT_SLOW, RIGHT_SLOW;

        /** The ordinary manoeuvre a slow one stands in for; itself for the others. */
        public Drive ordinary() {
            return switch (this) {
                case AHEAD_SLOW -> AHEAD;
                case ASTERN_SLOW -> ASTERN;
                case LEFT_SLOW -> LEFT;
                case RIGHT_SLOW -> RIGHT;
                default -> this;
            };
        }
    }

    public final String name;
    /**
     * The size class of the design (the user's ruling of 2026-10-14): where it may berth, which lines it may fly, which
     * airfields build it. From the file's {@code "class"} ("small", "medium", "large"); small when it says nothing.
     */
    public com.asteain.airline.route.ShipClass shipClass = com.asteain.airline.route.ShipClass.SMALL;
    /** Block states in the text form of commands ("create:shaft[axis=x]"). The aircraft's own block is not in this map. */
    public final Map<BlockPos, String> parts = new LinkedHashMap<>();

    /**
     * How far the deck reaches to either side of the aircraft's own block (the widest of its blocks at the deck's height):
     * at an anchorage the aircraft is held so that this edge lies against the pier, where the pilot steps across.
     */
    public int deckReach() {
        int reach = 0;
        for (final BlockPos part : parts.keySet()) if (part.getY() == 0) reach = Math.max(reach, Math.abs(part.getZ()));
        return Math.min(reach, shipClass.halfWidth);
    }
    public BlockPos seat;
    /** The burners, the lever beside each (the height is held with these levers, all set alike), and the setting of each burner's own dial. */
    public BlockPos[] burners = {}, burnerLevers = {};
    public int[] burnerValues = {};
    /** The hold: containers (barrels) that carry the cargo. */
    public final List<BlockPos> cargo = new ArrayList<>();
    /** Places on the deck where somebody can stand to work the hold (the block the feet are in). */
    public final List<BlockPos> access = new ArrayList<>();
    /**
     * Seats for passengers (2026-10-17, the user's ruling "判断は一旦全部推奨で": 旅客 1): players sit down on them while the
     * aircraft stands on a pad and ride along; flights do not wait for them. Kept away from where the pilot walks (a pilot
     * that is sat down on one is taken off it again). Optional; none for most designs.
     */
    public final List<BlockPos> passengers = new ArrayList<>();
    /**
     * The connectors on the sides of the hull and the lever beside each (2026-10-18, the user's ruling: at an anchorage
     * the aircraft is joined to the pier's connector, which pulls it so that its deck lies right against the pier). The
     * key is the side: "port" to the left looking ahead (toward -z of the ship), "starboard" to the right. Optional.
     */
    public final Map<String, Dock> docks = new LinkedHashMap<>();

    public record Dock(BlockPos connector, BlockPos lever) {}

    /** The engines: they burn out of the aircraft's tank. */
    public BlockPos[] engines = {};
    /** The levers of the drives, and for every manoeuvre the value (0 to 15) each of them gets. */
    public BlockPos[] driveLevers = {};
    public final int[][] drive = new int[Drive.values().length][];
    /**
     * Which of the drive levers are throttles: levers the autopilot moves a few steps at a time instead of setting them
     * at once ({@link #throttleStep} steps for every decision), and closes before any other drive lever is changed. A
     * ship under a balloon that gets all of a strong drive's push at once, or loses it at once, pitches over. Null: none.
     */
    @Nullable public boolean[] throttle;

    // ---- handling, as measured (10 ticks between two looks at the ship) ----
    /** Ticks between two decisions. Create breaks parts that are switched too often; never go below 10. */
    public int period = 10;
    /** Burner lever value around which the height is held, and the lever value while parked. */
    public double hoverLever = 10.5;
    public int parkedLever = 10;
    public double heightGain = 1.0, heightIntegral = 0.003, heightDamping = 4.0;
    /**
     * The drives are switched off this far before a point: stopDistance + speed x coastSeconds. Measured: with the drives
     * off the ship coasts about 3.1 blocks from 1.8 blocks per second.
     */
    public double stopDistance = 0.3, coastSeconds = 1.7;
    /**
     * A fast design is stopped with its drives astern: inside stopDistance + speed x brakeSeconds of a point the drives go
     * astern until the speed is down to brakeBelow, then off. 0: the design coasts to rest, as slow ones do.
     */
    public double brakeSeconds = 0.0, brakeBelow = 0.6;
    /**
     * What the slow manoeuvres settle at (blocks per second; degrees per second); 0: the design has none of its own.
     * Within creepWithin blocks of the end of a leg, nearly at rest, short slow pushes take the place of the full drive;
     * heading errors of less than fineTurnBelow degrees are closed with the slow turn. 0: never.
     */
    public double slowSpeed = 0.0, slowTurnRate = 0.0, creepWithin = 0.0, fineTurnBelow = 0.0;
    /** Blocks per second the height target is raised when climbing to the line; 0: set to the line's height at once. */
    public double climbRate = 0.0;
    /** Steps a throttle lever is moved for every decision (see {@link #throttle}); 0: throttles are set at once like the other levers. */
    public int throttleStep = 2;
    /** Seconds ahead the nose is predicted when steering; heading errors (degrees) above which one side, or both against each other, are used. */
    public double leadSeconds = 1.5, smallError = 5.0, bigError = 30.0;
    /** Degrees of heading correction per block off the line, and the most it leans toward the line. */
    public double leanPerBlock = 12.0, maxLean = 35.0;
    /**
     * A fast design keeps its heading under way with short pushes of the gentle turn (ahead_left, ahead_right) around
     * going ahead: degrees per second the gentle turn makes of a push at cruising speed, seconds the turning carries on
     * after it, and the heading error that is left alone. 0: the design is steered as the first ones were (one side of
     * the drive for a small error, a turn on the spot for a large one).
     */
    public double gentleTurnRate = 0.0, gentleLead = 1.0, cruiseDeadband = 1.5;
    /** Turning on the spot: seconds the turning takes to die away, ticks of push per degree, and the error that is left alone. */
    public double turnCoastSeconds = 1.5, turnTicksPerDegree = 1.1, turnDeadband = 1.0;
    /** Before landing the ship is placed over the middle of the pad to within this many blocks, along each of two axes. */
    public double placeWithin = 0.2;
    /** Blocks per second the height target is lowered when landing. */
    public double descentRate = 0.4;
    /** Top speed (blocks per second), for looking ahead. */
    public double topSpeed = 1.9;
    /** Seconds a flight takes besides the way itself: climbing to the line, being placed over the pad, going down. */
    public double groundSeconds = 90.0;
    /**
     * For the plans aircraft keep clear of each other with: how fast the design climbs to the line (blocks per second,
     * all told), the seconds a point of the route costs it besides the way at top speed (getting under way, stopping,
     * turning into the next line), and the seconds it takes to be placed over a pad.
     */
    public double climbSpeed = 0.25, pointSeconds = 15.0, alignSeconds = 25.0;

    private AircraftType(String name) {
        this.name = name;
    }

    private void put(int x, int y, int z, String state) {
        parts.put(new BlockPos(x, y, z), state);
    }

    private void box(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) put(x, y, z, state);
    }

    private void manoeuvre(Drive which, int... values) {
        drive[which.ordinal()] = values;
    }

    /** Slow manoeuvres the design does not name are its ordinary ones. */
    private void gentleAsOrdinary() {
        for (final Drive which : Drive.values()) if (drive[which.ordinal()] == null) drive[which.ordinal()] = drive[which.ordinary().ordinal()];
    }

    /**
     * "Aircraft 2, geared 2:1, long deck": the design measured in the fourth lab round (9 long, 7 wide, 9 high, mass 62.75
     * with a plank where the aircraft's own block now sits; 1.87 blocks per second; mean tilt 3.2 degrees).
     */
    private static AircraftType small() {
        return core("small");
    }

    /** The machinery, envelope, seat and hold that were measured, under the given name. */
    private static AircraftType core(String name) {
        final AircraftType t = new AircraftType(name);
        final String plank = "minecraft:oak_planks", small = "create:cogwheel[axis=x]", large = "create:large_cogwheel[axis=x]", shaft = "create:shaft[axis=x]";
        t.box(-2, 0, -1, 2, 0, 1, plank);
        t.parts.remove(BlockPos.ZERO);      // the aircraft's own block takes the middle of the deck
        t.put(1, 1, 0, "aeronautics:adjustable_burner[powered=false]");
        t.put(1, 1, 1, "simulated:throttle_lever[face=floor,facing=east]");
        t.put(-1, 1, 0, "create:white_seat");
        t.engines = new BlockPos[2];
        for (int side = 0; side < 2; side++) {
            final int s = side == 0 ? -1 : 1, d = side == 0 ? 1 : -1;
            final String toward = d > 0 ? "east" : "west";
            t.put(-3 * d, 0, 2 * s, "simulated:white_portable_engine[facing=" + toward + "]");
            t.put(-2 * d, 0, 2 * s, large);
            t.put(-2 * d, 1, 3 * s, small);
            t.put(-d, 1, 3 * s, small);
            t.put(-d, 2, 3 * s, small);
            t.put(-d, 2, 2 * s, small);
            t.put(0, 2, 2 * s, "simulated:directional_gearshift[facing=up,axis_along_first=true]");
            t.put(0, 3, 2 * s, "simulated:throttle_lever[face=floor,facing=east]");
            t.put(0, 1, 2 * s, plank);
            t.put(0, 1, s, "simulated:throttle_lever[face=wall,facing=" + (s > 0 ? "north" : "south") + "]");
            t.put(d, 2, 2 * s, shaft);
            t.put(2 * d, 2, 2 * s, shaft);
            t.put(3 * d, 2, 2 * s, small);
            t.put(3 * d, 2, s, small);
            for (int y = 3; y <= 6; y++) t.put(3 * d, y, s, small);
            t.put(4 * d, 6, s, "aeronautics:wooden_propeller[facing=" + toward + "]");
            t.engines[side] = new BlockPos(-3 * d, 0, 2 * s);
        }
        final String envelope = "aeronautics:white_envelope";
        for (int y = 3; y <= 7; y++) for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++)
            if (Math.abs(x) == 2 || Math.abs(z) == 3) t.put(x, y, z, envelope);
        t.box(-2, 8, -3, 2, 8, 3, envelope);
        // The hold: two barrels on the two end cells of the deck that nobody can step onto from the ground anyway
        // (a column of cogwheels stands outside each); opposite corners, so the balance fore and aft stays.
        for (final BlockPos barrel : new BlockPos[] {new BlockPos(2, 1, -1), new BlockPos(-2, 1, 1)}) {
            t.parts.put(barrel, "minecraft:barrel[facing=up]");
            t.cargo.add(barrel);
        }
        // The middle cell of each end of the deck: one barrel stands beside each, and the deck is boarded over these ends.
        t.access.add(new BlockPos(-2, 1, 0));
        t.access.add(new BlockPos(2, 1, 0));
        t.seat = new BlockPos(-1, 1, 0);
        t.burners = new BlockPos[] {new BlockPos(1, 1, 0)};
        t.burnerLevers = new BlockPos[] {new BlockPos(1, 1, 1)};
        t.burnerValues = new int[] {80};
        // Two drives, one on each side, each with a lever for "toward the nose" and one for "toward the tail". Which
        // lever of a side does which was measured: left the inner lever ahead, right the upper one.
        t.driveLevers = new BlockPos[] {new BlockPos(0, 1, -1), new BlockPos(0, 3, -2), new BlockPos(0, 3, 2), new BlockPos(0, 1, 1)};
        //                          left ahead, left astern, right ahead, right astern
        t.manoeuvre(Drive.OFF, 0, 0, 0, 0);
        t.manoeuvre(Drive.AHEAD, 15, 0, 15, 0);
        t.manoeuvre(Drive.ASTERN, 0, 15, 0, 15);
        t.manoeuvre(Drive.RIGHT, 15, 0, 0, 15);
        t.manoeuvre(Drive.LEFT, 0, 15, 15, 0);
        t.manoeuvre(Drive.AHEAD_RIGHT, 15, 0, 0, 0);
        t.manoeuvre(Drive.AHEAD_LEFT, 0, 0, 15, 0);
        t.gentleAsOrdinary();
        return t;
    }

    /**
     * The outline every design has to stay inside, seen from the aircraft's own block: 9 long, 9 wide, 9 high (7 wide
     * until 0.11.0; the user's ruling of 2026-10-14: the propellers of the fast design go one block outward, off the
     * envelope). The pads (13 x 13), the distance between an airfield's two pads (17) and the shafts over the pads take
     * it; what that leaves of the tubes' margin is told at {@link com.asteain.airline.route.RouteRules#tubeRadius}.
     * This is the outline of a small aircraft; a design of another class stays inside its class's outline ({@link #shipClass},
     * 2026-10-15: until then a medium or large design was still held to this one).
     */
    public static final int HALF_LENGTH = 4, HALF_WIDTH = 4, HEIGHT = 10;
    /**
     * For the test bench only: a larger outline for the designs of this run, as "ahead x astern x half width x height"
     * (blocks from the aircraft's own block toward the nose and toward the tail, to either side, and layers; "5x7x4x12").
     * A design that needs it can be measured, but not put into the mod as it is: the pads and the tubes are made for the
     * outline above.
     */
    public static final String TRIAL_OUTLINE_PROPERTY = "airline.trialOutline";

    /** What is wrong with the design; null when it is complete and fits the outline. */
    @Nullable
    private String fault() {
        if (seat == null || !String.valueOf(parts.get(seat)).contains("_seat")) return "no seat at its place";
        if (burners.length == 0 || burnerLevers.length != burners.length || burnerValues.length != burners.length) return "no burner, or not one lever and one dial value for each";
        if (cargo.isEmpty()) return "no hold";
        if (access.isEmpty()) return "no place to stand at the hold";
        if (engines.length == 0) return "no engine";
        if (driveLevers.length == 0) return "no drive lever";
        for (final BlockPos at : burners) if (!String.valueOf(parts.get(at)).startsWith("aeronautics:adjustable_burner")) return "no burner at " + at.toShortString();
        for (final BlockPos at : engines) if (!String.valueOf(parts.get(at)).contains("portable_engine")) return "no engine at " + at.toShortString();
        for (final BlockPos at : cargo) if (!String.valueOf(parts.get(at)).startsWith("minecraft:barrel")) return "no barrel at " + at.toShortString();
        final List<BlockPos> levers = new ArrayList<>(List.of(burnerLevers));
        levers.addAll(List.of(driveLevers));
        for (final BlockPos at : levers) if (!String.valueOf(parts.get(at)).startsWith("simulated:throttle_lever")) return "no lever at " + at.toShortString();
        if (new java.util.HashSet<>(levers).size() != levers.size()) return "a lever is named twice";
        for (final Map.Entry<String, Dock> dock : docks.entrySet()) {
            if (!dock.getKey().equals("port") && !dock.getKey().equals("starboard")) return "a connector is on the side '" + dock.getKey() + "' (port or starboard)";
            if (!String.valueOf(parts.get(dock.getValue().connector())).startsWith("simulated:docking_connector")) return "no connector at " + dock.getValue().connector().toShortString();
            if (!String.valueOf(parts.get(dock.getValue().lever())).startsWith("simulated:throttle_lever")) return "no lever at " + dock.getValue().lever().toShortString();
            if (levers.contains(dock.getValue().lever())) return "the lever of a connector is a lever of the burners or the drives";
        }
        for (final Drive which : Drive.values()) {
            final int[] values = drive[which.ordinal()];
            if (values == null || values.length != driveLevers.length) return "the manoeuvre " + which + " does not say what every drive lever gets";
            for (final int value : values) if (value < 0 || value > 15) return "a lever value of the manoeuvre " + which + " is not between 0 and 15";
        }
        int ahead = shipClass.halfLength, astern = shipClass.halfLength, half = shipClass.halfWidth, height = shipClass.height;
        final String[] wider = System.getProperty(TRIAL_OUTLINE_PROPERTY, "").split("x");
        if (wider.length == 4) {
            try {
                ahead = Integer.parseInt(wider[0].trim());
                astern = Integer.parseInt(wider[1].trim());
                half = Integer.parseInt(wider[2].trim());
                height = Integer.parseInt(wider[3].trim());
            } catch (NumberFormatException e) {
                return "the outline to try out is not four numbers";
            }
        }
        for (final BlockPos part : parts.keySet()) {
            if (part.getX() > ahead || part.getX() < -astern || Math.abs(part.getZ()) > half || part.getY() < 0 || part.getY() >= height) return "the block at " + part.toShortString() + " is outside the outline";
        }
        return null;
    }

    private static BlockPos pos(JsonElement element) {
        final JsonArray a = element.getAsJsonArray();
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private void readBlocks(JsonObject file) {
        parts.clear();
        for (final JsonElement each : file.getAsJsonArray("blocks")) {
            final JsonArray block = each.getAsJsonArray();
            final BlockPos at = pos(block);
            if (!at.equals(BlockPos.ZERO)) parts.put(at, block.get(3).getAsString());
        }
    }

    private void readHandling(JsonObject file) throws ReflectiveOperationException {
        if (!file.has("handling")) return;
        for (final Map.Entry<String, JsonElement> number : file.getAsJsonObject("handling").entrySet()) {
            final java.lang.reflect.Field field = AircraftType.class.getField(number.getKey());
            if (field.getType() == int.class) field.setInt(this, number.getValue().getAsInt());
            else field.setDouble(this, number.getValue().getAsDouble());
        }
    }

    /**
     * A design from a file. Two kinds:
     *
     * <p><b>A hull around the measured machinery</b> (no "format", or 1): {@code "blocks": [[x, y, z, "state"], ...]} with
     * every block of the aircraft except its own block; the machinery, the seat and the hold of {@link #small()} have to be
     * exactly where they are there (the corner posts and the rim of the roof of the envelope, which hold no air, may be
     * left out).</p>
     *
     * <p><b>A design of its own</b> ("format": 2): besides the blocks it says where its devices are and how it is driven:
     * {@code "seat": [x, y, z]}, {@code "cargo": [[x, y, z], ...]} (barrels), {@code "access": [[x, y, z], ...]},
     * {@code "burners": [{"at": [x, y, z], "lever": [x, y, z], "value": 80}, ...]}, {@code "engines": [[x, y, z], ...]},
     * {@code "levers": {"name": [x, y, z], ...}} (the levers of the drives) and
     * {@code "drive": {"ahead": {"name": 15, ...}, "astern": ..., "left": ..., "right": ..., "ahead_left": ..., "ahead_right": ...}}
     * (for each manoeuvre the levers that are not at 0; "ahead_slow", "astern_slow", "left_slow" and "right_slow" may be
     * named as well, and "off" when the drives are off with a lever at something else than 0), and optionally
     * {@code "throttles": ["name", ...]}: the drive levers that are moved a few steps at a time.</p>
     *
     * <p>Both may carry {@code "handling": {"topSpeed": 2.1, ...}}: numbers that were measured with this design.</p>
     */
    public static AircraftType read(String name, java.io.Reader json) throws IOException {
        try {
            final JsonObject file = com.google.gson.JsonParser.parseReader(json).getAsJsonObject();
            final boolean own = file.has("format") && file.get("format").getAsInt() >= 2;
            final AircraftType t = own ? new AircraftType(name) : core(name);
            if (file.has("class")) t.shipClass = com.asteain.airline.route.ShipClass.of(file.get("class").getAsString());
            if (own) {
                // A design of its own climbs to the line with its target raised a block a second (the measured designs go on as they were measured).
                t.climbRate = 1.0;
                t.readBlocks(file);
                t.seat = pos(file.get("seat"));
                for (final JsonElement each : file.getAsJsonArray("cargo")) t.cargo.add(pos(each));
                for (final JsonElement each : file.getAsJsonArray("access")) t.access.add(pos(each));
                if (file.has("passengers")) for (final JsonElement each : file.getAsJsonArray("passengers")) t.passengers.add(pos(each));
                if (file.has("docks")) {
                    for (final Map.Entry<String, JsonElement> dock : file.getAsJsonObject("docks").entrySet()) {
                        final JsonObject one = dock.getValue().getAsJsonObject();
                        t.docks.put(dock.getKey(), new Dock(pos(one.get("connector")), pos(one.get("lever"))));
                    }
                }
                final JsonArray burners = file.getAsJsonArray("burners");
                t.burners = new BlockPos[burners.size()];
                t.burnerLevers = new BlockPos[burners.size()];
                t.burnerValues = new int[burners.size()];
                for (int i = 0; i < burners.size(); i++) {
                    final JsonObject burner = burners.get(i).getAsJsonObject();
                    t.burners[i] = pos(burner.get("at"));
                    t.burnerLevers[i] = pos(burner.get("lever"));
                    t.burnerValues[i] = burner.get("value").getAsInt();
                }
                final JsonArray engines = file.getAsJsonArray("engines");
                t.engines = new BlockPos[engines.size()];
                for (int i = 0; i < engines.size(); i++) t.engines[i] = pos(engines.get(i));
                final List<String> names = new ArrayList<>(file.getAsJsonObject("levers").keySet());
                t.driveLevers = new BlockPos[names.size()];
                for (int i = 0; i < names.size(); i++) t.driveLevers[i] = pos(file.getAsJsonObject("levers").get(names.get(i)));
                final JsonObject drive = file.getAsJsonObject("drive");
                for (final Drive which : Drive.values()) {
                    final String key = which.name().toLowerCase(java.util.Locale.ROOT);
                    if (!drive.has(key) && which == Drive.OFF) {
                        // Not named: with the drives off every lever is at 0.
                        t.manoeuvre(Drive.OFF, new int[names.size()]);
                        continue;
                    }
                    if (!drive.has(key) && which.ordinary() != which) continue;
                    if (!drive.has(key)) throw new IOException("the manoeuvre '" + key + "' is missing");
                    final int[] values = new int[names.size()];
                    for (final Map.Entry<String, JsonElement> lever : drive.getAsJsonObject(key).entrySet()) {
                        final int index = names.indexOf(lever.getKey());
                        if (index < 0) throw new IOException("the manoeuvre '" + key + "' names the lever '" + lever.getKey() + "', which is not among the levers");
                        values[index] = lever.getValue().getAsInt();
                    }
                    t.manoeuvre(which, values);
                }
                t.gentleAsOrdinary();
                if (file.has("throttles")) {
                    t.throttle = new boolean[names.size()];
                    for (final JsonElement each : file.getAsJsonArray("throttles")) {
                        final int index = names.indexOf(each.getAsString());
                        if (index < 0) throw new IOException("the throttle '" + each.getAsString() + "' is not among the levers");
                        t.throttle[index] = true;
                    }
                }
            } else {
                final Map<BlockPos, String> machinery = new LinkedHashMap<>(t.parts);
                t.readBlocks(file);
                // The devices the autopilot works, and what turns between them, have to be exactly where they were measured.
                for (final Map.Entry<BlockPos, String> part : machinery.entrySet()) {
                    final String was = part.getValue(), is = t.parts.get(part.getKey());
                    final boolean device = was.startsWith("create:") && !was.endsWith("_seat") || was.startsWith("simulated:") || was.startsWith("aeronautics:") && !was.endsWith("_envelope");
                    if (device && !was.equals(is)) throw new IOException("the device " + was + " at " + part.getKey().toShortString() + " is " + is);
                }
            }
            t.readHandling(file);
            final String fault = t.fault();
            if (fault != null) throw new IOException(fault);
            return t;
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException(e.toString(), e);
        }
    }

    /** The design of that name out of the jar ({@code airline_aircraft/<name>.json}); null (and a line in the log) when it cannot be read. */
    @Nullable
    private static AircraftType bundled(String name) {
        try (java.io.InputStream in = AircraftType.class.getResourceAsStream("/airline_aircraft/" + name + ".json")) {
            if (in == null) throw new IOException("no such file in the jar");
            return read(name, new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("Airline").error("[Airline/flight] the aircraft design '{}' could not be read and is left out: {}", name, e.getMessage());
            return null;
        }
    }

    /** The designs that come as files in the jar (airline_aircraft/), in the order they are offered after the first design. */
    private static final String[] BUNDLED = {"whitesail", "koeda", "stratus", "oonagi"};
    /**
     * The design an airfield gets when nothing else is asked for (the user's ruling of 2026-10-14: the fast one; larger
     * and better ones are to follow it). It is offered first. If its file cannot be read, the first design takes its place.
     * Koeda since 2026-10-15 (the user's ruling: "こかぜと入れ替える"; kokaze itself is gone from 0.12.0: no backward
     * compatibility before 1.0.0, the user's ruling of 2026-10-16).
     */
    public static final String STANDARD = "koeda";

    /**
     * The parts a propeller bearing turns (2026-10-15, the medium aircraft): the hub in front of the bearing and the sails
     * fastened to it in the same plane. Once the rotation reaches the bearing they are no longer blocks of the ship but a
     * turning contraption, and their places are empty; keeping the aircraft as it was built leaves those places alone.
     */
    @Nullable private java.util.Set<BlockPos> moving;

    public java.util.Set<BlockPos> moving() {
        if (moving != null) return moving;
        final java.util.Set<BlockPos> found = new java.util.HashSet<>();
        for (final Map.Entry<BlockPos, String> part : parts.entrySet()) {
            final String state = part.getValue();
            if (!state.startsWith("aeronautics:") || !state.contains("propeller_bearing")) continue;
            final java.util.regex.Matcher facing = java.util.regex.Pattern.compile("facing=([a-z]+)").matcher(state);
            final net.minecraft.core.Direction toward = facing.find() ? net.minecraft.core.Direction.byName(facing.group(1)) : null;
            if (toward == null) continue;
            final BlockPos hub = part.getKey().relative(toward);
            if (!parts.containsKey(hub)) continue;
            final java.util.ArrayDeque<BlockPos> todo = new java.util.ArrayDeque<>(List.of(hub));
            while (!todo.isEmpty()) {
                final BlockPos at = todo.poll();
                if (!found.add(at)) continue;
                for (final net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                    if (side.getAxis() == toward.getAxis()) continue;           // the plane the hub turns in
                    final BlockPos next = at.relative(side);
                    if (!found.contains(next) && String.valueOf(parts.get(next)).contains("_sail")) todo.add(next);
                }
            }
        }
        return moving = java.util.Collections.unmodifiableSet(found);
    }

    /** The design that is made in code and always there; an aircraft of a save that does not name its design is one of these. */
    public static final String FIRST = "small";

    /**
     * For trying a design out before it is put into the mod: the path of a design file, given to the server as the
     * system property {@code airline.design}. It is offered under the file's name (without ".json") like any other.
     */
    public static final String TRIAL_PROPERTY = "airline.design";
    /** Why the design to try out could not be read; null when there is none, or it was read. */
    @Nullable public static String trialFault;

    /** The designs, in the order they are offered. The first one ({@link #STANDARD}) is what an airfield gets when nothing else is asked for. */
    public static final Map<String, AircraftType> ALL = all();
    public static final AircraftType DEFAULT = ALL.values().iterator().next();

    private static Map<String, AircraftType> all() {
        final Map<String, AircraftType> all = new LinkedHashMap<>();
        final AircraftType first = small();
        if (first.fault() != null) throw new IllegalStateException("aircraft design '" + first.name + "': " + first.fault());
        final AircraftType standard = bundled(STANDARD);
        if (standard != null) all.put(standard.name, standard);
        all.put(first.name, first);
        for (final String name : BUNDLED) {
            if (all.containsKey(name)) continue;
            final AircraftType type = bundled(name);
            if (type != null) all.put(type.name, type);
        }
        final String trial = System.getProperty(TRIAL_PROPERTY, "");
        if (!trial.isEmpty()) {
            final java.nio.file.Path path = java.nio.file.Path.of(trial);
            final String name = path.getFileName().toString().replaceFirst("\\.json$", "");
            try (java.io.Reader in = java.nio.file.Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8)) {
                all.put(name, read(name, in));
            } catch (IOException | RuntimeException e) {
                trialFault = e.getMessage() == null ? e.toString() : e.getMessage();
                org.slf4j.LoggerFactory.getLogger("Airline").error("[Airline/flight] the aircraft design to try out ({}) could not be read: {}", trial, trialFault);
            }
        }
        return java.util.Collections.unmodifiableMap(all);
    }

    /** The design of that name; an unknown name (a design that was taken out of the mod) gives the first design. */
    public static AircraftType of(String name) {
        return ALL.getOrDefault(name, DEFAULT);
    }

    /** The translation key of the design's name as players see it. */
    public String title() {
        return "aircraft.airline." + name;
    }
}
