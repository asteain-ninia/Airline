package com.asteain.airline.pathing;

import com.asteain.airline.pathing.mixin.AbstractPathJobAccessor;
import com.minecolonies.core.entity.pathfinding.PathPointExtended;
import com.minecolonies.core.entity.pathfinding.SurfaceType;
import com.minecolonies.core.entity.pathfinding.pathjobs.AbstractPathJob;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry points called from the mixins on MineColonies' path finding. Everything here runs on the server thread;
 * the path finding thread only reads the immutable {@link ShipOverlaySet} a search was given.
 *
 * Failures are contained here. A linkage error (Sable or MineColonies changed under us) switches the feature off until
 * the server restarts. An ordinary exception only costs the one call it happened in (ships count as air for that call);
 * the feature is switched off only when such exceptions keep coming.
 */
public final class PathingHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** While a citizen rides a moving ship its navigator is paused in steps of this many ticks. */
    public static final int MOVING_SHIP_PAUSE_TICKS = 10;
    /** How long (ticks) a hop onto a ship's edge is helped along before another one may start. */
    public static final int HOP_TICKS = 12;
    /** A citizen hops at most this many times at the same step; after that the stuck handler takes over. */
    public static final int MAX_HOPS_PER_STEP = 3;
    public static final int HOP_NONE = 0, HOP_STARTED = 1, HOP_TOO_HIGH = 2;
    /** Highest real step (blocks) a jump is taken to reach. A jump peaks about 1.25 blocks up. */
    private static final double MAX_JUMP_RISE = 1.2;
    /** A hop is only started, and only helped along, within this distance (2.5 blocks) of the node above. */
    private static final double HOP_RANGE_SQUARED = 6.25;
    /** Forward speed (blocks per tick) given in the air; about a citizen's walking speed. */
    private static final double HOP_FORWARD_SPEED = 0.15;
    /** More than this many ordinary exceptions within FAILURE_WINDOW_MS switch the feature off. */
    private static final int MAX_FAILURES = 5;
    private static final long FAILURE_WINDOW_MS = 60_000L;

    private static volatile boolean broken;
    private static long failureWindowStart;
    private static int failuresInWindow;

    private PathingHooks() {}

    static boolean broken() {
        return broken;
    }

    /** @param where which entry point failed, for the log */
    static synchronized void fail(String where, Throwable t) {
        if (broken) return;
        if (t instanceof LinkageError) {
            broken = true;
            LOGGER.error("[Airline/pathing] {} hit a linkage error: Sable or MineColonies no longer matches this build. "
                    + "Ship path finding is switched off until the server restarts; citizens ignore ships again. Update Airline.", where, t);
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - failureWindowStart > FAILURE_WINDOW_MS) {
            failureWindowStart = now;
            failuresInWindow = 0;
        }
        failuresInWindow++;
        if (failuresInWindow > MAX_FAILURES) {
            broken = true;
            LOGGER.error("[Airline/pathing] {} failed again ({} failures within {} seconds). "
                    + "Ship path finding is switched off until the server restarts; citizens ignore ships again.", where, failuresInWindow, FAILURE_WINDOW_MS / 1000L, t);
        } else if (failuresInWindow == 1) {
            LOGGER.warn("[Airline/pathing] {} failed once; ships were ignored for this one call and ship path finding stays on. "
                    + "It is switched off only if this keeps happening (more than {} times within {} seconds).", where, MAX_FAILURES, FAILURE_WINDOW_MS / 1000L, t);
        } else {
            LOGGER.warn("[Airline/pathing] {} failed ({} of {} allowed within {} seconds): {}", where, failuresInWindow, MAX_FAILURES, FAILURE_WINDOW_MS / 1000L, t.toString());
        }
    }

    @Nullable
    private static ShipTracker tracker(Level level) {
        if (broken || !(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return null;
        return ShipTracker.get(server);
    }

    /** Hook 1: ships for a new search covering the given block columns. Only the server thread may ask; others get nothing. */
    @Nullable
    public static ShipOverlaySet collect(Level level, int minX, int minZ, int maxX, int maxZ) {
        final ShipTracker tracker = tracker(level);
        if (tracker == null) return null;
        try {
            return tracker.collect((ServerLevel) level, minX, minZ, maxX, maxZ);
        } catch (RuntimeException | LinkageError e) {
            fail("collecting ships for a path search", e);
            return null;
        }
    }

    /**
     * Hook 5: where a search starts for an entity standing on a stopped ship. MineColonies' own logic only looks at the
     * ordinary world, sees air under the entity and walks down to whatever lies below the ship.
     *
     * @return the cell above the ship block that carries the entity, or null to let MineColonies decide.
     */
    @Nullable
    public static BlockPos startOnShip(LivingEntity entity) {
        final Level level = entity.level();
        final ShipTracker tracker = tracker(level);
        if (tracker == null) return null;
        try {
            final double ex = entity.getX(), ey = entity.getY(), ez = entity.getZ();
            final ShipOverlaySet ships = tracker.collect((ServerLevel) level, Mth.floor(ex) - 2, Mth.floor(ez) - 2, Mth.floor(ex) + 2, Mth.floor(ez) + 2);
            return ships == null ? null : startOnShip(entity, new OverlayView(level, ships), ships);
        } catch (RuntimeException | LinkageError e) {
            fail("choosing the start of a path on a ship", e);
            return null;
        }
    }

    /**
     * Looks at the cells the entity's feet overlap, one level up and down, in the view a search will have, and takes the
     * nearest cell that has a walkable block below and room for the entity. Only answers when that block belongs to a ship;
     * on ordinary ground MineColonies' own start logic stays in charge.
     */
    @Nullable
    private static BlockPos startOnShip(LivingEntity entity, OverlayView view, ShipOverlaySet ships) {
        final double ex = entity.getX(), ey = entity.getY(), ez = entity.getZ();
        final double half = entity.getBbWidth() / 2.0;
        final int nearestY = Mth.floor(ey + 0.5);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), below = new BlockPos.MutableBlockPos();
        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        boolean bestOnShip = false;
        for (int y = nearestY - 1; y <= nearestY + 1; y++) {
            final double dy = y - ey;
            if (Math.abs(dy) > 1.0) continue;
            for (int x = Mth.floor(ex - half); x <= Mth.floor(ex + half); x++) {
                for (int z = Mth.floor(ez - half); z <= Mth.floor(ez + half); z++) {
                    below.set(x, y - 1, z);
                    if (SurfaceType.getSurfaceType(view, view.getBlockState(below), below) != SurfaceType.WALKABLE) continue;
                    if (!isOpen(view, pos.set(x, y, z)) || !isOpen(view, pos.set(x, y + 1, z))) continue;
                    final double dx = x + 0.5 - ex, dz = z + 0.5 - ez;
                    final double distance = dx * dx + dz * dz + dy * dy;
                    if (distance < best) {
                        best = distance;
                        bestPos = new BlockPos(x, y, z);
                        bestOnShip = ships.stateAt(x, y - 1, z) != null;
                    }
                }
            }
        }
        return bestOnShip ? bestPos : null;
    }

    private static boolean isOpen(OverlayView view, BlockPos pos) {
        final BlockState state = view.getBlockState(pos);
        if (state.isAir()) return true;
        final VoxelShape shape = state.getCollisionShape(view, pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.1;
    }

    /** For other features of this mod: whether the ship counts as stopped right now (false when the feature is off). */
    public static boolean isStopped(ServerLevel level, java.util.UUID ship) {
        final ShipTracker tracker = tracker(level);
        if (tracker == null) return false;
        try {
            return tracker.isStopped(ship);
        } catch (RuntimeException | LinkageError e) {
            fail("checking whether a ship is stopped", e);
            return false;
        }
    }

    /** Hooks 6 and 8: true while the entity stands on a ship that is not stopped. */
    public static boolean ridesMovingShip(Entity entity) {
        final ShipTracker tracker = tracker(entity.level());
        if (tracker == null) return false;
        try {
            return tracker.ridesMovingShip(entity);
        } catch (RuntimeException | LinkageError e) {
            fail("checking whether a citizen rides a moving ship", e);
            return false;
        }
    }

    /**
     * Height for the stuck handler's "teleport a few nodes ahead". A path node above a ship block sits at the top of the
     * world cell the ship block was shown in, but the real deck surface can be up to half a block higher than that
     * (ships do not sit on the block grid). Dropping the citizen from just above the highest possible surface keeps it
     * from being placed inside the deck.
     */
    public static double teleportHeight(Entity entity, double x, double y, double z) {
        final Level level = entity.level();
        final ShipTracker tracker = tracker(level);
        if (tracker == null) return y;
        try {
            final int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
            final ShipOverlaySet ships = tracker.collect((ServerLevel) level, bx - 1, bz - 1, bx + 1, bz + 1);
            return ships != null && ships.stateAt(bx, by - 1, bz) != null ? y + 0.55 : y;
        } catch (RuntimeException | LinkageError e) {
            fail("choosing the height of a stuck teleport", e);
            return y;
        }
    }

    /**
     * A step up onto a ship. The ship's blocks are shown at the nearest world cells, so on a ship that is turned against
     * the block grid the real edge of a block can stick out up to most of a block further than the cell the path knows
     * about. A citizen walking toward a node one block up is then stopped by the real edge while it is still more than a
     * block away from the node, which is outside the range in which MineColonies' movement decides to jump, and it stays
     * pressed against the edge. So: standing on the ground, blocked sideways, next node close by and resting on a ship
     * block whose real top is a jump higher: jump.
     *
     * Only for nodes that rest on a ship block. Steps of the ordinary world sit on the block grid, where MineColonies'
     * own jump works. Never while riding, climbing or swimming.
     *
     * @param hop receives the real height of the step's top when the answer is HOP_STARTED or HOP_TOO_HIGH
     * @return HOP_NONE, HOP_STARTED (the jump was requested) or HOP_TOO_HIGH (the real step is higher than a jump reaches)
     */
    public static int hopOntoShipStep(Mob mob, Path path, ShipPathPoints[] ships, double[] hop) {
        if (!mob.onGround() || !mob.horizontalCollision) return HOP_NONE;
        if (mob.isPassenger() || mob.onClimbable() || mob.isInWaterOrBubble()) return HOP_NONE;
        final Node node = path.getNextNode();
        if (node instanceof PathPointExtended extended && extended.isOnLadder()) return HOP_NONE;
        final double plannedRise = node.y - mob.getY();
        if (plannedRise < 0.25 || plannedRise > 1.75) return HOP_NONE;
        final double dx = node.x + 0.5 - mob.getX(), dz = node.z + 0.5 - mob.getZ();
        if (dx * dx + dz * dz > HOP_RANGE_SQUARED) return HOP_NONE;
        double top = Double.NaN;
        for (final ShipPathPoints points : ships) {
            top = points.ship.surfaceY(node.x, node.y - 1, node.z);
            if (!Double.isNaN(top)) break;
        }
        if (Double.isNaN(top)) return HOP_NONE;
        final double rise = top - mob.getY();
        if (rise <= mob.maxUpStep()) return HOP_NONE;
        hop[0] = top;
        if (rise > MAX_JUMP_RISE) return HOP_TOO_HIGH;
        mob.getJumpControl().jump();
        return HOP_STARTED;
    }

    /**
     * Second half of the hop above. A citizen that jumps while pressed against the edge has no forward speed, and in the
     * air it gains almost none, so it would come straight back down in front of the edge. Once its feet are above the
     * real top of the step it is given ordinary walking speed toward the node, as if it had jumped from a run.
     */
    public static void carryOverEdge(Mob mob, Path path, double top) {
        if (mob.onGround() || mob.isPassenger()) return;
        if (mob.getY() < top - 0.05) return;
        final Node node = path.getNextNode();
        final double dx = node.x + 0.5 - mob.getX(), dz = node.z + 0.5 - mob.getZ();
        final double distanceSquared = dx * dx + dz * dz;
        if (distanceSquared > HOP_RANGE_SQUARED || distanceSquared < 1.0e-4) return;
        final double scale = HOP_FORWARD_SPEED / Math.sqrt(distanceSquared);
        final double vx = dx * scale, vz = dz * scale;
        final Vec3 motion = mob.getDeltaMovement();
        if (motion.x * vx + motion.z * vz >= HOP_FORWARD_SPEED * HOP_FORWARD_SPEED) return;
        mob.setDeltaMovement(vx, motion.y, vz);
    }

    /** Log line for a step that a jump cannot reach; the caller makes sure it is written once per step. */
    public static void logStepTooHigh(Mob mob, double top) {
        final ShipTracker tracker = tracker(mob.level());
        if (tracker != null && tracker.logDecisions()) {
            LOGGER.info("[Airline/pathing] {} cannot get onto the ship here: the real step is {} blocks high, a jump reaches {}.",
                    mob.getName().getString(), String.format("%.2f", top - mob.getY()), MAX_JUMP_RISE);
        }
    }

    /**
     * Path search (runs on the path finding thread, reads only the search's immutable ship copies): is the step from the
     * block the parent stands on up to the block at (x, y, z) really higher than a jump reaches? MineColonies compares
     * the tops of the two world cells; with a ship involved the real tops can be up to half a block off either way.
     * Only ever says "too high" for a step that involves a ship; it never makes anything walkable.
     */
    public static boolean stepTooHigh(ShipOverlaySet ships, LevelReader world, int parentX, int parentY, int parentZ, int x, int y, int z, BlockState target) {
        final double shipTarget = ships.surfaceY(x, y, z);
        final double shipParent = ships.surfaceY(parentX, parentY - 1, parentZ);
        if (Double.isNaN(shipTarget) && Double.isNaN(shipParent)) return false;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final double targetTop = !Double.isNaN(shipTarget) ? shipTarget : y + cellTop(world, pos.set(x, y, z), target);
        final double parentTop;
        if (!Double.isNaN(shipParent)) {
            parentTop = shipParent;
        } else {
            pos.set(parentX, parentY - 1, parentZ);
            parentTop = parentY - 1 + cellTop(world, pos, world.getBlockState(pos));
        }
        return targetTop - parentTop > MAX_JUMP_RISE;
    }

    private static double cellTop(LevelReader world, BlockPos pos, BlockState state) {
        final VoxelShape shape = state.getCollisionShape(world, pos);
        return shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
    }

    /**
     * Hook 6: the points of this path that rest on ships, grouped by ship and taken from the ships its search was given;
     * null when the path stays on the ground.
     */
    @Nullable
    public static ShipPathPoints[] shipPoints(Path path, @Nullable PathResult<?> result) {
        if (broken || result == null) return null;
        final AbstractPathJob job = result.getJob();
        if (job == null) return null;
        final LevelReader world = ((AbstractPathJobAccessor) job).airline$getWorld();
        if (!(world instanceof ShipOverlayHolder holder)) return null;
        final ShipOverlaySet ships = holder.airline$ships();
        if (ships == null) return null;
        final int count = path.getNodeCount();
        final ShipSnapshot[] under = new ShipSnapshot[count];
        List<ShipSnapshot> used = null;
        for (int i = 0; i < count; i++) {
            final Node node = path.getNode(i);
            ShipSnapshot ship = ships.shipAt(node.x, node.y - 1, node.z);
            if (ship == null) ship = ships.shipAt(node.x, node.y, node.z);
            if (ship == null) continue;
            under[i] = ship;
            if (used == null) used = new ArrayList<>(2);
            if (!used.contains(ship)) used.add(ship);
        }
        if (used == null) return null;
        final ShipPathPoints[] points = new ShipPathPoints[used.size()];
        for (int s = 0; s < points.length; s++) {
            final ShipSnapshot ship = used.get(s);
            int n = 0;
            for (int i = 0; i < count; i++) if (under[i] == ship) n++;
            final int[] nodes = new int[n];
            final double[] at = new double[3 * n];
            n = 0;
            for (int i = 0; i < count; i++) {
                if (under[i] != ship) continue;
                final Node node = path.getNode(i);
                nodes[n] = i;
                at[3 * n] = node.x + 0.5;
                at[3 * n + 1] = node.y;
                at[3 * n + 2] = node.z + 0.5;
                n++;
            }
            points[s] = new ShipPathPoints(ship, nodes, at);
        }
        return points;
    }

    /**
     * Hook 6: false when one of the ships has gone, or the deck under a point of the path that is still ahead has moved
     * further from that point than the discard distance since the path was computed.
     *
     * The discard distance is below half a citizen's width. A citizen heading for a path point therefore always has
     * the deck that point was planned on under some part of its feet, even where the point sat on the very edge.
     * This runs every tick, after the ship's movement of that tick and before the citizen takes its step.
     */
    public static boolean shipsUnchanged(Entity entity, Path path, ShipPathPoints[] ships) {
        final Level level = entity.level();
        final ShipTracker tracker = tracker(level);
        if (tracker == null) return false;
        try {
            // The node the walker last passed still counts: it may be walking away from it along the stretch behind the next node.
            final int fromNode = Math.max(0, path.getNextNodeIndex() - 1);
            final double limit = tracker.discardMove();
            for (final ShipPathPoints points : ships) {
                final double moved = tracker.pathMove((ServerLevel) level, points, fromNode);
                if (moved > limit) {
                    if (tracker.logDecisions()) {
                        if (Double.isInfinite(moved)) {
                            LOGGER.info("[Airline/pathing] dropping the path of {}: ship {} is gone.", entity.getName().getString(), points.ship.id);
                        } else {
                            LOGGER.info("[Airline/pathing] dropping the path of {}: the deck of ship {} has moved {} blocks under it (limit {}).",
                                    entity.getName().getString(), points.ship.id, String.format("%.3f", moved), limit);
                        }
                    }
                    return false;
                }
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            fail("checking the ships under a path", e);
            return false;
        }
    }
}
