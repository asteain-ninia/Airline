package com.asteain.airline.client;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.WantsModuleView;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/** The requests tab of the airfield's window (2026-10-18): every colony and what it wants, how much is on its way. Client only. */
public class WantsWindow extends AbstractModuleWindow<WantsModuleView> {
    private static final String KEY = "com." + Airline.MOD_ID + ".gui.wants.";
    private final ScrollingList list;
    private int shown = -1;

    public WantsWindow(WantsModuleView view) {
        super(view, ResourceLocation.fromNamespaceAndPath(Airline.MOD_ID, "gui/layouthuts/layoutwants.xml"));
        list = window.findPaneOfTypeByID("colonies", ScrollingList.class);
    }

    @Override
    public void onOpened() {
        super.onOpened();
        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return moduleView.rows.size();
            }

            @Override
            public void updateElement(int index, Pane row) {
                fill(moduleView.rows.get(index), row);
            }
        });
        list.refreshElementPanes();
        shown = moduleView.version;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (shown != moduleView.version) {
            shown = moduleView.version;
            list.refreshElementPanes();
        }
    }

    private void fill(WantsModuleView.Row row, Pane pane) {
        final MutableComponent name = Component.literal(row.name());
        if (row.own()) name.append(" ").append(Component.translatable(KEY + "own"));
        pane.findPaneOfTypeByID("name", Text.class).setText(name);
        final MutableComponent lines = Component.empty();
        if (row.wants().isEmpty()) lines.append(Component.translatable(KEY + "nothing"));
        for (int i = 0; i < row.wants().size(); i++) {
            final WantsModuleView.Want want = row.wants().get(i);
            if (i > 0) lines.append("\n");
            lines.append(Component.translatable(KEY + "line", BuiltInRegistries.ITEM.get(want.item()).getDescription(), want.wanted(), want.onTheWay()));
        }
        if (row.more() > 0) lines.append("\n").append(Component.translatable(KEY + "more", row.more()));
        pane.findPaneOfTypeByID("wants", Text.class).setText(lines);
    }
}
