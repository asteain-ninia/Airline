package com.asteain.airline.pathing.mixin;

import com.asteain.airline.boarding.BoardingManager;
import com.asteain.airline.pathing.PathingHooks;
import com.asteain.airline.pathing.ShipPathPoints;
import com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate;
import com.minecolonies.core.entity.pathfinding.pathresults.PathResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A path over a ship is made of world coordinates that were right for the ship's pose when the search was created.
 * Each tick, before MineColonies follows the path:
 * - a citizen standing on a moving ship gets no path at all (its navigator is stopped and paused), so it is carried along
 *   instead of walking toward world positions the deck has left;
 * - a path that uses a ship is dropped as soon as that ship is gone, or the deck under a point of the path still ahead has
 *   moved further from that point than the discard distance;
 * - a citizen that is stopped by the real edge of a ship just below a step up onto that ship is helped onto it.
 */
@Mixin(value = MinecoloniesAdvancedPathNavigate.class, remap = false)
public abstract class MinecoloniesAdvancedPathNavigateMixin {
    @Unique private Path airline$checkedPath;
    @Unique private ShipPathPoints[] airline$pathShips;
    /** Remaining ticks of a hop in progress, and the real height of the step it is aimed at. */
    @Unique private int airline$hopTicks;
    @Unique private double airline$hopTop;
    /** The path node the hops so far were for, how many there were, and whether "too high" was already logged for it. */
    @Unique private int airline$hopNode;
    @Unique private int airline$hopCount;
    @Unique private boolean airline$hopRefusedLogged;
    @Unique private double[] airline$hopResult;

    @Inject(method = "tick", at = @At("HEAD"))
    private void airline$guardShipPath(final CallbackInfo ci) {
        final MinecoloniesAdvancedPathNavigate nav = (MinecoloniesAdvancedPathNavigate) (Object) this;
        final Mob mob = nav.getOurEntity();
        if (mob.level().isClientSide) return;
        // A citizen sitting on a boarding seat is looked after by the boarding code; stop() here would make it stand up.
        if (BoardingManager.isSeated(mob)) return;

        // Only a path that is still being followed counts. MineColonies keeps a finished path (and its result) around for a
        // while after arrival; stopping that one would do nothing useful and stop() also makes a seated citizen stand up.
        final Path current = nav.getPath();
        final Path path = current != null && !current.isDone() ? current : null;

        if (PathingHooks.ridesMovingShip(mob)) {
            final PathResult<?> result = nav.getPathResult();
            if (path != null || (result != null && result.isComputing())) nav.stop();
            nav.setPauseTicks(PathingHooks.MOVING_SHIP_PAUSE_TICKS);
            this.airline$checkedPath = null;
            this.airline$pathShips = null;
            return;
        }

        if (path != this.airline$checkedPath) {
            this.airline$checkedPath = path;
            this.airline$pathShips = path == null ? null : PathingHooks.shipPoints(path, nav.getPathResult());
            this.airline$hopTicks = 0;
            this.airline$hopNode = -1;
        }
        final ShipPathPoints[] ships = this.airline$pathShips;
        if (ships == null) return;
        if (!PathingHooks.shipsUnchanged(mob, path, ships)) {
            nav.stop();
            this.airline$checkedPath = null;
            this.airline$pathShips = null;
            return;
        }

        if (this.airline$hopTicks > 0) {
            this.airline$hopTicks--;
            PathingHooks.carryOverEdge(mob, path, this.airline$hopTop);
            return;
        }
        final int node = path.getNextNodeIndex();
        if (node != this.airline$hopNode) {
            this.airline$hopNode = node;
            this.airline$hopCount = 0;
            this.airline$hopRefusedLogged = false;
        }
        if (this.airline$hopCount >= PathingHooks.MAX_HOPS_PER_STEP) return;
        if (this.airline$hopResult == null) this.airline$hopResult = new double[1];
        final int hop = PathingHooks.hopOntoShipStep(mob, path, ships, this.airline$hopResult);
        if (hop == PathingHooks.HOP_STARTED) {
            this.airline$hopCount++;
            this.airline$hopTicks = PathingHooks.HOP_TICKS;
            this.airline$hopTop = this.airline$hopResult[0];
        } else if (hop == PathingHooks.HOP_TOO_HIGH && !this.airline$hopRefusedLogged) {
            this.airline$hopRefusedLogged = true;
            PathingHooks.logStepTooHigh(mob, this.airline$hopResult[0]);
        }
    }
}
