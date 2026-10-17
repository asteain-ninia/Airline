package com.asteain.airline.client;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.RoutesModuleView;
import com.asteain.airline.network.RouteOrderPayload;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The routes tab of the airfield's window (2026-10-18): every other colony, nearest first, with whether a route is drawn
 * to it and, for each class this colony can send, whether it can be flown or why not; a button draws or takes it off.
 * Client only.
 */
public class RoutesWindow extends AbstractModuleWindow<RoutesModuleView> {
    private static final String KEY = "com." + Airline.MOD_ID + ".gui.routes.";
    private final ScrollingList list;
    private final Text empty;
    private int shown = -1;

    public RoutesWindow(RoutesModuleView view) {
        super(view, ResourceLocation.fromNamespaceAndPath(Airline.MOD_ID, "gui/layouthuts/layoutroutes.xml"));
        list = window.findPaneOfTypeByID("colonies", ScrollingList.class);
        empty = window.findPaneOfTypeByID("empty", Text.class);
        registerButton("toggle", this::toggle);
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
        refresh();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (shown != moduleView.version) refresh();
    }

    private void refresh() {
        shown = moduleView.version;
        empty.setText(moduleView.rows.isEmpty() ? Component.translatable(KEY + "none") : Component.empty());
        list.refreshElementPanes();
    }

    private void fill(RoutesModuleView.Row row, Pane pane) {
        // (Whether a route is drawn shows on the button: "draw" or "take off".)
        pane.findPaneOfTypeByID("name", Text.class).setText(Component.literal(row.name()).append(Component.translatable(KEY + "distance", row.distance())));
        final MutableComponent lines = Component.empty();
        for (int i = 0; i < row.lines().size(); i++) {
            if (i > 0) lines.append("\n");
            lines.append(row.lines().get(i));
        }
        pane.findPaneOfTypeByID("status", Text.class).setText(lines);
        pane.findPaneOfTypeByID("toggle", Button.class).setText(Component.translatable(KEY + (row.drawn() ? "remove" : "draw")));
    }

    private void toggle(Button button) {
        final int index = list.getListElementIndexByPane(button);
        if (index < 0 || index >= moduleView.rows.size()) return;
        final RoutesModuleView.Row row = moduleView.rows.get(index);
        PacketDistributor.sendToServer(new RouteOrderPayload(buildingView.getID(), row.colony(), !row.drawn()));
    }
}
