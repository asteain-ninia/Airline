package com.asteain.shiptest;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures how well a ship moored with Simulated docking connectors sits on the block grid.
 * Ground connector (facing east) and ship connector (facing west) are three blocks apart; tips meet in the middle.
 */
final class MooringSuite {
    private MooringSuite() {}

    /** Per-tick sampler of plot-space sample points transformed to the world. */
    private static final class Recorder {
        final Object sub; final List<Vec3> plotPoints; final Vec3 groundTip; final Vec3 shipTipPlot;
        int ticks;
        final double[] maxFrac = new double[3];
        final double[] minFrac = {9, 9, 9}, maxFracSigned = {-9, -9, -9};
        double maxSway, maxYawDev, maxTilt, maxLin, maxAng, maxTipGap;
        Vec3[] first;

        Recorder(Object sub, List<Vec3> plotPoints, Vec3 groundTip, Vec3 shipTipPlot) {
            this.sub = sub; this.plotPoints = plotPoints; this.groundTip = groundTip; this.shipTipPlot = shipTipPlot;
        }

        static double frac(double v) { return v - (Math.floor(v) + 0.5); }

        static double yawDev(Quaterniond q) {
            Vector3d v = q.transform(new Vector3d(1, 0, 0));
            double ang = Math.toDegrees(Math.atan2(-v.z, v.x));
            return ang - 90.0 * Math.round(ang / 90.0);
        }

        static double tilt(Quaterniond q) {
            Vector3d v = q.transform(new Vector3d(0, 1, 0));
            return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, v.y))));
        }

        void sample() throws Exception {
            ticks++;
            if (first == null) first = new Vec3[plotPoints.size()];
            for (int i = 0; i < plotPoints.size(); i++) {
                Vec3 w = Ships.toWorld(sub, plotPoints.get(i));
                double[] f = {frac(w.x), frac(w.y), frac(w.z)};
                for (int a = 0; a < 3; a++) {
                    maxFrac[a] = Math.max(maxFrac[a], Math.abs(f[a]));
                    minFrac[a] = Math.min(minFrac[a], f[a]);
                    maxFracSigned[a] = Math.max(maxFracSigned[a], f[a]);
                }
                if (first[i] == null) first[i] = w;
                maxSway = Math.max(maxSway, w.distanceTo(first[i]));
            }
            Quaterniond q = Ships.orientation(sub);
            maxYawDev = Math.max(maxYawDev, Math.abs(yawDev(q)));
            maxTilt = Math.max(maxTilt, tilt(q));
            maxLin = Math.max(maxLin, Ships.velocity(sub).length());
            maxAng = Math.max(maxAng, Ships.angularVelocity(sub).length());
            maxTipGap = Math.max(maxTipGap, Ships.toWorld(sub, shipTipPlot).distanceTo(groundTip));
        }

        String report() throws Exception {
            Quaterniond q = Ships.orientation(sub);
            Vec3 p = Ships.position(sub);
            return String.format("samples=%d | final pose pos=(%.4f,%.4f,%.4f) yawDevFrom90deg=%.4f tilt=%.4f | grid fraction of block centres "
                            + "(offset from the nearest integer-grid block centre, over all samples) max|dx|=%.5f max|dy|=%.5f max|dz|=%.5f "
                            + "range dx[%.5f,%.5f] dy[%.5f,%.5f] dz[%.5f,%.5f] | worst over the window: yawDev=%.4f deg tilt=%.4f deg "
                            + "sway=%.5f blocks linSpeed=%.5f angSpeed=%.5f tipGap=%.5f",
                    ticks, p.x, p.y, p.z, yawDev(q), tilt(q), maxFrac[0], maxFrac[1], maxFrac[2],
                    minFrac[0], maxFracSigned[0], minFrac[1], maxFracSigned[1], minFrac[2], maxFracSigned[2],
                    maxYawDev, maxTilt, maxSway, maxLin, maxAng, maxTipGap);
        }
    }

    private static Block connectorBlock() {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("simulated", "docking_connector"));
        if (b == Blocks.AIR) {
            StringBuilder found = new StringBuilder();
            for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) if (id.getPath().contains("docking")) found.append(id).append(' ');
            throw new AssertionError("simulated:docking_connector is not registered; blocks containing 'docking': " + found);
        }
        return b;
    }

    private static boolean call(BlockEntity be, String method) {
        try { return (boolean) be.getClass().getMethod(method).invoke(be); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "mooring (" + (Boolean.getBoolean("shiptest.gravity") ? "gravity on, Sable default -11" : "zero gravity") + ")";
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        // Unique site per run (the world is never deleted) and modest coordinates: float precision grows with distance.
        int idx = (int) ((System.currentTimeMillis() / 1000L) % 2000L);
        BlockPos base = new BlockPos(spawn.getX() + 8192 + 256 * (idx % 40), 100, spawn.getZ() + 256 * (idx / 40));
        s.step("blocks", () -> {
            Block c = connectorBlock();
            s.note("OBS connector block = " + BuiltInRegistries.BLOCK.getKey(c));
        });
        addScenario(s, level, "A aligned start, pairTo", base, null);
        addScenario(s, level, "B displaced start (0.3 block, 10 deg), natural magnets", base.offset(0, 0, 40), new double[]{0.3, 0.3, 10, 0});
        addScenario(s, level, "C large displacement (1.2,-1.0 blocks, 35 deg yaw, 8 deg tilt)", base.offset(0, 0, 80), new double[]{1.2, -1.0, 35, 8});
        return s;
    }

    private static void addScenario(Script s, ServerLevel level, String name, BlockPos g, double[] disp) {
        boolean displaced = disp != null;
        BlockPos sPos = g.east(3);
        Object[] ship = new Object[1];
        BlockEntity[] ground = new BlockEntity[1];
        BlockEntity[] shipConn = new BlockEntity[1];
        Recorder[] rec = new Recorder[1];
        int[] waited = new int[1];
        boolean[] shipSideLocked = new boolean[1];

        s.step(name + ": build", () -> {
            for (int cx = (g.getX() - 4) >> 4; cx <= (g.getX() + 12) >> 4; cx++)
                for (int cz = (g.getZ() - 6) >> 4; cz <= (g.getZ() + 6) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            Block c = connectorBlock();
            BlockState east = c.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST).setValue(BlockStateProperties.POWERED, true);
            BlockState west = c.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST).setValue(BlockStateProperties.POWERED, true);
            level.setBlockAndUpdate(g.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(g, east);
            ground[0] = level.getBlockEntity(g);
            s.check(ground[0] != null, name + ": ground connector block entity exists");

            List<BlockPos> blocks = new ArrayList<>();
            for (int x = 1; x <= 5; x++) for (int z = -2; z <= 2; z++) {
                BlockPos p = sPos.offset(x, 0, z);
                level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
                blocks.add(p);
            }
            level.setBlockAndUpdate(sPos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            blocks.add(sPos.above());
            level.setBlockAndUpdate(sPos, west);
            blocks.add(sPos);
            ship[0] = Ships.assemble(level, blocks, sPos.offset(0, 0, -2), sPos.offset(5, 1, 2), sPos);
            s.check(ship[0] != null, name + ": ship assembled");

            List<Vec3> samples = new ArrayList<>();
            for (BlockPos off : new BlockPos[]{BlockPos.ZERO, new BlockPos(5, 0, 2), new BlockPos(5, 0, -2), new BlockPos(1, 0, -2), new BlockPos(1, 0, 2), new BlockPos(3, 0, 0)}) {
                Vec3 plot = Ships.toPlot(ship[0], Vec3.atCenterOf(sPos.offset(off)));
                samples.add(Vec3.atCenterOf(BlockPos.containing(plot)));
            }
            BlockPos plotConn = BlockPos.containing(Ships.toPlot(ship[0], Vec3.atCenterOf(sPos)));
            shipConn[0] = level.getBlockEntity(plotConn);
            s.check(shipConn[0] != null, name + ": ship connector block entity at plot " + plotConn);
            Vec3 groundTip = Vec3.atCenterOf(g).add(1.5, 0, 0);
            Vec3 shipTipPlot = Vec3.atCenterOf(plotConn).add(-1.5, 0, 0);
            rec[0] = new Recorder(ship[0], samples, groundTip, shipTipPlot);
            Vec3 pos = Ships.position(ship[0]);
            Quaterniond q = Ships.orientation(ship[0]);
            s.note("OBS " + name + ": after assembly pos=" + String.format("(%.4f,%.4f,%.4f)", pos.x, pos.y, pos.z) + " yawDev=" + Recorder.yawDev(q) + " tilt=" + Recorder.tilt(q)
                    + " tipGap=" + String.format("%.4f", Ships.toWorld(ship[0], shipTipPlot).distanceTo(groundTip)));
            if (displaced) {
                Quaterniond rq = new Quaterniond(q).rotateY(Math.toRadians(disp[2])).rotateX(Math.toRadians(disp[3]));
                Ships.teleport(ship[0], new Vector3d(pos.x + disp[0], pos.y, pos.z + disp[1]), rq);
                s.note("OBS " + name + ": displaced by " + java.util.Arrays.toString(disp) + " (dx,dz,yaw deg,tilt deg); tipGap="
                        + String.format("%.4f", Ships.toWorld(ship[0], shipTipPlot).distanceTo(groundTip)));
            }
        });
        s.until(name + ": both connectors extended", () -> {},
                () -> call(ground[0], "isExtended") && call(shipConn[0], "isExtended"), 200);
        s.step(name + ": extended", () -> s.note("OBS " + name + ": both connectors extended; ship=" + Ships.position(ship[0])
                + " groundHasOther=" + call(ground[0], "hasOtherConnector") + " shipHasOther=" + call(shipConn[0], "hasOtherConnector")));
        if (displaced) {
            // let the magnets do the work first
            s.until(name + ": natural lock", () -> waited[0] = 0, () -> {
                waited[0]++;
                return (call(ground[0], "isLocked") && call(shipConn[0], "isLocked")) || waited[0] >= 300;
            }, 320);
            s.step(name + ": natural lock result", () -> {
                boolean locked = call(ground[0], "isLocked") && call(shipConn[0], "isLocked");
                s.note("OBS " + name + ": locked by the magnets alone within " + waited[0] + " ticks = " + locked);
                if (!locked) {
                    shipConn[0].getClass().getMethod("pairTo", ground[0].getClass()).invoke(shipConn[0], ground[0]);
                    s.note("OBS " + name + ": magnets did not lock; called pairTo");
                }
            });
        } else {
            s.step(name + ": pairTo", () -> shipConn[0].getClass().getMethod("pairTo", ground[0].getClass()).invoke(shipConn[0], ground[0]));
        }
        s.until(name + ": locked", () -> waited[0] = 0, () -> {
            waited[0]++;
            return (call(ground[0], "isLocked") && call(shipConn[0], "isLocked")) || waited[0] >= 400;
        }, 420);
        s.step(name + ": lock result", () -> {
            boolean g1 = call(ground[0], "isLocked"), g2 = call(shipConn[0], "isLocked");
            shipSideLocked[0] = g1 && g2;
            Object otherId = shipConn[0].getClass().getField("otherConnectorSubLevelId").get(shipConn[0]);
            s.note("OBS " + name + ": isLocked ground=" + g1 + " ship=" + g2 + " after " + waited[0] + " ticks; ship connector otherConnectorSubLevelId=" + otherId
                    + " (null = other end is the ground)");
            s.check(shipSideLocked[0], name + ": the connectors locked");
        });
        s.settle(name + ": settle 20", 20);
        s.step(name + ": start recording", () -> s.monitor(() -> {
            try { rec[0].sample(); } catch (Exception e) { throw new AssertionError(e); }
        }));
        s.settle(name + ": record 100 ticks", 100);
        s.step(name + ": report", () -> {
            s.clearMonitors();
            String r = rec[0].report();
            LogUtils.getLogger().info("[SHIPTEST] {}", r);
            s.note("MOORED " + name + ": " + r);
        });
    }
}
