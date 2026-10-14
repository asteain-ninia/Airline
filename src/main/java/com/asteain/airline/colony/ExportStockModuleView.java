package com.asteain.airline.colony;

import com.asteain.airline.Airline;
import com.minecolonies.core.colony.buildings.moduleviews.MinimumStockModuleView;
import net.minecraft.network.chat.Component;

/** Client side of the export table. The window and the messages are MineColonies' minimum stock ones; only the tab's name differs. */
public class ExportStockModuleView extends MinimumStockModuleView {
    public static final String TAB_KEY = "com." + Airline.MOD_ID + ".gui.airfield.export";

    @Override
    public Component getDesc() {
        return Component.translatable(TAB_KEY);
    }
}
