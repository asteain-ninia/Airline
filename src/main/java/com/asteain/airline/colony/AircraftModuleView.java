package com.asteain.airline.colony;

import com.asteain.airline.Airline;
import com.asteain.airline.client.AircraftWindow;
import com.asteain.airline.flight.Flights;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Client side of the aircraft tab: what {@link AircraftModule} sent. */
public class AircraftModuleView extends AbstractBuildingModuleView {
    public static final String TAB_KEY = "com." + Airline.MOD_ID + ".gui.airfield.aircraft";

    /** Whether aircraft can be built at all (the mods the flying part needs are there); whether the airfield repairs its aircraft by itself. */
    public boolean available, selfRepair = true;
    /** The airfield's own aircraft: 0 none, 1 being built, 2 there is one; the translation key of its design's name; the blocks still to go while it is built. */
    public int own, ownNumber;
    public String ownDesign = "";
    /** The designs that can be built, in the order they are offered. */
    public final List<Flights.Design> designs = new ArrayList<>();
    /** Counted up with every new state from the server, so the window knows when to fill its list again. */
    public int version;

    @Override
    public void deserialize(@NotNull RegistryFriendlyByteBuf buf) {
        available = buf.readBoolean();
        selfRepair = buf.readBoolean();
        own = buf.readByte();
        ownDesign = buf.readUtf();
        ownNumber = buf.readInt();
        designs.clear();
        for (int i = buf.readVarInt(); i > 0; i--) designs.add(new Flights.Design(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readDouble(), buf.readVarLong()));
        version++;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BOWindow getWindow() {
        return new AircraftWindow(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/map.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable(TAB_KEY);
    }
}
