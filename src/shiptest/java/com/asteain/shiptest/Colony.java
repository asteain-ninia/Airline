package com.asteain.shiptest;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.colony.managers.interfaces.ICitizenManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/** Colony, town hall, citizen and platform fixtures (same MineColonies calls Nevi's regression uses). */
final class Colony {
    private Colony() {}

    /** Stone floor (top block at y = origin.y - 1) of half-size rx/rz around origin, chunks forced. */
    static void platform(ServerLevel level, BlockPos origin, int rx, int rz) {
        for (int cx = (origin.getX() - rx) >> 4; cx <= (origin.getX() + rx) >> 4; cx++)
            for (int cz = (origin.getZ() - rz) >> 4; cz <= (origin.getZ() + rz) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        for (int x = -rx; x <= rx; x++) for (int z = -rz; z <= rz; z++)
            level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
    }

    static int existing() {
        return IColonyManager.getInstance().getAllColonies().size();
    }

    static IColony create(ServerLevel level, BlockPos origin, String name) {
        Player owner = FakePlayerFactory.getMinecraft(level);
        owner.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        IColony colony = IColonyManager.getInstance().createColony(level, origin, owner, name, "minecolonies:medievaloak");
        if (colony == null) throw new AssertionError("MineColonies could not create a colony at " + origin);
        return colony;
    }

    /** Registers a level-1 town hall at pos (private RegisteredStructureManager.addBuilding, as Nevi does for a guard tower). */
    static IBuilding townHall(IColony colony, BlockPos pos) throws ReflectiveOperationException {
        BuildingEntry entry = ModBuildings.townHall.get();
        IBuilding hall = entry.produceBuilding(pos, colony);
        hall.getClass().getMethod("setBuildingLevel", int.class).invoke(hall, 1);
        hall.getClass().getMethod("setCorners", BlockPos.class, BlockPos.class).invoke(hall, pos.offset(-1, 0, -1), pos.offset(1, 2, 1));
        hall.getClass().getMethod("setParent", BlockPos.class).invoke(hall, BlockPos.ZERO);
        Class<?> rotation = Class.forName("com.ldtteam.structurize.api.RotationMirror");
        hall.getClass().getMethod("setRotationMirror", rotation).invoke(hall, rotation.getField("NONE").get(null));
        Object manager = colony.getServerBuildingManager();
        Method add = manager.getClass().getDeclaredMethod("addBuilding", IBuilding.class);
        add.setAccessible(true);
        add.invoke(manager, hall);
        return hall;
    }

    /**
     * Registers a building of the given kind and level at pos, the same way as {@link #townHall}. The public
     * addNewBuilding is not usable here: it looks up the colony's structure pack, which a bare test server does not have.
     */
    static IBuilding building(IColony colony, BuildingEntry entry, BlockPos pos, int level) throws ReflectiveOperationException {
        IBuilding building = entry.produceBuilding(pos, colony);
        building.getClass().getMethod("setBuildingLevel", int.class).invoke(building, level);
        building.getClass().getMethod("setCorners", BlockPos.class, BlockPos.class).invoke(building, pos.offset(-1, 0, -1), pos.offset(1, 2, 1));
        building.getClass().getMethod("setParent", BlockPos.class).invoke(building, BlockPos.ZERO);
        Class<?> rotation = Class.forName("com.ldtteam.structurize.api.RotationMirror");
        building.getClass().getMethod("setRotationMirror", rotation).invoke(building, rotation.getField("NONE").get(null));
        Object manager = colony.getServerBuildingManager();
        Method add = manager.getClass().getDeclaredMethod("addBuilding", IBuilding.class);
        add.setAccessible(true);
        add.invoke(manager, building);
        return building;
    }

    /** Creates citizen data (jobless) and asks for the entity to be spawned; poll {@link #entity}. */
    static ICitizenData spawn(IColony colony, ServerLevel level, BlockPos at) throws ReflectiveOperationException {
        ICitizenManager cm = colony.getCitizenManager();
        cm.setMaxCitizens(5);
        ICitizenData data = cm.createAndRegisterCivilianData();
        Class<?> civilian = Class.forName("com.minecolonies.api.colony.ICivilianData");
        cm.getClass().getMethod("spawnOrCreateCivilian", civilian, Level.class, List.class, boolean.class)
                .invoke(cm, data, level, List.of(at), true);
        return data;
    }

    static Mob entity(ICitizenData data) {
        Optional<?> e = data.getEntity();
        return e.isPresent() ? (Mob) e.get() : null;
    }
}
