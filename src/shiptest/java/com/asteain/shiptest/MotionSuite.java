package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Ships that start moving while citizens walk or stand on them (zero gravity; the ship is pushed sideways along the ground edge so it stays in contact).
 * Expected: no teleport, no fall, citizens stop using the stale path; once the ship has stopped again they can walk to the ground.
 */
final class MotionSuite {
    private MotionSuite() {}

    /** Watches a citizen every tick: biggest per-tick jump, lowest and highest y, and its offset relative to the ship. */
    private static final class Obs {
        final PathingSuite.Walk unused = null;
        final net.minecraft.world.entity.Mob mob; final Object ship;
        Vec3 last; double maxJump, minY = 1e9; int ticks, pausedWhileMoving, movingTicks; Vec3 prevRel, prevShip; Vec3 relStart, relNow, shipStart; boolean alive = true;
        Obs(net.minecraft.world.entity.Mob mob, Object ship) throws Exception {
            this.mob = mob; this.ship = ship; last = mob.position(); shipStart = Ships.position(ship);
            relStart = mob.position().subtract(shipStart);
        }
        void tick() throws Exception {
            ticks++;
            Vec3 p = mob.position();
            maxJump = Math.max(maxJump, p.distanceTo(last));
            minY = Math.min(minY, p.y);
            last = p;
            alive &= mob.isAlive();
            Vec3 rel = p.subtract(Ships.position(ship));
            Vec3 shipNow = Ships.position(ship);
            boolean shipMoving = prevShip != null && shipNow.distanceTo(prevShip) > 0.004;   // by position, not by the speed reading
            prevShip = shipNow;
            if (shipMoving) {
                movingTicks++;
                if (prevRel != null && Math.hypot(rel.x - prevRel.x, rel.z - prevRel.z) < 0.02) pausedWhileMoving++;
            }
            prevRel = rel;
            relNow = rel;
        }
        String describe(double deckTopY) throws Exception {
            Vec3 shipNow = Ships.position(ship);
            return String.format("movingTicks=%d pausedWhileShipMoving=%d ticks=%d maxJump/tick=%.3f minY=%.3f (deck top %.2f) alive=%s shipMoved=%.2f citizenOffsetFromShip start=(%.2f,%.2f,%.2f) end=(%.2f,%.2f,%.2f) citizenEnd=(%.2f,%.2f,%.2f)",
                    movingTicks, pausedWhileMoving, ticks, maxJump, minY, deckTopY, alive, shipNow.distanceTo(shipStart), relStart.x, relStart.y, relStart.z, relNow.x, relNow.y, relNow.z, last.x, last.y, last.z);
        }
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "motion (ships that move under citizens, zero gravity)";
        ServerLevel level = server.overworld();
        BlockPos origin = Rig.site(level, 20480);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS motion", 12, 8);
        m1(s, rig, origin.offset(0, 0, 60));
        m2(s, rig, origin.offset(0, 0, 120));
        m3(s, rig, origin.offset(0, 0, 180));
        return s;
    }

    private static Object deckShip(Rig rig, BlockPos site, int len) throws Exception {
        Colony.platform(rig.level, site, 12, 8);
        Rig.force(rig.level, site.offset(14, 0, 0), 20, 16);
        List<BlockPos> b = new ArrayList<>();
        for (int x = 13; x < 13 + len; x++) for (int z = -2; z <= 2; z++) {
            BlockPos p = site.offset(x, -1, z);
            rig.level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
            b.add(p);
        }
        return rig.assemble(b);
    }

    private static void stopAndWalkOff(Script s, Rig rig, String name, BlockPos site, Object[] ship, int stillTicks) {
        s.step(name + ": ship stops", () -> Ships.zeroVelocity(ship[0]));
        s.settle(name + ": wait for 'stopped' (more than 20 ticks)", stillTicks);
        s.step(name + ": ship speed after stop", () -> s.note("OBS " + name + ": ship speed after stop = " + Ships.velocity(ship[0]).length() + ", pose " + Ships.position(ship[0])));
        rig.walkLive(s, name + " / walk to the ground after the ship stopped", () -> null, site.offset(-4, 0, 0), true, 500, t -> {},
                w -> s.soft(name + " / after the ship stopped again, the citizen can walk to the ground", w.walkedHonestly(), w.describe()));
    }

    /** M1: the citizen walks along the deck; 12 ticks into the walk the ship is pushed away sideways. */
    private static void m1(Script s, Rig rig, BlockPos site) {
        String name = "M1 ship starts moving during a walk";
        Object[] ship = new Object[1];
        Obs[] obs = new Obs[1];
        s.step(name + ": build", () -> ship[0] = deckShip(rig, site, 24));
        s.settle(name + ": settle", 60);
        s.step(name + ": watch", () -> {
            rig.citizen.setPos(Rig.standing(site.offset(13, 0, 0)));
            obs[0] = new Obs(rig.citizen, ship[0]);
            s.monitor(() -> { try { obs[0].tick(); } catch (Exception e) { throw new AssertionError(e); } });
        });
        rig.walkLive(s, name + " / deck walk 21 blocks, push at tick 12", () -> Rig.standing(site.offset(13, 0, 0)), site.offset(34, 0, 0), true, 220,
                t -> { if (t == 12) { try { Ships.impulse(ship[0], 0, 0, 12); } catch (Exception e) { throw new AssertionError(e); } } },
                w -> {
                    try {
                        s.clearMonitors();
                        double top = site.getY();
                        boolean steady = obs[0].minY > top - 0.6 && obs[0].maxJump < PathingSuite.JUMP && obs[0].alive;
                        boolean stopped = obs[0].pausedWhileMoving >= 3;
                        s.soft(name + " / no fall, no teleport, alive", steady, obs[0].describe(top) + " | " + w.describe());
                        s.note("OBS " + name + ": arrived=" + w.arrived + " (after the ship stopped again a new path may be followed) shipSpeed=" + Ships.velocity(ship[0]).length());
                        s.soft(name + " / while the ship moves the citizen stops walking along the old path (at least 3 ticks without walking relative to the ship)", stopped, "pausedWhileShipMoving=" + obs[0].pausedWhileMoving + " of " + obs[0].movingTicks + " moving ticks");
                    } catch (Exception e) { throw new AssertionError(e); }
                });
        stopAndWalkOff(s, rig, name, site, ship, 70);
        s.step(name + ": remove", () -> rig.removeShipSafely(ship[0], site.offset(-8, 0, 0)));
    }

    /** M2: the citizen stands still on the deck while the ship leaves (1.2 blocks/s for 3 s), then stops. */
    private static void m2(Script s, Rig rig, BlockPos site) {
        String name = "M2 ship departs with a standing citizen";
        Object[] ship = new Object[1];
        Obs[] obs = new Obs[1];
        s.step(name + ": build", () -> ship[0] = deckShip(rig, site, 10));
        s.settle(name + ": settle", 60);
        s.step(name + ": place citizen and push", () -> {
            rig.citizen.setPos(Rig.standing(site.offset(16, 0, 0)));
            rig.hold();
            obs[0] = new Obs(rig.citizen, ship[0]);
            s.monitor(() -> { try { obs[0].tick(); } catch (Exception e) { throw new AssertionError(e); } });
            Ships.impulse(ship[0], 0, 0, 12);
        });
        s.settle(name + ": departing", 60);
        s.step(name + ": report departure", () -> {
            s.clearMonitors();
            double top = site.getY();
            s.soft(name + " / no teleport, no fall, alive while departing", obs[0].minY > top - 0.6 && obs[0].maxJump < PathingSuite.JUMP && obs[0].alive, obs[0].describe(top));
        });
        stopAndWalkOff(s, rig, name, site, ship, 70);
        s.step(name + ": remove", () -> rig.removeShipSafely(ship[0], site.offset(-8, 0, 0)));
    }

    /** M3: the citizen is walking toward the ground when the ship departs. */
    private static void m3(Script s, Rig rig, BlockPos site) {
        String name = "M3 ship departs under a walking citizen";
        Object[] ship = new Object[1];
        Obs[] obs = new Obs[1];
        s.step(name + ": build", () -> ship[0] = deckShip(rig, site, 10));
        s.settle(name + ": settle", 60);
        s.step(name + ": watch", () -> {
            rig.citizen.setPos(Rig.standing(site.offset(21, 0, 0)));
            obs[0] = new Obs(rig.citizen, ship[0]);
            s.monitor(() -> { try { obs[0].tick(); } catch (Exception e) { throw new AssertionError(e); } });
        });
        rig.walkLive(s, name + " / walk toward the ground (9 blocks away), push at tick 8", () -> Rig.standing(site.offset(21, 0, 0)), site.offset(-4, 0, 0), true, 120,
                t -> { if (t == 8) { try { Ships.impulse(ship[0], 0, 0, 12); } catch (Exception e) { throw new AssertionError(e); } } },
                w -> {
                    try {
                        s.clearMonitors();
                        double top = site.getY();
                        s.soft(name + " / no teleport, no fall, alive", obs[0].minY > top - 0.6 && obs[0].maxJump < PathingSuite.JUMP && obs[0].alive, obs[0].describe(top) + " | " + w.describe());
                        s.note("OBS " + name + ": citizen reached the ground destination before/while the ship left = " + w.arrived);
                    } catch (Exception e) { throw new AssertionError(e); }
                });
        stopAndWalkOff(s, rig, name, site, ship, 70);
        s.step(name + ": remove", () -> rig.removeShipSafely(ship[0], site.offset(-8, 0, 0)));
    }
}
