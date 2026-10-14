package com.asteain.airline.client;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.TimetableModuleView;
import com.asteain.airline.flight.Timetable;
import com.ldtteam.blockui.controls.Text;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The timetable tab of the airfield's window: "next departure" and "next arrival", counted down while the window is
 * open. Client only.
 */
public class TimetableWindow extends AbstractModuleWindow<TimetableModuleView> {
    private static final String KEY = "com." + Airline.MOD_ID + ".gui.timetable.";
    private final Text departure, arrival, aircraft;

    public TimetableWindow(TimetableModuleView view) {
        super(view, ResourceLocation.fromNamespaceAndPath(Airline.MOD_ID, "gui/layouthuts/layouttimetable.xml"));
        departure = window.findPaneOfTypeByID("departure", Text.class);
        arrival = window.findPaneOfTypeByID("arrival", Text.class);
        aircraft = window.findPaneOfTypeByID("aircraft", Text.class);
        registerButton("depart", () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.asteain.airline.network.AircraftOrderPayload(buildingView.getID(), com.asteain.airline.network.AircraftOrderPayload.DEPART, "")));
    }

    @Override
    public void onOpened() {
        super.onOpened();
        refresh();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        refresh();
    }

    private void refresh() {
        if (departure == null || arrival == null || Minecraft.getInstance().level == null) return;
        final long now = Minecraft.getInstance().level.getGameTime();
        departure.setText(departure(now));
        arrival.setText(arrival(now));
        if (aircraft != null) aircraft.setText(aircraft());
    }

    private List<MutableComponent> aircraft() {
        final TimetableModuleView v = moduleView;
        if (v.own == 0) return List.of(Component.translatable(KEY + "aircraft.none"));
        final MutableComponent design = Component.translatable(v.ownDesign);
        if (v.own == 1) return List.of(Component.translatable(KEY + "aircraft.building", design, v.ownNumber));
        final List<MutableComponent> lines = new java.util.ArrayList<>();
        lines.add(v.ownNumber < 0 ? Component.translatable(KEY + "aircraft.away", design) : Component.translatable(KEY + "aircraft.fuel", design, v.ownNumber));
        if (v.ownDistress) lines.add(Component.translatable(KEY + "aircraft.distress"));
        else if (v.ownLacks > 0) lines.add(Component.translatable(KEY + "aircraft.lacks", v.ownLacks));
        return lines;
    }

    private List<MutableComponent> departure(long now) {
        final TimetableModuleView v = moduleView;
        if (!v.standing) return List.of(Component.translatable(KEY + "departure.none"));
        final long left = v.leaves - now;
        final MutableComponent when;
        if (left > 0) when = Component.translatable(KEY + "departure.in", Timetable.clock(left));
        else if (v.state.equals("PARKED")) when = Component.translatable(KEY + "departure.waiting");
        else if (v.state.equals("BOARDING")) when = Component.translatable(KEY + "departure.boarding");
        else when = Component.translatable(KEY + "departure.loading");
        final MutableComponent where = v.to.isEmpty() ? Component.translatable(KEY + "destination.none")
                : Component.translatable(KEY + "destination", v.to, Timetable.clock(v.arrivesThere - now));
        return List.of(when, where);
    }

    private List<MutableComponent> arrival(long now) {
        final TimetableModuleView v = moduleView;
        if (!v.coming) return List.of(Component.translatable(KEY + "arrival.none"));
        final long left = v.arrivesHere - now;
        final MutableComponent who = Component.translatable(KEY + (v.flying ? "arrival.flying" : "arrival.berthed"), v.from);
        return List.of(who, left > 0 ? Component.translatable(KEY + "arrival.in", Timetable.clock(left)) : Component.translatable(KEY + "arrival.late"));
    }
}
