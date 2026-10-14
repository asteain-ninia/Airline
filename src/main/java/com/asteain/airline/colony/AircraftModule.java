package com.asteain.airline.colony;

import com.asteain.airline.flight.Flights;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The airfield's aircraft tab. Building an aircraft is the airfield's business alone (the user's ruling of 2026-10-14):
 * here the airfield's own aircraft is shown as it stands (none, being built, there is one), with every design that can
 * be built and a button for each, which asks the builders for it ({@link com.asteain.airline.network.AircraftOrderPayload}).
 * Besides, it keeps whether the airfield repairs its aircraft by itself.
 */
public class AircraftModule extends AbstractBuildingModule implements ITickingModule, IPersistentModule {
    /**
     * Whether the airfield puts its aircraft back the way the design has it while it stands at home (taking out what was
     * added, putting in what is missing from the racks, not flying while parts are missing). On unless switched off in
     * this tab (the user's ask of 2026-10-14: to change an aircraft by hand).
     */
    private boolean selfRepair = true;

    public boolean selfRepair() {
        return selfRepair;
    }

    public void selfRepair(boolean on) {
        selfRepair = on;
        markDirty();
    }

    @Override
    public void serializeNBT(@NotNull HolderLookup.Provider provider, CompoundTag tag) {
        if (!selfRepair) tag.putBoolean("selfRepair", false);
    }

    @Override
    public void deserializeNBT(@NotNull HolderLookup.Provider provider, CompoundTag tag) {
        selfRepair = !tag.contains("selfRepair") || tag.getBoolean("selfRepair");
    }

    @Override
    public void onColonyTick(@NotNull IColony colony) {
        // Sent again with every colony tick: a site goes on (blocks put up), an aircraft is made.
        markDirty();
    }

    @Override
    public void serializeToView(@NotNull RegistryFriendlyByteBuf buf) {
        List<Flights.Design> designs = List.of();
        final String[] design = {""};
        int[] own = {0, 0, 0, 0};
        if (building instanceof BuildingAirfield airfield) {
            designs = Flights.designCards(airfield);
            if (building.getColony().getWorld() instanceof ServerLevel level) own = Flights.ownAircraft(level, airfield.pad(), design);
        }
        buf.writeBoolean(Flights.available());
        buf.writeBoolean(selfRepair);
        // The airfield's own aircraft: 0 none, 1 being built (blocks still to go), 2 there is one; and its design's title.
        buf.writeByte(own[0]);
        buf.writeUtf(design[0]);
        buf.writeInt(own[1]);
        buf.writeVarInt(designs.size());
        for (final Flights.Design card : designs) {
            buf.writeUtf(card.name());
            buf.writeUtf(card.title());
            buf.writeVarInt(card.blocks());
            buf.writeDouble(card.topSpeed());
            buf.writeVarLong(card.tankMinutes());
        }
    }
}
