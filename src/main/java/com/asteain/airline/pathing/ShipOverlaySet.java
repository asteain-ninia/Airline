package com.asteain.airline.pathing;

import net.minecraft.world.level.block.state.BlockState;

/** The stopped ships one path search may walk on. Immutable; safe to read from the path finding thread. */
public final class ShipOverlaySet {
    private final ShipSnapshot[] ships;

    ShipOverlaySet(ShipSnapshot[] ships) {
        this.ships = ships;
    }

    /** The non-air ship block shown at this world cell, or null when no ship has a block there. */
    public BlockState stateAt(int x, int y, int z) {
        for (final ShipSnapshot ship : ships) {
            final BlockState state = ship.stateAt(x, y, z);
            if (state != null) return state;
        }
        return null;
    }

    /** World height of the real top face of the ship block shown at this world cell, or NaN when no ship has a block there. */
    public double surfaceY(int x, int y, int z) {
        for (final ShipSnapshot ship : ships) {
            final double top = ship.surfaceY(x, y, z);
            if (!Double.isNaN(top)) return top;
        }
        return Double.NaN;
    }

    /** The ship that provides a block at this world cell, or null. */
    ShipSnapshot shipAt(int x, int y, int z) {
        for (final ShipSnapshot ship : ships) {
            if (ship.stateAt(x, y, z) != null) return ship;
        }
        return null;
    }
}
