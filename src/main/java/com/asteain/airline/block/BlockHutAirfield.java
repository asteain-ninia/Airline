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
    private final com.asteain.airline.route.Facility facility;
    /**
     * MineColonies' block asks for the hut's name while it is being made, before this block's own field is set: for
     * that moment the kind of place is kept here.
     */
    private static final ThreadLocal<com.asteain.airline.route.Facility> MAKING = new ThreadLocal<>();

    public BlockHutAirfield() {
        this(com.asteain.airline.route.Facility.SMALL_AIRFIELD);
    }

    /** The hut of a small, medium or large airfield, or of an anchorage. */
    public BlockHutAirfield(com.asteain.airline.route.Facility facility) {
        this(facility, making(facility));
    }

    private BlockHutAirfield(com.asteain.airline.route.Facility facility, boolean making) {
        super();
        this.facility = facility;
        MAKING.remove();
    }

    private static boolean making(com.asteain.airline.route.Facility facility) {
        MAKING.set(facility);
        return true;
    }

    /** The kind of place this hut is for. */
    public com.asteain.airline.route.Facility facility() {
        if (facility != null) return facility;
        final com.asteain.airline.route.Facility being = MAKING.get();
        return being != null ? being : com.asteain.airline.route.Facility.SMALL_AIRFIELD;
    }

    @NotNull
    @Override
    public String getHutName() {
        return Airline.hutName(facility());
    }

    @Override
    public BuildingEntry getBuildingEntry() {
        return Airline.building(facility());
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
