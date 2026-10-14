package com.asteain.airline.colony;

import com.asteain.airline.flight.Cargo;
import com.asteain.airline.flight.Timetable;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The airfield's timetable tab (ruling of 2026-10-11): when the aircraft that stands on the pad leaves, and when the
 * next one comes in. The module keeps nothing itself; it sends what {@link Timetable} knows to whoever has the
 * airfield's window open. Times go over as game ticks, so the window can count them down by itself.
 */
public class TimetableModule extends AbstractBuildingModule implements ITickingModule {
    @Override
    public void onColonyTick(@NotNull IColony colony) {
        // Sent again with every colony tick: plans change (a destination is chosen, a flight is called off).
        markDirty();
    }

    @Override
    public void serializeToView(@NotNull RegistryFriendlyByteBuf buf) {
        Timetable.Entry standing = null, coming = null;
        ServerLevel level = null;
        if (building instanceof BuildingAirfield airfield && building.getColony().getWorld() instanceof ServerLevel world) {
            level = world;
            final Timetable table = Timetable.get(world.getServer());
            final String dimension = world.dimension().location().toString();
            // Over all pads: the aircraft that stands here (the airfield's own pad first), and the one expected first.
            for (final BlockPos pad : airfield.pads()) {
                if (standing == null) standing = table.standing(dimension, pad);
                final Timetable.Entry next = table.coming(dimension, pad);
                if (next != null && (coming == null || next.arrives() < coming.arrives())) coming = next;
            }
        }
        buf.writeBoolean(standing != null);
        if (standing != null) {
            buf.writeUtf(standing.state());
            buf.writeLong(standing.leaves());
            buf.writeUtf(name(level, standing.to()));
            buf.writeLong(standing.arrives());
        }
        buf.writeBoolean(coming != null);
        if (coming != null) {
            buf.writeBoolean(coming.flying());
            buf.writeLong(coming.arrives());
            buf.writeUtf(name(level, coming.home()));
        }
        // The airfield's own aircraft: none (0), being built (1: design, blocks to go), or there is one (2: design, minutes of
        // fuel or -1 when it is not loaded, kinds of parts it lacks, whether it is in distress).
        final int[] own = level != null && building instanceof BuildingAirfield airfield ? com.asteain.airline.flight.Flights.ownAircraft(level, airfield.pad(), ownDesign) : new int[] {0, 0, 0, 0};
        buf.writeByte(own[0]);
        if (own[0] != 0) {
            buf.writeUtf(ownDesign[0]);
            buf.writeInt(own[1]);
            buf.writeInt(own[2]);
            buf.writeBoolean(own[3] != 0);
        }
    }

    private final String[] ownDesign = {""};

    /** The name of the colony whose airfield has this pad; the place itself when no airfield is known there; "" for no place. */
    private static String name(ServerLevel level, @Nullable BlockPos pad) {
        if (pad == null) return "";
        final BuildingAirfield airfield = Cargo.airfieldAt(level, pad);
        return airfield == null ? pad.toShortString() : airfield.getColony().getName();
    }
}
