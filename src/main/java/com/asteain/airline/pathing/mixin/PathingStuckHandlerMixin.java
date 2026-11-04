package com.asteain.airline.pathing.mixin;

import com.asteain.airline.boarding.BoardingManager;
import com.asteain.airline.pathing.PathingHooks;
import com.minecolonies.api.entity.pathfinding.IMinecoloniesNavigator;
import com.minecolonies.api.entity.pathfinding.IStuckHandler;
import com.minecolonies.core.entity.pathfinding.navigation.PathingStuckHandler;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The stuck handler teleports a citizen along its path or next to its destination. On a moving ship both targets are
 * world positions the deck has left, so the citizen would be put into mid-air. While a citizen rides a moving ship the
 * stuck timers are held at zero; they start again once the ship has stopped. The same goes for a citizen sitting on a
 * boarding seat: it is meant to sit still.
 */
@Mixin(value = PathingStuckHandler.class, remap = false)
public abstract class PathingStuckHandlerMixin {
    @Inject(method = "checkStuck", at = @At("HEAD"), cancellable = true)
    private void airline$noStuckActionOnMovingShip(final PathNavigation navigator, final CallbackInfo ci) {
        if (navigator instanceof IMinecoloniesNavigator nav
                && (PathingHooks.ridesMovingShip(nav.getOurEntity()) || BoardingManager.isSeated(nav.getOurEntity()))) {
            ((IStuckHandler<?>) (Object) this).resetGlobalStuckTimers();
            ci.cancel();
        }
    }

    /**
     * "Teleport a few nodes ahead" onto a node that lies on a stopped ship: land from just above the deck instead of at the
     * node's own height, which can be inside the deck when the ship is off the block grid. Optional (require = 0): if
     * MineColonies changes this method, the plain teleport stays.
     */
    @Redirect(method = "tryUnstuck", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;teleportTo(DDD)V"), require = 0)
    private void airline$teleportOntoDeck(final Mob mob, final double x, final double y, final double z) {
        mob.teleportTo(x, PathingHooks.teleportHeight(mob, x, y, z), z);
    }
}
