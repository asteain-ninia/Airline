package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Real gravity: a ship is released a little above a flat stone platform and lands on it. After it settles we measure how far
 * the ship sits from the block grid. Drop styles: straight, yawed, shifted sideways, tilted, and a mix.
 */
final class LandingSuite {
    private LandingSuite() {}

    private static final int SETTLE = 200, WINDOW = 100;

    private record Drop(String name, double dx, double dz, double yawDeg, double pitchDeg, double rollDeg) {}

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "landing (gravity " + (Boolean.getBoolean("shiptest.gravity") ? "on" : "OFF - wrong run") + ")";
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int idx = (int) ((System.currentTimeMillis() / 1000L) % 2000L);
        BlockPos base = new BlockPos(spawn.getX() + 8192 + 256 * (idx % 40), 100, spawn.getZ() + 256 * (idx / 40));
        s.note("OBS landing site base=" + base);
        List<Drop> drops = List.of(
                new Drop("straight drop (aligned, 2 blocks)", 0, 0, 0, 0, 0),
                new Drop("yawed 20 deg", 0, 0, 20, 0, 0),
                new Drop("yawed 45 deg", 0, 0, 45, 0, 0),
                new Drop("shifted 0.3/0.37 blocks sideways", 0.3, 0.37, 0, 0, 0),
                new Drop("tilted 6 deg (pitch) and 4 deg (roll)", 0, 0, 0, 6, 4),
                new Drop("mixed: yaw 33, shift 0.5/0.2, pitch 5", 0.5, 0.2, 33, 5, 0));
        for (int i = 0; i < drops.size(); i++) addDrop(s, level, base.offset(0, 0, 60 * i), drops.get(i));
        return s;
    }

    private static void addDrop(Script s, ServerLevel level, BlockPos site, Drop d) {
        String name = d.name();
        Object[] ship = new Object[1];
        PoseRecorder[] whole = new PoseRecorder[1], steady = new PoseRecorder[1];
        BlockPos min = site.offset(-2, 2, -3), max = site.offset(2, 2, 3);   // deck 5 x 1 x 7, bottom face 2 blocks above the floor top (y=100)
        s.step(name + ": build", () -> {
            for (int cx = (site.getX() - 20) >> 4; cx <= (site.getX() + 20) >> 4; cx++)
                for (int cz = (site.getZ() - 20) >> 4; cz <= (site.getZ() + 20) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++)
                level.setBlockAndUpdate(site.offset(x, -1, z), Blocks.STONE.defaultBlockState());   // floor top face at y = site.y
            List<BlockPos> blocks = new ArrayList<>();
            for (BlockPos raw : BlockPos.betweenClosed(min, max)) {
                BlockPos p = raw.immutable();
                level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
                blocks.add(p);
            }
            ship[0] = Ships.assemble(level, blocks, min, max, min);
            s.check(ship[0] != null, name + ": ship assembled");
            List<Vec3> samples = new ArrayList<>();
            for (BlockPos corner : new BlockPos[]{min, max, new BlockPos(min.getX(), min.getY(), max.getZ()), new BlockPos(max.getX(), min.getY(), min.getZ()), site.offset(0, 2, 0)}) {
                Vec3 plot = Ships.toPlot(ship[0], Vec3.atCenterOf(corner));
                samples.add(Vec3.atCenterOf(BlockPos.containing(plot)));
            }
            whole[0] = new PoseRecorder(ship[0], samples);
            steady[0] = new PoseRecorder(ship[0], samples);
            Vec3 pos = Ships.position(ship[0]);
            Quaterniond q = Ships.orientation(ship[0]);
            if (d.dx() != 0 || d.dz() != 0 || d.yawDeg() != 0 || d.pitchDeg() != 0 || d.rollDeg() != 0) {
                Quaterniond rq = new Quaterniond(q).rotateY(Math.toRadians(d.yawDeg())).rotateX(Math.toRadians(d.pitchDeg())).rotateZ(Math.toRadians(d.rollDeg()));
                Ships.teleport(ship[0], new Vector3d(pos.x + d.dx(), pos.y, pos.z + d.dz()), rq);
            }
            s.note("OBS " + name + ": released at pos=" + Ships.position(ship[0]) + " yawDev=" + PoseRecorder.yawDev(Ships.orientation(ship[0])) + " tilt=" + PoseRecorder.tilt(Ships.orientation(ship[0])));
        });
        s.step(name + ": start recording", () -> s.monitor(() -> {
            try {
                whole[0].sample();
                if (whole[0].ticks > SETTLE) steady[0].sample();
            } catch (Exception e) { throw new AssertionError(e); }
        }));
        s.settle(name + ": land and settle", SETTLE + WINDOW);
        s.step(name + ": report", () -> {
            s.clearMonitors();
            Vec3 p = Ships.position(ship[0]);
            s.note("LANDED " + name + " | ALL " + SETTLE + WINDOW + " ticks: " + whole[0].report());
            s.note("LANDED " + name + " | STEADY last " + WINDOW + " ticks: " + steady[0].report());
            s.check(p.y > 99 && p.y < 110, name + ": the ship landed on the platform (centre y=" + p.y + ")");
        });
        s.step(name + ": remove ship", () -> { try { Ships.remove(level, ship[0]); } catch (Throwable t) { throw new AssertionError(t); } });
    }
}
