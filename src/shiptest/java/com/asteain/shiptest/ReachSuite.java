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
 * Steps between the ground and a ship that are too high to climb (real step above about 1.25 blocks), with controls at exactly 1.0.
 * Zero gravity, ship held in place. Expected for too-high steps: no arrival, no repeated jumping, no fall, no teleport, no exception;
 * with another entrance (a ledge cut into the ship) the citizen goes round and arrives.
 */
final class ReachSuite {
    private ReachSuite() {}

    private interface Layout { List<BlockPos> place(ServerLevel level, BlockPos site); }

    private record Case(String name, Layout layout, double dy, boolean groundToDeck, boolean expectArrive, int deckCellY) {}

    private static void put(ServerLevel level, List<BlockPos> blocks, BlockPos p) {
        level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
        blocks.add(p);
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "reach (steps too high for a jump, zero gravity)";
        ServerLevel level = server.overworld();
        BlockPos origin = Rig.site(level, 0);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS reach", 12, 8);
        // A: deck cells at y=Y (deck top Y+1 plus dy), ground top Y.   B: deck cells at y=Y-2 (deck top Y-1 plus dy), the ground wall above it.
        Layout deckUp = (lv, site) -> {
            List<BlockPos> b = new ArrayList<>();
            for (int x = 13; x <= 17; x++) for (int z = -1; z <= 1; z++) put(lv, b, site.offset(x, 0, z));
            return b;
        };
        Layout deckUpWithLedge = (lv, site) -> {
            List<BlockPos> b = deckUp.place(lv, site);
            for (int x = 13; x <= 17; x++) for (int z = 2; z <= 3; z++) put(lv, b, site.offset(x, -1, z));      // ledge: top at ground level (+dy)
            return b;
        };
        Layout deckDown = (lv, site) -> {
            List<BlockPos> b = new ArrayList<>();
            for (int x = 13; x <= 17; x++) for (int z = -1; z <= 1; z++) put(lv, b, site.offset(x, -2, z));
            return b;
        };
        List<Case> cases = List.of(
                new Case("A0 control: ground -> deck 1.0 above the ground", deckUp, 0.0, true, true, 0),
                new Case("A1 ground -> deck 1.3 above the ground (too high)", deckUp, 0.3, true, false, 0),
                new Case("A2 same ship with a ledge entrance: goes round", deckUpWithLedge, 0.3, true, true, 0),
                new Case("B0 control: deck 1.0 below the ground -> ground", deckDown, 0.0, false, true, -2),
                new Case("B1 deck 1.3 below the ground -> ground (too high)", deckDown, -0.3, false, false, -2));
        for (int i = 0; i < cases.size(); i++) addCase(s, rig, origin.offset(0, 0, 60 * (i + 1)), cases.get(i));
        return s;
    }

    private static void addCase(Script s, Rig rig, BlockPos site, Case c) {
        ServerLevel level = rig.level;
        String name = c.name();
        Object[] ship = new Object[1];
        int[] jumps = new int[1];
        boolean[] prevGround = new boolean[1];
        double[] lastY = new double[1];
        s.step(name + ": build", () -> {
            Colony.platform(level, site, 12, 8);
            Rig.force(level, site.offset(15, 0, 0), 10, 10);
            ship[0] = rig.assemble(c.layout().place(level, site));
            Vec3 pos = Ships.position(ship[0]);
            Quaterniond q = Ships.orientation(ship[0]);
            org.joml.Vector3d hp = new Vector3d(pos.x, pos.y + c.dy(), pos.z);
            Ships.teleport(ship[0], hp, q);
            s.monitor(() -> { try { Ships.teleport(ship[0], hp, q); Ships.zeroVelocity(ship[0]); } catch (Exception e) { throw new AssertionError(e); } });
        });
        s.settle(name + ": settle", 20);
        double groundTop = site.getY();
        Vec3 deckStart = new Vec3(site.getX() + 15.5, site.getY() + c.deckCellY() + 1 + c.dy() + 0.05, site.getZ() + 0.5);
        Vec3 from = c.groundToDeck() ? Rig.standing(site.offset(-8, 0, 0)) : deckStart;
        BlockPos dest = c.groundToDeck() ? site.offset(15, 1, 0) : site.offset(-4, 0, 0);
        rig.walkLive(s, name, () -> {
            jumps[0] = 0;
            prevGround[0] = true;
            lastY[0] = from.y;
            return from;
        }, dest, true, 350, t -> {
            net.minecraft.world.entity.Mob m = rig.citizen;
            boolean g = m.onGround();
            if (prevGround[0] && !g && m.getY() > lastY[0] + 0.05) jumps[0]++;
            prevGround[0] = g;
            lastY[0] = m.getY();
        }, w -> {
            double lowest = Math.min(groundTop, groundTop + c.deckCellY() + 1 + c.dy()) - 0.6;
            boolean safe = w.mob.isAlive() && w.minY > lowest && w.teleportTick < 0;
            String obs = w.describe() + " jumps=" + jumps[0];
            if (c.expectArrive()) s.soft(name + ": arrives", w.walkedHonestly(), obs);
            else s.softNot(name + ": cannot get over the step (expected not to arrive)", w.arrived || w.teleportTick >= 0, obs);
            s.soft(name + ": no fall, no teleport, alive", safe, "minY=" + String.format("%.2f", w.minY) + " lowestAllowed=" + String.format("%.2f", lowest));
            if (!c.expectArrive()) s.soft(name + ": does not keep jumping at the step (at most 3 jumps)", jumps[0] <= 3, "jumps=" + jumps[0] + " in " + w.ticks + " ticks");
            else s.note("OBS " + name + ": jumps=" + jumps[0]);
        });
        s.step(name + ": remove", () -> { s.clearMonitors(); rig.removeShipSafely(ship[0], site.offset(-8, 0, 0)); });
    }
}
