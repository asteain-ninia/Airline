package com.asteain.airline.colony;

import com.asteain.airline.route.ColonyRoutes;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The airfield's routes tab (2026-10-18, the user's ruling: "ワールド中の任意の村を選択して、そこに航路を引くタブ"): every
 * other colony of the dimension, nearest first, whether a route is drawn to it, and for each class this colony can send
 * whether it can be flown or why not. A route is drawn or taken off with the row's button ({@link com.asteain.airline.network.RouteOrderPayload}).
 */
public class RoutesModule extends AbstractBuildingModule implements ITickingModule {
    private int ticks;

    @Override
    public void onColonyTick(@NotNull IColony colony) {
        // What the routes look like changes slowly; the tab is sent again now and then.
        if (++ticks % 5 == 0) markDirty();
    }

    @Override
    public void serializeToView(@NotNull RegistryFriendlyByteBuf buf) {
        final IColony own = building.getColony();
        if (!(own.getWorld() instanceof ServerLevel level)) {
            buf.writeVarInt(0);
            return;
        }
        final List<IColony> others = new ArrayList<>();
        for (final IColony colony : IColonyManager.getInstance().getColonies(level)) if (colony != own) others.add(colony);
        others.sort(Comparator.comparingDouble(c -> c.getCenter().distSqr(own.getCenter())));
        buf.writeVarInt(others.size());
        for (final IColony other : others) {
            buf.writeVarInt(other.getID());
            buf.writeUtf(other.getName());
            buf.writeVarInt((int) Math.round(Math.sqrt(other.getCenter().distSqr(own.getCenter()))));
            buf.writeBoolean(ColonyRoutes.drawn(level, own.getID(), other.getID()));
            final List<ColonyRoutes.ClassStatus> status = ColonyRoutes.status(level, own.getID(), other.getID());
            buf.writeVarInt(status.size());
            for (final ColonyRoutes.ClassStatus line : status) {
                buf.writeBoolean(line.open());
                ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, line.text());
            }
        }
    }
}
