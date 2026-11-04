package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Real gravity: a straight-dropped ship lands in a pad cut into the floor so that its deck top ends up a chosen height
 * above/below the ground top (snow layers give the fractional heights). Walks both ways; records the landed height and what happens.
 */
final class LandHeightSuite {
    private LandHeightSuite() {}

    /** delta = wanted deck top minus ground top; stoneTop = integer top of the stone pad; layers = snow layers on it (0 = none). */
    private record Case(String name, double delta, int stoneTopRel, int layers, int zOffset) {}

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "landed ship height steps (gravity " + (Boolean.getBoolean("shiptest.gravity") ? "on" : "OFF - wrong run") + ")";
        ServerLevel level = server.overworld();
        BlockPos origin = Rig.site(level, 0);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS landheight", 16, 16);
        // wanted bottom B = Y + delta - 1; pad stone top = floor(B), snow collision = B - floor(B) = (layers-1)*0.125
        List<Case> cases = List.of(
                new Case("deck top flush with the ground (delta 0)", 0.0, -1, 0, 0),
                new Case("deck top 0.25 above the ground", 0.25, -1, 3, 0),
                new Case("deck top 0.5 above the ground", 0.5, -1, 5, 0),
                new Case("deck top 0.375 below the ground", -0.375, -2, 6, 0),
                new Case("deck top 0.5 below the ground", -0.5, -2, 5, 0));
        for (Case c : cases) addCase(s, rig, origin, c);
        return s;
    }

    private static void addCase(Script s, Rig rig, BlockPos origin, Case c) {
        ServerLevel level = rig.level;
        String name = c.name();
        Object[] ship = new Object[1];
        Vec3[] deckTop = new Vec3[1], deckPlot = new Vec3[1];
        BlockPos pad = origin.offset(6, 0, c.zOffset());        // pad centre; footprint x -2..2, z -3..3
        s.step(name + ": cut the pad and drop the ship", () -> {
            for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++) {
                BlockPos col = pad.offset(x, 0, z);
                level.setBlockAndUpdate(col.offset(0, -1, 0), Blocks.AIR.defaultBlockState());              // the floor cell
                int stoneCellY = origin.getY() + c.stoneTopRel() - 1;
                level.setBlockAndUpdate(new BlockPos(col.getX(), stoneCellY, col.getZ()), Blocks.STONE.defaultBlockState());
                if (c.layers() > 0)
                    level.setBlockAndUpdate(new BlockPos(col.getX(), stoneCellY + 1, col.getZ()), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, c.layers()));
            }
            BlockPos drop = pad.offset(0, 2, 0);
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++) {
                BlockPos p = drop.offset(x, 0, z);
                level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
                blocks.add(p);
            }
            ship[0] = rig.assemble(blocks);
            s.check(ship[0] != null, name + ": ship assembled");
            deckPlot[0] = Vec3.atCenterOf(BlockPos.containing(Ships.toPlot(ship[0], Vec3.atCenterOf(drop.offset(0, 0, 0)))));
        });
        s.settle(name + ": land and settle", 200);
        s.step(name + ": landed", () -> {
            Vec3 top = Ships.toWorld(ship[0], deckPlot[0]);
            deckTop[0] = top;
            double deckTopY = top.y + 0.5;
            s.note(String.format("OBS %s: landed deck top y=%.4f (ground top %d): delta=%.4f (wanted %.3f) speed=%.5f tilt=%.3f", name, deckTopY, origin.getY(),
                    deckTopY - origin.getY(), c.delta(), Ships.velocity(ship[0]).length(), PoseRecorder.tilt(Ships.orientation(ship[0]))));
        });
        rig.walkLive2(s, name + " / ground -> deck", () -> Rig.standing(origin.offset(-12, 0, c.zOffset())),
                () -> BlockPos.containing(deckTop[0].x, deckTop[0].y + 0.6, deckTop[0].z), true, 500, t -> {},
                w -> s.soft(name + " / ground -> deck", w.walkedHonestly(), w.describe() + String.format(" deckTop=%.3f", deckTop[0].y + 0.5)));
        rig.walkLive2(s, name + " / deck -> ground", () -> new Vec3(deckTop[0].x, deckTop[0].y + 0.55, deckTop[0].z),
                () -> origin.offset(-12, 0, c.zOffset()), true, 500, t -> {},
                w -> s.soft(name + " / deck -> ground", w.walkedHonestly(), w.describe() + String.format(" deckTop=%.3f", deckTop[0].y + 0.5)));
        s.step(name + ": remove", () -> {
            rig.removeShipSafely(ship[0], origin.offset(-12, 0, c.zOffset()));
            for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++) {          // restore the floor for the next case
                BlockPos col = pad.offset(x, 0, z);
                for (int dy = -4; dy <= 1; dy++) level.setBlockAndUpdate(col.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(col.offset(0, -1, 0), Blocks.STONE.defaultBlockState());
            }
        });
    }
}
