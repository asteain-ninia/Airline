package com.asteain.airline.client;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.AircraftModuleView;
import com.asteain.airline.flight.Flights;
import com.asteain.airline.network.AircraftOrderPayload;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The aircraft tab of the airfield's window: the airfield's own aircraft, and the designs that can be built, one row each
 * with a button that asks the builders for it (or, while one is being built, builds that design instead). Client only.
 */
public class AircraftWindow extends AbstractModuleWindow<AircraftModuleView> {
    private static final String KEY = "com." + Airline.MOD_ID + ".gui.aircraft.";
    private final Text status;
    private final Button stopButton, toAnchorageButton, toAirfieldButton;
    private final ScrollingList list;
    private int shown = -1;

    public AircraftWindow(AircraftModuleView view) {
        super(view, ResourceLocation.fromNamespaceAndPath(Airline.MOD_ID, "gui/layouthuts/layoutaircraft.xml"));
        status = window.findPaneOfTypeByID("status", Text.class);
        stopButton = window.findPaneOfTypeByID("stop", Button.class);
        toAnchorageButton = window.findPaneOfTypeByID("to_anchorage", Button.class);
        toAirfieldButton = window.findPaneOfTypeByID("to_airfield", Button.class);
        list = window.findPaneOfTypeByID("designs", ScrollingList.class);
        registerButton("build", this::build);
        registerButton("stop", this::stop);
        registerButton("to_anchorage", () -> PacketDistributor.sendToServer(new AircraftOrderPayload(buildingView.getID(), AircraftOrderPayload.TO_ANCHORAGE, "")));
        registerButton("to_airfield", () -> PacketDistributor.sendToServer(new AircraftOrderPayload(buildingView.getID(), AircraftOrderPayload.TO_AIRFIELD, "")));
    }

    @Override
    public void onOpened() {
        super.onOpened();
        list.setDataProvider(new ScrollingList.DataProvider() {
            @Override
            public int getElementCount() {
                return moduleView.designs.size();
            }

            @Override
            public void updateElement(int index, Pane row) {
                fill(moduleView.designs.get(index), row);
            }
        });
        refresh();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        // Only when the server sent a new state: the rows are filled again then.
        if (shown != moduleView.version) refresh();
    }

    private void refresh() {
        shown = moduleView.version;
        final AircraftModuleView v = moduleView;
        final Component design = Component.translatable(v.ownDesign);
        final net.minecraft.network.chat.MutableComponent text;
        if (!v.available) text = Component.translatable(KEY + "unavailable");
        else if (v.own == 1) text = Component.translatable(KEY + "building", design, v.ownNumber);
        else if (v.own == 2) text = Component.translatable(KEY + "exists", design);
        else text = Component.translatable(KEY + "none");
        // The aircraft whose home is one of the colony's anchorages.
        if (v.available && v.fleet > 0) text.append(" ").append(Component.translatable(KEY + "fleet", v.fleet));
        status.setText(text);
        stopButton.setVisible(v.available && v.own == 1);
        toAnchorageButton.setVisible(v.available && v.canToAnchorage);
        toAirfieldButton.setVisible(v.available && v.canToAirfield);
        list.refreshElementPanes();
    }

    private void fill(Flights.Design card, Pane row) {
        final AircraftModuleView v = moduleView;
        row.findPaneOfTypeByID("title", Text.class).setText(Component.translatable(card.title()));
        row.findPaneOfTypeByID("facts", Text.class).setText(Component.translatable(KEY + "facts",
                String.format(java.util.Locale.ROOT, "%.1f", card.topSpeed()), card.tankMinutes(), card.blocks()));
        final Button button = row.findPaneOfTypeByID("build", Button.class);
        final boolean building = v.own == 1, current = building && card.title().equals(v.ownDesign);
        button.setText(Component.translatable(KEY + (current ? "current" : building ? "switch" : "build")));
        button.setEnabled(v.available && v.own != 2 && !current);
    }

    private void build(Button button) {
        final int index = list.getListElementIndexByPane(button);
        if (index < 0 || index >= moduleView.designs.size()) return;
        PacketDistributor.sendToServer(new AircraftOrderPayload(buildingView.getID(), AircraftOrderPayload.BUILD, moduleView.designs.get(index).name()));
    }

    private void stop() {
        PacketDistributor.sendToServer(new AircraftOrderPayload(buildingView.getID(), AircraftOrderPayload.STOP, ""));
    }
}
