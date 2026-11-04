package com.asteain.shiptest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** MineColonies + Sable + Create + Airline start, a colony and a citizen exist, a small ship assembles, nothing throws. */
final class SmokeSuite {
    private SmokeSuite() {}

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "smoke";
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos origin = new BlockPos(spawn.getX() + 2048 + 256 * Colony.existing(), 100, spawn.getZ());
        Object[] ship = new Object[1];
        ICitizenData[] data = new ICitizenData[1];
        Mob[] citizen = new Mob[1];
        Vec3[] citizenStart = new Vec3[1];
        Vec3[] shipStart = new Vec3[1];

        s.step("mods", () -> {
            ModList mods = ModList.get();
            for (String id : new String[]{"minecolonies", "airline", "shiptest", "sable", "create"})
                s.check(mods.isLoaded(id), "mod loaded: " + id);
            s.note("OBS mods simulated=" + mods.isLoaded("simulated") + " aeronautics=" + mods.isLoaded("aeronautics")
                    + " offroad=" + mods.isLoaded("offroad") + " sablecompanion=" + mods.isLoaded("sablecompanion"));
            s.note("OBS world spawn=" + spawn + " origin=" + origin);
        });
        s.step("platform+colony", () -> {
            level.setDayTime(1000);
            Colony.platform(level, origin, 16, 16);
            IColony colony = Colony.create(level, origin, "CPS smoke " + Colony.existing());
            Colony.townHall(colony, origin);
            s.check(colony.getServerBuildingManager().getTownHall() != null, "colony has a town hall");
            data[0] = Colony.spawn(colony, level, origin);
        });
        s.until("citizen spawns", () -> {}, () -> (citizen[0] = Colony.entity(data[0])) != null, 100);
        s.step("citizen placed", () -> {
            citizen[0].setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
            citizenStart[0] = citizen[0].position();
            s.check(citizen[0].isAlive(), "citizen entity is alive at " + citizenStart[0]);
        });
        s.step("assemble ship", () -> {
            BlockPos min = origin.offset(20, -1, -1), max = origin.offset(22, -1, 4);
            level.setChunkForced((origin.getX() + 21) >> 4, origin.getZ() >> 4, true);
            level.getChunk((origin.getX() + 21) >> 4, origin.getZ() >> 4);
            ship[0] = Ships.assemblePlanks(level, min, max, min);
            s.check(ship[0] != null, "planks became a sub-level");
            s.check(level.getBlockState(min).isAir(), "ship blocks left the ordinary world");
            shipStart[0] = Ships.position(ship[0]);
            s.note("OBS ship pose after assembly = " + shipStart[0] + " velocity=" + Ships.velocity(ship[0]));
        });
        s.settle("let physics run", 100);
        s.step("observe", () -> {
            Vec3 now = Ships.position(ship[0]);
            Vec3 v = Ships.velocity(ship[0]);
            s.note("OBS zero-gravity " + (now.distanceTo(shipStart[0]) < 0.25 ? "EFFECTIVE" : "NOT EFFECTIVE")
                    + ": ship moved " + String.format("%.3f", now.distanceTo(shipStart[0])) + " blocks in 100 ticks, velocity=" + v);
            double jump = citizen[0].position().distanceTo(citizenStart[0]);
            s.note("OBS citizen idle drift after 100+ ticks = " + String.format("%.2f", jump) + " blocks");
            s.check(citizen[0].isAlive(), "citizen still alive");
        });
        s.step("push ship", () -> Ships.impulse(ship[0], 4, 0, 0));
        s.settle("ship glides", 20);
        s.step("physics is alive", () -> {
            Vec3 v = Ships.velocity(ship[0]);
            double moved = Ships.position(ship[0]).distanceTo(shipStart[0]);
            s.note("OBS after a linear impulse of 4: velocity=" + v + " moved=" + String.format("%.2f", moved));
            s.check(moved > 0.05 && v.length() > 0.01, "the physics engine moves the ship when pushed (so the zero reading is not a frozen engine)");
        });
        s.onFinish(() -> {
            try { if (ship[0] != null && !Ships.isRemoved(ship[0])) Ships.remove(level, ship[0]); }
            catch (Throwable t) { throw new AssertionError("could not remove test ship", t); }
        });
        return s;
    }
}
