package com.asteain.airline.flight;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * For the test bench of aircraft designs (a development check, not a part of play): takes one parked aircraft out of the
 * hands of the flight controller and lets the bench work it, by exactly the means the autopilot has: the levers of the
 * burners (through the autopilot's own height keeping), the manoeuvres of the drives, and the autopilot's three tasks
 * (fly a leg and stop, turn to a heading, be placed over a point). Nothing here pushes the ship.
 *
 * <p>While a trial runs the aircraft is in the phase TRIAL: its tank feeds its engines and its autopilot ticks; the
 * controller does nothing else with it.</p>
 */
public final class Trial {
    private final AircraftBlockEntity be;
    private final FlightController.Runtime rt;
    private final Craft c;

    private Trial(AircraftBlockEntity be, FlightController.Runtime rt) {
        this.be = be;
        this.rt = rt;
        this.c = rt.craft;
    }

    /** The trial of an aircraft that stands parked and is loaded; null otherwise. */
    @Nullable
    public static Trial of(AircraftBlockEntity aircraft) {
        if (!Flights.available()) return null;
        final FlightController.Runtime rt = FlightController.runtime(aircraft);
        return rt == null || aircraft.phase != AircraftBlockEntity.Phase.PARKED ? null : new Trial(aircraft, rt);
    }

    public AircraftType type() {
        return c.type;
    }

    /**
     * Takes the aircraft over, still fixed to the world where it stands: its engines are fed and turn, the levers of the
     * burners are held where the design hovers, and the drives can be tried without the ship moving.
     */
    public void begin() {
        be.phase = AircraftBlockEntity.Phase.TRIAL;
        be.at = null;
        c.ticket(true);
        rt.pilot.resetHeight();
        rt.pilot.altTarget = c.pos().y;
        rt.pilot.hover();
    }

    /** Lets go of the pad: the ship is no longer fixed, and holds the height it has. */
    public void release() {
        rt.pilot.resetHeight();
        rt.pilot.altTarget = c.pos().y;
        c.lock(false);
    }

    /** Fixed where it is, drives off, no ticket: left like an aircraft that was given up in the air (the clear command takes it away). */
    public void end() {
        c.drivesOff();
        c.lock(true);
        c.ticket(false);
        be.phase = AircraftBlockEntity.Phase.STRANDED;
        be.note = "left where its trial ended";
        be.setChanged();
    }

    // ---- what the bench asks for ----------------------------------------------------------------------------

    /** The height to keep (world y of the ship's own position). */
    public void altitude(double y) {
        rt.pilot.altTarget = y;
    }

    /** Whether the flight controller would take the climb to that height as over and fly off along the line. */
    public boolean atHeight(double y) {
        return rt.pilot.atHeight(y);
    }

    /** One tick of climbing toward a height the way the flight controller does it (the design's climbing rate); the target it is at now. */
    public double climbToward(double y) {
        rt.pilot.climbToward(y);
        return rt.pilot.altTarget;
    }

    /** Only the height is kept; the drives are left to {@link #drive}. */
    public void hover() {
        rt.pilot.hover();
    }

    /**
     * The drives go to that manoeuvre and stay there, the way the autopilot brings them there (a design's throttles
     * step by step); only the height is kept besides.
     */
    public void drive(AircraftType.Drive manoeuvre) {
        rt.pilot.manual(manoeuvre);
    }

    /** The same, with every lever set at once (for looking at what turns while the aircraft is fixed to its pad). */
    public void driveNow(AircraftType.Drive manoeuvre) {
        c.drive(manoeuvre);
        rt.pilot.manual(manoeuvre);
    }

    public void leg(double ax, double az, double bx, double bz) {
        rt.pilot.leg(ax, az, bx, bz);
    }

    public void again() {
        rt.pilot.again();
    }

    public void turnTo(double degrees) {
        rt.pilot.turnTo(degrees);
    }

    public void placeAt(double degrees, double x, double z) {
        rt.pilot.placeAt(degrees, x, z);
    }

    /** The autopilot has switched the drives off for the end of the leg. */
    public boolean stopped() {
        return rt.pilot.stopped;
    }

    public boolean placed() {
        return rt.pilot.placed;
    }

    public double remaining() {
        return rt.pilot.remaining();
    }

    public double headingError() {
        return rt.pilot.headingError();
    }

    // ---- what the bench reads -------------------------------------------------------------------------------

    public Vec3 pos() {
        return c.pos();
    }

    public Vec3 vel() {
        return c.vel();
    }

    /** Heading of the nose, degrees: 0 = +x, 90 = +z; and how fast it turns, degrees per second. */
    public double yaw() {
        return c.yaw();
    }

    public double yawRate() {
        return c.yawRate();
    }

    /** {nose up (degrees), right side up (degrees), the deck's tilt from level (degrees)}. */
    public double[] attitude() {
        final Quaterniondc q = c.ship.logicalPose().orientation();
        final Vector3d nose = q.transform(new Vector3d(1, 0, 0)), right = q.transform(new Vector3d(0, 0, 1)), up = q.transform(new Vector3d(0, 1, 0));
        return new double[] {Math.toDegrees(Math.asin(clamp(nose.y))), Math.toDegrees(Math.asin(clamp(right.y))), Math.toDegrees(Math.acos(clamp(up.y)))};
    }

    private static double clamp(double v) {
        return Math.max(-1.0, Math.min(1.0, v));
    }

    /** Mass, and the centre of mass seen from the middle of the aircraft's own block: toward the nose, up, to the right. */
    public double[] weight() {
        return FlightController.weight(c.level, be);
    }

    public int leverWrites() {
        return c.writes;
    }

    /** The value the lever of a burner has now. */
    public int burnerLever(int burner) {
        final BlockEntity lever = c.level.getBlockEntity(c.inShip(c.type.burnerLevers[burner]));
        return lever == null ? -1 : (int) Craft.get(lever, "state");
    }

    /** Sable's air pressure where the aircraft's own block is. */
    public double air() {
        return c.air();
    }

    /** The balloon of a burner: {hot air in it, hot air it is being filled to, room, lift}; null when the burner sees no balloon. */
    @Nullable
    public double[] balloon(int burner) {
        final BlockEntity device = c.level.getBlockEntity(c.inShip(c.type.burners[burner]));
        if (device == null) return null;
        final Object balloon = Craft.call(device, "getBalloon");
        if (balloon == null) return null;
        return new double[] {number(balloon, "getTotalFilledVolume"), number(balloon, "getTotalTargetVolume"), number(balloon, "getCapacity"), number(balloon, "getTotalLift")};
    }

    /** One line for every part that turns or burns: where it is, what it is, how fast it turns, whether it has too much to turn, its thrust. */
    public record Turning(BlockPos at, String block, double speed, boolean overstressed, double thrust) {}

    public List<Turning> turning() {
        final List<Turning> all = new ArrayList<>();
        for (final BlockPos part : c.type.parts.keySet()) {
            final BlockEntity device = c.level.getBlockEntity(c.inShip(part));
            if (device == null) continue;
            final double speed = number(device, "getSpeed");
            if (Double.isNaN(speed)) continue;
            boolean overstressed = false;
            try {
                overstressed = (boolean) Craft.call(device, "isOverStressed");
            } catch (RuntimeException notKinetic) {
                // not a part of Create's that can have too much to turn
            }
            all.add(new Turning(part, String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(device.getBlockState().getBlock())), speed, overstressed, number(device, "getThrust")));
        }
        return all;
    }

    /** What a method without arguments gives as a number; NaN when the object has no such method or it gives no number. */
    private static double number(Object target, String method) {
        try {
            return Craft.call(target, method) instanceof Number n ? n.doubleValue() : Double.NaN;
        } catch (RuntimeException absent) {
            return Double.NaN;
        }
    }
}
