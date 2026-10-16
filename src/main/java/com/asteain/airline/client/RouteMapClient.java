package com.asteain.airline.client;

import com.asteain.airline.route.RouteMap;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a player has been sent of the air network ({@link com.asteain.airline.network.RouteMapPayload}): the lines of
 * each dimension, as last sent. Read by the map overlays (JourneyMap); plain data, no client classes.
 */
public final class RouteMapClient {
    private static final Map<ResourceLocation, List<RouteMap.Line>> LINES = new HashMap<>();
    /** Goes up with everything received, so that the map knows when to draw again. */
    private static int version;

    private RouteMapClient() {
    }

    public static synchronized void receive(ResourceLocation dimension, List<RouteMap.Line> lines) {
        LINES.put(dimension, List.copyOf(lines));
        version++;
    }

    public static synchronized List<RouteMap.Line> lines(ResourceLocation dimension) {
        return LINES.getOrDefault(dimension, List.of());
    }

    public static synchronized int version() {
        return version;
    }

    /** Leaving a server: nothing of it is kept. */
    public static synchronized void clear() {
        LINES.clear();
        version++;
    }
}
