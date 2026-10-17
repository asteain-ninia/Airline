package com.asteain.airlinetest;

import com.ldtteam.structurize.api.RotationMirror;
import com.minecolonies.api.blocks.AbstractColonyBlock;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.managers.interfaces.ICitizenManager;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Colony, building, rack and citizen fixtures (the colony part is the one ColonyPatchServer's checks use). */
final class Fixture {
    /**
     * How far airfields reach, in chunks, by building level from level 1 on (0: as it follows from MineColonies' settings).
     * Levels that are not named keep theirs.
     */
    static void airfieldReachChunks(int... byLevel) {
        for (int level = 1; level <= byLevel.length; level++) com.asteain.airline.route.RouteRules.airfieldReachSetting(level).set(byLevel[level - 1]);
    }

    /**
     * The scenes of these suites were laid out when every airfield reached as far as a grade 1 buoy (48 blocks, 3 chunks),
     * and they look at how things work, not at how far an airfield reaches: they run with that reach, and without the
     * rule of the large maps (every scene lies on one large map, where all airfields would connect directly). (What the
     * reach is as the mod comes, and what follows from it, is looked at in sections G and H of the network suite.)
     */
    static void reachAsLaidOut() {
        airfieldReachChunks(3, 3, 3, 3, 3);
        largeMapChunks(0);
        // Every pair of colonies counts as having a route drawn (2026-10-18; the scene of the drawn routes switches it off).
        com.asteain.airline.route.ColonyRoutes.allForChecks(true);
    }

    /** The side of a large map in chunks (airfields reach each other by large maps); 0 switches that rule off. */
    static void largeMapChunks(int side) {
        com.asteain.airline.route.RouteRules.largeMapSetting().set(side);
    }

    private Fixture() {}

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

    static Player owner(ServerLevel level) {
        return FakePlayerFactory.getMinecraft(level);
    }

    static IColony create(ServerLevel level, BlockPos origin, String name) {
        Player owner = owner(level);
        owner.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        IColony colony = IColonyManager.getInstance().createColony(level, origin, owner, name, "Medieval Oak");
        if (colony == null) throw new AssertionError("MineColonies could not create a colony at " + origin);
        return colony;
    }

    /** Registers a level-1 town hall at pos without a block (private RegisteredStructureManager.addBuilding). */
    static IBuilding townHall(IColony colony, BlockPos pos) throws ReflectiveOperationException {
        IBuilding hall = ModBuildings.townHall.get().produceBuilding(pos, colony);
        hall.setBuildingLevel(1);
        hall.setCorners(pos.offset(-1, 0, -1), pos.offset(1, 2, 1));
        hall.setParent(BlockPos.ZERO);
        hall.setRotationMirror(RotationMirror.NONE);
        Object manager = colony.getServerBuildingManager();
        Method add = manager.getClass().getDeclaredMethod("addBuilding", IBuilding.class);
        add.setAccessible(true);
        add.invoke(manager, hall);
        return hall;
    }

    /**
     * A town hall like {@link #townHall}, with its hut block standing in the world. The building then has an inventory
     * (it finds the block's own storage by itself), so a courier can deliver to it. Without the block a delivery to the
     * town hall fails inside MineColonies and the delivered goods are lost.
     */
    static IBuilding townHallWithBlock(ServerLevel level, IColony colony, BlockPos pos) throws ReflectiveOperationException {
        IBuilding hall = townHall(colony, pos);
        level.setBlockAndUpdate(pos, ModBlocks.blockHutTownHall.defaultBlockState());
        if (hall.getItemHandlerCap() == null) throw new AssertionError("the town hall at " + pos + " has no inventory");
        return hall;
    }

    /** Makes the entity of a citizen anew after it was removed (a colony nobody is near does not do that by itself). */
    static void respawn(IColony colony, ICitizenData data, ServerLevel level, BlockPos at) throws ReflectiveOperationException {
        ICitizenManager cm = colony.getCitizenManager();
        Class<?> civilian = Class.forName("com.minecolonies.api.colony.ICivilianData");
        cm.getClass().getMethod("spawnOrCreateCivilian", civilian, net.minecraft.world.level.Level.class, List.class, boolean.class)
                .invoke(cm, data, level, List.of(at), true);
    }

    /** Places a hut block the way a player's hand would (Block#setPlacedBy registers the building with the colony). */
    static AbstractBuilding placeHut(ServerLevel level, IColony colony, AbstractColonyBlock<?> hut, BlockPos pos) {
        BlockState state = hut.defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        hut.setPlacedBy(level, pos, state, owner(level), new ItemStack(hut));
        IBuilding building = colony.getServerBuildingManager().getBuilding(pos);
        if (!(building instanceof AbstractBuilding)) throw new AssertionError("no building registered for " + hut + " at " + pos + ": " + building);
        return (AbstractBuilding) building;
    }

    /** Places a rack and registers it as a container of the building. */
    static TileEntityRack rack(ServerLevel level, AbstractBuilding building, BlockPos pos) {
        BlockState state = ModBlocks.blockRack.defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        building.registerBlockPosition(state, pos, level);
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof TileEntityRack rack)) throw new AssertionError("no rack block entity at " + pos + ": " + entity);
        return rack;
    }

    static void stock(TileEntityRack rack, Item item, int count) {
        ItemStack rest = new ItemStack(item, count);
        for (int slot = 0; slot < rack.getInventory().getSlots() && !rest.isEmpty(); slot++) rest = rack.getInventory().insertItem(slot, rest, false);
        if (!rest.isEmpty()) throw new AssertionError("rack is full, could not insert " + rest);
        rack.updateItemStorage();
    }

    static void empty(TileEntityRack rack) {
        for (int slot = 0; slot < rack.getInventory().getSlots(); slot++) rack.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
        rack.updateItemStorage();
    }

    static int count(IBuilding building, Item item) {
        Predicate<ItemStack> same = stack -> stack.is(item);
        return InventoryUtils.hasBuildingEnoughElseCount(building, same, Integer.MAX_VALUE);
    }

    /** Creates citizen data (jobless) and asks for the entity to be spawned; poll {@link #entity}. */
    static ICitizenData spawn(IColony colony, ServerLevel level, BlockPos at) throws ReflectiveOperationException {
        ICitizenManager cm = colony.getCitizenManager();
        cm.setMaxCitizens(10);
        ICitizenData data = cm.createAndRegisterCivilianData();
        Class<?> civilian = Class.forName("com.minecolonies.api.colony.ICivilianData");
        cm.getClass().getMethod("spawnOrCreateCivilian", civilian, net.minecraft.world.level.Level.class, List.class, boolean.class)
                .invoke(cm, data, level, List.of(at), true);
        return data;
    }

    static Mob entity(ICitizenData data) {
        Optional<?> e = data.getEntity();
        return e.isPresent() ? (Mob) e.get() : null;
    }
}
