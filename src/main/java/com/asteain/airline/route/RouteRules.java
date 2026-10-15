package com.asteain.airline.route;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Every rule and number of the buoy network in one place. Most of them are the assistant's proposals that the user has
 * not ruled on yet (how buoys connect, how far they reach, where the line passes, what counts as "arrived"); when a
 * ruling comes, this is the class to change.
 *
 * <p>The numbers are in the common config file. The rules that are not numbers are the static functions below; nothing
 * else in the mod decides whether two points connect or where a line runs.</p>
 */
public final class RouteRules {
    private RouteRules() {}

    public static final int TIERS = 3;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("The buoy network.").push("network");
    }

    /** How far each grade of buoy reaches (blocks). Two points connect when they are closer than the sum of their reaches. */
    private static final ModConfigSpec.IntValue REACH_1 = BUILDER
            .comment("Reach of a grade 1 buoy, in blocks. Two points connect when their distance is less than the sum of their reaches.")
            .defineInRange("buoyReach1", 48, 8, 1024);
    private static final ModConfigSpec.IntValue REACH_2 = BUILDER.comment("Reach of a grade 2 buoy.").defineInRange("buoyReach2", 96, 8, 1024);
    private static final ModConfigSpec.IntValue REACH_3 = BUILDER.comment("Reach of a grade 3 buoy.").defineInRange("buoyReach3", 192, 8, 1024);
    /**
     * An airfield reaches further the higher its level (the user's wish of 2026-10-12: colonies that lie very near each
     * other get a direct flight without buoys), and it reaches on the scale of colonies: its reach is counted in chunks and
     * comes from MineColonies' own settings for how near colonies may be founded and how large they may grow (the user,
     * the same day: a reach that ignores the size of a colony is of no use even at the highest level). With the settings
     * as they come (town halls at least 8 chunks apart, a colony at most 20 chunks from its town hall) the levels reach
     * 8, 10, 12, 16 and 20 chunks: level 1 as far as the nearest possible neighbour's town hall, level 5 as far as a
     * colony can stretch. The rule is the one for buoys: two points connect when they are closer than the sum of their
     * reaches, so two level 1 airfields connect within 16 chunks (256 blocks) and two of level 5 within 40 chunks (640).
     * A route still never leads through an airfield, so a junction of lines is always a buoy.
     *
     * <p>That reach is what an airfield has toward buoys, and toward an airfield that lies just across the edge of a
     * large map. Between airfields the wider rule is the one of the large maps ({@link #LARGE_MAP}).</p>
     */
    public static final int AIRFIELD_LEVELS = 5;
    private static final ModConfigSpec.IntValue[] AIRFIELD_REACH = new ModConfigSpec.IntValue[AIRFIELD_LEVELS];

    static {
        for (int level = 1; level <= AIRFIELD_LEVELS; level++) {
            AIRFIELD_REACH[level - 1] = (level == 1 ? BUILDER.comment("Reach of an airfield's landing pads at building level 1, in chunks (16 blocks); airfieldReachChunks2 to 5 are for the higher levels.",
                    "Two airfields connect directly when their distance is less than the sum of their reaches.",
                    "0 takes the reach from MineColonies' settings: level 1 reaches minColonyDistance (8), level 5 maxColonySize (20), the levels between lie between them (10, 12, 16).") : BUILDER)
                    .defineInRange("airfieldReachChunks" + level, 0, 0, 64);
        }
    }
    /**
     * Airfields reach each other by large maps (the user's ruling of 2026-10-13): the world is cut into squares of this
     * many chunks a side, starting at the world's origin (128 chunks: sixteen region files, four by four; 2048 blocks; the
     * user's ruling of 2026-10-14). An
     * airfield of level 1 reaches every airfield on its own map, level 2 the 3 x 3 maps around its own, and so on to
     * 9 x 9 maps at level 5. Further than that a flight needs points in between. Two airfields connect when the higher
     * of the two reaches the other (the user's ruling of 2026-10-14). The map an airfield is on is the map its hut block
     * stands on, and between airfields nothing but the maps counts: two airfields of level 1 that lie close to each other
     * on two sides of an edge between maps do not connect until one of them is built up (the same ruling).
     */
    private static final ModConfigSpec.IntValue LARGE_MAP = BUILDER
            .comment("Airfields connect to each other directly by 'large maps': squares of this many chunks a side, counted from the world's origin.",
                    "128 chunks are sixteen region files (four by four, 2048 blocks). An airfield of level 1 reaches all airfields on its own large map,",
                    "level 2 those on the 3 x 3 large maps around its own, level 3 on 5 x 5, level 4 on 7 x 7, level 5 on 9 x 9.",
                    "Two airfields connect when the one with the higher level reaches the other; the large map of an airfield is the one its hut block stands on.",
                    "0 switches this off (then airfields connect by airfieldReachChunks, as buoys do).")
            .defineInRange("largeMapChunks", 128, 0, 4096);
    /** The line passes this far above the buoy block, so that an aircraft never touches the buoy itself. */
    private static final ModConfigSpec.IntValue WAYPOINT_HEIGHT = BUILDER
            .comment("The line passes this many blocks above a buoy.")
            .defineInRange("waypointHeight", 8, 2, 64);
    /** Over a landing pad the line passes higher: an aircraft goes straight up to there, clear of the airfield's own roof. */
    private static final ModConfigSpec.IntValue AIRFIELD_WAYPOINT_HEIGHT = BUILDER
            .comment("The line passes this many blocks above an airfield's landing pad. An aircraft goes straight up to there before it leaves,",
                    "so that the airfield's own building (up to 8 blocks lower than this) is not in its way.")
            .defineInRange("airfieldWaypointHeight", 16, 2, 64);
    /** Room added around the aircraft's outline to get the tube that has to be free of solid blocks. */
    private static final ModConfigSpec.DoubleValue TUBE_MARGIN = BUILDER
            .comment("Room added around the aircraft's outline, in blocks, to get the tube that must be free of solid blocks.")
            .defineInRange("tubeMargin", 2.0, 0.0, 16.0);
    /** An aircraft has arrived at a point when it has stopped within this distance of it (flat). */
    private static final ModConfigSpec.DoubleValue ARRIVAL_RADIUS = BUILDER
            .comment("An aircraft has arrived at a point when it has stopped within this many blocks of it (flat distance). Landing pads are built this much larger.")
            .defineInRange("arrivalRadius", 1.5, 0.5, 8.0);
    private static final ModConfigSpec.IntValue VERIFY_BLOCKS_PER_TICK = BUILDER
            .comment("How many block positions the verification of lines may look at in one tick (per dimension).")
            .defineInRange("verifyBlocksPerTick", 4000, 100, 200000);
    private static final ModConfigSpec.IntValue RETRY_SECONDS = BUILDER
            .comment("Lines that could not be verified completely (parts not loaded) are looked at again this often, in seconds.")
            .defineInRange("retryUnverifiedSeconds", 30, 5, 3600);

    private static final ModConfigSpec.IntValue LOOK_AHEAD = BUILDER
            .comment("How far ahead of itself a flying aircraft looks for solid blocks and other ships, in blocks. Must be well beyond its stopping distance (about 4).")
            .defineInRange("lookAhead", 24, 8, 64);

    static {
        BUILDER.pop();
        BUILDER.comment("How things look.").push("looks");
    }

    /** Not a rule of the network; it lives here because this is the mod's common settings file. */
    private static final ModConfigSpec.BooleanValue PILOT_SKIN = BUILDER
            .comment("Pilots wear their own skin (flying cap, goggles, scarf). Set to false and they look like any other citizen.",
                    "Read on the server. Switch it off if a client fails when a pilot comes into view.")
            .define("pilotSkin", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean pilotSkin() {
        return PILOT_SKIN.get();
    }

    // ---- numbers ------------------------------------------------------------------------------------------

    /** Reach of a buoy of the given grade (1 to 3). */
    public static int reach(int tier) {
        return switch (tier) {
            case 1 -> REACH_1.get();
            case 2 -> REACH_2.get();
            default -> REACH_3.get();
        };
    }

    /** Reach, in blocks, of the landing pads of an airfield of the given building level (a hut that is not built yet counts as level 1). */
    public static int airfieldReach(int level) {
        final int lv = Math.max(1, Math.min(AIRFIELD_LEVELS, level));
        final int set = AIRFIELD_REACH[lv - 1].get();
        return 16 * (set > 0 ? set : reachFromColonySizes(lv));
    }

    /**
     * The reach in chunks that follows from MineColonies' settings: level 1 as far as two town halls have to be apart at
     * least, level 5 as far as a colony may stretch from its town hall, the levels between a sixth, a third and two
     * thirds of the way.
     */
    public static int reachFromColonySizes(int level) {
        int near = 8, far = 20;
        try {
            final com.minecolonies.api.configuration.ServerConfiguration colonies = com.minecolonies.core.MineColonies.getConfig().getServer();
            near = colonies.minColonyDistance.get();
            far = colonies.maxColonySize.get();
        } catch (RuntimeException | LinkageError notLoaded) {
            // MineColonies' server settings are read when a world is opened; before that the numbers they come with stand in.
        }
        far = Math.max(near, far);
        final int span = far - near;
        return switch (level) {
            case 1 -> near;
            case 2 -> near + Math.round(span / 6.0F);
            case 3 -> near + Math.round(span / 3.0F);
            case 4 -> near + Math.round(span * 2 / 3.0F);
            default -> far;
        };
    }

    /** The setting itself, for the tests (which lay out their scenes for fixed reaches). */
    public static ModConfigSpec.IntValue airfieldReachSetting(int level) {
        return AIRFIELD_REACH[level - 1];
    }

    /** The setting itself, for the tests (whose scenes lie within a few hundred blocks). */
    public static ModConfigSpec.IntValue largeMapSetting() {
        return LARGE_MAP;
    }

    /** Side of a large map in blocks; 0 when airfields do not connect by large maps. */
    public static int largeMap() {
        return 16 * LARGE_MAP.get();
    }

    /** How many large maps beyond its own an airfield of that level reaches in every direction: 0 at level 1, 4 at level 5. */
    public static int mapsAround(int level) {
        return Math.max(1, Math.min(AIRFIELD_LEVELS, level)) - 1;
    }

    /** How many large maps two places are apart: 0 on the same map, 1 on maps that touch (also at a corner). */
    public static int mapsApart(BlockPos a, BlockPos b) {
        final int side = largeMap();
        if (side <= 0) return Integer.MAX_VALUE;
        return Math.max(Math.abs(Math.floorDiv(a.getX(), side) - Math.floorDiv(b.getX(), side)), Math.abs(Math.floorDiv(a.getZ(), side) - Math.floorDiv(b.getZ(), side)));
    }

    /** Reach of a point: {@code tier} is the grade of a buoy, or the building level of an airfield. */
    public static int reach(RouteNetwork.Kind kind, int tier) {
        return kind == RouteNetwork.Kind.AIRFIELD ? airfieldReach(tier) : reach(tier);
    }

    public static int waypointHeight() {
        return WAYPOINT_HEIGHT.get();
    }

    public static int airfieldWaypointHeight() {
        return AIRFIELD_WAYPOINT_HEIGHT.get();
    }

    public static double arrivalRadius() {
        return ARRIVAL_RADIUS.get();
    }

    public static int verifyBlocksPerTick() {
        return VERIFY_BLOCKS_PER_TICK.get();
    }

    public static int lookAhead() {
        return LOOK_AHEAD.get();
    }

    public static int retryTicks() {
        return RETRY_SECONDS.get() * 20;
    }

    /**
     * The outline of the one aircraft design there is so far (aircraft 2, geared 2:1, long deck: 9 long, 7 wide, 9 high).
     * When aircraft types become data these two numbers come from the type; the network is verified for this one design only.
     */
    private static final double AIRCRAFT_HALF_DIAGONAL = Math.hypot(4.5, 3.5);
    private static final double AIRCRAFT_HALF_HEIGHT = 4.5;
    /**
     * The outline as it is since 2026-10-14: 9 long, 9 wide. The tube keeps the radius that was made for 9 x 7 (8 with
     * the margin as it comes): a wider one would take in lanterns and eaves of the airfields as they are built (the
     * shaft over a pad is a tube as well). An aircraft that fills the wider outline has 4.5 to either side along a line
     * (3.5 of margin), and 6.4 to its corners when it turns on the spot (1.6 of margin: it does that at the points of
     * the network, at rest, and high over the pads). On a pad it goes up and down in the pad's heading.
     */
    private static final double WIDEST_HALF_DIAGONAL = Math.hypot(4.5, 4.5);

    /** Flat radius of the tube around a line that must be free of solid blocks. */
    public static double tubeRadius() {
        return Math.ceil(AIRCRAFT_HALF_DIAGONAL + TUBE_MARGIN.get());
    }

    /** Half the height of that tube (above and below the line). */
    public static double tubeHalfHeight() {
        return AIRCRAFT_HALF_HEIGHT + TUBE_MARGIN.get();
    }

    /**
     * The tube for aircraft of a class (the user's ruling of 2026-10-15: lines are verified for the class they are for).
     * Small aircraft keep the tube there always was (above); the larger classes their outline's half diagonal and the margin.
     */
    public static double tubeRadius(ShipClass ship) {
        return ship == ShipClass.SMALL ? tubeRadius() : Math.ceil(ship.halfDiagonal() + TUBE_MARGIN.get());
    }

    public static double tubeHalfHeight(ShipClass ship) {
        return ship.halfHeight() + TUBE_MARGIN.get();
    }

    /**
     * How much higher than the settings the lines pass over a point made for a larger class: over a buoy, so far that the
     * larger tube keeps the same distance from the buoy as the small one; over a pad, so far that its lines still pass
     * over the pad beside it with an aircraft of that class standing there ({@link #padClearance}). 0 for small.
     */
    public static int waypointRaise(RouteNetwork.Kind kind, ShipClass ship) {
        if (ship == ShipClass.SMALL) return 0;
        final double more = kind == RouteNetwork.Kind.AIRFIELD ? padClearance(ship, ship) - padClearance(ShipClass.SMALL, ShipClass.SMALL)
                : tubeHalfHeight(ship) - tubeHalfHeight(ShipClass.SMALL);
        return (int) Math.ceil(more);
    }

    // ---- rules that are not numbers -----------------------------------------------------------------------

    /** Where the line passes for a point whose block (buoy, or the middle of a landing pad's floor) is at {@code anchor}. */
    public static Vec3 waypoint(BlockPos anchor, RouteNetwork.Kind kind) {
        return waypoint(anchor, kind, ShipClass.SMALL);
    }

    /** ... for a point made for aircraft up to this class (a pad: its facility's largest; a buoy: by its grade). */
    public static Vec3 waypoint(BlockPos anchor, RouteNetwork.Kind kind, ShipClass ship) {
        final int up = (kind == RouteNetwork.Kind.AIRFIELD ? airfieldWaypointHeight() : waypointHeight()) + waypointRaise(kind, ship);
        return new Vec3(anchor.getX() + 0.5, anchor.getY() + 0.5 + up, anchor.getZ() + 0.5);
    }

    /** Over a landing pad nothing may stand from this many blocks above the pad's floor upward (a fence on the floor is fine). */
    public static final int SHAFT_FREE_FROM = 2;

    /**
     * The space over a landing pad an aircraft goes up and down in: an upright cylinder of the tube's radius, from just
     * above the floor to where the lines begin. It belongs to every line of that pad.
     */
    public static Tube shaft(BlockPos pad) {
        return shaft(pad, ShipClass.SMALL, ShipClass.SMALL);
    }

    /** ... over a pad made for aircraft up to {@code padClass}, for aircraft of class {@code ship}. */
    public static Tube shaft(BlockPos pad, ShipClass padClass, ShipClass ship) {
        final Vec3 top = waypoint(pad, RouteNetwork.Kind.AIRFIELD, padClass);
        final double bottom = pad.getY() + SHAFT_FREE_FROM + tubeHalfHeight(ship) + 0.5;
        return new Tube(top, new Vec3(top.x, Math.min(top.y, bottom), top.z), tubeRadius(ship), tubeHalfHeight(ship));
    }

    /**
     * An aircraft may stand on any landing pad, so no line may come low over one (proposal of the assistant; it matters
     * from the second aircraft on). Within this distance of a pad's middle, seen from above (the half diagonals of two
     * aircraft and a block), ...
     */
    public static double padReach() {
        return 2 * WIDEST_HALF_DIAGONAL + 1.0;
    }

    /** ... for an aircraft of class {@code flying} over a pad where one of class {@code parked} may stand. */
    public static double padReach(ShipClass parked, ShipClass flying) {
        return parked.halfDiagonal() + flying.halfDiagonal() + 1.0;
    }

    /**
     * ... a line has to pass at least this high above the pad's floor block: the top of the floor, an aircraft standing
     * on it, a block of air, and the lower half of the aircraft that flies. (The lines of a pad itself start a block
     * higher than that with the settings as they come, so the lines of the pad beside it pass.)
     */
    public static double padClearance() {
        return 1.0 + 2 * AIRCRAFT_HALF_HEIGHT + 1.0 + AIRCRAFT_HALF_HEIGHT;
    }

    /** ... for an aircraft of class {@code flying} over a pad where one of class {@code parked} may stand. */
    public static double padClearance(ShipClass parked, ShipClass flying) {
        return 1.0 + parked.height + 1.0 + flying.halfHeight();
    }

    /** Whether a line (or a piece of one) comes too low over the landing pad at {@code pad}. The line's own ends are not asked about. */
    public static boolean grazes(Tube line, BlockPos pad) {
        return line.lowestNear(pad.getX() + 0.5, pad.getZ() + 0.5, padReach()) < pad.getY() + padClearance();
    }

    /** ... for aircraft of class {@code flying}, over a pad where one of class {@code parked} may stand. */
    public static boolean grazes(Tube line, BlockPos pad, ShipClass parked, ShipClass flying) {
        return line.lowestNear(pad.getX() + 0.5, pad.getZ() + 0.5, padReach(parked, flying)) < pad.getY() + padClearance(parked, flying);
    }

    /** Whether two points connect: their reaches overlap. Whether the line can be flown is a different question (verification). */
    public static boolean connects(BlockPos a, int reachA, BlockPos b, int reachB) {
        final double reach = reachA + reachB;
        return !a.equals(b) && a.distSqr(b) < reach * reach;
    }

    /**
     * Whether two points of the network connect. Two landing pads connect when the airfield of the higher level reaches
     * the other one's large map, counted from where the airfields stand (their hut blocks), and by nothing else (the
     * user's ruling of 2026-10-14). With the large maps switched off, and for buoys, the reaches have to overlap.
     */
    public static boolean connects(RouteNetwork.Node a, RouteNetwork.Node b) {
        if (a.pos().equals(b.pos())) return false;
        if (a.kind() == RouteNetwork.Kind.AIRFIELD && b.kind() == RouteNetwork.Kind.AIRFIELD && largeMap() > 0) {
            return mapsApart(a.home(), b.home()) <= mapsAround(Math.max(a.tier(), b.tier()));
        }
        return connects(a.pos(), a.reach(), b.pos(), b.reach());
    }

    /**
     * Where an airfield's landing pad is: the middle block of the pad's floor, seen from the hut block of a building that
     * was not turned (the pad lies 14 blocks to the north and one to the east, its floor one block lower than the hut;
     * tools/make_blueprint.py builds the airfield to this). The pad is a square of this half size plus one: room for the
     * aircraft in any direction plus the arrival radius.
     */
    public static final BlockPos PAD_FROM_HUT = new BlockPos(1, -1, -14);
    public static final int PAD_HALF_SIZE = 6;

    public static BlockPos pad(BlockPos hut, Rotation turned) {
        return hut.offset(PAD_FROM_HUT.rotate(turned));
    }

    /**
     * From this level on an airfield has a second landing pad, for aircraft of other airfields (proposal of the assistant):
     * 17 blocks east of the first, the same size and the same heading. The two shafts stand side by side with a block
     * between them (tools/make_blueprint.py builds level 2 to this).
     */
    public static final int GUEST_PAD_LEVEL = 2;
    public static final BlockPos GUEST_PAD_FROM_HUT = new BlockPos(18, -1, -14);

    /** The heading an aircraft rests in on the pad: its tail toward the hut, so the nose points away from it. */
    public static int padHeading(Rotation turned) {
        return Math.floorMod(270 + 90 * turned.ordinal(), 360);
    }

    /**
     * The same from where "north of the building as it was designed" (0, 0, -1) has come to lie after the building was
     * turned and mirrored: right for every way a building can be placed, whichever axis a mirror flips.
     */
    public static int padHeading(BlockPos designNorth) {
        return Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(designNorth.getZ(), designNorth.getX()))), 360);
    }
}
