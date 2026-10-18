package com.asteain.airline.flight;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import com.asteain.airline.Airline;
import net.minecraft.util.Unit;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * The hands and eyes of the autopilot on one aircraft: reads where the ship is and how it moves, and sets the values of
 * its levers. Only loaded when Sable is installed. Nothing here pushes the ship: the only writes are lever values (what
 * a player can do by hand), the fixing to the world and the loading ticket (the work of the aircraft's own block).
 *
 * <p>The devices of Create Aeronautics are reached by name (reflection), so this mod does not have to be compiled
 * against them.</p>
 */
final class Craft {
    final ServerLevel level;
    final ServerSubLevel ship;
    final AircraftType type;
    /** The aircraft's own block, in the ship's own coordinates. */
    final BlockPos base;
    int writes;

    Craft(ServerLevel level, ServerSubLevel ship, AircraftType type, BlockPos base) {
        this.level = level;
        this.ship = ship;
        this.type = type;
        this.base = base;
    }

    /** The ship a block belongs to, when it is part of one. */
    @Nullable
    static ServerSubLevel shipAt(ServerLevel level, BlockPos pos) {
        final SubLevel ship = Sable.HELPER.getContaining(level, pos);
        return ship instanceof ServerSubLevel server && !server.isRemoved() ? server : null;
    }

    UUID id() {
        return ship.getUniqueId();
    }

    BlockPos inShip(BlockPos part) {
        return base.offset(part);
    }

    // ---- where it is ------------------------------------------------------------------------------------------

    Vec3 pos() {
        final Vector3dc p = ship.logicalPose().position();
        return new Vec3(p.x(), p.y(), p.z());
    }

    Vec3 vel() {
        final Vector3dc v = RigidBodyHandle.of(ship).getLinearVelocity();
        return new Vec3(v.x(), v.y(), v.z());
    }

    /** Heading of the nose, degrees: 0 = +x, 90 = +z. */
    double yaw() {
        final Quaterniondc q = ship.logicalPose().orientation();
        final Vector3d nose = q.transform(new Vector3d(1, 0, 0));
        return Math.toDegrees(Math.atan2(nose.z, nose.x));
    }

    /** Turning speed, degrees per second, in the sense of {@link #yaw()}. */
    double yawRate() {
        return Math.toDegrees(-RigidBodyHandle.of(ship).getAngularVelocity().y());
    }

    /**
     * How near the ship's blocks are to a block of the world, each of the ship's blocks where it is now: the gap between
     * the two, with the ship's block taken as the ball around it (0.87; a little less than the truth, never more).
     */
    double nearest(BlockPos block) {
        final Pose3dc pose = ship.logicalPose();
        final Vector3d at = new Vector3d();
        double nearest = Double.MAX_VALUE;
        final java.util.Iterator<BlockPos> parts = java.util.stream.Stream.concat(java.util.stream.Stream.of(BlockPos.ZERO), type.parts.keySet().stream()).iterator();
        while (parts.hasNext()) {
            final BlockPos part = parts.next();
            final Vector3d w = pose.transformPosition(at.set(base.getX() + part.getX() + 0.5, base.getY() + part.getY() + 0.5, base.getZ() + part.getZ() + 0.5));
            final double dx = Math.max(0, Math.abs(w.x - (block.getX() + 0.5)) - 0.5), dy = Math.max(0, Math.abs(w.y - (block.getY() + 0.5)) - 0.5),
                    dz = Math.max(0, Math.abs(w.z - (block.getZ() + 0.5)) - 0.5);
            nearest = Math.min(nearest, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return Math.max(0.0, nearest - 0.87);
    }

    /** Where the middle of the aircraft's own block is in the world now (the middle of the hull's floor). */
    Vec3 ownBlock() {
        return toWorld(new Vec3(base.getX() + 0.5, base.getY() + 0.5, base.getZ() + 0.5));
    }

    Vec3 toWorld(Vec3 inShip) {
        final Pose3dc pose = ship.logicalPose();
        final Vector3d p = pose.transformPosition(new Vector3d(inShip.x, inShip.y, inShip.z));
        return new Vec3(p.x, p.y, p.z);
    }

    // ---- levers -----------------------------------------------------------------------------------------------

    private void lever(BlockPos part, int value) {
        final BlockPos at = inShip(part);
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(at);
        if (state.getBlock() instanceof net.minecraft.world.level.block.LeverBlock plain) {
            // A plain lever (2026-10-18): on or off, anything above 0 is on. Pulled as a hand pulls it.
            if (state.getValue(net.minecraft.world.level.block.LeverBlock.POWERED) == value > 0) return;
            plain.pull(state, level, at, null);
            writes++;
            return;
        }
        final BlockEntity lever = level.getBlockEntity(at);
        if (lever == null) return;
        if ((int) get(lever, "state") == value) return;
        call(lever, "setSignal", value);
        writes++;
    }

    /** What a lever gives now: a throttle lever 0 to 15, a plain lever 0 or 15; {@code missing} when there is none. */
    private int current(BlockPos part, int missing) {
        final BlockPos at = inShip(part);
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(at);
        if (state.getBlock() instanceof net.minecraft.world.level.block.LeverBlock) return state.getValue(net.minecraft.world.level.block.LeverBlock.POWERED) ? 15 : 0;
        final BlockEntity lever = level.getBlockEntity(at);
        return lever == null ? missing : (int) get(lever, "state");
    }

    /** The lever beside a connector of the hull: 15 lets the connector out (it joins one it meets), 0 takes it in. */
    void dockLever(BlockPos part, int value) {
        lever(part, value);
    }

    /** What a lever of the ship gives now (0 to 15); 0 when it is not there. */
    int leverValue(BlockPos part) {
        return current(part, 0);
    }

    /** Whether a connector of the ship is joined to another and held (its claws are out); false when it is not there. */
    boolean connectorLocked(BlockPos part) {
        return connector(level, inShip(part), "isLocked");
    }

    /** Whether a connector of the ship is out (or on its way out or in); false when it is not there. */
    boolean connectorOut(BlockPos part) {
        return connector(level, inShip(part), "isExtended");
    }

    /** Asks a connector (of a ship or of the world) a yes-or-no question of Simulated's; false when there is none to ask. */
    static boolean connector(net.minecraft.world.level.Level level, BlockPos at, String question) {
        final BlockEntity connector = level.getBlockEntity(at);
        if (connector == null || !connector.getClass().getSimpleName().startsWith("DockingConnector")) return false;
        try {
            return Boolean.TRUE.equals(call(connector, question));
        } catch (RuntimeException notThere) {
            return false;
        }
    }

    /** The levers beside the burners, all alike: 0 to 15. */
    void burner(int value) {
        for (final BlockPos lever : type.burnerLevers) lever(lever, Math.max(0, Math.min(15, value)));
    }

    /** Sets every drive lever to what the design says for that manoeuvre. */
    void drive(AircraftType.Drive manoeuvre) {
        final int[] values = type.drive[manoeuvre.ordinal()];
        for (int i = 0; i < type.driveLevers.length; i++) lever(type.driveLevers[i], values[i]);
    }

    void drivesOff() {
        drive(AircraftType.Drive.OFF);
    }

    /**
     * One step toward a manoeuvre, the way a careful hand works throttles: the design's throttle levers move a few steps
     * at a time, and before any other drive lever is changed (a drive is engaged, reversed or let go) the throttles are
     * closed first, step by step. Called once for every decision until it returns true (the levers are where the
     * manoeuvre wants them). A design without throttles has its levers set at once.
     */
    boolean driveToward(AircraftType.Drive manoeuvre) {
        final boolean[] throttle = type.throttle;
        final int step = type.throttleStep;
        if (throttle == null || step <= 0) {
            drive(manoeuvre);
            return true;
        }
        final int[] want = type.drive[manoeuvre.ordinal()], off = type.drive[AircraftType.Drive.OFF.ordinal()];
        final int[] now = new int[want.length];
        boolean shift = false, open = false;
        for (int i = 0; i < want.length; i++) {
            now[i] = current(type.driveLevers[i], want[i]);
            if (!throttle[i]) shift |= now[i] != want[i];
            else open |= now[i] != off[i];
        }
        if (shift && open) {
            // Something is to be engaged, reversed or let go while a throttle is open: the throttles are closed first.
            for (int i = 0; i < want.length; i++) if (throttle[i]) lever(type.driveLevers[i], toward(now[i], off[i], step));
            return false;
        }
        boolean there = true;
        for (int i = 0; i < want.length; i++) {
            final int next = throttle[i] ? toward(now[i], want[i], step) : want[i];
            lever(type.driveLevers[i], next);
            there &= next == want[i];
        }
        return there;
    }

    private static int toward(int now, int goal, int step) {
        return now < goal ? Math.min(goal, now + step) : Math.max(goal, now - step);
    }

    // ---- the work of the aircraft's own block -------------------------------------------------------------------

    private Object staff() throws ReflectiveOperationException {
        return Class.forName("dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler").getMethod("get", ServerLevel.class).invoke(null, level);
    }

    /** Whether the ship is fixed to the world (the same fixing the physics staff of Simulated makes). */
    boolean locked() {
        try {
            return (boolean) call(staff(), "isLocked", ship);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    void lock(boolean on) {
        try {
            final Object staff = staff();
            if ((boolean) call(staff, "isLocked", ship) != on) call(staff, "toggleLock", id());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sable's loading ticket: with it the ship keeps itself (and who sits on it) loaded wherever it flies. */
    void ticket(boolean on) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        if (on) container.addForceLoadTicket(ship, TICKET, Unit.INSTANCE);
        else container.removeForceLoadTicket(ship, TICKET, Unit.INSTANCE);
    }

    /**
     * This mod's own kind of ticket, so that it does not share a key with Sable's /sable forceload command: an
     * administrator's ticket is not taken away by the end of a flight, and the command does not take away a flight's.
     */
    static final SubLevelLoadingTicketType<Unit> TICKET = SubLevelLoadingTicketType.create(Airline.id("flight"), Unit.CODEC);

    int engines() {
        return type.engines.length;
    }

    /** Every engine set going again as a source of rotation (Create's updateGeneratedRotation); see FlightController.step. */
    void restartEngines() {
        for (final BlockPos at : type.engines) {
            final BlockEntity engine = level.getBlockEntity(inShip(at));
            if (engine == null) continue;
            try {
                call(engine, "updateGeneratedRotation");
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger("Airline").warn("[Airline/flight] could not set the engine at {} going again: {}", at.toShortString(), e.toString());
            }
        }
    }

    /** Ticks an engine still burns of what it has lit; -1 when there is no engine at its place. */
    int engineBurn(int side) {
        final BlockEntity engine = level.getBlockEntity(inShip(type.engines[side]));
        return engine == null ? -1 : ((Number) call(engine, "getCurrentBurnTime")).intValue();
    }

    void engineBurn(int side, int ticks) {
        final BlockEntity engine = level.getBlockEntity(inShip(type.engines[side]));
        if (engine != null) call(engine, "setCurrentBurnTime", ticks);
    }

    /** The fuel that lies in an engine (put there by hand; the tank feeds an engine its burn time, not items). */
    ItemStack engineStack(int side) {
        final BlockEntity engine = level.getBlockEntity(inShip(type.engines[side]));
        return engine == null ? ItemStack.EMPTY : (ItemStack) call(get(get(engine, "inventory"), "slot"), "getStack");
    }

    void engineStack(int side, ItemStack stack) {
        final BlockEntity engine = level.getBlockEntity(inShip(type.engines[side]));
        if (engine != null) call(get(get(engine, "inventory"), "slot"), "setStack", stack);
    }

    /** Each burner's own dial is set to what the design was measured with (a burner that was put in anew has its own). */
    void dial() {
        for (int i = 0; i < type.burners.length; i++) {
            final BlockEntity burner = level.getBlockEntity(inShip(type.burners[i]));
            if (burner != null) call(get(burner, "hotAirAmountBehaviour"), "setValue", type.burnerValues[i]);
        }
    }

    // ---- reflection ---------------------------------------------------------------------------------------------

    /** Calls a method by name; the first one with that name and number of arguments that accepts them wins. */
    static Object call(Object target, String name, Object... args) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (final Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (IllegalArgumentException wrongTypes) {
                    // another overload may fit
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("calling " + name + " on " + target.getClass().getSimpleName() + ": " + e.getCause(), e);
                }
            }
        }
        for (final Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try {
                return m.invoke(target, args);
            } catch (IllegalArgumentException wrongTypes) {
                // next
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("calling " + name + " on " + target.getClass().getSimpleName() + ": " + e.getCause(), e);
            }
        }
        throw new IllegalStateException("no method " + name + "/" + args.length + " on " + target.getClass().getName());
    }

    static Object get(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException next) {
                // look in the superclass
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("no field " + name + " on " + target.getClass().getName());
    }
}
