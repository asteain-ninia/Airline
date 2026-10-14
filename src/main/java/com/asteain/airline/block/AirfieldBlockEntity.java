package com.asteain.airline.block;

import com.asteain.airline.Airline;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * MineColonies' building block entity under this mod's own type, so that the airfield hut is a valid block for it.
 * Same idea as Volcanic Mason's ModTileEntityColonyBuilding (https://github.com/ferriarnus/VolcanicMason,
 * Copyright (c) Ferri_Arnus, LGPL-3.0).
 */
public class AirfieldBlockEntity extends TileEntityColonyBuilding {
    public AirfieldBlockEntity(BlockPos pos, BlockState state) {
        super(Airline.AIRFIELD_BLOCK_ENTITY.get(), pos, state);
    }

    /**
     * MineColonies derives a missing blueprint path from a table of its own hut names; for a hut it does not know the result
     * is "null1.blueprint". Point such a hut at the blueprint this mod ships.
     */
    @Override
    public void loadAdditional(@NotNull CompoundTag compound, @NotNull HolderLookup.Provider provider) {
        super.loadAdditional(compound, provider);
        String path = getBlueprintPath();
        if (path == null || path.isEmpty() || path.contains("null")) {
            setBlueprintPath(Airline.BLUEPRINT_PATH);
            setPackName(Airline.PACK_NAME);
        }
    }
}
