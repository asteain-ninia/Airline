package com.asteain.airline.route;

import net.minecraft.core.BlockPos;

/**
 * What kind of place a landing pad belongs to (the user's ruling of 2026-10-14): the small airfield (the one there was
 * before), the medium and the large airfield, and the anchorage (泊地: a large airfield without what makes an airfield,
 * a place where aircraft only berth; it needs an airfield of any size in its colony, as a warehouse needs a courier).
 *
 * <p>Which classes of aircraft may berth where, as the user laid it down:</p>
 * <pre>
 *                     small airfield   medium airfield   large airfield   anchorage
 *   small aircraft          yes              yes               no             no
 *   medium aircraft         no               yes               yes            yes
 *   large aircraft          no               no                yes            yes
 * </pre>
 * <p>Connectors (Create Aeronautics): a small airfield has a connector in the floor of its pads from level 3 on, a
 * medium airfield always; a large airfield and an anchorage have side connectors and none in the floor.</p>
 *
 * <p>The geometry of an airfield's pads, seen from its hut block in a building that was not turned (the designs are made
 * to it): the first pad lies so far to the north and one block to the east, its floor one block lower than the hut; the
 * second (from {@link RouteRules#GUEST_PAD_LEVEL}) lies east of the first, with room for both shafts. Each pad is a
 * square of {@link #padHalfSize} each way from its middle.</p>
 *
 * <p>The anchorage (the user's rulings of 2026-10-15) is a pier, one berth, level 1 only: its hut block (a bollard) stands
 * at the root of the pier, the pier runs north from it into the air, and the aircraft is held in the air beside the pier
 * (east of it), its deck level with the pier's. Its "pad" is the block under where a large aircraft's own block is held
 * ({@link #ANCHORAGE_BERTH}); there is no floor under it. The pier's deck runs along the berth a large aircraft's half
 * width and one block to the west of the pad, one block higher; a smaller aircraft is held closer to the pier.</p>
 */
public enum Facility {
    SMALL_AIRFIELD("airfield", 6),
    MEDIUM_AIRFIELD("mediumairfield", 10),
    LARGE_AIRFIELD("largeairfield", 15),
    ANCHORAGE("anchorage", 0);

    /** The anchorage's berth from its hut block (not turned): east of the pier, so far out that the bollard is outside the shaft. */
    public static final BlockPos ANCHORAGE_BERTH = new BlockPos(ShipClass.LARGE.halfWidth + 2, -2, -20);
    /** From an anchorage's pad to the edge of its pier's deck, sideways (and one block up). */
    public static final int PIER_FROM_PAD = ShipClass.LARGE.halfWidth + 1;

    /** The building's name in MineColonies and in the blueprints ("airfield1" to "airfield5", and so on). */
    public final String schematic;
    private final int padHalfSize;

    Facility(String schematic, int padHalfSize) {
        this.schematic = schematic;
        this.padHalfSize = padHalfSize;
    }

    /** Whether an aircraft of this class may berth here (the table above). */
    public boolean berths(ShipClass ship) {
        return switch (this) {
            case SMALL_AIRFIELD -> ship == ShipClass.SMALL;
            case MEDIUM_AIRFIELD -> ship == ShipClass.SMALL || ship == ShipClass.MEDIUM;
            case LARGE_AIRFIELD, ANCHORAGE -> ship == ShipClass.MEDIUM || ship == ShipClass.LARGE;
        };
    }

    /** The largest class that berths here: what the pad, its shaft and its lines are made for. */
    public ShipClass largest() {
        return this == SMALL_AIRFIELD ? ShipClass.SMALL : this == MEDIUM_AIRFIELD ? ShipClass.MEDIUM : ShipClass.LARGE;
    }

    /** Whether aircraft of this class are built here (provisional: what an airfield can berth; an anchorage builds none). */
    public boolean builds(ShipClass ship) {
        return this != ANCHORAGE && berths(ship);
    }

    /** Whether this is an airfield (with pilot, hangar and building site), not an anchorage. */
    public boolean airfield() {
        return this != ANCHORAGE;
    }

    /** Whether its aircraft are held in the air beside a pier instead of set down on a floor (the anchorage). */
    public boolean airborne() {
        return this == ANCHORAGE;
    }

    /** The highest building level (an anchorage has one level only). */
    public int maxLevel() {
        return this == ANCHORAGE ? 1 : 5;
    }

    public boolean floorConnector(int level) {
        return this == SMALL_AIRFIELD ? level >= 3 : this == MEDIUM_AIRFIELD;
    }

    /** The anchorage's pier. (The large airfield had two at the sides of its pad, which no aircraft reached: taken out on 2026-10-18, the user's ruling.) */
    public boolean sideConnector() {
        return this == ANCHORAGE;
    }

    public int padHalfSize() {
        return padHalfSize;
    }

    /** The middle of the first pad's floor, from the hut block (not turned). For the small airfield: 1 east, 1 down, 14 north. */
    public BlockPos padFromHut() {
        return this == ANCHORAGE ? ANCHORAGE_BERTH : new BlockPos(1, -1, -(padHalfSize + 8));
    }

    /** The middle of the second pad's floor, from the hut block (not turned): east of the first, a block between the two shafts. */
    public BlockPos guestPadFromHut() {
        return this == ANCHORAGE ? ANCHORAGE_BERTH : new BlockPos(1 + 2 * padHalfSize + 5, -1, -(padHalfSize + 8));
    }

    /** Read from a save; an unknown or missing name is the small airfield (saves from before there were others). */
    public static Facility of(String name) {
        for (final Facility f : values()) if (f.name().equals(name)) return f;
        return SMALL_AIRFIELD;
    }
}
