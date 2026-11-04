package com.asteain.shiptest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate;
import com.minecolonies.core.entity.pathfinding.navigation.PathingStuckHandler;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Measures how MineColonies citizens walk between the ground and a stopped Sable ship today.
 * Control case (ground to ground) must pass; the ship cases are expected failures (XFAIL) whose manner of failure is recorded.
 */
final class PathingSuite {
    private PathingSuite() {}

    static final int LIMIT_NO_TP = 300, LIMIT_DEFAULT = 1500; // the default stuck handler waits long before it acts
    static final double JUMP = 2.5; // blocks in one tick: not walking

    /** Observation of one walk, ticked once per server tick. */
    static final class Walk {
        final String name; final Mob mob; final BlockPos dest; final boolean noTeleport;
        int ticks; boolean arrived; double maxJump; int maxPathLen; int maxStuck; double minY = 1e9;
        Vec3 last; final Vec3 start; final Set<String> statuses = new LinkedHashSet<>();
        boolean reachedFlag, failedFlag; int teleportTick = -1; int limit = -1; int firstPathTick = -1;
        final StringBuilder trace = new StringBuilder();

        Walk(String name, Mob mob, Vec3 from, BlockPos dest, boolean noTeleport) {
            this.name = name; this.mob = mob; this.dest = dest; this.noTeleport = noTeleport;
            mob.setPos(from);
            mob.setDeltaMovement(Vec3.ZERO);
            this.start = from; this.last = from;
            MinecoloniesAdvancedPathNavigate nav = (MinecoloniesAdvancedPathNavigate) mob.getNavigation();
            nav.stop();
            if (noTeleport) nav.setStuckHandler(PathingStuckHandler.createStuckHandler());
            else nav.setStuckHandler(PathingStuckHandler.createStuckHandler().withTeleportOnFullStuck().withTeleportSteps(5));
        }

        /** @return true when the walk is over (arrived or time is up). */
        boolean tick() {
            ticks++;
            Vec3 now = mob.position();
            double jump = now.distanceTo(last);
            if (jump > maxJump) maxJump = jump;
            if (jump > JUMP && teleportTick < 0) teleportTick = ticks;
            minY = Math.min(minY, now.y);
            if (ticks % 5 == 1 && trace.length() < 3000) trace.append(String.format("[t%d (%.2f,%.2f,%.2f)]", ticks, now.x, now.y, now.z));
            last = now;
            MinecoloniesAdvancedPathNavigate nav = (MinecoloniesAdvancedPathNavigate) mob.getNavigation();
            arrived = EntityNavigationUtils.walkToPos(mob, dest, 1, false, 1.0);
            PathResult<?> r = nav.getPathResult();
            if (r != null) {
                statuses.add(r.getStatus().name());
                if (r.hasPath() && firstPathTick < 0) firstPathTick = ticks;
                if (r.hasPath()) maxPathLen = Math.max(maxPathLen, r.getPathLength());
                reachedFlag |= r.isPathReachingDestination();
                failedFlag |= r.failedToReachDestination();
            }
            maxStuck = Math.max(maxStuck, nav.getStuckHandler() instanceof PathingStuckHandler<?> h ? h.getStuckLevel() : 0);
            return arrived || ticks >= (limit > 0 ? limit : noTeleport ? LIMIT_NO_TP : LIMIT_DEFAULT);
        }

        /** Arrived without a teleport, by really walking (a path of at least 2 points, at least 5 ticks). */
        boolean walkedForReal() { return walkedHonestly() && ticks >= 5 && maxPathLen >= 2; }

        boolean walkedHonestly() { return arrived && teleportTick < 0; }

        String describe() {
            Vec3 e = mob.position();
            return name + (noTeleport ? " [stuck-teleport off]" : " [default stuck handler]")
                    + ": arrived=" + arrived + " ticks=" + ticks + " from=" + fmt(start) + " end=" + fmt(e)
                    + " distToDest=" + String.format("%.2f", e.distanceTo(Vec3.atBottomCenterOf(dest)))
                    + " maxJump/tick=" + String.format("%.2f", maxJump) + (teleportTick >= 0 ? " TELEPORT@tick" + teleportTick : " no-teleport")
                    + " minY=" + String.format("%.2f", minY) + " pathStatuses=" + statuses + " maxPathLen=" + maxPathLen
                    + " pathReachesDest=" + reachedFlag + " pathFailedToReach=" + failedFlag + " maxStuckLevel=" + maxStuck
                    + " firstPathAtTick=" + firstPathTick + " alive=" + mob.isAlive();
        }

        static String fmt(Vec3 v) { return String.format("(%.1f,%.1f,%.1f)", v.x, v.y, v.z); }
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "pathing (ground control + stopped ship: all walks expected to succeed)";
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        // Ground: stone floor x in [-12,12], z in [-8,8] (top block y=origin.y-1, standing y=origin.y).
        // Ship: planks x in [13,17], z in [-1,1] at the same height, assembled into a sub-level flush with the ground edge.
        BlockPos origin = new BlockPos(spawn.getX() + 6144 + 256 * Colony.existing(), 100, spawn.getZ());
        BlockPos shipMin = origin.offset(13, -1, -1), shipMax = origin.offset(17, -1, 1);
        Object[] ship = new Object[1];
        ICitizenData[] data = new ICitizenData[1];
        Mob[] citizen = new Mob[1];
        Walk[] walk = new Walk[1];
        Vec3[] shipRef = new Vec3[1];

        s.step("fixtures", () -> {
            level.setDayTime(1000);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
            level.setWeatherParameters(6000, 0, false, false);
            Colony.platform(level, origin, 12, 8);
            level.setChunkForced((origin.getX() + 15) >> 4, origin.getZ() >> 4, true);
            level.getChunk((origin.getX() + 15) >> 4, origin.getZ() >> 4);
            IColony colony = Colony.create(level, origin, "CPS pathing " + Colony.existing());
            Colony.townHall(colony, origin);
            data[0] = Colony.spawn(colony, level, origin);
        });
        s.until("citizen spawns", () -> {}, () -> (citizen[0] = Colony.entity(data[0])) != null, 100);
        s.step("assemble ship", () -> {
            ship[0] = Ships.assemblePlanks(level, shipMin, shipMax, shipMin);
            s.check(ship[0] != null, "ship assembled");
            s.check(level.getBlockState(shipMin).isAir(), "ship blocks left the ordinary world");
            shipRef[0] = Ships.position(ship[0]);
            // Hold the citizen's own AI back so it does not start its own walks.
            citizen[0].setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        });
        s.settle("ship settles", 40);
        s.step("ship is stopped", () -> {
            Vec3 now = Ships.position(ship[0]);
            Vec3 v = Ships.velocity(ship[0]);
            boolean stopped = now.distanceTo(shipRef[0]) < 0.1 && v.length() < 0.05;
            s.note("OBS ship before walking: pose=" + Walk.fmt(now) + " drift=" + String.format("%.3f", now.distanceTo(shipRef[0])) + " velocity=" + v + " stopped=" + stopped);
            s.check(stopped, "the ship is stopped (zero-gravity data pack works)");
        });

        s.step("geometry", () -> {
            // ground east edge: scan the floor blocks along the walking line
            int lastGroundX = Integer.MIN_VALUE;
            for (int x = origin.getX(); x <= origin.getX() + 20; x++)
                if (!level.getBlockState(new BlockPos(x, origin.getY() - 1, origin.getZ())).isAir()) lastGroundX = x;
            double groundEastFace = lastGroundX + 1.0;
            double groundTop = origin.getY(); // floor blocks occupy y = origin.y-1, top face at origin.y
            double minX = 1e18, maxX = -1e18, maxTop = -1e18, minTop = 1e18;
            for (int dx : new int[]{13, 17}) for (int dz : new int[]{-1, 1}) {
                BlockPos orig = origin.offset(dx, -1, dz);
                Vec3 plot = Ships.toPlot(ship[0], Vec3.atCenterOf(orig));
                Vec3 w = Ships.toWorld(ship[0], Vec3.atCenterOf(BlockPos.containing(plot)));
                minX = Math.min(minX, w.x - 0.5); maxX = Math.max(maxX, w.x + 0.5);
                maxTop = Math.max(maxTop, w.y + 0.5); minTop = Math.min(minTop, w.y + 0.5);
            }
            double gap = minX - groundEastFace, dh = maxTop - groundTop;
            s.note(String.format("GEOMETRY ground east face x=%.4f (last floor block x=%d), ground top y=%.4f | deck world west face x=%.4f east face x=%.4f, "
                            + "deck top y=%.4f..%.4f | gap ground->deck = %.4f blocks, deck top minus ground top = %.4f blocks",
                    groundEastFace, lastGroundX, groundTop, minX, maxX, minTop, maxTop, gap, dh));
            s.check(Math.abs(gap) < 0.01 && Math.abs(maxTop - groundTop) < 0.01 && Math.abs(minTop - groundTop) < 0.01,
                    "ground and ship deck are adjacent (gap " + gap + ") and at the same height (diff " + dh + ")");
        });
        // case list: control, then the two ship cases, each with the default and the no-teleport stuck handler
        record Case(String name, Vec3 from, BlockPos dest, boolean noTeleport, boolean control) {}
        Vec3 groundFrom = new Vec3(origin.getX() - 8 + 0.5, origin.getY(), origin.getZ() + 0.5);
        BlockPos groundDest = origin.offset(4, 0, 0);
        Vec3 shipFrom = new Vec3(origin.getX() + 15 + 0.5, origin.getY(), origin.getZ() + 0.5);
        BlockPos shipDest = origin.offset(15, 0, 0);
        BlockPos shipEdge = origin.offset(10, 0, 0);
        Vec3 inShipFrom = new Vec3(origin.getX() + 13 + 0.5, origin.getY(), origin.getZ() + 0.5);
        BlockPos inShipDest = origin.offset(16, 0, 0);
        BlockPos bridgeDest = origin.offset(25, 0, 0);
        java.util.List<Case> cases = java.util.List.of(
                new Case("control ground->ground", groundFrom, groundDest, true, true),
                new Case("ship->ground", shipFrom, origin.offset(-4, 0, 0), false, false),
                new Case("ship->ground", shipFrom, origin.offset(-4, 0, 0), true, false),
                new Case("ground->ship", groundFrom, shipDest, false, false),
                new Case("ground->ship", groundFrom, shipDest, true, false),
                new Case("bridge: ground A -> ship -> ground B", groundFrom, bridgeDest, true, false),
                new Case("inside ship: two points on the deck", inShipFrom, inShipDest, true, false));
        for (Case c : cases) {
            if (c.name.startsWith("bridge")) s.step("ground B", () -> {
                Colony.platform(level, origin.offset(30, 0, 0), 12, 8); // floor x = origin+18..origin+42: touches the ship's east face
                level.setChunkForced((origin.getX() + 30) >> 4, origin.getZ() >> 4, true);
                double deckEast = -1e18;
                for (int dz : new int[]{-1, 1}) {
                    Vec3 plot = Ships.toPlot(ship[0], Vec3.atCenterOf(origin.offset(17, -1, dz)));
                    deckEast = Math.max(deckEast, Ships.toWorld(ship[0], Vec3.atCenterOf(BlockPos.containing(plot))).x + 0.5);
                }
                int firstB = Integer.MAX_VALUE;
                for (int x = origin.getX() + 14; x <= origin.getX() + 45; x++)
                    if (!level.getBlockState(new BlockPos(x, origin.getY() - 1, origin.getZ())).isAir()) { firstB = x; break; }
                s.note(String.format("GEOMETRY ground B west face x=%d | deck east face x=%.4f | gap deck->B = %.4f blocks", firstB, deckEast, firstB - deckEast));
                s.check(Math.abs(firstB - deckEast) < 0.01, "ground B touches the ship's east face");
            });
            s.until("walk " + c.name + (c.noTeleport ? " (no-tp)" : ""), () -> {
                citizen[0].setPos(c.from);
                // keep MineColonies' own citizen AI from starting walks of its own during the observation
                citizen[0].getClass().getMethod("getCitizenAI").invoke(citizen[0]);
                Object ai = citizen[0].getClass().getMethod("getCitizenAI").invoke(citizen[0]);
                try { ai.getClass().getMethod("setCurrentDelay", int.class).invoke(ai, 5000); }
                catch (java.lang.reflect.InvocationTargetException ignored) { /* AI has not run a transition yet */ }
                walk[0] = new Walk(c.name, citizen[0], c.from, c.dest, c.noTeleport);
            }, () -> walk[0].tick(), LIMIT_DEFAULT + 20);
            s.step("record " + c.name, () -> {
                Walk w = walk[0];
                Vec3 pose = Ships.position(ship[0]);
                String line = w.describe() + " | shipDrift=" + String.format("%.2f", pose.distanceTo(shipRef[0]));
                if (c.control) {
                    s.note("OBS " + line);
                    s.check(w.walkedHonestly(), "control walk on plain ground arrives without teleporting | " + line);
                } else {
                    s.soft(c.name + (c.noTeleport ? " (no-tp)" : ""), w.walkedHonestly(), line);
                }
            });
        }
        // Off-grid ship: yawed 20 degrees and shifted 0.3 block, set by code under zero gravity, citizen standing on it, walking to the ground.
        BlockPos o2 = origin.offset(0, 0, 40);
        Object[] ship2 = new Object[1];
        s.step("off-grid ship: build", () -> {
            Colony.platform(level, o2, 12, 8);
            BlockPos min2 = o2.offset(13, -1, -1), max2 = o2.offset(17, -1, 1);
            level.setChunkForced((o2.getX() + 15) >> 4, o2.getZ() >> 4, true);
            level.getChunk((o2.getX() + 15) >> 4, o2.getZ() >> 4);
            ship2[0] = Ships.assemblePlanks(level, min2, max2, min2);
            Vec3 pos = Ships.position(ship2[0]);
            org.joml.Quaterniond q = Ships.orientation(ship2[0]).rotateY(Math.toRadians(20));
            Ships.teleport(ship2[0], new org.joml.Vector3d(pos.x + 0.3, pos.y, pos.z + 0.3), q);
        });
        s.settle("off-grid ship: settle", 20);
        s.step("off-grid ship: pose", () -> {
            org.joml.Quaterniond q = Ships.orientation(ship2[0]);
            Vec3 v = Ships.velocity(ship2[0]);
            s.note(String.format("OBS off-grid ship: pos=%s yawDev=%.3f deg tilt=%.4f deg speed=%.5f", Ships.position(ship2[0]), PoseRecorder.yawDev(q), PoseRecorder.tilt(q), v.length()));
        });
        s.until("walk off-grid ship->ground (no-tp)", () -> {
            Vec3 deckCentre = Ships.toWorld(ship2[0], Vec3.atCenterOf(BlockPos.containing(Ships.toPlot(ship2[0], Vec3.atCenterOf(o2.offset(15, -1, 0))))));
            Vec3 from = new Vec3(deckCentre.x, o2.getY(), deckCentre.z);
            citizen[0].setPos(from);
            Object ai = citizen[0].getClass().getMethod("getCitizenAI").invoke(citizen[0]);
            try { ai.getClass().getMethod("setCurrentDelay", int.class).invoke(ai, 5000); } catch (java.lang.reflect.InvocationTargetException ignored) { }
            walk[0] = new Walk("off-grid ship (yaw 20, shift 0.3) -> ground", citizen[0], from, o2.offset(-4, 0, 0), true);
        }, () -> walk[0].tick(), LIMIT_DEFAULT + 20);
        s.step("record off-grid ship", () -> {
            Walk w = walk[0];
            s.soft("off-grid ship->ground (no-tp)", w.walkedHonestly(), w.describe());
        });
        s.onFinish(() -> {
            try { if (ship2[0] != null && !Ships.isRemoved(ship2[0])) Ships.remove(level, ship2[0]); }
            catch (Throwable t) { throw new AssertionError("could not remove off-grid ship", t); }
        });
        s.onFinish(() -> {
            try { if (ship[0] != null && !Ships.isRemoved(ship[0])) Ships.remove(level, ship[0]); }
            catch (Throwable t) { throw new AssertionError("could not remove test ship", t); }
        });
        return s;
    }
}
