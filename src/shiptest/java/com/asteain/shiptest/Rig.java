package com.asteain.shiptest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/** Shared fixture: one colony with one jobless citizen, calm weather, plus helpers for ships and walks. */
final class Rig {
    final MinecraftServer server;
    final ServerLevel level;
    final BlockPos origin;
    ICitizenData data;
    IColony colony;
    Mob citizen;
    PathingSuite.Walk walk;

    Rig(MinecraftServer server, BlockPos origin) {
        this.server = server;
        this.level = server.overworld();
        this.origin = origin;
    }

    /** Unique, modest-coordinate site per run (the world is never deleted). */
    static BlockPos site(ServerLevel level, int xBase) {
        // Small coordinates: Sable's pose precision degrades far from the origin (a ship pushed at z >= 8192 once barely moved).
        BlockPos spawn = level.getSharedSpawnPos();
        int idx = (int) ((System.currentTimeMillis() / 1000L) % 400L);
        return new BlockPos(spawn.getX() + 512 + 256 * (idx % 20), 100, spawn.getZ() + 256 * (idx / 20));
    }

    void addColony(Script s, String name, int platformRx, int platformRz) {
        s.step("colony and citizen", () -> {
            level.setDayTime(1000);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            level.setWeatherParameters(6000, 0, false, false);
            Colony.platform(level, origin, platformRx, platformRz);
            colony = Colony.create(level, origin, name + " " + Colony.existing());
            Colony.townHall(colony, origin);
            data = Colony.spawn(colony, level, origin);
        });
        s.until("citizen spawns", () -> {}, () -> (citizen = Colony.entity(data)) != null, 100);
    }

    static void force(ServerLevel level, BlockPos centre, int rx, int rz) {
        for (int cx = (centre.getX() - rx) >> 4; cx <= (centre.getX() + rx) >> 4; cx++)
            for (int cz = (centre.getZ() - rz) >> 4; cz <= (centre.getZ() + rz) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
    }

    /** Assembles the placed blocks (anchor = min corner). */
    Object assemble(List<BlockPos> blocks) throws Exception {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
        }
        BlockPos min = new BlockPos(x0, y0, z0);
        return Ships.assemble(level, blocks, min, new BlockPos(x1, y1, z1), min);
    }

    void hold() throws Exception {
        Object ai = citizen.getClass().getMethod("getCitizenAI").invoke(citizen);
        try { ai.getClass().getMethod("setCurrentDelay", int.class).invoke(ai, 5000); } catch (java.lang.reflect.InvocationTargetException ignored) { }
    }

    /** Adds a walk (observed per tick until arrival or the tick limit), then hands the finished walk to the verdict. */
    void walk(Script s, String name, Vec3 from, BlockPos dest, boolean noTeleport, int limit, Consumer<PathingSuite.Walk> verdict) {
        walkLive(s, name, () -> from, dest, noTeleport, limit, t -> {}, verdict);
    }

    /** Like walk, but the start is computed when the step runs (null = start where the citizen is) and a hook runs before each tick (argument = walk ticks so far). */
    void walkLive(Script s, String name, java.util.function.Supplier<Vec3> from, BlockPos dest, boolean noTeleport, int limit,
                  java.util.function.IntConsumer tickHook, Consumer<PathingSuite.Walk> verdict) {
        walkLive2(s, name, from, () -> dest, noTeleport, limit, tickHook, verdict);
    }

    void walkLive2(Script s, String name, java.util.function.Supplier<Vec3> from, java.util.function.Supplier<BlockPos> dest, boolean noTeleport, int limit,
                   java.util.function.IntConsumer tickHook, Consumer<PathingSuite.Walk> verdict) {
        // a citizen that died in an earlier (failed) walk is replaced, so one fall does not invalidate the walks after it
        s.until("citizen ready for " + name, () -> {
            if (!citizen.isAlive()) { data = Colony.spawn(colony, level, origin); citizen = null; }
        }, () -> { if (citizen == null) citizen = Colony.entity(data); return citizen != null && citizen.isAlive(); }, 300);
        s.until("walk " + name, () -> {
            Vec3 f = from.get();
            if (f != null) citizen.setPos(f);
            hold();
            walk = new PathingSuite.Walk(name, citizen, f != null ? f : citizen.position(), dest.get(), noTeleport);
            walk.limit = limit;
        }, () -> { tickHook.accept(walk.ticks); return walk.tick(); }, limit + 20);
        s.step("verdict " + name, () -> verdict.accept(walk));
    }

    static Vec3 standing(BlockPos feet) { return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5); }

    /** Moves the citizen to solid ground first (a citizen standing on a removed ship would fall into the void and die). */
    void removeShipSafely(Object ship, BlockPos groundSpot) {
        citizen.setPos(standing(groundSpot));
        citizen.fallDistance = 0;
        remove(ship);
        try { Ships.removeNear(level, Vec3.atCenterOf(groundSpot), 80); } catch (Throwable t) { throw new AssertionError("could not remove leftover ships", t); }
    }

    void remove(Object ship) {
        try { if (ship != null && !Ships.isRemoved(ship)) Ships.remove(level, ship); } catch (Throwable t) { throw new AssertionError("could not remove ship", t); }
    }
}
