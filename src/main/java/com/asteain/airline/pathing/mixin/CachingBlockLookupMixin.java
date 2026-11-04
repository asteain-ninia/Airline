package com.asteain.airline.pathing.mixin;

import com.asteain.airline.pathing.ShipOverlayHolder;
import com.asteain.airline.pathing.ShipOverlaySet;
import com.minecolonies.core.entity.pathfinding.world.CachingBlockLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The search reads almost every block through this 5x5x5 cache, and the cache reads the chunk directly instead of going
 * through ChunkCache.getBlockState. Without this redirect the ships laid over the ChunkCache would be skipped.
 * (The other two reads in the method call ChunkCache.getBlockState and are covered there.)
 */
@Mixin(value = CachingBlockLookup.class, remap = false)
public abstract class CachingBlockLookupMixin {
    @Shadow @Final private LevelReader world;

    @Redirect(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState airline$shipBlockFirst(final ChunkAccess chunk, final BlockPos pos) {
        if (this.world instanceof ShipOverlayHolder holder) {
            final ShipOverlaySet ships = holder.airline$ships();
            if (ships != null) {
                final BlockState state = ships.stateAt(pos.getX(), pos.getY(), pos.getZ());
                if (state != null) return state;
            }
        }
        return chunk.getBlockState(pos);
    }
}
