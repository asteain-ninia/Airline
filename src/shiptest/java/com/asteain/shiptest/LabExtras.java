package com.asteain.shiptest;

import com.asteain.airline.boarding.Boarding;
import com.asteain.airline.pathing.PathingHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;

/** Stage 2 lab: the wall (test 2), loading with a ticket and a seated citizen (test 1-8), and the lock (test 3-1, 3-2). */
final class LabExtras {
    private LabExtras() {}

    /** Shared start: build the aircraft, fill its balloon while held, then let the pilot keep the height. */
    private static final class Flight {
        LabFlight.Craft craft;
        LabAutopilot.Pilot pilot;
        Vec3 p0;
        Vector3d holdPos;
        Quaterniond holdQ;
        boolean hold = true;
        int t;
    }

    private static Flight start(Script s, ServerLevel level, BlockPos origin, Map<String, Double> p, String name, int forceX, int forceRadius) {
        Flight f = new Flight();
        BlockPos deck = origin.offset(0, 30, 0);
        s.step(name + ": build", () -> {
            Rig.force(level, origin.offset(forceX, 0, 0), forceRadius, forceRadius);
            f.craft = LabFlight.build(level, deck, 60);
            f.craft.forwardSign = -1;
            f.p0 = f.craft.pos();
            f.holdPos = new Vector3d(f.p0.x, f.p0.y, f.p0.z);
            f.holdQ = f.craft.q();
            f.pilot = new LabAutopilot.Pilot(f.craft, f.p0, (int) (double) p.getOrDefault("period", 10.0), p);
            f.pilot.altTarget = p.getOrDefault("alt", 2.0);
            f.craft.burner(10);
            f.craft.drive(0, 0);
            f.craft.drive(1, 0);
            s.monitor(() -> {
                try {
                    if (f.hold) { Ships.teleport(f.craft.ship, f.holdPos, f.holdQ); Ships.zeroVelocity(f.craft.ship); }
                    else if (!Ships.isRemoved(f.craft.ship)) f.pilot.tick();
                    f.t++;
                    if (f.t % 20 == 0 && !Ships.isRemoved(f.craft.ship)) s.note(LabFlight.line(f.craft, f.t, f.p0));
                } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        s.settle(name + ": fill while held", 400);
        return f;
    }

    private static void seat(Script s, Rig rig, Flight f, String name) {
        s.until(name + ": the citizen sits down", () -> {
            Vec3 on = Ships.toWorld(f.craft.ship, Vec3.atCenterOf(f.craft.seat).add(2, 0, 0));
            rig.citizen.setPos(on.x, on.y + 0.01, on.z);
            s.note(name + ": board order answered " + Boarding.board(rig.citizen, Ships.id(f.craft.ship), f.craft.seat));
        }, () -> Boarding.state(rig.citizen) == Boarding.State.SEATED, 600);
    }

    /** Test 2: fly into a wall of world blocks under propeller thrust; after ten seconds the wall is taken away. */
    static void wall(Script s, MinecraftServer server, ServerLevel level, BlockPos origin, Map<String, Double> p) {
        String name = "wall";
        boolean withCitizen = p.getOrDefault("citizen", 0.0) > 0;
        Rig rig = withCitizen ? new Rig(server, origin) : null;
        if (withCitizen) rig.addColony(s, "CPS lab wall", 12, 8);
        Flight f = start(s, level, origin, p, name, 16, 64);
        int wallX = 14;
        double[] st = new double[6];     // 0 max x, 1 x when first stopped, 2 min vx after touching, 3 tick of touch, 4 tick it moved on, 5 max tilt
        if (withCitizen) seat(s, rig, f, name);
        s.step(name + ": wall and go", () -> {
            for (int y = 24; y <= 44; y++) for (int z = -8; z <= 8; z++) level.setBlockAndUpdate(origin.offset(wallX, y, z), Blocks.STONE.defaultBlockState());
            f.hold = false;
            f.pilot.leg(0, 0, 60, 0);
            st[0] = -1e9; st[2] = 1e9; st[3] = -1;
            s.monitor(() -> {
                Vec3 v = f.craft.vel();
                double x = f.pilot.x();
                st[0] = Math.max(st[0], x);
                st[5] = Math.max(st[5], f.craft.tilt());
                if (st[3] < 0 && x > 5 && Math.abs(v.x) < 0.05) { st[3] = f.t; st[1] = x; }
                if (st[3] >= 0) st[2] = Math.min(st[2], v.x);
            });
        });
        s.until(name + ": reaches the wall", () -> {}, () -> st[3] >= 0, 900);
        s.settle(name + ": pressed against the wall for ten seconds", 200);
        s.step(name + ": report 2-1", () -> {
            s.note(String.format("WALL 2-1: the wall's near face is %.1f ahead of the start; the ship's centre stopped at x=%.2f (first still at tick %d), went at most to x=%.2f, lowest speed after touching %.3f blocks/s, largest tilt %.1f deg, now x=%.2f speed=%.3f",
                    (double) wallX - 0.5, st[1], (int) st[3], st[0], st[2], st[5], f.pilot.x(), f.craft.vel().x));
            if (withCitizen) s.note(String.format("WALL 2-3: citizen state=%s alive=%s passenger=%s", Boarding.state(rig.citizen), rig.citizen.isAlive(), rig.citizen.isPassenger()));
            for (int y = 24; y <= 44; y++) for (int z = -8; z <= 8; z++) level.setBlockAndUpdate(origin.offset(wallX, y, z), Blocks.AIR.defaultBlockState());
            st[4] = -1;
            int removedAt = f.t;
            s.monitor(() -> { if (st[4] < 0 && f.craft.vel().x > 0.3) st[4] = f.t - removedAt; });
        });
        s.settle(name + ": after the wall is gone", 300);
        s.step(name + ": report 2-2", () -> {
            s.note(String.format("WALL 2-2: after the wall was removed the ship %s; now x=%.2f speed=%.3f",
                    st[4] >= 0 ? "moved on again after " + (int) st[4] + " ticks" : "did NOT move on within 300 ticks", f.pilot.x(), f.craft.vel().x));
            if (withCitizen) s.note(String.format("WALL 2-3: citizen at the end state=%s alive=%s passenger=%s", Boarding.state(rig.citizen), rig.citizen.isAlive(), rig.citizen.isPassenger()));
            s.clearMonitors();
            try { Ships.removeNear(level, Vec3.atCenterOf(origin), 600); } catch (Throwable e) { throw new AssertionError(e); }
        });
    }

    /**
     * Test 1-8: the ship flies out of the area this test keeps loaded (two chunks around the start), with or without
     * Sable's own loading ticket, and with a citizen seated on it.
     */
    static void load(Script s, MinecraftServer server, ServerLevel level, BlockPos origin, Map<String, Double> p) {
        boolean ticket = p.getOrDefault("ticket", 1.0) > 0;
        String name = "load(" + (ticket ? "with ticket" : "no ticket") + ")";
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS lab load", 12, 8);
        Flight f = start(s, level, origin, p, name, 0, 40);
        seat(s, rig, f, name);
        double[] st = new double[4];   // 0 max seat offset, 1 last x seen, 2 tick the ship vanished, 3 last citizen x
        s.step(name + ": go", () -> {
            if (ticket) {
                Class<?> type = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType");
                Object commandForced = null;
                for (java.lang.reflect.Field field : type.getDeclaredFields())
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == type) { field.setAccessible(true); commandForced = field.get(null); s.note(name + ": ticket type field " + field.getName()); break; }
                Object container = Class.forName("dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder").getMethod("sable$getPlotContainer").invoke(level);
                LabKit.call(container, "addForceLoadTicket", f.craft.ship, commandForced, net.minecraft.util.Unit.INSTANCE);
            }
            f.hold = false;
            f.pilot.leg(0, 0, 400, 0);
            st[2] = -1;
            s.monitor(() -> {
                try {
                    if (Ships.isRemoved(f.craft.ship)) { if (st[2] < 0) st[2] = f.t; return; }
                    st[1] = f.pilot.x();
                    if (rig.citizen.isAlive() && !rig.citizen.isRemoved()) {
                        Vec3 seatAt = Ships.toWorld(f.craft.ship, Vec3.atCenterOf(f.craft.seat));
                        Vec3 at = rig.citizen.position();
                        st[3] = at.x - f.p0.x;
                        if (Boarding.state(rig.citizen) == Boarding.State.SEATED) st[0] = Math.max(st[0], Math.hypot(at.x - seatAt.x, at.z - seatAt.z));
                    }
                } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        int ticks = (int) (double) p.getOrDefault("ticks", 2600.0);
        for (int part = 1; part <= 4; part++) {
            int k = part;
            s.settle(name + ": flying, part " + part, ticks / 4);
            s.step(name + ": report part " + part, () -> {
                boolean gone = Ships.isRemoved(f.craft.ship);
                Vec3 at = rig.citizen.position();
                ChunkPos shipChunk = new ChunkPos(BlockPos.containing(f.p0.x + st[1], f.p0.y, f.p0.z));
                s.note(String.format("LOAD %s part %d: ship %s, last ship x=%.1f (the kept area ends at x=40; chunk of the ship loaded=%s), citizen entity present=%s removedReason=%s alive=%s state=%s passenger=%s citizen x=%.1f y=%.1f, largest seat offset %.3f, boarding failures %d",
                        name, k, gone ? "REMOVED from the ship list at tick " + (int) st[2] : "present", st[1], level.hasChunk(shipChunk.x, shipChunk.z),
                        Colony.entity(rig.data) == rig.citizen, rig.citizen.getRemovalReason(), rig.citizen.isAlive(), Boarding.state(rig.citizen), rig.citizen.isPassenger(),
                        at.x - f.p0.x, at.y, st[0], Boarding.failures()));
            });
        }
        s.step(name + ": end", () -> {
            s.clearMonitors();
            try { Ships.removeNear(level, Vec3.atCenterOf(origin), -1); } catch (Throwable e) { throw new AssertionError(e); }
        });
    }

    /** Test 3-1 / 3-2: Simulated's lock (a fixed constraint to the world) on a ship with no lift, under gravity. */
    static void lock(Script s, ServerLevel level, BlockPos origin, Map<String, Double> p) {
        String name = "lock";
        Flight f = start(s, level, origin, p, name, 0, 48);
        double[] st = new double[3];
        Object[] handler = new Object[1];
        s.step(name + ": lock, burner off, let go", () -> {
            f.craft.burner(0);
            f.pilot.altTarget = -1000;      // the pilot keeps the burner off
            Class<?> type = Class.forName("dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler");
            handler[0] = type.getMethod("get", ServerLevel.class).invoke(null, level);
            LabKit.call(handler[0], "toggleLock", Ships.id(f.craft.ship));
            s.note(name + ": isLocked=" + LabKit.call(handler[0], "isLocked", f.craft.ship));
            f.hold = false;
            Vec3 at = f.craft.pos();
            s.monitor(() -> { Vec3 now = f.craft.pos(); st[0] = Math.max(st[0], now.distanceTo(at)); });
        });
        s.settle(name + ": locked, gas running out", 700);
        s.step(name + ": report 3-1 / 3-2", () -> {
            s.note(String.format("LOCK 3-1: locked for 700 ticks with the burner off (gas now %.2f): moved at most %.4f blocks, speed now %.4f, tilt %.2f deg", f.craft.gas(), st[0], f.craft.vel().length(), f.craft.tilt()));
            s.note("LOCK 3-2: Airline sees the locked ship as stopped = " + PathingHooks.isStopped(level, Ships.id(f.craft.ship)));
            LabKit.call(handler[0], "toggleLock", Ships.id(f.craft.ship));
            st[1] = f.craft.pos().y;
        });
        s.settle(name + ": unlocked", 40);
        s.step(name + ": after unlocking", () -> {
            s.note(String.format("LOCK 3-1: 40 ticks after unlocking the ship has dropped %.2f blocks, vertical speed %.2f", st[1] - f.craft.pos().y, f.craft.vel().y));
            s.clearMonitors();
            try { Ships.removeNear(level, Vec3.atCenterOf(origin), -1); } catch (Throwable e) { throw new AssertionError(e); }
        });
    }
}
