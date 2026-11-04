package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Ship structures (zero gravity, stopped, flush with the ground edge): ladder, closed door, two levels with stairs, a big ship,
 * and a load measurement with 0 / 1 / 6 ships. Coordinates are relative to the site; the ground floor top is y=site.y (standing level Y).
 */
final class ShipsSuite {
    private ShipsSuite() {}

    private interface Layout { List<BlockPos> place(ServerLevel level, BlockPos site); }

    private record W(String label, int fx, int fz, int dx, int dy, int dz, boolean pass) {}

    private static Vec3 world(Object ship, Vec3 plot) {
        try { return Ships.toWorld(ship, plot); } catch (Exception e) { throw new AssertionError(e); }
    }

    private static void put(ServerLevel level, List<BlockPos> blocks, BlockPos p, BlockState st) {
        level.setBlockAndUpdate(p, st);
        blocks.add(p);
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "ships (structures, zero gravity)";
        ServerLevel level = server.overworld();
        BlockPos origin = Rig.site(level, 16384);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS ships", 12, 8);
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();

        // 1. ladder: deck x 13..17, wall at x=15 (y=Y..Y+1), platform x 15..17 at y=Y+2, ladder at x=14 (y=Y..Y+2) facing west
        scenario(s, rig, origin.offset(0, 0, 60), "ladder (aligned ship)", (lv, site) -> {
            List<BlockPos> b = new ArrayList<>();
            for (int x = 13; x <= 17; x++) for (int z = -1; z <= 1; z++) put(lv, b, site.offset(x, -1, z), planks);
            for (int y = 0; y <= 1; y++) put(lv, b, site.offset(15, y, 0), planks);
            for (int x = 15; x <= 17; x++) for (int z = -1; z <= 1; z++) put(lv, b, site.offset(x, 2, z), planks);
            for (int y = 0; y <= 2; y++) put(lv, b, site.offset(14, y, 0), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
            return b;
        }, List.of(new W("ground -> deck foot of ladder", -8, 0, 13, 0, 0, true), new W("ground -> upper platform by the ladder", -8, 0, 17, 3, 0, true)));

        // 2. closed door in a corridor: walls at z=+-1 (two high), door at x=15; before the door is reachable, behind it is not
        scenario(s, rig, origin.offset(0, 0, 120), "closed door", (lv, site) -> {
            List<BlockPos> b = new ArrayList<>();
            for (int x = 13; x <= 17; x++) for (int z = -1; z <= 1; z++) put(lv, b, site.offset(x, -1, z), planks);
            for (int x = 13; x <= 17; x++) for (int y = 0; y <= 1; y++) for (int z : new int[]{-1, 1}) put(lv, b, site.offset(x, y, z), planks);
            put(lv, b, site.offset(15, 0, 0), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST));
            put(lv, b, site.offset(15, 1, 0), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            return b;
        }, List.of(new W("ground -> before the door", -8, 0, 14, 0, 0, true), new W("ground -> behind the closed door (first version: not passable)", -8, 0, 17, 0, 0, false)));

        // 3. two levels: lower deck x 13..16, stairs at x=17 (ascending east), upper deck x 18..21 one block higher
        scenario(s, rig, origin.offset(0, 0, 180), "two levels with stairs", (lv, site) -> {
            List<BlockPos> b = new ArrayList<>();
            for (int x = 13; x <= 16; x++) for (int z = -1; z <= 1; z++) put(lv, b, site.offset(x, -1, z), planks);
            for (int z = -1; z <= 1; z++) put(lv, b, site.offset(17, 0, z), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
            for (int z = -1; z <= 1; z++) put(lv, b, site.offset(17, -1, z), planks);
            for (int x = 18; x <= 21; x++) for (int z = -1; z <= 1; z++) put(lv, b, site.offset(x, 0, z), planks);
            return b;
        }, List.of(new W("ground -> upper deck over the stairs", -8, 0, 21, 1, 0, true), new W("lower deck only", -8, 0, 15, 0, 0, true)));

        // 4. large ship: 40 x 2 x 21 hull
        scenario(s, rig, origin.offset(0, 0, 240), "large ship (40x2x21)", (lv, site) -> {
            List<BlockPos> b = new ArrayList<>();
            for (int x = 13; x <= 52; x++) for (int y = -2; y <= -1; y++) for (int z = -10; z <= 10; z++) put(lv, b, site.offset(x, y, z), planks);
            return b;
        }, List.of(new W("ground -> far end of the deck (37 blocks)", -8, 0, 50, 0, 0, true), new W("ground -> side corner", -8, 0, 40, 0, 9, true)));

        addLoad(s, rig, origin.offset(0, 0, 300));
        addOrdinarySteps(s, rig, origin.offset(0, 0, 360));
        EdgeLab.add(s, rig, origin.offset(0, 0, 420), 20, EdgeLab.SHAPES.get("s5"), false, true);
        EdgeLab.add(s, rig, origin.offset(0, 0, 490), 45, EdgeLab.SHAPES.get("s5"), false, true);
        return s;
    }

    private static void scenario(Script s, Rig rig, BlockPos site, String name, Layout layout, List<W> walks) {
        ServerLevel level = rig.level;
        Object[] ship = new Object[1];
        s.step(name + ": build", () -> {
            Colony.platform(level, site, 12, 8);
            Rig.force(level, site.offset(30, 0, 0), 30, 14);
            long t0 = System.nanoTime();
            ship[0] = rig.assemble(layout.place(level, site));
            s.note(String.format("OBS %s: assembled in %.1f ms", name, (System.nanoTime() - t0) / 1e6));
            s.check(ship[0] != null, name + ": assembled");
        });
        s.settle(name + ": settle", 60);
        for (W w : walks) {
            Vec3 from = Rig.standing(site.offset(w.fx(), 0, w.fz()));
            BlockPos dest = site.offset(w.dx(), w.dy(), w.dz());
            rig.walk(s, name + " / " + w.label(), from, dest, true, w.pass() ? 600 : 250, walk -> {
                if (w.pass()) s.soft(name + " / " + w.label(), walk.walkedHonestly(), walk.describe());
                else s.softNot(name + " / " + w.label(), walk.arrived, walk.describe() + (walk.teleportTick >= 0 ? " TELEPORTED" : ""));
            });
        }
        s.step(name + ": remove ship", () -> rig.removeShipSafely(ship[0], site.offset(-8, 0, 0)));
    }

    private static void addLoad(Script s, Rig rig, BlockPos site) {
        ServerLevel level = rig.level;
        List<Object> ships = new ArrayList<>();
        double[] baseline = new double[1];
        s.step("load: ground", () -> {
            Colony.platform(level, site, 12, 8);
            Rig.force(level, site.offset(0, 0, 30), 30, 40);
        });
        s.settle("load: warm up", 100);
        s.step("load: 0 ships", () -> { baseline[0] = rig.server.getAverageTickTimeNanos() / 1e6; });
        s.settle("load: measure 0 ships", 120);
        s.step("load: record 0 ships", () -> s.note(String.format("LOAD 0 ships: average tick %.3f ms (MinecraftServer.getAverageTickTimeNanos, last 100 ticks)", rig.server.getAverageTickTimeNanos() / 1e6)));
        s.step("load: 1 ship", () -> ships.add(smallShip(rig, site.offset(0, 0, 0))));
        s.settle("load: measure 1 ship", 120);
        s.step("load: record 1 ship", () -> s.note(String.format("LOAD 1 ship (stopped): average tick %.3f ms", rig.server.getAverageTickTimeNanos() / 1e6)));
        s.step("load: 6 ships", () -> { for (int i = 1; i < 6; i++) ships.add(smallShip(rig, site.offset(0, 0, 12 * i))); });
        s.settle("load: measure 6 ships", 120);
        s.step("load: record 6 ships", () -> s.note(String.format("LOAD 6 ships (stopped): average tick %.3f ms", rig.server.getAverageTickTimeNanos() / 1e6)));
        rig.walk(s, "load: walk past 6 ships", Rig.standing(site.offset(-8, 0, 0)), site.offset(15, 0, 0), true, 600, walk -> {
            s.note("LOAD walk with 6 ships around: first path available at tick " + walk.firstPathTick + " (50 ms granularity), arrived=" + walk.arrived + " in " + walk.ticks + " ticks; "
                    + String.format("average tick %.3f ms", rig.server.getAverageTickTimeNanos() / 1e6));
            s.soft("load: walk ground -> ship with 6 ships around", walk.walkedHonestly(), walk.describe());
        });
        s.onFinish(() -> { for (Object o : ships) rig.remove(o); });
    }

    private static Object smallShip(Rig rig, BlockPos site) {
        try {
            List<BlockPos> b = new ArrayList<>();
            for (int x = 13; x <= 17; x++) for (int z = -1; z <= 1; z++) put(rig.level, b, site.offset(x, -1, z), Blocks.OAK_PLANKS.defaultBlockState());
            return rig.assemble(b);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Ordinary ground-to-ground walk over a 1- and 2-high plateau: the ship feature (and its jump help) must not change the walk. Compared with the feature switched off. */
    private static void addOrdinarySteps(Script s, Rig rig, BlockPos site) {
        ServerLevel level = rig.level;
        int[] ticks = new int[3];
        double[] maxY = new double[3], maxJump = new double[3];
        boolean[] ok = new boolean[3];
        boolean originalEnabled = com.asteain.airline.ShipConfig.PATHING_ENABLED.get();
        s.step("steps: build plateau", () -> {
            Colony.platform(level, site, 16, 8);
            for (int z = -8; z <= 8; z++) {
                for (int x = -4; x <= -2; x++) level.setBlockAndUpdate(site.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                for (int x = -1; x <= 3; x++) { level.setBlockAndUpdate(site.offset(x, 0, z), Blocks.STONE.defaultBlockState()); level.setBlockAndUpdate(site.offset(x, 1, z), Blocks.STONE.defaultBlockState()); }
                for (int x = 4; x <= 6; x++) level.setBlockAndUpdate(site.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        });
        s.settle("steps: settle", 20);
        String[] labels = {"feature on", "feature off (as before the implementation)", "feature on again"};
        for (int i = 0; i < 3; i++) {
            int k = i;
            s.step("steps: set feature " + labels[i], () -> com.asteain.airline.ShipConfig.PATHING_ENABLED.set(k != 1));
            double[] my = {-1e9};
            rig.walkLive(s, "steps walk (" + labels[i] + ")", () -> Rig.standing(site.offset(-10, 0, 0)), site.offset(10, 0, 0), true, 500,
                    t -> { my[0] = Math.max(my[0], rig.citizen.getY()); },
                    w -> { ticks[k] = w.ticks; ok[k] = w.walkedHonestly(); maxY[k] = my[0]; maxJump[k] = w.maxJump;
                        s.note("OBS steps walk (" + labels[k] + "): " + w.describe() + " highestY=" + String.format("%.2f", my[0])); });
        }
        s.step("steps: compare", () -> {
            com.asteain.airline.ShipConfig.PATHING_ENABLED.set(originalEnabled);
            s.soft("ordinary walk over steps arrives in all three runs, no teleport", ok[0] && ok[1] && ok[2], "ticks on/off/on = " + ticks[0] + "/" + ticks[1] + "/" + ticks[2]);
            s.soft("the ship feature does not change an ordinary walk (tick counts within 4 and the same highest point)",
                    Math.abs(ticks[0] - ticks[1]) <= 4 && Math.abs(ticks[2] - ticks[1]) <= 4 && Math.abs(maxY[0] - maxY[1]) < 0.3,
                    String.format("ticks on/off/on = %d/%d/%d, highestY on/off/on = %.2f/%.2f/%.2f", ticks[0], ticks[1], ticks[2], maxY[0], maxY[1], maxY[2]));
        });
        s.onFinish(() -> com.asteain.airline.ShipConfig.PATHING_ENABLED.set(originalEnabled));
    }

    /** Oblique stopped ship (held in place, tilt checked): walk across the deck to cells near the outer edge and the corners; the citizen must not step off or fall. */
}
