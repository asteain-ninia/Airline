package com.asteain.airline.pathing.mixin;

import com.asteain.airline.pathing.PathingHooks;
import com.minecolonies.core.entity.pathfinding.PathfindingUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * prepareStart picks the cell a search starts from by reading the ordinary world. Under a citizen standing on a ship that
 * world is air, so the start slides down to whatever lies below the ship (or stays in mid-air over the void, which gives a
 * one-node path). For a citizen on a stopped ship the start is the cell above the deck block that carries it.
 */
@Mixin(value = PathfindingUtils.class, remap = false)
public abstract class PathfindingUtilsMixin {
    @Inject(method = "prepareStart", at = @At("HEAD"), cancellable = true)
    private static void airline$startOnShip(final LivingEntity entity, final CallbackInfoReturnable<BlockPos> cir) {
        final BlockPos start = PathingHooks.startOnShip(entity);
        if (start != null) cir.setReturnValue(start);
    }
}
