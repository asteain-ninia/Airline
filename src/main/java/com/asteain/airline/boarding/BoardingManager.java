package com.asteain.airline.boarding;

import com.asteain.airline.ShipConfig;
import com.asteain.airline.boarding.Boarding.Result;
import com.asteain.airline.boarding.Boarding.State;
import com.asteain.airline.pathing.PathingHooks;
import com.minecolonies.api.entity.ModEntities;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IBooleanConditionSupplier;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IStateSupplier;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickRateStateMachine;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.pathfinding.IMinecoloniesNavigator;
import com.minecolonies.api.util.EntityUtils;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.other.SittingEntity;
import com.minecolonies.core.entity.pathfinding.SurfaceType;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The working part of {@link Boarding}. Only loaded when Sable is installed. Server thread only.
 *
 * How a citizen is kept on a seat: the seat is MineColonies' own invisible seat entity, standing in the ordinary world
 * at the place where the ship's pose puts the seat block, and moved there again every tick. The citizen rides it the
 * vanilla way. Nothing lives in the ship's own coordinate space, so nothing can end up there, and when the ship
 * disappears the citizen is simply still in the world.
 *
 * Every tick of a session renews the seat's short lifetime and the hold on the citizen's own decisions. If this class
 * ever stops ticking, both run out by themselves within a few seconds and the citizen is an ordinary citizen again.
 */
public final class BoardingManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Close enough to the seat to sit down (blocks, flat distance), when standing on the same ship. MineColonies' own seats use 2. */
    private static final double REACH = 2.0;
    /** Close enough to sit down from anywhere (practically on the seat). */
    private static final double TOUCH = 0.9;
    private static final double MAX_HEIGHT_DIFFERENCE = 1.5;
    /** A walk to the seat or to the ground is given up after this many ticks of walking. */
    private static final int WALK_TIMEOUT = 1200;
    /** Seats further away than this are not walked to (MineColonies sends citizens home when asked to walk very far). */
    private static final double MAX_WALK = 200.0;
    /** The seat entity removes itself this many ticks after the last renewal. */
    private static final int SEAT_LIFETIME = 100;
    /** The hold on the citizen's own decisions ends this many ticks after the last renewal. */
    private static final int HOLD_TICKS = 40;
    private static final int NAVIGATOR_PAUSE_TICKS = 10;
    /** A seated citizen found this far from its seat without a seat under it was taken away by something else. */
    private static final double TAKEN_AWAY = 4.0;
    private static final int MISSING_TIMEOUT = 12000;
    private static final int MAX_FAILURES = 5;
    /** The cells around a seat where a place to stand is looked for (x and z steps), nearest first, always in this order. */
    private static final int[][] STAND_SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final Map<ServerLevel, BoardingManager> MANAGERS = new WeakHashMap<>();
    private static boolean broken;
    private static int failures;

    private static final class Session {
        final UUID citizen;
        final UUID ship;
        /**
         * The seat block and where a citizen sits on it, in the ship's own coordinates. 'stand' is the top of the seat
         * block: where a citizen is stood up when the deck beside the seat has no room (see standPlace).
         */
        final BlockPos seat;
        final Vector3d sit, stand;
        /** Ordinary ground the citizen came from; the place to bring it back to when the ship is lost. */
        @Nullable final Vec3 origin;
        State state = State.WALKING;
        int ticks;
        /** Ticks in a row in which the citizen entity was not loaded. */
        int missing;
        /** How far below the sitting point the seat entity is kept (so that the sitting figure rests on the block). */
        double seatDrop;
        /** Whether the seat entity is inside the ship (moving with it) rather than in the world (moved to the seat every tick). */
        boolean inShip;
        /** Where the sitting point was in the world the last time the ship was seen. */
        Vec3 lastSit;
        @Nullable BlockPos ground;
        /** Game time of the last tick this session was looked after; the hold on the citizen's decisions lapses without it. */
        long lookedAfter;
        boolean over;
        /** The citizen entity whose decisions are being held, and the hook that holds them. */
        @Nullable AbstractEntityCitizen held;
        @Nullable AIEventTarget<IState> hold;

        Session(UUID citizen, UUID ship, BlockPos seat, Vector3d sit, Vector3d stand, @Nullable Vec3 origin, Vec3 sitNow) {
            this.citizen = citizen;
            this.ship = ship;
            this.seat = seat;
            this.sit = sit;
            this.stand = stand;
            this.origin = origin;
            this.lastSit = sitNow;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    private BoardingManager() {}

    static int failures() {
        return failures;
    }

    private static void fail(String where, Throwable t) {
        failures++;
        if (t instanceof LinkageError || failures > MAX_FAILURES) {
            if (!broken) {
                broken = true;
                LOGGER.error("[Airline/boarding] {} failed; boarding is switched off until the server restarts. Seated citizens are stood up.", where, t);
            }
        } else {
            LOGGER.warn("[Airline/boarding] {} failed ({} of {} allowed).", where, failures, MAX_FAILURES, t);
        }
    }

    // ---- entry points -------------------------------------------------------------------------------------

    static Result board(ServerLevel level, Mob mob, UUID shipId, BlockPos seat) {
        if (broken) return Result.FAILED;
        if (!(mob instanceof AbstractEntityCitizen citizen) || !(mob.getNavigation() instanceof IMinecoloniesNavigator)) return Result.NOT_A_CITIZEN;
        try {
            final SubLevel ship = ship(level, shipId);
            if (ship == null) return Result.NO_SHIP;
            // The seat has to be one of this ship's own blocks. A position in the ordinary world, given by mistake, ends here.
            if (!ship.getPlot().getBoundingBox().contains(seat.getX(), seat.getY(), seat.getZ())) return Result.NO_SHIP;
            final BlockState state = level.getBlockState(seat);
            if (state.isAir()) return Result.NO_SHIP;
            if (!PathingHooks.isStopped(level, shipId)) return Result.SHIP_MOVING;

            double low = 1.0, high = 0.0;
            final List<AABB> boxes = state.getCollisionShape(level, seat).toAabbs();
            for (final AABB box : boxes) {
                low = Math.min(low, box.maxY);
                high = Math.max(high, box.maxY);
            }
            if (boxes.isEmpty()) low = 0.0;
            final Vector3d sit = new Vector3d(seat.getX() + 0.5, seat.getY() + low, seat.getZ() + 0.5);
            final Vector3d stand = new Vector3d(seat.getX() + 0.5, seat.getY() + high, seat.getZ() + 0.5);

            final BoardingManager manager = MANAGERS.computeIfAbsent(level, l -> new BoardingManager());
            final Session old = manager.sessions.get(citizen.getUUID());
            if (old != null) {
                if (old.state == State.SEATED) return old.ship.equals(shipId) && old.seat.equals(seat) ? Result.OK : Result.ALREADY_SEATED;
                manager.end(old, citizen, "a new order replaces it");
            }
            final BlockPos below = BlockPos.containing(citizen.getX(), citizen.getY() - 0.2, citizen.getZ());
            final Vec3 origin = citizen.onGround() && SurfaceType.getSurfaceType(level, level.getBlockState(below), below) == SurfaceType.WALKABLE
                    ? citizen.position() : null;
            final Session session = new Session(citizen.getUUID(), shipId, seat.immutable(), sit, stand, origin, world(ship, sit));
            manager.sessions.put(session.citizen, session);
            manager.hold(level, session, citizen);
            LOGGER.info("[Airline/boarding] {} is sent to a seat on ship {}.", citizen.getName().getString(), shipId);
            return Result.OK;
        } catch (RuntimeException | LinkageError e) {
            fail("accepting a boarding order", e);
            return Result.FAILED;
        }
    }

    static Result boardAt(ServerLevel level, Mob mob, Vec3 point) {
        if (broken) return Result.FAILED;
        try {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return Result.NO_SHIP;
            final Vector3d local = new Vector3d();
            // The middle of the cell first, then just under its top and just above its bottom: a deck rarely sits on the world's grid.
            for (final double dy : new double[] {0.0, 0.49, -0.49}) {
                for (final SubLevel ship : container.getAllSubLevels()) {
                    if (ship.isRemoved()) continue;
                    final BoundingBox3dc box = ship.boundingBox();
                    if (point.x < box.minX() - 1 || point.x > box.maxX() + 1 || point.y < box.minY() - 1 || point.y > box.maxY() + 1
                            || point.z < box.minZ() - 1 || point.z > box.maxZ() + 1) continue;
                    ship.logicalPose().transformPositionInverse(local.set(point.x, point.y + dy, point.z));
                    final BlockPos seat = BlockPos.containing(local.x, local.y, local.z);
                    if (!level.getBlockState(seat).isAir()) return board(level, mob, ship.getUniqueId(), seat);
                }
            }
            return Result.NO_SHIP;
        } catch (RuntimeException | LinkageError e) {
            fail("finding the ship for a boarding order", e);
            return Result.FAILED;
        }
    }

    static Result leave(ServerLevel level, Mob mob, @Nullable BlockPos ground) {
        if (broken) return Result.FAILED;
        if (!(mob instanceof AbstractEntityCitizen citizen)) return Result.NOT_A_CITIZEN;
        final BoardingManager manager = MANAGERS.get(level);
        final Session session = manager == null ? null : manager.sessions.get(citizen.getUUID());
        if (session == null) return Result.NOT_ABOARD;
        try {
            if (session.state == State.SEATED) {
                final SubLevel ship = ship(level, session.ship);
                if (ship != null && !PathingHooks.isStopped(level, session.ship)) return Result.SHIP_MOVING;
                if (ship == null) {
                    manager.evacuate(level, session, citizen, "the ship is gone");
                    return Result.OK;
                }
                final Vector3d stand = standPlace(level, ship, session);
                standUp(citizen, world(ship, stand));
                LOGGER.info("[Airline/boarding] {} stands up on ship {} ({}).", citizen.getName().getString(), session.ship,
                        stand == session.stand ? "on the seat block: no room on the deck beside it"
                                : "on the deck, " + (Mth.floor(stand.x) - session.seat.getX()) + " " + (Mth.floor(stand.y) - session.seat.getY())
                                + " " + (Mth.floor(stand.z) - session.seat.getZ()) + " blocks from the seat in the ship's own directions");
            } else {
                citizen.getNavigation().stop();
            }
            if (ground == null) {
                manager.end(session, citizen, "it only had to stand up");
                return Result.OK;
            }
            session.state = State.LEAVING;
            session.ground = ground.immutable();
            session.ticks = 0;
            return Result.OK;
        } catch (RuntimeException | LinkageError e) {
            fail("accepting an order to leave a ship", e);
            return Result.FAILED;
        }
    }

    static State state(ServerLevel level, Entity citizen) {
        final BoardingManager manager = MANAGERS.get(level);
        final Session session = manager == null ? null : manager.sessions.get(citizen.getUUID());
        return session == null ? State.NONE : session.state;
    }

    /** For the path finding hooks: a citizen sitting on one of our seats must be left alone by them. */
    public static boolean isSeated(Entity entity) {
        if (!entity.isPassenger() || !(entity.level() instanceof ServerLevel level)) return false;
        return state(level, entity) == State.SEATED;
    }

    // ---- every tick ---------------------------------------------------------------------------------------

    public static void tick(ServerLevel level) {
        final BoardingManager manager = MANAGERS.get(level);
        if (manager == null || manager.sessions.isEmpty()) return;
        if (broken || !ShipConfig.BOARDING_ENABLED.get()) {
            manager.releaseAll(level, "boarding is switched off");
            return;
        }
        for (final Session session : new ArrayList<>(manager.sessions.values())) {
            try {
                manager.tick(level, session);
            } catch (RuntimeException | LinkageError e) {
                fail("looking after a boarding citizen", e);
                manager.forget(session);
            }
        }
    }

    private void tick(ServerLevel level, Session session) {
        final Entity entity = level.getEntity(session.citizen);
        if (entity == null) {
            // Not loaded right now; picked up again when it is. A citizen that never comes back is forgotten after a while.
            if (++session.missing > MISSING_TIMEOUT) forget(session);
            return;
        }
        session.missing = 0;
        if (!(entity instanceof AbstractEntityCitizen citizen) || !citizen.isAlive()) {
            forget(session);
            return;
        }
        hold(level, session, citizen);
        final SubLevel ship = ship(level, session.ship);
        switch (session.state) {
            case WALKING -> walkToSeat(level, session, citizen, ship);
            case SEATED -> staySeated(level, session, citizen, ship);
            case LEAVING -> walkAway(session, citizen);
            default -> forget(session);
        }
    }

    private void walkToSeat(ServerLevel level, Session session, AbstractEntityCitizen citizen, @Nullable SubLevel ship) {
        if (ship == null) {
            citizen.getNavigation().stop();
            end(session, citizen, "the ship is gone");
            return;
        }
        final Vec3 sit = world(ship, session.sit);
        session.lastSit = sit;
        // While the ship moves the citizen waits; path finding only leads over stopped ships and drops paths over moving ones.
        if (!PathingHooks.isStopped(level, session.ship)) {
            if (++session.ticks > WALK_TIMEOUT) end(session, citizen, "the ship did not stop in time");
            return;
        }
        final double dx = sit.x - citizen.getX(), dz = sit.z - citizen.getZ();
        final double flat = dx * dx + dz * dz;
        if (Math.abs(citizen.getY() - sit.y) <= MAX_HEIGHT_DIFFERENCE
                && (flat <= TOUCH * TOUCH || (flat <= REACH * REACH && Sable.HELPER.getTrackingSubLevel(citizen) == ship))) {
            sitDown(level, session, citizen, sit);
            return;
        }
        if (++session.ticks > WALK_TIMEOUT || flat > MAX_WALK * MAX_WALK) {
            citizen.getNavigation().stop();
            end(session, citizen, flat > MAX_WALK * MAX_WALK ? "the seat is too far away" : "it could not reach the seat in time");
            return;
        }
        // The place to walk to is a world position worked out from the pose right now; never the ship's own coordinates.
        // While the navigator is busy with this very order the call does nothing; an idle navigator is asked again only now and then.
        if (!citizen.getNavigation().isDone() || session.ticks % 10 == 1) {
            EntityNavigationUtils.walkToPos(citizen, BlockPos.containing(sit.x, sit.y + 0.5, sit.z), 1, false, 1.0);
        }
    }

    private void sitDown(ServerLevel level, Session session, AbstractEntityCitizen citizen, Vec3 sit) {
        citizen.getNavigation().stop();
        final Entity made = ModEntities.SITTINGENTITY.create(level);
        if (!(made instanceof SittingEntity seat)) {
            end(session, citizen, "MineColonies gave no seat");
            return;
        }
        session.seatDrop = citizen.getBbHeight() / 2.0 + 0.25;      // the same drop MineColonies uses for its own seats
        // Inside the ship (2026-10-18, the user's ruling "Create の席に座らせるのを試す"): where the ship's own blocks are, so that
        // the seat entity moves with the ship as a ship's seat does, and the seated figure follows it smoothly. (Create's seat
        // itself cannot be used: a MineColonies citizen gets onto MineColonies' own seats, minecarts and horses only.)
        session.inShip = ShipConfig.BOARDING_SEAT_IN_SHIP.get();
        if (session.inShip) seat.setPos(session.sit.x, session.sit.y - session.seatDrop, session.sit.z);
        else seat.setPos(sit.x, sit.y - session.seatDrop, sit.z);
        seat.setMaxLifeTime(SEAT_LIFETIME);
        level.addFreshEntity(seat);
        if (!citizen.startRiding(seat, true)) {
            seat.discard();
            end(session, citizen, "it could not sit down");
            return;
        }
        seat.positionRider(citizen);
        session.state = State.SEATED;
        session.ticks = 0;
        LOGGER.info("[Airline/boarding] {} sits down on ship {}.", citizen.getName().getString(), session.ship);
    }

    private void staySeated(ServerLevel level, Session session, AbstractEntityCitizen citizen, @Nullable SubLevel ship) {
        if (!(citizen.getVehicle() instanceof SittingEntity seat)) {
            // A seat inside the ship goes when the ship goes (Sable's tag destroy_with_sub_level): to safe ground, as below.
            if (ship == null) {
                evacuate(level, session, citizen, "the ship is gone");
                return;
            }
            // Something else made it stand up or took it away. If it is still at the seat, make sure it stands on the deck.
            if (ship != null && citizen.position().distanceTo(session.lastSit) <= TAKEN_AWAY) standUp(citizen, world(ship, standPlace(level, ship, session)));
            end(session, citizen, "it is no longer on its seat");
            return;
        }
        if (ship == null) {
            evacuate(level, session, citizen, "the ship is gone");
            return;
        }
        final Vec3 sit = world(ship, session.sit);
        session.lastSit = sit;
        seat.setMaxLifeTime(SEAT_LIFETIME);
        // A seat inside the ship goes with it by itself.
        if (!session.inShip) {
            seat.setPos(sit.x, sit.y - session.seatDrop, sit.z);
            seat.positionRider(citizen);
        }
        // No path may start while seated: MineColonies' path following makes a seated citizen stand up and deletes the seat.
        if (citizen.getNavigation() instanceof IMinecoloniesNavigator navigator) navigator.setPauseTicks(NAVIGATOR_PAUSE_TICKS);
    }

    private void walkAway(Session session, AbstractEntityCitizen citizen) {
        final BlockPos ground = session.ground;
        if (ground == null) {
            end(session, citizen, "there is nowhere to go");
            return;
        }
        final double dx = ground.getX() + 0.5 - citizen.getX(), dz = ground.getZ() + 0.5 - citizen.getZ();
        final boolean there = dx * dx + dz * dz <= 1.5 * 1.5 && Math.abs(citizen.getY() - ground.getY()) <= MAX_HEIGHT_DIFFERENCE;
        final boolean ask = !citizen.getNavigation().isDone() || session.ticks % 10 == 0;
        if (there || (ask && EntityNavigationUtils.walkToPos(citizen, ground, 1, false, 1.0))) {
            citizen.getNavigation().stop();
            end(session, citizen, "it has arrived");
        } else if (++session.ticks > WALK_TIMEOUT) {
            citizen.getNavigation().stop();
            end(session, citizen, "it could not reach the place in time");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    @Nullable
    private static SubLevel ship(ServerLevel level, UUID id) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        final SubLevel ship = container.getSubLevel(id);
        return ship == null || ship.isRemoved() ? null : ship;
    }

    private static Vec3 world(SubLevel ship, Vector3d local) {
        final Pose3dc pose = ship.logicalPose();
        final Vector3d p = pose.transformPosition(new Vector3d(local));
        return new Vec3(p.x, p.y, p.z);
    }

    /**
     * Where a citizen that gets off its seat is put, in the ship's own coordinates: the nearest place on the deck beside
     * the seat where it can stand. Such a place is a cell next to the seat block (the eight around it, on the seat's own
     * level first, then one level lower) with a walkable block under it and nothing solid in the cell and in the cell
     * above. When there is none, the top of the seat block, as before.
     *
     * The top of the seat block is avoided because a walk does not always start from there: a seat is a low block,
     * often on the edge of the deck, and on a ship that lies slanted to the world's grid the citizen stayed on it.
     */
    private static Vector3d standPlace(ServerLevel level, SubLevel ship, Session session) {
        try {
            final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos(), floor = new BlockPos.MutableBlockPos();
            for (int down = 0; down <= 1; down++) {
                for (final int[] side : STAND_SIDES) {
                    cell.set(session.seat.getX() + side[0], session.seat.getY() - down, session.seat.getZ() + side[1]);
                    floor.set(cell.getX(), cell.getY() - 1, cell.getZ());
                    // Only the ship's own blocks count as its deck.
                    if (!ship.getPlot().getBoundingBox().contains(floor.getX(), floor.getY(), floor.getZ())) continue;
                    final BlockState under = level.getBlockState(floor);
                    if (SurfaceType.getSurfaceType(level, under, floor) != SurfaceType.WALKABLE) continue;
                    if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) continue;
                    cell.move(0, 1, 0);
                    if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) continue;
                    double top = 0.0;
                    for (final AABB box : under.getCollisionShape(level, floor).toAabbs()) top = Math.max(top, box.maxY);
                    if (top < 0.5) continue;      // a floor that low is the cell below, not this one
                    return new Vector3d(floor.getX() + 0.5, floor.getY() + top, floor.getZ() + 0.5);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[Airline/boarding] could not look for a place to stand beside a seat; the top of the seat block is used.", e);
        }
        return session.stand;
    }

    /** Off the seat and onto the given place. The vanilla dismount spot is not used: it can be inside the deck. */
    private static void standUp(AbstractEntityCitizen citizen, Vec3 stand) {
        final Entity seat = citizen.getVehicle();
        citizen.stopRiding();
        if (seat instanceof SittingEntity) seat.discard();
        citizen.setPos(stand.x, stand.y + 0.01, stand.z);
        citizen.setDeltaMovement(Vec3.ZERO);
        citizen.fallDistance = 0.0F;
    }

    /** The ship is gone under a seated citizen: to solid ground near where it sat, else back to where it came from, else home. */
    private void evacuate(ServerLevel level, Session session, AbstractEntityCitizen citizen, String why) {
        final Entity seat = citizen.getVehicle();
        citizen.stopRiding();
        if (seat instanceof SittingEntity) seat.discard();
        final Vec3 sat = session.lastSit;
        BlockPos safe = EntityUtils.getSpawnPoint(level, BlockPos.containing(sat.x, sat.y + 0.5, sat.z));
        String where = "ground next to where the seat was";
        if (safe == null && session.origin != null) {
            final BlockPos origin = BlockPos.containing(session.origin.x, session.origin.y + 0.2, session.origin.z);
            safe = EntityUtils.getSpawnPoint(level, origin);
            if (safe == null) safe = origin;
            where = "the place it boarded from";
        }
        if (safe == null && citizen.getCitizenData() != null && citizen.getCitizenData().getHomePosition() != null) {
            safe = EntityUtils.getSpawnPoint(level, citizen.getCitizenData().getHomePosition());
            where = "its home";
        }
        if (safe != null) {
            citizen.teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
            citizen.setDeltaMovement(Vec3.ZERO);
            citizen.fallDistance = 0.0F;
            LOGGER.info("[Airline/boarding] {}: {}; it was moved to {} at {}.", citizen.getName().getString(), why, where, safe);
        } else {
            LOGGER.warn("[Airline/boarding] {}: {}; no safe place was found, it stays where it is.", citizen.getName().getString(), why);
        }
        forget(session);
    }

    /**
     * Holds the citizen's own decisions for as long as the session is looked after: a blocking entry in MineColonies'
     * own decision machine that answers "stay in the state you are in". It lapses by itself when this class stops
     * renewing it.
     */
    private void hold(ServerLevel level, Session session, AbstractEntityCitizen citizen) {
        session.lookedAfter = level.getGameTime();
        if (session.held == citizen) return;
        final ITickRateStateMachine<IState> decisions = decisions(citizen);
        if (decisions == null) return;
        final IBooleanConditionSupplier active = () -> !session.over && level.getGameTime() - session.lookedAfter <= HOLD_TICKS;
        final IStateSupplier<IState> stay = decisions::getState;
        final AIEventTarget<IState> hold = new AIEventTarget<>(AIBlockingEventType.AI_BLOCKING, active, stay, 1);
        decisions.addTransition(hold);
        session.held = citizen;
        session.hold = hold;
    }

    /** The citizen's own decision machine (work, food, sleep). Visitors have none of this kind and are not held. */
    @Nullable
    private static ITickRateStateMachine<IState> decisions(AbstractEntityCitizen citizen) {
        return citizen instanceof EntityCitizen worker ? worker.getCitizenAI() : null;
    }

    private void forget(Session session) {
        session.over = true;
        sessions.remove(session.citizen, session);
        final AbstractEntityCitizen held = session.held;
        final AIEventTarget<IState> hold = session.hold;
        session.held = null;
        session.hold = null;
        if (held != null && hold != null) {
            try {
                final ITickRateStateMachine<IState> decisions = decisions(held);
                if (decisions != null) decisions.removeTransition(hold);
            } catch (RuntimeException e) {
                // The entry answers "no" from now on (session.over), so leaving it behind is harmless.
            }
        }
    }

    private void end(Session session, AbstractEntityCitizen citizen, String why) {
        LOGGER.info("[Airline/boarding] {} is released ({}).", citizen.getName().getString(), why);
        forget(session);
    }

    /** Stands every seated citizen of this level up on its deck (or brings it to safety) and forgets all sessions. */
    private void releaseAll(ServerLevel level, String why) {
        for (final Session session : new ArrayList<>(sessions.values())) {
            try {
                final Entity entity = level.getEntity(session.citizen);
                if (entity instanceof AbstractEntityCitizen citizen && session.state == State.SEATED) {
                    final SubLevel ship = ship(level, session.ship);
                    if (ship != null) standUp(citizen, world(ship, standPlace(level, ship, session)));
                    else {
                        evacuate(level, session, citizen, why);
                        continue;
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Airline/boarding] could not release a boarding citizen cleanly.", e);
            }
            forget(session);
        }
    }

    /** Before the world is saved or unloaded: nobody is left sitting on a seat that would not survive it. */
    public static void release(ServerLevel level, String why) {
        final BoardingManager manager = MANAGERS.get(level);
        if (manager != null && !manager.sessions.isEmpty()) manager.releaseAll(level, why);
    }
}
