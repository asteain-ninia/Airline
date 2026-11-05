package com.asteain.airline.flight;

import net.minecraft.world.phys.Vec3;

/**
 * Flies one aircraft by its levers, the way the stage 2 lab did: height with the levers of the burners, direction and
 * speed with the drives, switched between the few manoeuvres every design names ({@link AircraftType.Drive}). It decides
 * once every {@code period} ticks (10) and never pushes the ship itself.
 *
 * <p>Three things it can be asked to do: fly a leg and stop at its end ({@link #leg}), turn to a heading on the spot
 * and keep it ({@link #turnTo}), or just keep its height ({@link #hover}).</p>
 */
final class Autopilot {
    final Craft c;
    final AircraftType t;
    /** Height to keep (world y of the ship's own position). */
    double altTarget;
    private double integral, carry;
    /** The leg being flown, world coordinates. */
    private double ax, az, bx, bz;
    private boolean guide;
    /** The drives are off because the end of the leg is near (or reached). */
    boolean stopped;
    /** The drives are astern to take the way off before the end of the leg (designs that are stopped that way). */
    private boolean braking;
    /** The manoeuvre the drives are to be at. The levers are moved toward it once for every decision (throttles go step by step). */
    private AircraftType.Drive wanted = AircraftType.Drive.OFF;
    private boolean deciding;
    /**
     * A fast design covers several blocks between two decisions: taking its way off (drives astern, or off) is set for
     * the very tick it is due, not for the next decision. 0: nothing is due.
     */
    private int dueTick;
    private boolean dueAstern;
    private double heading = Double.NaN;
    /** Fine placing: while the heading is held, the place to come to rest over (along the ship's own axis). */
    private boolean placing;
    private double placeX, placeZ;
    /** Whether it is the aircraft's own block that is put over the place (else the ship's own position, its centre of mass). */
    private boolean placeByBlock;
    /**
     * How far ahead of the centre of mass the aircraft's own block lies along the ship's own heading (astern: below zero),
     * evened out over some seconds. The own block lies blocks below the centre of mass, so it swings to and fro with
     * every nod of the ship; a ship placed by where the block is at the moment chases its own nodding (a white sail gave
     * up placing itself that way and came down 2.3 blocks off). Placed by the centre of mass plus this, it does not.
     */
    private double fore;
    private boolean foreKnown;
    private static final double FORE_TICKS = 60.0;
    /** True once the heading is right, the place is reached and the ship is at rest. */
    boolean placed;
    private int tick, pushEnd;
    /** What the drives go back to when a short push ends: off, or going ahead for a push that trims the heading under way. */
    private AircraftType.Drive afterPush = AircraftType.Drive.OFF;
    int pushes;

    Autopilot(Craft craft) {
        this.c = craft;
        this.t = craft.type;
        this.placeWithin = craft.type.placeWithin;
    }

    /** How closely the ship is placed (blocks along each axis): the design's own, or less closely beside a pier (its connectors pull). */
    double placeWithin;

    /**
     * What the drives are to do from now on. In the middle of a decision this only notes it (the levers are moved once,
     * when the decision is made); at any other time (the end of a short push, a new task from the flight controller) the
     * levers take their first step toward it at once.
     */
    private void drive(AircraftType.Drive manoeuvre) {
        wanted = manoeuvre;
        if (!deciding) c.driveToward(manoeuvre);
    }

    /** For the test bench: the drives are set to a manoeuvre and left there (only the height is kept besides). */
    void manual(AircraftType.Drive manoeuvre) {
        dueTick = 0;
        guide = false;
        halting = false;
        placing = false;
        heading = Double.NaN;
        pushEnd = 0;
        drive(manoeuvre);
    }

    static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d > 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    void leg(double ax, double az, double bx, double bz) {
        this.ax = ax;
        this.az = az;
        this.bx = bx;
        this.bz = bz;
        this.guide = true;
        this.stopped = false;
        this.halting = false;
        this.dueTick = 0;
        // A design that is stopped with its drives astern takes its way off first when the new leg lies behind it.
        final double yaw = Math.toRadians(c.yaw());
        this.braking = t.brakeSeconds > 0 && ahead() > t.brakeBelow && (bx - ax) * Math.cos(yaw) + (bz - az) * Math.sin(yaw) < 0;
        this.heading = Double.NaN;
        this.pushEnd = 0;
    }

    /**
     * Takes the way off where the ship is (something is in the way ahead): drives astern while it still moves ahead
     * faster than the design's brakeBelow, then off. A design that is not stopped that way just has its drives off.
     */
    void halt() {
        dueTick = 0;
        guide = false;
        placing = false;
        heading = Double.NaN;
        pushEnd = 0;
        halting = t.brakeSeconds > 0;
        if (!halting) drive(AircraftType.Drive.OFF);
    }

    private boolean halting;

    private double ahead() {
        final Vec3 v = c.vel();
        final double yaw = Math.toRadians(c.yaw());
        return v.x * Math.cos(yaw) + v.z * Math.sin(yaw);
    }

    /** The ship came to rest too far from the end of the leg: try once more from where it is. */
    void again() {
        stopped = false;
        dueTick = 0;
    }

    void hover() {
        dueTick = 0;
        guide = false;
        halting = false;
        placing = false;
        heading = Double.NaN;
        pushEnd = 0;
        drive(AircraftType.Drive.OFF);
    }

    /**
     * Turns to a heading and then, keeping it, creeps along its own axis until it rests over the given place (as near
     * as that axis allows): short pushes ahead or astern, one per decision, at rest only.
     */
    void placeAt(double degrees, double x, double z) {
        placeAt(degrees, x, z, false);
    }

    /** ... {@code byBlock}: the aircraft's own block is put over the place, wherever the ship's centre of mass is then. */
    void placeAt(double degrees, double x, double z, boolean byBlock) {
        turnTo(degrees);
        placing = true;
        placed = false;
        placeX = x;
        placeZ = z;
        placeByBlock = byBlock;
    }

    void turnTo(double degrees) {
        dueTick = 0;
        guide = false;
        halting = false;
        placing = false;
        heading = degrees;
        pushes = 0;
        pushEnd = 0;
        drive(AircraftType.Drive.OFF);
    }

    void resetHeight() {
        integral = 0;
        carry = 0;
    }

    /**
     * Raises the height target toward {@code goal} at the design's climbing rate, more slowly over the last three
     * blocks (down to a quarter of the rate): hot air comes and goes slowly, and what is too much at the top takes long
     * to go. A design without a climbing rate has its target set to the goal at once.
     */
    void climbToward(double goal) {
        if (t.climbRate <= 0 || altTarget >= goal) {
            altTarget = goal;
            return;
        }
        final double ease = Math.max(0.25, Math.min(1.0, (goal - altTarget) / 3.0));
        altTarget = Math.min(goal, altTarget + t.climbRate * ease / 20.0);
    }

    /**
     * Whether the climb to the line is over and the flight along it can begin. A design that is brought up with a
     * climbing rate goes on as soon as it is near the line's height and nearly steady: hot air goes slowly, so the block
     * or two it rises too far take half a minute to go, and it can lose them just as well on its way. (The line's tube
     * has two blocks of room above the aircraft; 1.75 too high is inside it.) A design without a climbing rate waits
     * until it is at the height and steady, as it was measured.
     */
    boolean atHeight(double goal) {
        final double off = c.pos().y - goal, rate = Math.abs(c.vel().y);
        return t.climbRate > 0 ? off > -0.5 && off < 1.75 && rate < 0.6 : Math.abs(off) < 0.5 && rate < 0.2;
    }

    double remaining() {
        final Vec3 p = c.pos();
        return Math.hypot(bx - p.x, bz - p.z);
    }

    double headingError() {
        return Double.isNaN(heading) ? 0.0 : wrap(heading - c.yaw());
    }

    /** Signed distance from the line of the leg (positive = to the left of it when looking along it). */
    private double offLine(Vec3 p) {
        final double lx = bx - ax, lz = bz - az, len = Math.hypot(lx, lz);
        return len < 1.0e-6 ? 0.0 : ((p.x - ax) * lz - (p.z - az) * lx) / len;
    }

    /** Call every tick. */
    void tick() {
        tick++;
        {
            final double yaw = Math.toRadians(c.yaw());
            final Vec3 block = c.ownBlock(), mass = c.pos();
            final double now = (block.x - mass.x) * Math.cos(yaw) + (block.z - mass.z) * Math.sin(yaw);
            fore = foreKnown ? fore + (now - fore) / FORE_TICKS : now;
            foreKnown = true;
        }
        if (pushEnd > 0 && tick >= pushEnd) {
            drive(afterPush);
            afterPush = AircraftType.Drive.OFF;
            pushEnd = 0;
        }
        if (dueTick > 0 && tick >= dueTick) {
            dueTick = 0;
            if (guide && !stopped) {
                if (dueAstern) {
                    braking = true;
                    drive(AircraftType.Drive.ASTERN);
                } else {
                    stopped = true;
                    drive(AircraftType.Drive.OFF);
                }
            }
        }
        if (tick % t.period != 0) return;
        height();
        deciding = true;
        try {
            if (halting) {
                if (ahead() > t.brakeBelow) {
                    drive(AircraftType.Drive.ASTERN);
                } else {
                    halting = false;
                    drive(AircraftType.Drive.OFF);
                }
            } else if (guide) {
                steer();
            } else if (!Double.isNaN(heading)) {
                hold();
            }
        } finally {
            deciding = false;
        }
        // One step toward what is wanted, whether this decision changed it or not (throttles are still on their way).
        c.driveToward(wanted);
    }

    private void height() {
        final double e = altTarget - c.pos().y, vy = c.vel().y;
        integral = Math.max(-4, Math.min(4, integral + e * t.heightIntegral * t.period));
        final double want = Math.max(0, Math.min(15, t.hoverLever + integral + t.heightGain * e - t.heightDamping * vy));
        int out = (int) Math.floor(want);
        carry += want - out;
        if (carry >= 1.0) {
            carry -= 1.0;
            out++;
        }
        c.burner(out);
    }

    private void steer() {
        final Vec3 p = c.pos(), v = c.vel();
        final double dx = bx - p.x, dz = bz - p.z, dist = Math.hypot(dx, dz), speed = Math.hypot(v.x, v.z);
        final boolean brakes = t.brakeSeconds > 0;
        // What a fast design covers until the next decision: a stop that is due within that is set for its own tick.
        final double untilNext = t.creepWithin > 0 ? speed * t.period / 20.0 : 0.0;
        if (brakes && !stopped) {
            // A fast design: astern from where that brings it to rest at the end of the leg, until most of its way is off.
            if (!braking && speed > t.brakeBelow) {
                final double room = dist - (t.stopDistance + speed * t.brakeSeconds);
                if (room <= 0) braking = true;
                else if (room < untilNext) due(room, speed, true);
            }
            if (braking) {
                if (ahead() > t.brakeBelow) {
                    drive(AircraftType.Drive.ASTERN);
                    return;
                }
                braking = false;
            }
        }
        // The drives go off once, when the ship will coast to the end of the leg from here; they stay off until again() is called.
        // (A design that is stopped with its drives astern coasts only the little way it has left after that.)
        if (!stopped && (!brakes || speed <= t.brakeBelow)) {
            final double room = dist - (t.stopDistance + speed * t.coastSeconds);
            if (room <= 0) stopped = true;
            else if (room < untilNext) due(room, speed, false);
        }
        if (stopped) {
            drive(AircraftType.Drive.OFF);
            return;
        }
        if (t.creepWithin > 0 && dist < t.creepWithin && speed <= Math.max(t.brakeBelow, Math.max(0.2, 0.5 * slow()))) {
            // Close to the end and nearly at rest: one short, slow push along the ship's own axis, measured to end there.
            final double yaw = Math.toRadians(c.yaw()), along = dx * Math.cos(yaw) + dz * Math.sin(yaw);
            final double left = along - ahead() * t.coastSeconds;
            if (Math.abs(left) < Math.max(t.stopDistance, 0.3)) {
                stopped = true;
                drive(AircraftType.Drive.OFF);
                return;
            }
            final int length = (int) Math.max(2, Math.min(t.period, Math.round(Math.abs(left) / (slow() / 20.0) * 0.8)));
            drive(left > 0 ? AircraftType.Drive.AHEAD_SLOW : AircraftType.Drive.ASTERN_SLOW);
            pushes++;
            afterPush = AircraftType.Drive.OFF;
            pushEnd = length >= t.period ? 0 : tick + length;
            return;
        }
        if (dist < 6 && Math.abs(wrap(Math.toDegrees(Math.atan2(dz, dx)) - c.yaw())) > 100) {
            // The end is close behind (it went a little too far): back up instead of turning round.
            drive(AircraftType.Drive.ASTERN);
            return;
        }
        final double legHeading = Math.toDegrees(Math.atan2(bz - az, bx - ax)), toPoint = Math.toDegrees(Math.atan2(dz, dx));
        // Far from the end: follow the line, leaning toward it by the distance from it. Near the end: head for the point itself.
        // Past the end (it went too far, more than backing up covers): head for the point itself as well. (Otherwise a
        // heavy design on a short leg that went past it would, still following the leg's heading, fly on for ever: one
        // was seen going 23,000 blocks.)
        final boolean past = (p.x - bx) * (bx - ax) + (p.z - bz) * (bz - az) > 0;
        final double lean = Math.max(-t.maxLean, Math.min(t.maxLean, t.leanPerBlock * offLine(p)));
        final double course = dist > Math.max(6.0, t.creepWithin) && !past ? legHeading + lean : toPoint;
        final boolean slowNow = speed < Math.max(0.5, 0.5 * slow());
        if (t.creepWithin > 0 && slowNow && Math.abs(wrap(course - c.yaw())) > t.smallError) {
            // A fast design that is nearly at rest and not headed along its way (the beginning of a leg): it turns on the
            // spot first, with measured pushes, and goes ahead only when it is headed right. Going ahead while still far off
            // the heading would carry it out of the line's tube in a wide curve.
            if (turnStep(course)) drive(AircraftType.Drive.OFF);      // the turning it has will bring it there: wait
            return;
        }
        if (t.gentleTurnRate > 0) {
            // A fast design under way. Far off its course at speed (a leg that was changed under it): the way comes off
            // first, and the turn on the spot above takes over. Else its heading is trimmed with short pushes of the gentle
            // turn around going ahead; a push of one tick turns it by the gentle turn's speed / 20 in the end.
            if (!slowNow && Math.abs(wrap(course - c.yaw())) > t.bigError) {
                drive(AircraftType.Drive.OFF);
                return;
            }
            final double off = wrap(course - (c.yaw() + c.yawRate() * t.gentleLead));
            if (Math.abs(off) < t.cruiseDeadband) {
                drive(AircraftType.Drive.AHEAD);
                return;
            }
            final int length = (int) Math.max(2, Math.min(t.period, Math.round(Math.abs(off) / (t.gentleTurnRate / 20.0) * 0.8)));
            drive(off > 0 ? AircraftType.Drive.AHEAD_RIGHT : AircraftType.Drive.AHEAD_LEFT);
            pushes++;
            afterPush = AircraftType.Drive.AHEAD;
            pushEnd = length >= t.period ? 0 : tick + length;
            return;
        }
        final double error = wrap(course - (c.yaw() + c.yawRate() * t.leadSeconds));
        final int turn = error > 0 ? 1 : -1;
        if (Math.abs(error) > t.bigError) drive(turn > 0 ? AircraftType.Drive.RIGHT : AircraftType.Drive.LEFT);
        else if (Math.abs(error) > t.smallError) drive(turn > 0 ? AircraftType.Drive.AHEAD_RIGHT : AircraftType.Drive.AHEAD_LEFT);
        else drive(AircraftType.Drive.AHEAD);
    }

    /** In so many blocks, at this speed, the drives are to go astern (or off): set for the tick that is. */
    private void due(double room, double speed, boolean astern) {
        final int ticks = (int) Math.floor(room / Math.max(0.05, speed) * 20.0);
        if (ticks <= 0) {
            if (astern) braking = true;
            else stopped = true;
            return;
        }
        dueTick = tick + ticks;
        dueAstern = astern;
    }

    /** The speed the slow manoeuvres ahead and astern settle at (the top speed for a design that has no slow ones). */
    private double slow() {
        return t.slowSpeed > 0 ? t.slowSpeed : t.topSpeed;
    }

    /** The heading is right: one short push along the ship's own axis toward the place, if it will not get there by itself. */
    private void creep() {
        final Vec3 p = c.pos(), v = c.vel();
        final double yaw = Math.toRadians(c.yaw()), cos = Math.cos(yaw), sin = Math.sin(yaw);
        final double along = (placeX - p.x) * cos + (placeZ - p.z) * sin - (placeByBlock ? fore : 0), speed = v.x * cos + v.z * sin;
        final double left = along - speed * t.coastSeconds;      // what will be left once it has coasted to rest
        placed = Math.abs(along) < placeWithin && Math.abs(speed) < 0.03 && Math.abs(headingError()) < 2.0;
        if (placed || Math.abs(left) < placeWithin * 0.75 || Math.abs(headingError()) > 3.0) return;
        final int length = (int) Math.max(2, Math.min(t.period, Math.round(Math.abs(left) / (slow() / 20.0) * 0.8)));
        drive(left > 0 ? AircraftType.Drive.AHEAD_SLOW : AircraftType.Drive.ASTERN_SLOW);
        pushes++;
        afterPush = AircraftType.Drive.OFF;
        pushEnd = length >= t.period ? 0 : tick + length;
    }

    /**
     * Keeps the heading: one push per decision, as long as the heading will be off once the turning has died away
     * (heading now + turning speed x coast seconds), from 2 ticks up to the whole period.
     */
    private void hold() {
        if (turnStep(heading)) {
            drive(AircraftType.Drive.OFF);
            pushEnd = 0;
            if (placing) creep();
            return;
        }
        placed = false;
    }

    /**
     * One decision of turning on the spot toward a heading: a push as long as the heading will still be off once the
     * turning has died away (the last degrees with the slow turn, when the design has one). True when there is nothing
     * to push: the heading is right, or the turning the ship has will bring it there.
     */
    private boolean turnStep(double toward) {
        final double ahead = wrap(toward - (c.yaw() + c.yawRate() * t.turnCoastSeconds));
        // The last degrees with the slow turn, when the design has one: a push of one tick turns it by its turning speed / 20.
        // Over a pad the whole turn: a fast turn carries the ship sideways (koeda: 0.3 to 0.4 of a block).
        final boolean fine = t.slowTurnRate > 0 && (Math.abs(ahead) < t.fineTurnBelow || placing);
        int length = (int) Math.round(Math.abs(ahead) * (fine ? 0.8 * 20.0 / t.slowTurnRate : t.turnTicksPerDegree));
        if (Math.abs(ahead) < t.turnDeadband || length < 1) return true;
        length = Math.max(2, Math.min(t.period, length));
        drive(ahead > 0 ? (fine ? AircraftType.Drive.RIGHT_SLOW : AircraftType.Drive.RIGHT) : (fine ? AircraftType.Drive.LEFT_SLOW : AircraftType.Drive.LEFT));
        pushes++;
        afterPush = AircraftType.Drive.OFF;
        pushEnd = length >= t.period ? 0 : tick + length;
        return false;
    }
}
