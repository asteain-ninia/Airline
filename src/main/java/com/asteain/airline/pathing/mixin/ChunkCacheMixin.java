package com.asteain.airline.pathing.mixin;

import com.asteain.airline.pathing.PathingHooks;
import com.asteain.airline.pathing.ShipOverlayHolder;
import com.asteain.airline.pathing.ShipOverlaySet;
import com.minecolonies.core.entity.pathfinding.world.ChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MineColonies' ChunkCache is the world a path search reads. It is built on the server thread when the search is created
 * and read on the path finding thread. Here it also receives immutable copies of the stopped Sable ships in its area,
 * and shows their blocks at the world cells they currently occupy.
 */
@Mixin(value = ChunkCache.class, remap = false)
public abstract class ChunkCacheMixin implements ShipOverlayHolder {
    @Shadow protected int chunkX;
    @Shadow protected int chunkZ;
    @Shadow protected LevelChunk[][] chunkArray;

    /** Written once in the constructor (server thread), before the search is handed to the path finding thread. */
    @Unique private ShipOverlaySet airline$ships;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void airline$captureShips(final Level world, final BlockPos from, final BlockPos to, final CallbackInfo ci) {
        final int chunksX = chunkArray.length;
        final int chunksZ = chunksX == 0 ? 0 : chunkArray[0].length;
        if (chunksX == 0 || chunksZ == 0) return;
        this.airline$ships = PathingHooks.collect(world, chunkX << 4, chunkZ << 4, ((chunkX + chunksX) << 4) - 1, ((chunkZ + chunksZ) << 4) - 1);
    }

    @Override
    public ShipOverlaySet airline$ships() {
        return this.airline$ships;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void airline$shipBlock(final BlockPos pos, final CallbackInfoReturnable<BlockState> cir) {
        final ShipOverlaySet ships = this.airline$ships;
        if (ships == null) return;
        final BlockState state = ships.stateAt(pos.getX(), pos.getY(), pos.getZ());
        if (state != null) cir.setReturnValue(state);
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void airline$shipFluid(final BlockPos pos, final CallbackInfoReturnable<FluidState> cir) {
        final ShipOverlaySet ships = this.airline$ships;
        if (ships == null) return;
        final BlockState state = ships.stateAt(pos.getX(), pos.getY(), pos.getZ());
        if (state != null) cir.setReturnValue(state.getFluidState());
    }

    /** A cell shown as a ship block has no block entity for the search: the real one lives in the ship's plot and belongs to the server thread. */
    @Inject(method = "getTileEntity", at = @At("HEAD"), cancellable = true)
    private void airline$noShipBlockEntity(final BlockPos pos, final LevelChunk.EntityCreationType type, final CallbackInfoReturnable<BlockEntity> cir) {
        final ShipOverlaySet ships = this.airline$ships;
        if (ships != null && ships.stateAt(pos.getX(), pos.getY(), pos.getZ()) != null) cir.setReturnValue(null);
    }
}
