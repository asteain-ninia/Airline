package com.asteain.shiptest;

import com.asteain.airline.boarding.Boarding;
import com.asteain.airline.pathing.PathingHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Stage 2 lab, third round: one round trip between two stone pads with the recommended aircraft, with everything the
 * next stage needs in one scene. The aircraft starts landed and locked on pad A; a citizen walks on board and sits;
 * unlock, climb, fly a route with a corner, land on pad B, lock; the citizen walks off, waits, walks on again; and the
 * same way back. While it flies the ship carries Sable's loading ticket; pad B lies outside the area this test keeps
 * loaded, and is only kept loaded by the test once the ship has landed and locked there (as a port would).
 *
 * The autopilot writes lever values only. Locking and the loading ticket are called directly: later that is the job of
 * the aircraft's own block.
 */
final class LabTrip {
    private LabTrip() {}

    private static final class Trip {
        LabFlight.Craft craft;
        LabAutopilot.Pilot pilot;
        Vec3 p0;
        Object lockHandler, container, ticketType;
        boolean flying;
        int t, mark;
        double citizenMinY = 1e9, seatOffsetMax, tiltMax;
        boolean citizenDied;
        boolean align;
        int still;
        /** Holding a heading: tick at which the push now running ends (0 = none), pushes so far, largest error seen while going down. */
        int pushEnd, pushes;
        double headingErrorMax;
        Map<String, Double> p;
    }

    /**
     * Turns the ship toward a heading and keeps it there, with the two drives against each other. Called every tick;
     * decides every 'turnEvery' ticks (10). One decision is one push: its length is in proportion to how far off the
     * heading will be once the turning has died away (heading now + turning speed x 'turnCoast' seconds), from 2 ticks
     * up to the whole interval. At most one push per interval: Create breaks parts that are switched on and off too often.
     */
    private static void holdHeading(Trip k, double target) {
        if (k.pushEnd > 0 && k.t >= k.pushEnd) {
            k.craft.drive(0, 0);
            k.craft.drive(1, 0);
            k.pushEnd = 0;
        }
        int every = (int) (double) k.p.getOrDefault("turnEvery", 10.0);
        if (k.t % every != 0) return;
        double ahead = wrap(target - (k.craft.yaw() + k.craft.yawRate() * k.p.getOrDefault("turnCoast", 1.5)));
        int length = (int) Math.round(Math.abs(ahead) * k.p.getOrDefault("turnGain", 1.1));
        if (Math.abs(ahead) < k.p.getOrDefault("turnDead", 1.0) || length < 1) {
            k.craft.drive(0, 0);
            k.craft.drive(1, 0);
            k.pushEnd = 0;
            return;
        }
        length = Math.max(2, Math.min(every, length));
        int turn = ahead > 0 ? 1 : -1;
        k.craft.drive(0, turn);
        k.craft.drive(1, -turn);
        k.pushes++;
        k.pushEnd = length >= every ? 0 : k.t + length;      // a push as long as the interval simply runs on into the next decision
    }

    static void trip(Script s, MinecraftServer server, ServerLevel level, BlockPos origin, Map<String, Double> p) {
        p.putIfAbsent("craft", 2.0);
        p.putIfAbsent("gear", 2.0);
        p.putIfAbsent("value", 80.0);
        p.putIfAbsent("hover", 10.5);
        p.putIfAbsent("period", 10.0);
        p.putIfAbsent("coast", 1.4);
        p.putIfAbsent("lead", 1.0);
        p.putIfAbsent("small", 5.0);
        p.putIfAbsent("big", 30.0);
        int legX = (int) (double) p.getOrDefault("legX", 80.0), legZ = (int) (double) p.getOrDefault("legZ", 70.0);
        double alt = p.getOrDefault("alt", 5.0);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS lab trip", 12, 8);
        BlockPos deckA = origin.offset(5, 0, 0);
        BlockPos padB = deckA.offset(legX, -1, legZ);
        BlockPos groundA = origin.offset(-6, 0, 0);
        Trip k = new Trip();
        k.align = p.getOrDefault("align", 0.0) > 0;
        k.p = p;
        // The heading in which the ship has to rest on each pad (a multiple of 90 degrees); none when not given.
        double headA = p.getOrDefault("headA", Double.NaN), headB = p.getOrDefault("headB", Double.NaN);
        boolean twice = p.getOrDefault("twice", 0.0) > 0;

        s.step("trip: build on pad A, lock", () -> {
            // pad B: 21 x 21 stone, placed without asking for its chunks to stay loaded
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) level.setBlockAndUpdate(padB.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            k.craft = LabFlight.buildChosen(level, deckA, p);
            for (String part : k.craft.parts) s.note("PART " + part);
            k.p0 = k.craft.pos();
            k.pilot = new LabAutopilot.Pilot(k.craft, k.p0, (int) (double) p.get("period"), p);
            k.lockHandler = Class.forName("dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler").getMethod("get", ServerLevel.class).invoke(null, level);
            k.container = Class.forName("dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder").getMethod("sable$getPlotContainer").invoke(level);
            Class<?> type = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType");
            k.ticketType = type.getField("COMMAND_FORCED").get(null);
            lock(k, true);
            k.craft.burner(15);
            k.craft.drive(0, 1);
            k.craft.drive(1, 1);
            s.note(String.format("TRIP built on pad A: mass=%.2f, pad B centre is %d blocks east and %d blocks south of pad A; pad B chunk kept loaded by the test = %s",
                    LabFlight.mass(k.craft.ship), legX, legZ, level.getForcedChunks().contains(new ChunkPos(padB).toLong())));
            s.monitor(() -> {
                try {
                    k.t++;
                    if (Ships.isRemoved(k.craft.ship)) return;
                    if (k.flying) k.pilot.tick();
                    k.tiltMax = Math.max(k.tiltMax, k.craft.tilt());
                    if (rig.citizen != null) {
                        k.citizenMinY = Math.min(k.citizenMinY, rig.citizen.getY());
                        k.citizenDied |= !rig.citizen.isAlive();
                        if (Boarding.state(rig.citizen) == Boarding.State.SEATED) {
                            Vec3 seat = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat));
                            Vec3 at = rig.citizen.position();
                            k.seatOffsetMax = Math.max(k.seatOffsetMax, Math.hypot(at.x - seat.x, at.z - seat.z));
                        }
                    }
                    if (k.t % 20 == 0) {
                        s.note(LabFlight.line(k.craft, k.t, k.p0));
                        if (rig.citizen != null) {
                            Vec3 at = rig.citizen.position();
                            s.note(String.format("CIT t=%d x=%.2f y=%.2f z=%.2f state=%s passenger=%s", k.t, at.x - k.p0.x, at.y - origin.getY(), at.z - k.p0.z, Boarding.state(rig.citizen), rig.citizen.isPassenger()));
                        }
                    }
                } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        s.settle("trip: drives running while locked", 30);
        s.step("trip: calibrate", () -> {
            k.craft.calibrate();
            k.craft.drive(0, 0);
            k.craft.drive(1, 0);
            s.note("TRIP lever signs " + k.craft.sign[0] + " / " + k.craft.sign[1]);
        });
        s.settle("trip: fill while locked", 260);
        s.step("trip: burner to the hover setting", () -> {
            k.craft.burner(10);
            s.note(String.format("TRIP on pad A before boarding: gas=%.1f of %.0f, moved %.4f since it was built, tilt %.2f deg, seen as stopped by the path finding = %s",
                    k.craft.gas(), k.craft.capacity(), k.craft.pos().distanceTo(k.p0), k.craft.tilt(), PathingHooks.isStopped(level, Ships.id(k.craft.ship))));
        });

        board(s, rig, k, "A (first boarding)", () -> Rig.standing(groundA), origin);
        flight(s, level, k, "A to B", new double[][] {{0, 0, legX, 0}, {legX, 0, legX, legZ}}, alt, padB.offset(0, 1, 0), true, headB);
        leave(s, rig, k, "B", origin);
        s.settle("trip: ten seconds on pad B", 200);
        board(s, rig, k, "B (boarding again)", () -> null, origin);
        if (twice) {
            // one more time off and on at B, to have more boardings in one trip
            leave(s, rig, k, "B (second time)", origin);
            s.settle("trip: a moment on pad B", 40);
            board(s, rig, k, "B (third boarding)", () -> null, origin);
        }
        flight(s, level, k, "B to A", new double[][] {{legX, legZ, legX, 0}, {legX, 0, 0, 0}}, alt, deckA, false, headA);
        leave(s, rig, k, "A", origin);
        if (twice) {
            s.settle("trip: a moment on pad A", 40);
            board(s, rig, k, "A (last boarding)", () -> null, origin);
            leave(s, rig, k, "A (second time)", origin);
        }

        s.step("trip: summary", () -> {
            s.note(String.format("TRIP summary: boarding failures %d, citizen alive=%s died at some point=%s lowest citizen height %.2f above the pads' top, largest seat offset %.3f, largest tilt of the ship %.1f deg, lever writes %d, total ticks %d",
                    Boarding.failures(), rig.citizen.isAlive(), k.citizenDied, k.citizenMinY - origin.getY(), k.seatOffsetMax, k.tiltMax, k.craft.writes, k.t));
            s.clearMonitors();
            try { Ships.removeNear(level, Vec3.atCenterOf(origin), -1); } catch (Throwable e) { throw new AssertionError(e); }
        });
    }

    /**
     * A citizen that only stands on the deck (not seated) while the recommended aircraft takes off, flies 60 blocks and
     * stops: does it stay on the deck?
     */
    static void stand(Script s, MinecraftServer server, ServerLevel level, BlockPos origin, Map<String, Double> p) {
        p.putIfAbsent("craft", 2.0);
        p.putIfAbsent("gear", 2.0);
        p.putIfAbsent("value", 80.0);
        p.putIfAbsent("hover", 10.5);
        p.putIfAbsent("period", 10.0);
        p.putIfAbsent("coast", 1.4);
        p.putIfAbsent("lead", 1.0);
        p.putIfAbsent("small", 5.0);
        p.putIfAbsent("big", 30.0);
        double alt = p.getOrDefault("alt", 5.0), dist = p.getOrDefault("dist", 60.0);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS lab stand", 12, 8);
        BlockPos deckA = origin.offset(5, 0, 0);
        Trip k = new Trip();
        Vec3[] spot = new Vec3[1];
        double[] st = new double[4];      // 0 largest drift on the deck, 1 lowest height above the deck top, 2 ticks on the ship, 3 ticks seen
        s.step("stand: build on pad A, lock", () -> {
            Rig.force(level, origin.offset(40, 0, 0), 80, 40);
            k.craft = LabFlight.buildChosen(level, deckA, p);
            k.p0 = k.craft.pos();
            k.pilot = new LabAutopilot.Pilot(k.craft, k.p0, (int) (double) p.get("period"), p);
            k.lockHandler = Class.forName("dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler").getMethod("get", ServerLevel.class).invoke(null, level);
            lock(k, true);
            k.craft.burner(15);
            k.craft.drive(0, 1);
            k.craft.drive(1, 1);
            s.monitor(() -> {
                try {
                    k.t++;
                    if (k.flying) k.pilot.tick();
                    if (k.flying) k.tiltMax = Math.max(k.tiltMax, k.craft.tilt());
                    if (spot[0] != null) {
                        Vec3 in = Ships.toPlot(k.craft.ship, rig.citizen.position());
                        st[0] = Math.max(st[0], Math.hypot(in.x - spot[0].x, in.z - spot[0].z));
                        st[1] = Math.min(st[1], in.y - spot[0].y);
                        st[3]++;
                        if (onShip(rig, k)) st[2]++;
                    }
                    if (k.t % 20 == 0) {
                        s.note(LabFlight.line(k.craft, k.t, k.p0));
                        Vec3 in = Ships.toPlot(k.craft.ship, rig.citizen.position());
                        if (spot[0] != null) s.note(String.format("CIT t=%d onDeck dx=%.2f dy=%.2f dz=%.2f alive=%s", k.t, in.x - spot[0].x, in.y - spot[0].y, in.z - spot[0].z, rig.citizen.isAlive()));
                    }
                } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        s.settle("stand: drives running while locked", 30);
        s.step("stand: calibrate", () -> { k.craft.calibrate(); k.craft.drive(0, 0); k.craft.drive(1, 0); });
        s.settle("stand: fill while locked", 260);
        s.step("stand: citizen onto the deck", () -> {
            k.craft.burner(10);
            Vec3 on = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat).add(1, 0, 0));
            rig.citizen.setPos(on.x, on.y - 0.45, on.z);
            rig.citizen.getNavigation().stop();
            rig.hold();
        });
        s.settle("stand: citizen settles", 10);
        s.until("stand: unlock and climb", () -> {
            spot[0] = Ships.toPlot(k.craft.ship, rig.citizen.position());
            st[1] = 1e9;
            k.mark = k.t;
            lock(k, false);
            k.pilot.altTarget = alt;
            k.flying = true;
        }, () -> Math.abs(k.pilot.y() - alt) < 0.5 && Math.abs(k.craft.vel().y) < 0.2, 2400);
        s.step("stand: after the climb", () -> s.note(String.format("STAND after the climb (%d ticks): drift on the deck %.2f blocks, lowest %.2f relative to where it stood, on the ship in %d of %d ticks, alive=%s",
                k.t - k.mark, st[0], st[1], (int) st[2], (int) st[3], rig.citizen.isAlive())));
        s.until("stand: fly", () -> { k.pilot.leg(0, 0, dist, 0); k.mark = k.t; },
                () -> k.pilot.stopped && Math.hypot(k.craft.vel().x, k.craft.vel().z) < 0.05, (int) (dist * 40) + 1500);
        s.step("stand: report", () -> {
            Vec3 in = Ships.toPlot(k.craft.ship, rig.citizen.position());
            s.note(String.format("STAND after %.0f blocks (%d ticks, largest tilt %.1f deg): largest drift on the deck %.2f blocks, now (%.2f, %.2f, %.2f) from where it stood, lowest %.2f, on the ship in %d of %d ticks, alive=%s",
                    dist, k.t - k.mark, k.tiltMax, st[0], in.x - spot[0].x, in.y - spot[0].y, in.z - spot[0].z, st[1], (int) st[2], (int) st[3], rig.citizen.isAlive()));
            s.clearMonitors();
            try { Ships.removeNear(level, Vec3.atCenterOf(origin), -1); } catch (Throwable e) { throw new AssertionError(e); }
        });
    }

    /**
     * The landed, locked aircraft on pad A, turned to several headings: (1) an ordinary MineColonies walk from the ground
     * to the middle of the deck and back, (2) boarding from the ground, with a record of where the citizen stood in the
     * tick before it sat down (on the deck or still on the pad).
     */
    static void deckwalk(Script s, MinecraftServer server, ServerLevel level, BlockPos origin, Map<String, Double> p) {
        p.putIfAbsent("craft", 2.0);
        p.putIfAbsent("gear", 2.0);
        p.putIfAbsent("value", 80.0);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS lab deckwalk", 12, 8);
        BlockPos deckA = origin.offset(5, 0, 0);
        BlockPos groundA = origin.offset(-6, 0, 0);
        Trip k = new Trip();
        org.joml.Quaterniond[] q0 = new org.joml.Quaterniond[1];
        double[] last = new double[4];      // 0 height above the pad, 1 on this ship (1/0), 2 flat distance to the seat, 3 highest point while walking
        s.step("deckwalk: build on pad A, lock", () -> {
            k.craft = LabFlight.buildChosen(level, deckA, p);
            k.p0 = k.craft.pos();
            q0[0] = k.craft.q();
            k.lockHandler = Class.forName("dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler").getMethod("get", ServerLevel.class).invoke(null, level);
            lock(k, true);
            s.monitor(() -> {
                try {
                    k.t++;
                    if (rig.citizen == null) return;
                    k.citizenMinY = Math.min(k.citizenMinY, rig.citizen.getY());
                    if (Boarding.state(rig.citizen) == Boarding.State.WALKING) {
                        Vec3 at = rig.citizen.position();
                        Vec3 seat = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat));
                        last[0] = at.y - origin.getY();
                        last[1] = onShip(rig, k) ? 1 : 0;
                        last[2] = Math.hypot(at.x - seat.x, at.z - seat.z);
                        last[3] = Math.max(last[3], last[0]);
                    }
                } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        for (double yaw : new double[] {0, 12, 30, 45}) {
            if (yaw < p.getOrDefault("from", 0.0)) continue;
            String name = "deckwalk yaw " + (int) yaw;
            s.step(name + ": turn the landed ship", () -> {
                lock(k, false);
                Ships.teleport(k.craft.ship, new org.joml.Vector3d(k.p0.x, k.p0.y, k.p0.z), new org.joml.Quaterniond(q0[0]).rotateY(Math.toRadians(yaw)));
                Ships.zeroVelocity(k.craft.ship);
                lock(k, true);
            });
            s.settle(name + ": locked, waiting to count as stopped", 45);
            s.step(name + ": state", () -> s.note(String.format("DECK %s: heading %.1f deg, tilt %.2f deg, seen as stopped by the path finding=%s", name, k.craft.yaw(), k.craft.tilt(), PathingHooks.isStopped(level, Ships.id(k.craft.ship)))));
            // (1) ordinary walks
            rig.walkLive2(s, name + " / ground -> middle of the deck", () -> Rig.standing(groundA),
                    () -> { try { return BlockPos.containing(Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat).add(1, 0, 0))); } catch (Exception e) { throw new AssertionError(e); } }, true, 300, t -> {},
                    w -> {
                        Vec3 at = w.mob.position();
                        s.note(String.format("DECK %s walk ground -> deck: arrived=%s in %d ticks, ends %.2f above the pad's top, standing on the ship=%s, pathLen=%d reachesDest=%s maxJump/tick=%.2f | %s",
                                name, w.arrived, w.ticks, at.y - origin.getY(), onShip(rig, k), w.maxPathLen, w.reachedFlag, w.maxJump, w.teleportTick >= 0 ? "TELEPORT" : "no teleport"));
                    });
            rig.walkLive2(s, name + " / deck -> ground", () -> null, () -> groundA, true, 300, t -> {},
                    w -> {
                        Vec3 at = w.mob.position();
                        s.note(String.format("DECK %s walk deck -> ground: arrived=%s in %d ticks, ends %.2f above the pad's top, lowest %.2f, pathLen=%d | %s",
                                name, w.arrived, w.ticks, at.y - origin.getY(), w.minY - origin.getY(), w.maxPathLen, w.teleportTick >= 0 ? "TELEPORT" : "no teleport"));
                    });
            // (2) boarding from the ground
            s.until(name + ": board from the ground", () -> {
                rig.citizen.setPos(Rig.standing(groundA));
                rig.citizen.getNavigation().stop();
                last[3] = 0;
                k.mark = k.t;
                s.note("DECK " + name + " board: order answered " + Boarding.board(rig.citizen, Ships.id(k.craft.ship), k.craft.seat));
            }, () -> Boarding.state(rig.citizen) != Boarding.State.WALKING || k.t - k.mark > 500, 600);
            s.step(name + ": board result", () -> s.note(String.format("DECK %s board: state %s after %d ticks; in the tick before sitting the citizen was %.2f above the pad's top, standing on the ship=%s, %.2f blocks (flat) from the seat; highest point while walking %.2f",
                    name, Boarding.state(rig.citizen), k.t - k.mark, last[0], last[1] > 0, last[2], last[3])));
            s.until(name + ": leave to the ground", () -> {
                k.mark = k.t;
                Boarding.Result r = Boarding.leave(rig.citizen, groundA);
                Vec3 in = Ships.toPlot(k.craft.ship, rig.citizen.position());
                s.note(String.format("DECK %s leave: order answered %s; right after it the citizen stands %.2f above the pad's top, at (%.2f, %.2f, %.2f) from the seat block's middle in the ship's own directions",
                        name, r, rig.citizen.getY() - origin.getY(), in.x - (k.craft.seat.getX() + 0.5), in.y - (k.craft.seat.getY() + 0.5), in.z - (k.craft.seat.getZ() + 0.5)));
            }, () -> Boarding.state(rig.citizen) == Boarding.State.NONE || k.t - k.mark > 500, 600);
            s.step(name + ": leave result", () -> {
                Vec3 at = rig.citizen.position();
                s.note(String.format("DECK %s leave: state %s after %d ticks, %.2f blocks from the ground spot, %.2f above the pad's top", name, Boarding.state(rig.citizen), k.t - k.mark,
                        Math.hypot(groundA.getX() + 0.5 - at.x, groundA.getZ() + 0.5 - at.z), at.y - origin.getY()));
            });
        }
        s.step("deckwalk: end", () -> {
            s.note(String.format("DECK summary: boarding failures %d, citizen alive=%s, lowest citizen height %.2f above the pad's top", Boarding.failures(), rig.citizen.isAlive(), k.citizenMinY - origin.getY()));
            s.clearMonitors();
            try { Ships.removeNear(level, Vec3.atCenterOf(origin), -1); } catch (Throwable e) { throw new AssertionError(e); }
        });
    }

    private static void lock(Trip k, boolean on) throws Exception {
        boolean locked = (boolean) LabKit.call(k.lockHandler, "isLocked", k.craft.ship);
        if (locked != on) LabKit.call(k.lockHandler, "toggleLock", Ships.id(k.craft.ship));
    }

    private static void ticket(Trip k, boolean on) {
        LabKit.call(k.container, on ? "addForceLoadTicket" : "removeForceLoadTicket", k.craft.ship, k.ticketType, net.minecraft.util.Unit.INSTANCE);
    }

    /** The citizen walks to the seat. from = null: from where it stands. If it cannot, that is recorded and it is put on the deck so the trip can go on. */
    private static void board(Script s, Rig rig, Trip k, String where, java.util.function.Supplier<Vec3> from, BlockPos origin) {
        boolean[] walked = new boolean[1];
        Vec3[] startAt = new Vec3[1];
        s.until("trip: board at " + where, () -> {
            Vec3 f = from.get();
            if (f != null) { rig.citizen.setPos(f); rig.citizen.getNavigation().stop(); }
            startAt[0] = rig.citizen.position();
            k.mark = k.t;
            Vec3 seat = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat));
            Boarding.Result r = Boarding.board(rig.citizen, Ships.id(k.craft.ship), k.craft.seat);
            s.note(String.format("TRIP board at %s: order answered %s; the citizen is %.1f blocks from the seat, the seat top is %.2f above its feet; ship seen as stopped = %s",
                    where, r, Math.hypot(startAt[0].x - seat.x, startAt[0].z - seat.z), seat.y + 0.5 - startAt[0].y, PathingHooks.isStopped(rig.level, Ships.id(k.craft.ship))));
        }, () -> {
            walked[0] = Boarding.state(rig.citizen) == Boarding.State.SEATED;
            return walked[0] || Boarding.state(rig.citizen) == Boarding.State.NONE || k.t - k.mark > 700;
        }, 800);
        s.until("trip: board at " + where + " (result)", () -> {
            Vec3 at = rig.citizen.position();
            Vec3 seat = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat));
            if (walked[0]) {
                s.note(String.format("TRIP board at %s: WALKED ON AND SAT after %d ticks (walked from %.1f blocks away)", where, k.t - k.mark, Math.hypot(startAt[0].x - seat.x, startAt[0].z - seat.z)));
            } else {
                s.note(String.format("TRIP board at %s: DID NOT GET TO THE SEAT in %d ticks: state=%s, citizen %.2f blocks from the seat (flat), %.2f below the seat top, standing on this ship=%s. It is now put on the deck by the test.",
                        where, k.t - k.mark, Boarding.state(rig.citizen), Math.hypot(at.x - seat.x, at.z - seat.z), seat.y + 0.5 - at.y, onShip(rig, k)));
                Vec3 on = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat).add(1, 0, 0));
                rig.citizen.setPos(on.x, on.y - 0.45, on.z);
                rig.citizen.getNavigation().stop();
                s.note("TRIP board at " + where + ": second order answered " + Boarding.board(rig.citizen, Ships.id(k.craft.ship), k.craft.seat));
            }
        }, () -> Boarding.state(rig.citizen) == Boarding.State.SEATED || k.t - k.mark > 1100, 500);
        s.step("trip: board at " + where + " (seated?)", () -> s.note("TRIP board at " + where + ": state now " + Boarding.state(rig.citizen)));
    }

    private static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d > 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private static boolean onShip(Rig rig, Trip k) {
        try {
            Object tracking = Class.forName("dev.ryanhcode.sable.Sable").getField("HELPER").get(null);
            Object sub = LabKit.call(tracking, "getTrackingSubLevel", rig.citizen);
            return sub == k.craft.ship;
        } catch (Exception | AssertionError e) {
            return false;
        }
    }

    /** Unlock, ticket on, climb, fly the legs, descend onto the pad, lock, ticket off. */
    private static void flight(Script s, ServerLevel level, Trip k, String name, double[][] legs, double alt, BlockPos padTopCentre, boolean keepLoadedAfterLanding, double landHeading) {
        int[] start = new int[1];
        boolean heading = !Double.isNaN(landHeading);
        s.until("trip " + name + ": unlock and climb", () -> {
            start[0] = k.t;
            k.mark = k.t;
            ticket(k, true);
            lock(k, false);
            k.pilot.altTarget = alt;
            k.pilot.integral = 0;
            k.pilot.guide = false;
            k.flying = true;
        }, () -> Math.abs(k.pilot.y() - alt) < 0.5 && Math.abs(k.craft.vel().y) < 0.2, 2400);
        s.step("trip " + name + ": at height", () -> { s.note(String.format("TRIP %s: climbed to %.1f in %d ticks", name, alt, k.t - k.mark)); k.mark = k.t; });
        for (int i = 0; i < legs.length; i++) {
            double[] leg = legs[i];
            int n = i + 1;
            double[] off = new double[2];
            s.until("trip " + name + ": leg " + n, () -> { k.pilot.leg(leg[0], leg[1], leg[2], leg[3]); k.mark = k.t; off[0] = 0; off[1] = 0; },
                    () -> {
                        off[0] = Math.max(off[0], Math.abs(k.pilot.offLine()));
                        off[1] = Math.max(off[1], Math.abs(k.pilot.y() - alt));
                        return k.pilot.stopped && Math.hypot(k.craft.vel().x, k.craft.vel().z) < 0.05;
                    }, (int) (Math.hypot(leg[2] - leg[0], leg[3] - leg[1]) * 40) + 1500);
            s.step("trip " + name + ": leg " + n + " done", () -> s.note(String.format("TRIP %s leg %d (%.0f blocks): %d ticks, largest distance from the line %.2f, height error at most %.2f, stopped %.2f from the point; ship chunk loaded=%s",
                    name, n, Math.hypot(leg[2] - leg[0], leg[3] - leg[1]), k.t - k.mark, off[0], off[1], k.pilot.remaining(), level.hasChunk(BlockPos.containing(k.craft.pos()).getX() >> 4, BlockPos.containing(k.craft.pos()).getZ() >> 4))));
        }
        if (k.align) {
            // Over the pad: turn on the spot to the nearest quarter turn, so that the landed ship lies along the world's block grid.
            double[] target = new double[1];
            s.until("trip " + name + ": turn to the block grid", () -> {
                k.pilot.guide = false;
                k.mark = k.t;
                target[0] = 90.0 * Math.round(k.craft.yaw() / 90.0);
            }, () -> {
                double yaw = k.craft.yaw(), rate = k.craft.yawRate();
                double error = wrap(target[0] - yaw);
                if (k.t % k.pilot.period == 0) {
                    double ahead = wrap(target[0] - (yaw + rate * 1.5));
                    int turn = Math.abs(ahead) > 3.0 ? (ahead > 0 ? 1 : -1) : 0;
                    k.craft.drive(0, turn);
                    k.craft.drive(1, -turn);
                }
                return (Math.abs(error) < 4.0 && Math.abs(rate) < 0.5) || k.t - k.mark > 900;
            }, 1000);
            s.step("trip " + name + ": turned", () -> {
                k.craft.drive(0, 0);
                k.craft.drive(1, 0);
                s.note(String.format("TRIP %s: turned to the block grid in %d ticks: heading %.1f deg (wanted %.0f), %.2f from the point", name, k.t - k.mark, k.craft.yaw(), target[0], k.pilot.remaining()));
            });
        }
        if (heading) {
            // Over the pad: turn on the spot to the heading this pad asks for. The descent starts as soon as the heading is
            // close; it is held all the way down.
            s.until("trip " + name + ": turn to the pad's heading", () -> {
                k.pilot.guide = false;
                k.mark = k.t;
                k.pushes = 0;
                k.pushEnd = 0;
                s.note(String.format("TRIP %s: arrived over the pad with heading %.1f deg; the pad asks for %.0f", name, k.craft.yaw(), landHeading));
            }, () -> {
                holdHeading(k, landHeading);
                return (Math.abs(wrap(landHeading - k.craft.yaw())) < 2.0 && Math.abs(k.craft.yawRate()) < 2.0) || k.t - k.mark > 700;
            }, 800);
            s.step("trip " + name + ": turned", () -> s.note(String.format("TRIP %s: turned to the pad's heading in %d ticks (%d pushes): heading %.1f deg, off by %.1f, turning at %.2f deg/s, %.2f from the point",
                    name, k.t - k.mark, k.pushes, k.craft.yaw(), wrap(landHeading - k.craft.yaw()), k.craft.yawRate(), k.pilot.remaining())));
        }
        double[] touch = new double[1];
        s.until("trip " + name + ": descend and land", () -> { k.mark = k.t; k.still = 0; k.pushes = 0; k.headingErrorMax = 0; },
                () -> {
                    k.pilot.altTarget = Math.max(-0.4, k.pilot.altTarget - 0.4 / 20.0);
                    if (k.pilot.y() > 0.3) touch[0] = k.craft.vel().y;
                    k.still = k.pilot.y() < 0.12 ? k.still + 1 : 0;
                    if (heading) {
                        if (k.still == 0) {
                            holdHeading(k, landHeading);
                            k.headingErrorMax = Math.max(k.headingErrorMax, Math.abs(wrap(landHeading - k.craft.yaw())));
                        } else if (k.still == 1) {
                            // touching the pad: no more pushes
                            k.craft.drive(0, 0);
                            k.craft.drive(1, 0);
                            k.pushEnd = 0;
                        }
                    }
                    return k.still > 40;
                }, 3000);
        s.step("trip " + name + ": landed, lock", () -> {
            k.flying = false;
            k.craft.drive(0, 0);
            k.craft.drive(1, 0);
            Vec3 at = k.craft.pos();
            boolean loadedByTest = level.getForcedChunks().contains(new ChunkPos(BlockPos.containing(at)).toLong());
            lock(k, true);
            k.craft.burner(10);
            if (keepLoadedAfterLanding) Rig.force(level, padTopCentre, 24, 24);
            ticket(k, false);
            Vec3 deckCentre = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat).add(1, -1, 0));
            s.note(String.format("TRIP %s landed: %d ticks from starting down (touching at %.2f blocks/s), whole flight %d ticks; rests %.2f from the point; resting height %.3f above the height it had on pad A; deck top %.3f above the pad's top; tilt %.2f deg; heading %.1f deg; the place was kept loaded by the test before landing = %s",
                    name, k.t - k.mark, touch[0], k.t - start[0], k.pilot.remaining(), k.pilot.y(), deckCentre.y + 0.5 - padTopCentre.getY(), k.craft.tilt(), k.craft.yaw(), loadedByTest));
            if (heading) s.note(String.format("TRIP %s landed: HEADING OFF BY %.2f deg from the pad's %.0f (largest while going down %.2f; %d pushes while going down)",
                    name, wrap(landHeading - k.craft.yaw()), landHeading, k.headingErrorMax, k.pushes));
        });
        s.settle("trip " + name + ": locked, waiting to count as stopped", 40);
        s.step("trip " + name + ": after locking", () -> s.note(String.format("TRIP %s after locking: locked=%s, seen as stopped by the path finding=%s, tilt %.2f deg, citizen state %s",
                name, LabKit.call(k.lockHandler, "isLocked", k.craft.ship), PathingHooks.isStopped(level, Ships.id(k.craft.ship)), k.craft.tilt(), "see CIT lines")));
    }

    /** The citizen stands up and walks to a spot on the pad, four blocks behind the seat. */
    private static void leave(Script s, Rig rig, Trip k, String where, BlockPos origin) {
        BlockPos[] ground = new BlockPos[1];
        boolean[] stood = new boolean[1];
        s.until("trip: leave at " + where, () -> {
            Vec3 spot = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat).add(-5, 0, 1));
            ground[0] = new BlockPos((int) Math.floor(spot.x), origin.getY(), (int) Math.floor(spot.z));
            k.mark = k.t;
            Vec3 seatAt = Ships.toWorld(k.craft.ship, Vec3.atCenterOf(k.craft.seat));
            Boarding.Result r = Boarding.leave(rig.citizen, ground[0]);
            stood[0] = !rig.citizen.isPassenger();
            Vec3 in = Ships.toPlot(k.craft.ship, rig.citizen.position());
            s.note(String.format("TRIP leave at %s: order answered %s, stood up at once = %s, ground spot %.1f blocks from the seat; it stands %.2f above the pad's top, at (%.2f, %.2f, %.2f) from the seat block's middle in the ship's own directions",
                    where, r, stood[0], Math.hypot(ground[0].getX() + 0.5 - seatAt.x, ground[0].getZ() + 0.5 - seatAt.z), rig.citizen.getY() - origin.getY(),
                    in.x - (k.craft.seat.getX() + 0.5), in.y - (k.craft.seat.getY() + 0.5), in.z - (k.craft.seat.getZ() + 0.5)));
        }, () -> Boarding.state(rig.citizen) == Boarding.State.NONE || k.t - k.mark > 700, 800);
        s.step("trip: leave at " + where + " (result)", () -> {
            Vec3 at = rig.citizen.position();
            double d = Math.hypot(ground[0].getX() + 0.5 - at.x, ground[0].getZ() + 0.5 - at.z);
            boolean ok = d < 2.0 && Math.abs(at.y - origin.getY()) < 0.6 && rig.citizen.isAlive() && !rig.citizen.isPassenger();
            s.note(String.format("TRIP leave at %s: %s after %d ticks: %.2f blocks from the ground spot, height %.2f above the pad's top, state=%s, alive=%s",
                    where, ok ? "WALKED OFF TO THE GROUND" : "DID NOT REACH THE GROUND SPOT", k.t - k.mark, d, at.y - origin.getY(), Boarding.state(rig.citizen), rig.citizen.isAlive()));
        });
    }
}
