package com.asteain.airline.pathing;

import com.asteain.airline.ShipConfig;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server thread only. Watches every Sable ship of one level, decides which ones are stopped, and hands out
 * immutable {@link ShipSnapshot}s for path finding. The path finding thread never comes here.
 *
 * A ship becomes "stopped" once its pose has stayed within the strict tolerance of a reference pose for
 * {@code stoppedTicks} ticks; it stays stopped until some part of it has moved further than the looser discard distance
 * from where it was when it stopped. (A citizen stepping on a weightless ship makes it wobble; that must not flip the
 * ship back to "moving".) That movement is measured at the corner of the ship's block box that moved most: a small turn
 * hardly moves the middle of a long ship but swings its ends a long way.
 *
 * Both the pose after this tick's physics step and the pose before it must pass these tests: Sable carries a ship's
 * riders from the one to the other, so a ship that is pushed every tick and put back every tick is not stopped,
 * even though it is found at the same place every time it is looked at.
 */
public final class ShipTracker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ServerLevel, ShipTracker> TRACKERS = new WeakHashMap<>();
    private static boolean discardWarned;

    /** Config values read once per tick on the server thread. */
    record Settings(boolean enabled, double stoppedPosition, double stoppedAngle, int stoppedTicks, double maxTilt,
                    double discardMove, double alignedYaw, int snapshotRefresh, int maxVolume, boolean log) {
        /**
         * The discard distance also decides when a stopped ship counts as moving again. Set below the stopped
         * tolerance it would make a ship flip between stopped and moving on its own jitter, so it is raised to
         * at least the stopped tolerance.
         */
        static Settings read() {
            final double stoppedPosition = ShipConfig.PATHING_STOPPED_POSITION.get(), stoppedAngle = ShipConfig.PATHING_STOPPED_ANGLE.get();
            final double discardMove = ShipConfig.PATHING_DISCARD_MOVE.get();
            if (discardMove < stoppedPosition && !discardWarned) {
                discardWarned = true;
                LOGGER.warn("[Airline/pathing] discardMoveTolerance is smaller than stoppedPositionTolerance; "
                        + "using stoppedPositionTolerance ({} blocks) as the lower limit.", stoppedPosition);
            }
            return new Settings(ShipConfig.PATHING_ENABLED.get(), stoppedPosition, stoppedAngle,
                    ShipConfig.PATHING_STOPPED_TICKS.get(), ShipConfig.PATHING_MAX_TILT.get(), Math.max(discardMove, stoppedPosition),
                    ShipConfig.PATHING_ALIGNED_YAW.get(), ShipConfig.PATHING_SNAPSHOT_REFRESH.get(),
                    ShipConfig.PATHING_MAX_SHIP_VOLUME.get(), ShipConfig.PATHING_LOG_DECISIONS.get());
        }
    }

    private static final class ShipState {
        Pose3d reference;        // the pose the stillness counter is measured against
        int stillTicks;
        @Nullable Pose3d stoppedAt; // pose at which the ship was accepted as stopped; null while it counts as moving
        @Nullable ShipSnapshot snapshot;
        long seen;
        boolean oversizeLogged;
        boolean stepLogged;
    }

    private final Map<UUID, ShipState> ships = new HashMap<>();
    @Nullable private Settings settings;
    private long now;
    /** Work vectors; this class is only ever used on the server thread. */
    private final Vector3d scratchA = new Vector3d(), scratchB = new Vector3d();

    private ShipTracker() {}

    @Nullable
    static ShipTracker get(ServerLevel level) {
        return TRACKERS.get(level);
    }

    static void forget(ServerLevel level) {
        TRACKERS.remove(level);
    }

    /** Called once per level tick. */
    static void tick(ServerLevel level) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        TRACKERS.computeIfAbsent(level, l -> new ShipTracker()).update(container);
    }

    private void update(ServerSubLevelContainer container) {
        Settings s;
        try {
            s = Settings.read();
        } catch (IllegalStateException configNotLoaded) {
            s = null;
        }
        this.settings = s;
        this.now++;
        if (s == null || !s.enabled) {
            ships.clear();
            return;
        }
        for (final ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved()) continue;
            final Pose3dc pose = ship.logicalPose();
            final Pose3dc before = poseBeforeStep(ship, pose);
            ShipState state = ships.get(ship.getUniqueId());
            if (state == null) {
                state = new ShipState();
                state.reference = new Pose3d(pose);
                ships.put(ship.getUniqueId(), state);
            } else if (within(state.reference, pose, s.stoppedPosition, s.stoppedAngle)
                    && within(state.reference, before, s.stoppedPosition, s.stoppedAngle)) {
                state.stillTicks++;
                state.stepLogged = false;
            } else {
                if (s.log && !state.stepLogged && within(state.reference, pose, s.stoppedPosition, s.stoppedAngle)) {
                    state.stepLogged = true;
                    LOGGER.info("[Airline/pathing] ship {} is found at the same place every tick, but something other than its own "
                            + "motion set its pose in between; whatever stands on it is carried by the difference, so it does not count as stopped.",
                            ship.getUniqueId());
                }
                state.reference.set(pose);
                state.stillTicks = 0;
            }
            state.seen = now;
            if (state.stoppedAt != null) {
                final BoundingBox3ic box = ship.getPlot().getBoundingBox();
                final double moved = Math.max(farthestMove(state.stoppedAt, pose, box), farthestMove(state.stoppedAt, before, box));
                if (moved > s.discardMove) {
                    state.stoppedAt = null;
                    state.snapshot = null;
                    if (s.log) {
                        LOGGER.info("[Airline/pathing] ship {} is moving (its farthest corner is {} blocks from where it stopped); "
                                + "it is no longer used for paths.", ship.getUniqueId(), String.format("%.2f", moved));
                    }
                }
            }
            if (state.stoppedAt == null && state.stillTicks >= s.stoppedTicks) {
                state.stoppedAt = new Pose3d(pose);
                if (s.log) {
                    final BoundingBox3ic b = ship.getPlot().getBoundingBox();
                    LOGGER.info("[Airline/pathing] ship {} has stopped (tilt {} deg, block box {}x{}x{}).", ship.getUniqueId(),
                            String.format("%.2f", tiltDegrees(pose)), b.maxX() - b.minX() + 1, b.maxY() - b.minY() + 1, b.maxZ() - b.minZ() + 1);
                }
            }
        }
        for (final Iterator<ShipState> it = ships.values().iterator(); it.hasNext(); ) {
            if (it.next().seen != now) it.remove();
        }
    }

    /**
     * The pose the ship had before this tick's physics step. Sable carries everything that stands on a ship from that pose
     * to the current one, every tick. Normally it is simply the pose of the previous tick; it differs when something else
     * (a command, another mod) sets the pose between ticks, and then the pose read once per tick can look perfectly still
     * while the ship's riders are being carried away.
     */
    private static Pose3dc poseBeforeStep(ServerSubLevel ship, Pose3dc pose) {
        final Pose3dc last = ship.lastPose();
        if (last.rotationPoint().lengthSquared() > 0.0) return last;
        // Not filled in yet. Sable's own entity code uses the current rotation point in this case.
        final Pose3d filled = new Pose3d(last);
        filled.rotationPoint().set(pose.rotationPoint());
        return filled;
    }

    /** The stopped, level ships whose world box touches the given block columns; null when there are none. */
    @Nullable
    ShipOverlaySet collect(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        final Settings s = settings;
        if (s == null || !s.enabled || ships.isEmpty()) return null;
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        List<ShipSnapshot> found = null;
        for (final ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved()) continue;
            final BoundingBox3dc box = ship.boundingBox();
            if (box.maxX() < minX - 1 || box.minX() > maxX + 2 || box.maxZ() < minZ - 1 || box.minZ() > maxZ + 2) continue;
            final ShipState state = ships.get(ship.getUniqueId());
            if (state == null || state.stoppedAt == null) continue;
            final ShipSnapshot snapshot = snapshot(container, ship, state, s);
            if (snapshot == null || !snapshot.touchesColumns(minX, minZ, maxX, maxZ)) continue;
            if (found == null) found = new ArrayList<>(2);
            found.add(snapshot);
        }
        return found == null ? null : new ShipOverlaySet(found.toArray(new ShipSnapshot[0]));
    }

    @Nullable
    private ShipSnapshot snapshot(ServerSubLevelContainer container, ServerSubLevel ship, ShipState state, Settings s) {
        final Pose3dc pose = ship.logicalPose();
        if (tiltDegrees(pose) > s.maxTilt) return null;
        final BoundingBox3ic bounds = ship.getPlot().getBoundingBox();
        final long volume = ShipBlocks.volume(bounds);
        if (volume <= 0) return null;
        if (volume > s.maxVolume) {
            if (!state.oversizeLogged) {
                state.oversizeLogged = true;
                LOGGER.warn("[Airline/pathing] ship {} is too large for path finding ({} cells > maxShipVolume {}); citizens will not path over it.",
                        ship.getUniqueId(), volume, s.maxVolume);
            }
            return null;
        }
        final double yaw = yawDegrees(pose);
        final int quarter = Math.floorMod(Math.round((float) (yaw / 90.0)), 4);
        final Rotation rotation = switch (quarter) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        final boolean aligned = Math.abs(yaw - 90.0 * Math.round(yaw / 90.0)) <= s.alignedYaw;

        final ShipSnapshot old = state.snapshot;
        ShipBlocks blocks = old == null ? null : old.blocks;
        final boolean blocksFresh = blocks != null && now - blocks.capturedAt < s.snapshotRefresh
                && blocks.rotation == rotation && blocks.aligned == aligned && blocks.sameBounds(bounds);
        // The copy of the pose is kept only while no corner of the ship has left the stopped tolerance around it.
        if (blocksFresh && farthestMove(old.pose(), pose, bounds) <= s.stoppedPosition) return old;
        if (!blocksFresh) blocks = ShipBlocks.capture(container, bounds, rotation, aligned, now);

        final BoundingBox3dc box = ship.boundingBox();
        final ShipSnapshot fresh = new ShipSnapshot(ship.getUniqueId(), new Pose3d(pose), blocks,
                Mth.floor(box.minX()) - 1, Mth.floor(box.minY()) - 1, Mth.floor(box.minZ()) - 1,
                Mth.ceil(box.maxX()) + 1, Mth.ceil(box.maxY()) + 1, Mth.ceil(box.maxZ()) + 1);
        state.snapshot = fresh;
        return fresh;
    }

    /**
     * How far (blocks) the deck under a path has moved away from the path since it was searched, at the path point where
     * it moved most, counting the points from path node {@code fromNode} on. Infinite when the ship is gone or the
     * feature is off.
     */
    double pathMove(ServerLevel level, ShipPathPoints points, int fromNode) {
        final Settings s = settings;
        if (s == null || !s.enabled) return Double.POSITIVE_INFINITY;
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return Double.POSITIVE_INFINITY;
        final SubLevel ship = container.getSubLevel(points.ship.id);
        if (ship == null || ship.isRemoved()) return Double.POSITIVE_INFINITY;
        return points.largestMove(ship.logicalPose(), fromNode, scratchA);
    }

    /** The largest path movement a path is kept for; see {@link #pathMove}. */
    double discardMove() {
        final Settings s = settings;
        return s == null ? 0.0 : s.discardMove;
    }

    /** True when the entity stands on a ship that does not count as stopped. */
    boolean ridesMovingShip(Entity entity) {
        final Settings s = settings;
        if (s == null || !s.enabled) return false;
        final SubLevel ship = Sable.HELPER.getTrackingSubLevel(entity);
        if (ship == null || ship.isRemoved()) return false;
        final ShipState state = ships.get(ship.getUniqueId());
        return state == null || state.stoppedAt == null;
    }

    /** Whether this ship counts as stopped right now. */
    boolean isStopped(UUID ship) {
        final Settings s = settings;
        if (s == null || !s.enabled) return false;
        final ShipState state = ships.get(ship);
        return state != null && state.stoppedAt != null;
    }

    boolean logDecisions() {
        final Settings s = settings;
        return s != null && s.log;
    }

    /**
     * How far (blocks) the two poses put the same point of the ship apart, at the corner of the ship's block box where
     * that is most. No point of the ship is moved further than the worst corner: the difference between two rigid
     * placements grows evenly along any straight line, so it is largest at an end of it.
     */
    private double farthestMove(Pose3dc a, Pose3dc b, BoundingBox3ic box) {
        double largest = 0.0;
        for (int i = 0; i < 8; i++) {
            final double x = (i & 1) == 0 ? box.minX() : box.maxX() + 1.0;
            final double y = (i & 2) == 0 ? box.minY() : box.maxY() + 1.0;
            final double z = (i & 4) == 0 ? box.minZ() : box.maxZ() + 1.0;
            a.transformPosition(scratchA.set(x, y, z));
            b.transformPosition(scratchB.set(x, y, z));
            final double moved = scratchA.distanceSquared(scratchB);
            if (moved > largest) largest = moved;
        }
        return Math.sqrt(largest);
    }

    /**
     * Whether pose b is within the given distance (blocks) and angle (degrees) of pose a.
     * The distance is measured where both poses put the same ship point, so a changed rotation point alone is not movement.
     */
    static boolean within(Pose3dc a, Pose3dc b, double distance, double angleDegrees) {
        final double dot = Math.min(1.0, Math.abs(a.orientation().dot(b.orientation())));
        if (Math.toDegrees(2.0 * Math.acos(dot)) > angleDegrees) return false;
        final Vector3d pa = a.transformPosition(b.rotationPoint(), new Vector3d());
        final Vector3d pb = b.transformPosition(b.rotationPoint(), new Vector3d());
        return pa.distanceSquared(pb) <= distance * distance;
    }

    /** Angle between the ship's up direction and the world's up direction. */
    static double tiltDegrees(Pose3dc pose) {
        final Vector3d up = pose.transformNormal(new Vector3d(0.0, 1.0, 0.0));
        final double length = up.length();
        return length < 1.0e-9 ? 180.0 : Math.toDegrees(Math.acos(Mth.clamp(up.y / length, -1.0, 1.0)));
    }

    /** World heading of the ship's +X axis, in degrees: 0 = +X, 90 = +Z (the same sense as Rotation.CLOCKWISE_90). */
    static double yawDegrees(Pose3dc pose) {
        final Vector3d x = pose.transformNormal(new Vector3d(1.0, 0.0, 0.0));
        return Math.toDegrees(Math.atan2(x.z, x.x));
    }
}
