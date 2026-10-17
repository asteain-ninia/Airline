package com.asteain.airline.colony;

import com.asteain.airline.Airline;
import com.asteain.airline.client.WantsWindow;
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

/** Client side of the requests tab: what {@link WantsModule} sent. */
public class WantsModuleView extends AbstractBuildingModuleView {
    public static final String TAB_KEY = "com." + Airline.MOD_ID + ".gui.airfield.wants";

    public record Want(ResourceLocation item, int wanted, int onTheWay) {}

    /** A colony: its name, whether it is this airfield's own, what it wants, and how many more items there are than shown. */
    public record Row(String name, boolean own, List<Want> wants, int more) {}

    public final List<Row> rows = new ArrayList<>();
    public int version;

    @Override
    public void deserialize(@NotNull RegistryFriendlyByteBuf buf) {
        rows.clear();
        for (int i = buf.readVarInt(); i > 0; i--) {
            final String name = buf.readUtf();
            final boolean own = buf.readBoolean();
            final List<Want> wants = new ArrayList<>();
            for (int j = buf.readVarInt(); j > 0; j--) wants.add(new Want(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt()));
            rows.add(new Row(name, own, wants, buf.readVarInt()));
        }
        version++;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BOWindow getWindow() {
        return new WantsWindow(this);
    }

    @Override
    public ResourceLocation getIconResourceLocation() {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/info.png");
    }

    @Override
    public Component getDesc() {
        return Component.translatable(TAB_KEY);
    }
}
