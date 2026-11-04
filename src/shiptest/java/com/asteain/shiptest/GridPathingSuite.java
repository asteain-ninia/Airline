package com.asteain.shiptest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Walking between the ground and stopped ships whose pose is NOT on the block grid (values measured from the real world:
 * yaw 45/22/6/0 deg, height offset +0.28/-0.45, tilt 19 deg). Zero gravity, pose set by code. Ground edge and ship always touch
 * (the ship's western-most corner is moved onto the ground's east edge line). Every walk is an expected failure today (XFAIL).
 */
final class GridPathingSuite {
    private GridPathingSuite() {}

    private static final int LIMIT = 450;

    private record Config(String name, double yaw, double dy, double tilt) {}

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "grid pathing (off-grid ships, zero gravity; tilted ship stays unused)";
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos origin = new BlockPos(spawn.getX() + 12288 + 256 * Colony.existing(), 100, spawn.getZ());
        ICitizenData[] data = new ICitizenData[1];
        Mob[] citizen = new Mob[1];
        PathingSuite.Walk[] walk = new PathingSuite.Walk[1];
        List<Config> configs = List.of(
                new Config("yaw 0", 0, 0, 0), new Config("yaw 6", 6, 0, 0), new Config("yaw 22", 22, 0, 0), new Config("yaw 45", 45, 0, 0),
                new Config("height +0.28", 0, 0.28, 0), new Config("height -0.45", 0, -0.45, 0),
                new Config("yaw 45 + height -0.45", 45, -0.45, 0), new Config("tilt 19", 0, 0, 19));

        s.step("colony and citizen", () -> {
            level.setDayTime(1000);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
            level.setWeatherParameters(6000, 0, false, false);
            Colony.platform(level, origin, 12, 8);
            IColony colony = Colony.create(level, origin, "CPS grid " + Colony.existing());
            Colony.townHall(colony, origin);
            data[0] = Colony.spawn(colony, level, origin);
        });
        s.until("citizen spawns", () -> {}, () -> (citizen[0] = Colony.entity(data[0])) != null, 100);

        for (int i = 0; i < configs.size(); i++) addConfig(s, level, origin.offset(0, 0, 40 * (i + 1)), configs.get(i), citizen, walk);
        return s;
    }

    private static void addConfig(Script s, ServerLevel level, BlockPos site, Config c, Mob[] citizen, PathingSuite.Walk[] walk) {
        Object[] ship = new Object[1];
        List<Vec3>[] deckCentres = new List[1];
        double[] edge = new double[1], deckTop = new double[1];
        Vec3[] onDeck = new Vec3[1];
        s.step(c.name() + ": build ship", () -> {
            Colony.platform(level, site, 12, 8);                              // floor x in [site-12, site+12], top face y = site.y
            level.setChunkForced((site.getX() + 15) >> 4, site.getZ() >> 4, true);
            level.getChunk((site.getX() + 15) >> 4, site.getZ() >> 4);
            BlockPos min = site.offset(13, -1, -1), max = site.offset(17, -1, 1);
            ship[0] = Ships.assemblePlanks(level, min, max, min);
            List<Vec3> plotCentres = new ArrayList<>();
            for (BlockPos raw : BlockPos.betweenClosed(min, max))
                plotCentres.add(Vec3.atCenterOf(BlockPos.containing(Ships.toPlot(ship[0], Vec3.atCenterOf(raw)))));
            deckCentres[0] = plotCentres;
            Vec3 pos = Ships.position(ship[0]);
            Quaterniond q = Ships.orientation(ship[0]).rotateY(Math.toRadians(c.yaw())).rotateZ(Math.toRadians(c.tilt()));
            Ships.teleport(ship[0], new Vector3d(pos.x, pos.y, pos.z), q);
        });
        s.settle(c.name() + ": rotate", 2);
        s.step(c.name() + ": touch the ground edge", () -> {
            // western-most point of the rotated deck (any block corner), moved onto the ground's east edge line x = site.x + 13
            double minX = 1e18;
            for (Vec3 pc : deckCentres[0])
                for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) for (int sz : new int[]{-1, 1})
                    minX = Math.min(minX, Ships.toWorld(ship[0], pc.add(0.5 * sx, 0.5 * sy, 0.5 * sz)).x);
            double groundEdge = site.getX() + 13.0;
            Vec3 pos = Ships.position(ship[0]);
            Ships.teleport(ship[0], new Vector3d(pos.x + (groundEdge - minX), pos.y + c.dy(), pos.z), Ships.orientation(ship[0]));
        });
        s.step(c.name() + ": hold pose", () -> {
            // Contact with the ground edge nudges a free body, so the test holds the pose each tick (teleport + cancel velocity): a stopped ship.
            Vec3 hp = Ships.position(ship[0]);
            Vector3d holdPos = new Vector3d(hp.x, hp.y, hp.z);
            Quaterniond holdQ = Ships.orientation(ship[0]);
            s.monitor(() -> {
                try { Ships.teleport(ship[0], holdPos, holdQ); Ships.zeroVelocity(ship[0]); } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        s.settle(c.name() + ": settle", 10);
        s.step(c.name() + ": measure", () -> {
            double minX = 1e18, maxTop = -1e18;
            for (Vec3 pc : deckCentres[0])
                for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
                    Vec3 w = Ships.toWorld(ship[0], pc.add(0.5 * sx, 0.5 * sy, 0.5 * sz));
                    minX = Math.min(minX, w.x);
                    if (sy == 1) maxTop = Math.max(maxTop, w.y);
                }
            Vec3 centre = Ships.toWorld(ship[0], deckCentres[0].get(deckCentres[0].size() / 2).add(0, 0.5, 0));
            onDeck[0] = centre;
            edge[0] = minX;
            org.joml.Quaterniond q = Ships.orientation(ship[0]);
            Vec3 v = Ships.velocity(ship[0]);
            s.note(String.format("OBS grid %s: deck west-most x=%.4f (ground edge x=%.1f, gap %.4f) deck highest top y=%.4f (ground top %.1f) deck-centre top=(%.3f,%.3f,%.3f) yawDev=%.2f tilt=%.2f speed=%.5f",
                    c.name(), minX, site.getX() + 13.0, minX - (site.getX() + 13.0), maxTop, (double) site.getY(), centre.x, centre.y, centre.z,
                    PoseRecorder.yawDev(q), PoseRecorder.tilt(q), v.length()));
        });
        // ship -> ground
        s.until(c.name() + ": walk ship->ground", () -> {
            Vec3 from = onDeck[0].add(0, 0.02, 0);
            citizen[0].setPos(from);
            hold(citizen[0]);
            walk[0] = new PathingSuite.Walk("grid[" + c.name() + "] ship->ground", citizen[0], from, site.offset(-4, 0, 0), true);
            walk[0].limit = LIMIT;
        }, () -> walk[0].tick(), LIMIT + 20);
        s.step(c.name() + ": record ship->ground", () -> expect(s, c, "ship->ground", walk[0]));
        // ground -> ship (destination: the block cell containing the deck centre top)
        s.until(c.name() + ": walk ground->ship", () -> {
            Vec3 from = new Vec3(site.getX() - 8 + 0.5, site.getY(), site.getZ() + 0.5);
            citizen[0].setPos(from);
            hold(citizen[0]);
            BlockPos dest = BlockPos.containing(onDeck[0].x, onDeck[0].y - 0.1, onDeck[0].z).above();
            walk[0] = new PathingSuite.Walk("grid[" + c.name() + "] ground->ship", citizen[0], from, dest, true);
            walk[0].limit = LIMIT;
        }, () -> walk[0].tick(), LIMIT + 20);
        s.step(c.name() + ": record ground->ship", () -> expect(s, c, "ground->ship", walk[0]));
        s.step(c.name() + ": remove ship", () -> { s.clearMonitors(); try { Ships.remove(level, ship[0]); } catch (Throwable t) { throw new AssertionError(t); } });
    }

    private static void expect(Script s, Config c, String dir, PathingSuite.Walk w) {
        String name = "grid[" + c.name() + "] " + dir;
        if (c.tilt() > 5) s.softNot(name + " (tilted ship is not used; no teleport, no exception)", w.arrived || w.teleportTick >= 0, w.describe());
        else s.soft(name, w.walkedHonestly(), w.describe());
    }

    private static void hold(Mob citizen) throws Exception {
        Object ai = citizen.getClass().getMethod("getCitizenAI").invoke(citizen);
        try { ai.getClass().getMethod("setCurrentDelay", int.class).invoke(ai, 5000); } catch (java.lang.reflect.InvocationTargetException ignored) { }
    }
}
