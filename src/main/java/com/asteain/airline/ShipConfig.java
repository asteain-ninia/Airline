package com.asteain.airline;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side config of the ship walking and boarding (saves/&lt;world&gt;/serverconfig/airline-ships.toml): citizens see stopped
 * Sable ships in their path finding and are sent to seats on them. Both were moved here from ColonyPatch Server unchanged.
 */
public final class ShipConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue PATHING_ENABLED;
    public static final ModConfigSpec.DoubleValue PATHING_STOPPED_POSITION;
    public static final ModConfigSpec.DoubleValue PATHING_STOPPED_ANGLE;
    public static final ModConfigSpec.IntValue PATHING_STOPPED_TICKS;
    public static final ModConfigSpec.DoubleValue PATHING_MAX_TILT;
    public static final ModConfigSpec.DoubleValue PATHING_DISCARD_MOVE;
    public static final ModConfigSpec.DoubleValue PATHING_ALIGNED_YAW;
    public static final ModConfigSpec.IntValue PATHING_SNAPSHOT_REFRESH;
    public static final ModConfigSpec.IntValue PATHING_MAX_SHIP_VOLUME;
    public static final ModConfigSpec.BooleanValue PATHING_LOG_DECISIONS;

    public static final ModConfigSpec.BooleanValue BOARDING_ENABLED;
    public static final ModConfigSpec.BooleanValue BOARDING_SEAT_IN_SHIP;

    static {
        final ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("pathing");
        PATHING_ENABLED = b.comment(
                        "Let MineColonies path finding see the blocks of stopped Sable ships (sub-levels), so citizens can walk",
                        "between the ground and a parked ship. Only has an effect when Sable is installed.")
                .define("enabled", true);
        PATHING_STOPPED_POSITION = b.comment(
                        "A ship counts as stopped once its pose has stayed within this distance (blocks) and the angle below",
                        "of a reference pose for stoppedTicks consecutive ticks. Only stopped ships are used for paths.")
                .defineInRange("stoppedPositionTolerance", 0.01, 0.0, 0.25);
        PATHING_STOPPED_ANGLE = b.comment("Angle part (degrees) of the stopped test.")
                .defineInRange("stoppedAngleToleranceDegrees", 0.1, 0.0, 10.0);
        PATHING_STOPPED_TICKS = b.comment("How many consecutive ticks a ship has to hold still before it counts as stopped.")
                .defineInRange("stoppedTicks", 20, 1, 72000);
        PATHING_MAX_TILT = b.comment("Ships whose deck leans more than this many degrees from level are never used for paths.")
                .defineInRange("maxTiltDegrees", 5.0, 0.0, 45.0);
        PATHING_DISCARD_MOVE = b.comment(
                        "While a citizen follows a path over a ship, the path is dropped as soon as the deck under any point of the",
                        "path still ahead has moved further than this (blocks) from where it was when the path was computed.",
                        "A turn of the ship counts through the movement it causes at those points, so the far end of a long ship",
                        "is judged as strictly as its middle. Keep this below 0.3, half the width of a citizen: a citizen heading",
                        "for a point on the very edge of the deck then still has the deck under its feet.",
                        "Looser than the stopped test on purpose: a citizen stepping on board must not cancel its own path.",
                        "The same distance, measured at the corner of the ship that moved most, decides when a stopped ship",
                        "counts as moving again. Values below stoppedPositionTolerance are raised to it.")
                .defineInRange("discardMoveTolerance", 0.2, 0.0, 0.29);
        PATHING_ALIGNED_YAW = b.comment(
                        "A ship whose heading is within this many degrees of a multiple of 90 keeps its ladders, vines and open",
                        "trapdoors in the path finding view. On other ships those blocks are treated as plain gaps (not climbable).")
                .defineInRange("alignedYawToleranceDegrees", 5.0, 0.0, 45.0);
        PATHING_SNAPSHOT_REFRESH = b.comment("The copy of a ship's blocks used by path finding is rebuilt at most every this many ticks.")
                .defineInRange("snapshotRefreshTicks", 20, 1, 1200);
        PATHING_MAX_SHIP_VOLUME = b.comment("Ships whose block bounding box holds more cells than this are ignored by path finding.")
                .defineInRange("maxShipVolume", 500000, 1000, 50000000);
        PATHING_LOG_DECISIONS = b.comment("Log when a ship starts or stops being used for paths and when a path is dropped because its ship moved.")
                .define("logDecisions", false);
        b.pop();

        b.push("boarding");
        BOARDING_ENABLED = b.comment(
                        "Allow citizens to be sent to a seat on a stopped Sable ship (the /airline board command, and the pilots",
                        "of the aircraft). Needs Sable and [pathing] enabled. Switching it off stands every seated citizen up.")
                .define("enabled", true);
        BOARDING_SEAT_IN_SHIP = b.comment(
                        "The seat entity the citizen sits on (MineColonies' own) is put inside the ship, where the ship's blocks are, so",
                        "that it moves with the ship and the seated figure follows the ship smoothly. Off: it is kept in the world and",
                        "moved to the seat every tick (it lags behind and shakes on a moving ship).")
                .define("seatInShip", true);
        b.pop();

        SPEC = b.build();
    }

    private ShipConfig() {}
}
