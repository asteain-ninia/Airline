package com.asteain.airline.client.jm;

import com.asteain.airline.Airline;
import com.asteain.airline.client.RouteMapClient;
import com.asteain.airline.route.RouteMap;
import com.asteain.airline.route.ShipClass;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lines of the air network on JourneyMap's maps (the full map and the minimap): each a band two blocks wide from
 * point to point, coloured by the largest class that may fly it (small green, medium orange, large blue), red when
 * nothing may (blocked), grey while it is being looked at. Its name, shown when the mouse is over it, says the same in
 * words. Drawn again whenever the server sends the lines anew or the player goes to another dimension. A failure here
 * stops the drawing and is logged once; it never stops the game.
 */
final class RouteOverlays {
    private static final Logger LOGGER = LoggerFactory.getLogger("Airline");
    private static final int[] COLOURS = {0x43A047, 0xFB8C00, 0x1E88E5};
    private static final int BLOCKED = 0xE53935, UNVERIFIED = 0x9E9E9E;
    /** Half the width of a line's band, in blocks. */
    private static final double HALF_WIDTH = 1.0;

    private final IClientAPI api;
    private int shown = -1;
    private ResourceKey<Level> shownIn;
    private boolean failed;

    RouteOverlays(IClientAPI api) {
        this.api = api;
    }

    void onClientTick() {
        if (failed) return;
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            final ResourceKey<Level> dimension = mc.level.dimension();
            if (RouteMapClient.version() == shown && dimension.equals(shownIn)) return;
            shown = RouteMapClient.version();
            shownIn = dimension;
            api.removeAll(Airline.MOD_ID);
            int i = 0;
            for (final RouteMap.Line line : RouteMapClient.lines(dimension.location())) {
                for (final MapPolygon piece : pieces(line.a(), line.b())) api.show(overlay(dimension, line, piece, i));
                i++;
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("[Airline/map] drawing the air network on JourneyMap failed; it is not drawn any more in this session", t);
        }
    }

    void onLogout() {
        try {
            api.removeAll(Airline.MOD_ID);
        } catch (Throwable t) {
            LOGGER.warn("[Airline/map] could not take the air network off JourneyMap", t);
        }
        RouteMapClient.clear();
        shown = -1;
        shownIn = null;
    }

    private static PolygonOverlay overlay(ResourceKey<Level> dimension, RouteMap.Line line, MapPolygon piece, int index) {
        final boolean unverified = line.state() == 0, blocked = line.open() < 0;
        final int colour = unverified ? UNVERIFIED : blocked ? BLOCKED : COLOURS[Math.min(line.open(), COLOURS.length - 1)];
        final ShapeProperties shape = new ShapeProperties().setStrokeColor(colour).setFillColor(colour).setStrokeOpacity(0.9f).setFillOpacity(blocked ? 0.35f : 0.55f).setStrokeWidth(1.0f);
        final PolygonOverlay overlay = new PolygonOverlay(Airline.MOD_ID, dimension, shape, piece);
        final long length = Math.round(Math.sqrt(line.a().distSqr(line.b())));
        final String title = unverified ? I18n.get("journeymap.airline.line.unverified", length)
                : blocked ? I18n.get("journeymap.airline.line.blocked", length)
                : I18n.get("journeymap.airline.line.open", I18n.get("ship_class." + Airline.MOD_ID + "." + ShipClass.values()[line.open()].name().toLowerCase(java.util.Locale.ROOT)), length);
        overlay.setTitle(title);
        overlay.setOverlayGroupName(I18n.get("journeymap.airline.group"));
        overlay.setDisplayOrder(10 + index);
        return overlay;
    }

    /**
     * The band along the line in pieces (drawn whole, its name would come up far from the line on the full map).
     * JourneyMap shows a shape's name wherever the mouse is inside the box around the shape, and the box around a long
     * slanting band is wide: a slanting line is drawn in pieces short enough that the box of each stays within a few
     * blocks of the line. A line along the x or z axis is one piece.
     */
    private static java.util.List<MapPolygon> pieces(BlockPos a, BlockPos b) {
        final double ax = a.getX() + 0.5, az = a.getZ() + 0.5, bx = b.getX() + 0.5, bz = b.getZ() + 0.5;
        final double length = Math.max(1.0e-6, Math.hypot(bx - ax, bz - az));
        final double slant = Math.min(Math.abs(bx - ax), Math.abs(bz - az)) / length;
        final int count = slant < 1.0e-3 ? 1 : (int) Math.max(1, Math.ceil(length * slant / PIECE_SPREAD));
        final java.util.List<MapPolygon> pieces = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final double t0 = (double) i / count, t1 = (double) (i + 1) / count;
            pieces.add(band(ax + (bx - ax) * t0, az + (bz - az) * t0, ax + (bx - ax) * t1, az + (bz - az) * t1, Math.max(a.getY(), b.getY())));
        }
        return pieces;
    }

    /** How far a piece of a slanting line may reach sideways (the shorter side of its box, apart from the band's width), in blocks. */
    private static final double PIECE_SPREAD = 4.0;

    /** A band along a piece of a line, from x0, z0 to x1, z1. */
    private static MapPolygon band(double x0, double z0, double x1, double z1, int y) {
        final double length = Math.max(1.0e-6, Math.hypot(x1 - x0, z1 - z0));
        final double px = -(z1 - z0) / length * HALF_WIDTH, pz = (x1 - x0) / length * HALF_WIDTH;
        return new MapPolygon(BlockPos.containing(x0 + px, y, z0 + pz), BlockPos.containing(x1 + px, y, z1 + pz),
                BlockPos.containing(x1 - px, y, z1 - pz), BlockPos.containing(x0 - px, y, z0 - pz));
    }
}
