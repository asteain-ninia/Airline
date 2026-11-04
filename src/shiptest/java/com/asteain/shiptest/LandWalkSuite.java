package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/** Real gravity: ships are dropped onto the stone floor, settle, and then citizens walk onto and off the landed ship. */
final class LandWalkSuite {
    private LandWalkSuite() {}

    private record Case(String name, int zOffset, double yawDeg, double dx, double dz, boolean burner) {}

    private static Block aero(String exact) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("aeronautics", exact));
        if (b == Blocks.AIR) throw new AssertionError("no aeronautics:" + exact);
        return b;
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "landed ships walk (gravity " + (Boolean.getBoolean("shiptest.gravity") ? "on" : "OFF - wrong run") + ")";
        ServerLevel level = server.overworld();
        BlockPos origin = Rig.site(level, 24576);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS landwalk", 16, 16);
        List<Case> cases = List.of(
                new Case("straight landing", -8, 0, 0, 0, false),
                new Case("landing yawed 20 deg and shifted", 0, 20, 0.3, 0.37, false),
                new Case("landing with the burner lit", 8, 0, 0, 0, true),
                new Case("landing yawed 20 deg only", -8, 20, 0, 0, false),
                new Case("landing shifted 0.3/0.37 only", 8, 0, 0.3, 0.37, false),
                new Case("landing yawed 45 deg only", 0, 45, 0, 0, false));
        for (Case c : cases) addCase(s, rig, origin, c);
        return s;
    }

    private static void addCase(Script s, Rig rig, BlockPos origin, Case c) {
        ServerLevel level = rig.level;
        String name = c.name();
        Object[] ship = new Object[1];
        Vec3[] deckTop = new Vec3[1], deckPlot = new Vec3[1];
        BlockPos drop = origin.offset(6, 2, c.zOffset());       // deck layer, bottom face 2 blocks above the floor top
        PoseRecorder[] rec = new PoseRecorder[1];
        s.step(name + ": drop ship", () -> {
            List<BlockPos> blocks = new ArrayList<>();
            int half = c.burner() ? 2 : 2;
            int zlen = c.burner() ? 2 : 3;
            for (int x = -half; x <= half; x++) for (int z = -zlen; z <= zlen; z++) {
                BlockPos p = drop.offset(x, 0, z);
                level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
                blocks.add(p);
            }
            if (c.burner()) {
                Block env = aero("white_envelope");
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) { BlockPos p = drop.offset(x, 4, z); level.setBlockAndUpdate(p, env.defaultBlockState()); blocks.add(p); }
                // corner pillars join the canopy to the deck (a loose canopy splits off as a second ship and drops onto the burner)
                for (int y = 1; y <= 3; y++) for (int cx : new int[]{-2, 2}) for (int cz : new int[]{-2, 2}) { BlockPos p = drop.offset(cx, y, cz); level.setBlockAndUpdate(p, env.defaultBlockState()); blocks.add(p); }
                BlockPos rp = drop.offset(-1, 1, 0), bp = drop.offset(0, 1, 0);
                level.setBlockAndUpdate(rp, Blocks.REDSTONE_BLOCK.defaultBlockState()); blocks.add(rp);
                level.setBlockAndUpdate(bp, aero("adjustable_burner").defaultBlockState()); blocks.add(bp);
            }
            ship[0] = rig.assemble(blocks);
            s.check(ship[0] != null, name + ": ship assembled");
            // plot-space centre of a deck cell clear of the burner, taken while the ship is still at its assembly pose
            deckPlot[0] = Vec3.atCenterOf(BlockPos.containing(Ships.toPlot(ship[0], Vec3.atCenterOf(drop.offset(1, 0, 2)))));
            if (c.yawDeg() != 0 || c.dx() != 0 || c.dz() != 0) {
                Vec3 pos = Ships.position(ship[0]);
                Quaterniond q = Ships.orientation(ship[0]).rotateY(Math.toRadians(c.yawDeg()));
                Ships.teleport(ship[0], new Vector3d(pos.x + c.dx(), pos.y, pos.z + c.dz()), q);
            }
            List<Vec3> samples = new ArrayList<>();
            samples.add(Vec3.atCenterOf(BlockPos.containing(Ships.toPlot(ship[0], Vec3.atCenterOf(drop)))));
            rec[0] = new PoseRecorder(ship[0], samples);
            s.monitor(() -> { try { rec[0].sample(); } catch (Exception e) { throw new AssertionError(e); } });
        });
        s.settle(name + ": land and settle", 200);
        s.step(name + ": landed", () -> {
            s.clearMonitors();
            Vec3 p = Ships.position(ship[0]);
            org.joml.Quaterniond q = Ships.orientation(ship[0]);
            s.note(String.format("OBS %s: landed at y=%.4f yawDev=%.2f tilt=%.3f speed=%.5f", name, p.y, PoseRecorder.yawDev(q), PoseRecorder.tilt(q), Ships.velocity(ship[0]).length()));
            s.check(p.y > 99 && p.y < 110, name + ": the ship landed (y=" + p.y + ")");
            // a deck cell clear of the burner: deck block at (+1, 0, +2) relative to the drop point
            deckTop[0] = Ships.toWorld(ship[0], deckPlot[0]);
            s.note(String.format("OBS %s: deck cell centre in the world = (%.3f,%.3f,%.3f)", name, deckTop[0].x, deckTop[0].y, deckTop[0].z));
        });
        s.step(name + ": hold the ship", () -> {});
        rig.walkLive2(s, name + " / ground -> landed deck", () -> Rig.standing(origin.offset(-12, 0, c.zOffset())),
                () -> BlockPos.containing(deckTop[0].x, deckTop[0].y + 0.6, deckTop[0].z), true, 600,
                t -> {}, w -> s.soft(name + " / ground -> landed deck", w.walkedHonestly(), w.describe()));
        rig.walkLive2(s, name + " / landed deck -> ground", () -> new Vec3(deckTop[0].x, deckTop[0].y + 0.55, deckTop[0].z),
                () -> origin.offset(-12, 0, c.zOffset()), true, 600,
                t -> {}, w -> s.soft(name + " / landed deck -> ground", w.walkedHonestly(), w.describe()));
        s.step(name + ": remove", () -> rig.removeShipSafely(ship[0], origin.offset(-12, 0, c.zOffset())));
    }
}
