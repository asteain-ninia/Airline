package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Exploration: a ship with a hot air burner and a small envelope, under real gravity, at several burner strengths. */
final class BurnerSuite {
    private BurnerSuite() {}

    private static Block find(String exact, String contains) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("aeronautics", exact));
        if (b != Blocks.AIR) return b;
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet())
            if (id.getNamespace().equals("aeronautics") && id.getPath().contains(contains)) return BuiltInRegistries.BLOCK.get(id);
        throw new AssertionError("no aeronautics block like " + exact + "/" + contains);
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "burner landing exploration (gravity on)";
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int idx = (int) ((System.currentTimeMillis() / 1000L) % 2000L);
        BlockPos base = new BlockPos(spawn.getX() + 8192 + 256 * (idx % 40), 100, spawn.getZ() + 256 * (idx / 40));
        s.step("blocks", () -> {
            StringBuilder sb = new StringBuilder();
            for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet())
                if (id.getNamespace().equals("aeronautics") && (id.getPath().contains("burner") || id.getPath().contains("envelope") || id.getPath().contains("balloon")))
                    sb.append(id).append(' ');
            s.note("OBS aeronautics blocks: " + sb);
        });
        int[] strengths = {-1, 8, 3, 1};   // -1 = default scroll value
        for (int i = 0; i < strengths.length; i++) addScenario(s, level, base.offset(0, 0, 60 * i), strengths[i]);
        return s;
    }

    private static void addScenario(Script s, ServerLevel level, BlockPos site, int strength) {
        String name = "burner scroll=" + (strength < 0 ? "default" : strength);
        Object[] ship = new Object[1];
        BlockEntity[] burner = new BlockEntity[1];
        double[] maxY = {-1e9}, minY = {1e9};
        PoseRecorder[] rec = new PoseRecorder[1], steady = new PoseRecorder[1];
        s.step(name + ": build", () -> {
            for (int cx = (site.getX() - 20) >> 4; cx <= (site.getX() + 20) >> 4; cx++)
                for (int cz = (site.getZ() - 20) >> 4; cz <= (site.getZ() + 20) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++)
                level.setBlockAndUpdate(site.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            Block burnerBlock = find("hot_air_burner", "burner");
            Block envelope = find("white_envelope", "envelope");
            BlockPos o = site.offset(0, 2, 0);              // deck layer
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                BlockPos p = o.offset(x, 0, z);
                level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
                blocks.add(p);
            }
            for (int y = 1; y <= 3; y++) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) != 2 && Math.abs(z) != 2) continue;
                BlockPos p = o.offset(x, y, z);
                level.setBlockAndUpdate(p, envelope.defaultBlockState());
                blocks.add(p);
            }
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                BlockPos p = o.offset(x, 4, z);
                level.setBlockAndUpdate(p, envelope.defaultBlockState());
                blocks.add(p);
            }
            BlockPos bp = o.offset(0, 1, 0), rp = o.offset(1, 1, 0);
            level.setBlockAndUpdate(rp, Blocks.REDSTONE_BLOCK.defaultBlockState());
            blocks.add(rp);
            level.setBlockAndUpdate(bp, burnerBlock.defaultBlockState());
            blocks.add(bp);
            s.note("OBS " + name + ": burner block=" + BuiltInRegistries.BLOCK.getKey(burnerBlock) + " state=" + level.getBlockState(bp) + " envelope=" + BuiltInRegistries.BLOCK.getKey(envelope));
            BlockPos min = o.offset(-2, 0, -2), max = o.offset(2, 4, 2);
            ship[0] = Ships.assemble(level, blocks, min, max, min);
            s.check(ship[0] != null, name + ": assembled");
            Vec3 plot = Ships.toPlot(ship[0], Vec3.atCenterOf(bp));
            burner[0] = level.getBlockEntity(BlockPos.containing(plot));
            s.note("OBS " + name + ": burner block entity after assembly = " + (burner[0] == null ? "null" : burner[0].getClass().getSimpleName()));
            if (burner[0] != null && strength >= 0) {
                java.lang.reflect.Field f = burner[0].getClass().getDeclaredField("hotAirAmountBehaviour");
                f.setAccessible(true);
                Object beh = f.get(burner[0]);
                beh.getClass().getMethod("setValue", int.class).invoke(beh, strength);
            }
            List<Vec3> samples = new ArrayList<>();
            for (BlockPos c : new BlockPos[]{min, max.atY(min.getY()), new BlockPos(min.getX(), min.getY(), max.getZ()), new BlockPos(max.getX(), min.getY(), min.getZ()), o}) {
                Vec3 pl = Ships.toPlot(ship[0], Vec3.atCenterOf(c));
                samples.add(Vec3.atCenterOf(BlockPos.containing(pl)));
            }
            rec[0] = new PoseRecorder(ship[0], samples);
            steady[0] = new PoseRecorder(ship[0], samples);
        });
        s.step(name + ": record", () -> s.monitor(() -> {
            try {
                rec[0].sample();
                if (rec[0].ticks > 150) steady[0].sample();
                double y = Ships.position(ship[0]).y;
                maxY[0] = Math.max(maxY[0], y);
                minY[0] = Math.min(minY[0], y);
            } catch (Exception e) { throw new AssertionError(e); }
        }));
        s.settle(name + ": settle", 250);
        s.step(name + ": report", () -> {
            s.clearMonitors();
            String gas = "n/a";
            if (burner[0] != null) {
                Object out = burner[0].getClass().getMethod("getGasOutput").invoke(burner[0]);
                Object balloon = burner[0].getClass().getMethod("getBalloon").invoke(burner[0]);
                gas = "gasOutput=" + out + " balloon=" + (balloon != null) + " signal=" + burner[0].getClass().getMethod("getSignalStrength").invoke(burner[0]);
            }
            Vec3 p = Ships.position(ship[0]);
            s.note("BURNER " + name + ": finalY=" + String.format("%.4f", p.y) + " rangeY=[" + String.format("%.3f", minY[0]) + "," + String.format("%.3f", maxY[0]) + "] "
                    + gas + " | ALL 250 ticks: " + rec[0].report());
            s.note("BURNER " + name + " | STEADY last 100 ticks: " + steady[0].report());
        });
        s.step(name + ": remove", () -> { try { Ships.remove(level, ship[0]); } catch (Throwable t) { throw new AssertionError(t); } });
    }
}
