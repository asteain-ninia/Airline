package com.asteain.airline.block;

import com.asteain.airline.Airline;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The airfield's hut block. Shape taken from Volcanic Mason's VolcanicMasonHutBlock
 * (https://github.com/ferriarnus/VolcanicMason, Copyright (c) Ferri_Arnus, LGPL-3.0).
 */
public class BlockHutAirfield extends AbstractBlockHut<BlockHutAirfield> {
    @NotNull
    @Override
    public String getHutName() {
        return Airline.HUT_NAME;
    }

    @Override
    public BuildingEntry getBuildingEntry() {
        return Airline.AIRFIELD.get();
    }

    /** MineColonies answers with its own namespace here; the block is registered under this mod's id. */
    @Override
    public ResourceLocation getRegistryName() {
        return Airline.id(getHutName());
    }

    /** The parent would create MineColonies' block entity type, which rejects blocks of other mods. */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        AirfieldBlockEntity entity = Airline.AIRFIELD_BLOCK_ENTITY.get().create(pos, state);
        if (entity != null) entity.registryName = getBuildingEntry().getRegistryName();
        return entity;
    }
}
