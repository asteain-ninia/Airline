package com.asteain.airline.colony;

import com.asteain.airline.Airline;
import com.asteain.airline.flight.Cargo;
import com.minecolonies.api.util.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Telling people about the air network in terms of colonies: which colony a landing pad belongs to, and the notice to
 * both colonies when a way between their airfields comes into being or is lost (MineColonies tells colonies the same
 * way when their sign chains meet).
 */
public final class AirLinks {
    private AirLinks() {}

    /** The colony's name for a landing pad that belongs to an airfield; the place itself otherwise. */
    public static String name(ServerLevel level, BlockPos pad) {
        final BuildingAirfield airfield = Cargo.airfieldAt(level, pad);
        return airfield == null ? pad.toShortString() : airfield.getColony().getName();
    }

    /** A way between two landing pads exists now, or does not any more: the managers of both colonies are told. */
    public static void changed(ServerLevel level, BlockPos a, BlockPos b, boolean linked, double length) {
        final BuildingAirfield one = Cargo.airfieldAt(level, a), two = Cargo.airfieldAt(level, b);
        if (one == null || two == null || one == two) return;
        // Told once for two airfields: by their own pads. (The pads for visitors come and go with them.)
        if (!a.equals(one.pad()) || !b.equals(two.pad())) return;
        final String key = "message." + Airline.MOD_ID + (linked ? ".link.made" : ".link.lost");
        final Object[] args = {one.getColony().getName(), two.getColony().getName(), String.valueOf(Math.round(length))};
        MessageUtils.format(key, args).sendTo(one.getColony()).forManagers();
        if (two.getColony() != one.getColony()) MessageUtils.format(key, args).sendTo(two.getColony()).forManagers();
    }
}
