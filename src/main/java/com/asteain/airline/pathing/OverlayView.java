package com.asteain.airline.pathing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/** Server thread only: the ordinary world with the blocks of stopped ships laid over it, the way a path search sees it. */
final class OverlayView implements BlockGetter {
    private final Level level;
    private final ShipOverlaySet ships;

    OverlayView(Level level, ShipOverlaySet ships) {
        this.level = level;
        this.ships = ships;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        final BlockState ship = ships.stateAt(pos.getX(), pos.getY(), pos.getZ());
        return ship != null ? ship : level.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        final BlockState ship = ships.stateAt(pos.getX(), pos.getY(), pos.getZ());
        return ship != null ? ship.getFluidState() : level.getFluidState(pos);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return ships.stateAt(pos.getX(), pos.getY(), pos.getZ()) != null ? null : level.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }
}
