package com.asteain.shiptest;

import com.asteain.airline.boarding.Boarding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;

/**
 * Stage 2 lab, test 4: an autopilot that only writes values a player could set by hand (the lever beside the burner
 * and the forward / reverse levers of the two drives), at a chosen rate (every tick, every 10 or every 20 ticks).
 * It reads the ship's position, speed and heading freely; reading is not restricted.
 *
 * Mission: fill the balloon while held; climb to the hold height and keep it (4-1); fly a straight leg and stop
 * (4-2); turn by 90 degrees onto a second leg and stop (4-3); descend onto a pad (4-4).
 */
final class LabAutopilot {
    private LabAutopilot() {}

    private static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d > 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** What the autopilot remembers between two looks at the ship. */
    static final class Pilot {
        final LabFlight.Craft c;
        final Vec3 p0;
        final int period;
        final Map<String, Double> p;
        double hover, integral, carry;
        double altTarget;
        /** Leg being flown: from (ax, az) to (bx, bz), relative to p0; null target = hold still. */
        double ax, az, bx, bz;
        boolean guide;
        boolean stopped;
        int tick;

        Pilot(LabFlight.Craft c, Vec3 p0, int period, Map<String, Double> p) {
            this.c = c;
            this.p0 = p0;
            this.period = period;
            this.p = p;
            this.hover = p.getOrDefault("hover", 10.0);
        }

        double x() { return c.pos().x - p0.x; }
        double y() { return c.pos().y - p0.y; }
        double z() { return c.pos().z - p0.z; }

        void leg(double ax, double az, double bx, double bz) {
            this.ax = ax; this.az = az; this.bx = bx; this.bz = bz;
            this.guide = true;
            this.stopped = false;
        }

        double remaining() { return Math.hypot(bx - x(), bz - z()); }

        /** Signed distance from the line of the leg (positive = to the left of it when looking along it in the x-z plane). */
        double offLine() {
            double lx = bx - ax, lz = bz - az, len = Math.hypot(lx, lz);
            if (len < 1e-6) return 0;
            return ((x() - ax) * lz - (z() - az) * lx) / len;
        }

        /** Called every tick; acts only every 'period' ticks. */
        void tick() {
            tick++;
            if (tick % period != 0) return;
            height();
            if (guide) steer();
        }

        private void height() {
            double e = altTarget - y(), vy = c.vel().y;
            integral += e * p.getOrDefault("ki", 0.003) * period;
            integral = Math.max(-4, Math.min(4, integral));
            double want = hover + integral + p.getOrDefault("kp", 1.0) * e - p.getOrDefault("kd", 4.0) * vy;
            want = Math.max(0, Math.min(15, want));
            int out = (int) Math.floor(want);
            carry += want - out;
            if (carry >= 1.0) { carry -= 1.0; out++; }
            c.burner(out);
        }

        private void steer() {
            double dx = bx - x(), dz = bz - z();
            double dist = Math.hypot(dx, dz);
            Vec3 v = c.vel();
            double speed = Math.hypot(v.x, v.z);
            double stopAt = p.getOrDefault("stop", 1.0) + speed * (period / 20.0 + p.getOrDefault("coast", 0.0));
            boolean brake = p.getOrDefault("brake", 0.0) > 0;
            double yawNow = Math.toRadians(c.yaw());
            double along = v.x * Math.cos(yawNow) + v.z * Math.sin(yawNow);      // speed along the ship's own nose
            if (dist < stopAt || stopped) {
                if (brake && !stopped && Math.abs(along) > p.getOrDefault("brakeBelow", 0.5)) {
                    // still fast inside the stopping distance: push against the motion with both drives
                    int against = along > 0 ? -1 : 1;
                    c.drive(0, against);
                    c.drive(1, against);
                    return;
                }
                // on the point (or as close as coasting will bring it): everything off; if it drifted away again, come back
                c.drive(0, 0);
                c.drive(1, 0);
                stopped = dist < p.getOrDefault("again", 2.5);
                if (stopped) return;
            }
            if (brake && dist < 6 && Math.abs(wrap(Math.toDegrees(Math.atan2(dz, dx)) - c.yaw())) > 100) {
                // the point is close behind: back up instead of turning round
                c.drive(0, -1);
                c.drive(1, -1);
                return;
            }
            double legHeading = Math.toDegrees(Math.atan2(bz - az, bx - ax));
            double toPoint = Math.toDegrees(Math.atan2(dz, dx));
            // far from the point: follow the line, leaning toward it by the distance from the line; close to it: head for the point itself
            double lean = Math.max(-35, Math.min(35, p.getOrDefault("lean", 12.0) * offLine()));
            double wanted = dist > 6 ? legHeading + lean : toPoint;
            // where the nose will be once this order has had its effect
            double lead = period / 20.0 + p.getOrDefault("lead", 0.45);
            double error = wrap(wanted - (c.yaw() + c.yawRate() * lead));
            double big = p.getOrDefault("big", 25.0), small = p.getOrDefault("small", 4.0);
            int turn = error > 0 ? 1 : -1;
            if (Math.abs(error) > big) {            // turn on the spot
                c.drive(0, turn);
                c.drive(1, -turn);
            } else if (Math.abs(error) > small) {   // one side only: turns while it goes
                c.drive(0, turn > 0 ? 1 : 0);
                c.drive(1, turn > 0 ? 0 : 1);
            } else {
                c.drive(0, 1);
                c.drive(1, 1);
            }
        }
    }

    static void fly(Script s, MinecraftServer server, ServerLevel level, BlockPos origin, Map<String, Double> p) {
        int period = (int) (double) p.getOrDefault("period", 1.0);
        double dist = p.getOrDefault("dist", 100.0), side = p.getOrDefault("side", 30.0), alt = p.getOrDefault("alt", 8.0);
        int holdTicks = (int) (double) p.getOrDefault("hold", 1200.0);
        boolean withCitizen = p.getOrDefault("citizen", 0.0) > 0;
        String name = "fly(period " + period + (withCitizen ? ", citizen" : "") + ")";
        BlockPos deck = origin.offset(0, 30, 0);
        LabFlight.Craft[] craft = new LabFlight.Craft[1];
        Pilot[] pilot = new Pilot[1];
        Vec3[] p0 = new Vec3[1];
        Vector3d[] holdPos = new Vector3d[1];
        Quaterniond[] holdQ = new Quaterniond[1];
        boolean[] hold = {true};
        int[] t = new int[1];
        Rig rig = withCitizen ? new Rig(server, origin) : null;
        if (withCitizen) rig.addColony(s, "CPS lab", 12, 8);

        // statistics of the phase that is running
        double[] st = new double[8];   // 0 min y, 1 max y, 2 max |off line|, 3 max |y - target|, 4 max tilt, 5 max speed, 6 seat offset max, 7 ticks
        Runnable resetStats = () -> { st[0] = 1e9; st[1] = -1e9; st[2] = 0; st[3] = 0; st[4] = 0; st[5] = 0; st[6] = 0; st[7] = 0; };

        s.step(name + ": build", () -> {
            Rig.force(level, origin.offset((int) (dist / 2), 0, (int) (side / 2)), (int) (dist / 2) + 48, (int) (side / 2) + 48);
            // landing pad under the last point, six blocks below where the deck is built
            BlockPos pad = deck.offset((int) Math.round(dist), -7, (int) Math.round(side));
            for (int x = -3; x <= 3; x++) for (int z = -4; z <= 4; z++) level.setBlockAndUpdate(pad.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            craft[0] = LabFlight.buildChosen(level, deck, p);
            LabFlight.Craft c = craft[0];
            for (String part : c.parts) s.note("PART " + part);
            p0[0] = c.pos();
            holdPos[0] = new Vector3d(p0[0].x, p0[0].y, p0[0].z);
            holdQ[0] = c.q();
            pilot[0] = new Pilot(c, p0[0], period, p);
            pilot[0].altTarget = alt;
            s.note(String.format("FLY %s built: mass=%.2f seatInShip=%s", name, LabFlight.mass(c.ship), c.seat));
            c.burner((int) (double) p.getOrDefault("hover", 10.0));
            c.drive(0, 0);
            c.drive(1, 0);
            resetStats.run();
            s.monitor(() -> {
                try {
                    LabFlight.Craft k = craft[0];
                    if (hold[0]) { Ships.teleport(k.ship, holdPos[0], holdQ[0]); Ships.zeroVelocity(k.ship); }
                    else pilot[0].tick();
                    t[0]++;
                    Pilot q = pilot[0];
                    double y = q.y();
                    Vec3 v = k.vel();
                    st[0] = Math.min(st[0], y); st[1] = Math.max(st[1], y);
                    st[2] = Math.max(st[2], Math.abs(q.guide ? q.offLine() : 0));
                    st[3] = Math.max(st[3], Math.abs(y - q.altTarget));
                    st[4] = Math.max(st[4], k.tilt());
                    st[5] = Math.max(st[5], Math.hypot(v.x, v.z));
                    st[7]++;
                    if (withCitizen && rig.citizen != null && Boarding.state(rig.citizen) == Boarding.State.SEATED) {
                        Vec3 seat = Ships.toWorld(k.ship, Vec3.atCenterOf(k.seat));
                        Vec3 at = rig.citizen.position();
                        st[6] = Math.max(st[6], Math.hypot(at.x - seat.x, at.z - seat.z));
                    }
                    if (t[0] % 10 == 0) s.note(LabFlight.line(k, t[0], p0[0]));
                } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        if (p.getOrDefault("craft", 1.0) >= 2) {
            s.settle(name + ": fill a little", 20);
            s.step(name + ": which lever is forward", () -> { craft[0].drive(0, 1); craft[0].drive(1, 1); });
            s.settle(name + ": drives running while held", 30);
            s.step(name + ": calibrate", () -> {
                craft[0].calibrate();
                craft[0].drive(0, 0);
                craft[0].drive(1, 0);
                s.note("FLY " + name + ": lever signs " + craft[0].sign[0] + " / " + craft[0].sign[1] + ", capacity " + craft[0].capacity());
            });
        }
        s.settle(name + ": fill while held", 400);
        if (withCitizen) {
            s.until(name + ": the citizen sits down", () -> {
                LabFlight.Craft c = craft[0];
                Vec3 on = Ships.toWorld(c.ship, Vec3.atCenterOf(c.seat).add(p.getOrDefault("craft", 1.0) >= 2 ? 1 : 2, 0, 0));
                rig.citizen.setPos(on.x, on.y + 0.01, on.z);
                Boarding.Result r = Boarding.board(rig.citizen, Ships.id(c.ship), c.seat);
                s.note("FLY " + name + ": board order answered " + r);
            }, () -> Boarding.state(rig.citizen) == Boarding.State.SEATED || t[0] > 1400, 800);
            s.step(name + ": seated?", () -> s.note("FLY " + name + ": citizen state before take-off = " + Boarding.state(rig.citizen)));
        }
        // ---- 4-1 climb and hold
        s.until(name + ": climb to the hold height", () -> { hold[0] = false; resetStats.run(); },
                () -> Math.abs(pilot[0].y() - alt) < 0.5 && Math.abs(craft[0].vel().y) < 0.15, 2400);
        s.step(name + ": at height", () -> { s.note(String.format("FLY %s 4-1 climb: reached the hold height %.1f after %d ticks", name, alt, (int) st[7])); resetStats.run(); });
        s.settle(name + ": hold the height", holdTicks);
        s.step(name + ": report 4-1", () -> {
            s.note(String.format("FLY %s 4-1 hold %d ticks: height between %.2f and %.2f (target %.1f): largest error %.2f, swing %.2f; burner lever writes so far %d",
                    name, holdTicks, st[0], st[1], alt, st[3], st[1] - st[0], craft[0].writes));
            resetStats.run();
        });
        // ---- 4-2 straight leg
        int[] legStart = new int[1];
        s.until(name + ": straight leg", () -> { pilot[0].leg(0, 0, dist, 0); legStart[0] = t[0]; resetStats.run(); },
                () -> pilot[0].stopped && Math.hypot(craft[0].vel().x, craft[0].vel().z) < 0.05, (int) (dist * 40) + 1500);
        s.step(name + ": report 4-2", () -> {
            Pilot q = pilot[0];
            s.note(String.format("FLY %s 4-2 leg %.0f blocks: time %d ticks, largest distance from the line %.2f, height error at most %.2f, stopped %.2f from the point (x=%.2f z=%.2f), top speed %.2f blocks/s, largest tilt %.1f deg, seat offset %.3f, lever writes so far %d",
                    name, dist, t[0] - legStart[0], st[2], st[3], q.remaining(), q.x(), q.z(), st[5], st[4], st[6], craft[0].writes));
            if (withCitizen) s.note(String.format("FLY %s citizen after the leg: state=%s alive=%s passenger=%s", name, Boarding.state(rig.citizen), rig.citizen.isAlive(), rig.citizen.isPassenger()));
            resetStats.run();
        });
        // ---- 4-3 turn onto the second leg
        s.until(name + ": second leg after a 90 degree turn", () -> { pilot[0].leg(dist, 0, dist, side); legStart[0] = t[0]; resetStats.run(); },
                () -> pilot[0].stopped && Math.hypot(craft[0].vel().x, craft[0].vel().z) < 0.05, (int) (side * 40) + 1500);
        s.step(name + ": report 4-3", () -> {
            Pilot q = pilot[0];
            s.note(String.format("FLY %s 4-3 turn and %.0f blocks: time %d ticks, largest distance from the new line (the bulge at the corner included) %.2f, height error at most %.2f, stopped %.2f from the point, lever writes so far %d",
                    name, side, t[0] - legStart[0], st[2], st[3], q.remaining(), craft[0].writes));
            resetStats.run();
        });
        // ---- 4-4 descend onto the pad
        double[] touch = new double[3];
        int[] still = new int[1];
        s.until(name + ": descend and land", () -> { legStart[0] = t[0]; resetStats.run(); touch[0] = 0; },
                () -> {
                    Pilot q = pilot[0];
                    // lower the wanted height steadily; the pad's top is 6 below where the deck was built
                    q.altTarget = Math.max(-6.3, q.altTarget - p.getOrDefault("sink", 0.4) / 20.0);
                    double vy = craft[0].vel().y;
                    if (q.y() > -5.7) { touch[0] = vy; touch[1] = t[0]; }   // speed just before reaching the pad, and when
                    still[0] = q.y() < -5.9 ? still[0] + 1 : 0;            // the deck rests on the pad (the speed reading jitters there)
                    return still[0] > 40;
                }, 4000);
        s.step(name + ": report 4-4", () -> {
            Pilot q = pilot[0];
            craft[0].burner(0);
            s.note(String.format("FLY %s 4-4 landing: touched down %d ticks after starting down,", name, (int) touch[1] - legStart[0]));
            s.note(String.format("FLY %s 4-4 landing: time %d ticks, came down at %.2f blocks/s, rests %.2f from the point (x=%.2f z=%.2f), deck %.2f above the pad, tilt %.1f deg, lever writes in total %d",
                    name, t[0] - legStart[0], touch[0], q.remaining(), q.x(), q.z(), q.y() + 6.0, craft[0].tilt(), craft[0].writes));
            if (withCitizen) s.note(String.format("FLY %s citizen after landing: state=%s alive=%s passenger=%s boardingFailures=%d", name, Boarding.state(rig.citizen), rig.citizen.isAlive(), rig.citizen.isPassenger(), Boarding.failures()));
        });
        s.step(name + ": end", () -> {
            s.clearMonitors();
            try { Ships.removeNear(level, Vec3.atCenterOf(origin), 600); } catch (Throwable e) { throw new AssertionError(e); }
        });
    }
}
