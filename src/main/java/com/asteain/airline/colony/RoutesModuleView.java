package com.asteain.airline.colony;

import com.asteain.airline.Airline;
import com.asteain.airline.client.RoutesWindow;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Client side of the routes tab: what {@link RoutesModule} sent. */
public class RoutesModuleView extends AbstractBuildingModuleView {
    public static final String TAB_KEY = "com." + Airline.MOD_ID + ".gui.airfield.routes";

    /** Another colony: its id and name, how far its town hall is, whether a route is drawn to it, and a line for each class. */
    public record Row(int colony, String name, int distance, boolean drawn, List<Boolean> open, List<Component> lines) {}

    public final List<Row> rows = new ArrayList<>();
    /** Counted up with every new state from the server, so the window knows when to fill its list again. */
    public int version;

    @Override
    public void deserialize(@NotNull RegistryFriendlyByteBuf buf) {
        rows.clear();
        for (int i = buf.readVarInt(); i > 0; i--) {
            final int colony = buf.readVarInt();
            final String name = buf.readUtf();
            final int distance = buf.readVarInt();
            final boolean drawn = buf.readBoolean();
            final List<Boolean> open = new ArrayList<>();
            final List<Component> lines = new ArrayList<>();
            for (int j = buf.readVarInt(); j > 0; j--) {
                open.add(buf.readBoolean());
                lines.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
            }
            rows.add(new Row(colony, name, distance, drawn, open, lines));
        }
        version++;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BOWindow getWindow() {
        return new RoutesWindow(this);
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
