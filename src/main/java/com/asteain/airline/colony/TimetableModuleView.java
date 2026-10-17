package com.asteain.airline.colony;

import com.asteain.airline.Airline;
import com.asteain.airline.client.TimetableWindow;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

/** Client side of the timetable tab: what {@link TimetableModule} sent. Times are game ticks. */
public class TimetableModuleView extends AbstractBuildingModuleView {
    public static final String TAB_KEY = "com." + Airline.MOD_ID + ".gui.airfield.timetable";

    /** An aircraft stands on this airfield's pad: what it is doing, when its berth is over, where it goes next ("" for nowhere yet) and when it is expected there. */
    public boolean standing;
    public String state = "", to = "";
    public long leaves, arrivesThere;
    /** An aircraft is on its way here (or will leave for here): in the air or still berthed, when it is expected, and whose it is. */
    public boolean coming, flying;
    public long arrivesHere;
    public String from = "";
    /**
     * The airfield's own aircraft: 0 none, 1 being built, 2 there is one. With it the translation key of its design's name;
     * the blocks still to be put up (being built) or the minutes of fuel in its tank (-1: not known now); how many kinds
     * of parts it lacks; whether it stands in the air without fuel.
     */
    public int own, ownNumber, ownLacks;
    public String ownDesign = "";
    public boolean ownDistress;

    @Override
    public void deserialize(@NotNull RegistryFriendlyByteBuf buf) {
        standing = buf.readBoolean();
        if (standing) {
            state = buf.readUtf();
            leaves = buf.readLong();
            to = buf.readUtf();
            arrivesThere = buf.readLong();
        }
        coming = buf.readBoolean();
        if (coming) {
            flying = buf.readBoolean();
            arrivesHere = buf.readLong();
            from = buf.readUtf();
        }
        own = buf.readByte();
        if (own != 0) {
            ownDesign = buf.readUtf();
            ownNumber = buf.readInt();
            ownLacks = buf.readInt();
            ownDistress = buf.readBoolean();
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BOWindow getWindow() {
        return new TimetableWindow(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath(com.asteain.airline.Airline.MOD_ID, "textures/gui/modules/timetable.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable(TAB_KEY);
    }
}
