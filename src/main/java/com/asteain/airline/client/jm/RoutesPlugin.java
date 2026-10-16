package com.asteain.airline.client.jm;

import com.asteain.airline.Airline;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The entry point JourneyMap looks for (only when JourneyMap is there; nothing else of the mod touches this package):
 * draws the lines of the air network on JourneyMap's maps ({@link RouteOverlays}).
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public class RoutesPlugin implements IClientPlugin {
    @Override
    public String getModId() {
        return Airline.MOD_ID;
    }

    @Override
    public void initialize(IClientAPI api) {
        final RouteOverlays overlays = new RouteOverlays(api);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> overlays.onClientTick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> overlays.onLogout());
        org.slf4j.LoggerFactory.getLogger("Airline").info("[Airline/map] JourneyMap plugin started: the air network is drawn on JourneyMap's maps.");
    }
}
