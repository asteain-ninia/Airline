package com.asteain.airline.pathing.mixin;

import com.asteain.airline.pathing.PathingHooks;
import com.asteain.airline.pathing.ShipOverlayHolder;
import com.asteain.airline.pathing.ShipOverlaySet;
import com.minecolonies.core.entity.pathfinding.MNode;
import com.minecolonies.core.entity.pathfinding.pathjobs.AbstractPathJob;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * handleTargetNotPassable decides whether a search may step one block up onto the block in front of a node. It compares
 * the tops of the two world cells. A ship's blocks are shown at the nearest world cells but really sit up to half a block
 * higher or lower, so a step the search takes for one block can really be 1.5 blocks, which no citizen can jump.
 * Such a step is refused here, so the search looks for another way instead of sending the citizen to hop at a wall.
 *
 * This only ever removes a step that involves a ship block; it never makes anything walkable.
 * Optional (require = 0): without it the search behaves as before and the hop in the navigator simply gives up there.
 */
@Mixin(value = AbstractPathJob.class, remap = false)
public abstract class AbstractPathJobMixin {
    @Shadow @Final protected LevelReader world;

    @Inject(method = "handleTargetNotPassable", at = @At("HEAD"), cancellable = true, require = 0)
    private void airline$noStepHigherThanAJump(final MNode parent, final int x, final int y, final int z, final BlockState target,
                                                         final CallbackInfoReturnable<Integer> cir) {
        if (parent == null || !(this.world instanceof ShipOverlayHolder holder)) return;
        final ShipOverlaySet ships = holder.airline$ships();
        if (ships == null) return;
        try {
            if (PathingHooks.stepTooHigh(ships, this.world, parent.x, parent.y, parent.z, x, y, z, target)) {
                cir.setReturnValue(Integer.MIN_VALUE);
            }
        } catch (RuntimeException ignored) {
            // no opinion: MineColonies decides as usual
        }
    }
}
