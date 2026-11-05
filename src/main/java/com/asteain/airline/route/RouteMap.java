package com.asteain.airline.route;

import com.asteain.airline.network.RouteMapPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lines of the air network as players' maps show them (JourneyMap, when a player has it). Each player is sent the
 * lines of the dimension they
 * are in when they join or change dimension, and again whenever those lines change (looked at every five seconds).
 */
public final class RouteMap {
    /** A line between two points. {@code open}: the largest class that may fly it ({@link ShipClass} ordinal), -1 for none. */
    public record Line(BlockPos a, BlockPos b, byte open, byte state) {
    }

    private static final int EVERY = 100;
    /** What each dimension's players were sent last (to send again only on a change). */
    private static final Map<ResourceKey<Level>, List<Line>> SENT = new HashMap<>();

    private RouteMap() {
    }

    /** The lines of a dimension that a map shows: those of the routes players drew. */
    public static List<Line> lines(ServerLevel level) {
        final List<Line> lines = new ArrayList<>();
        for (final RouteNetwork.Edge edge : ColonyRoutes.drawnLines(level)) {
            byte open = -1;
            for (final ShipClass c : ShipClass.values()) if (edge.opens(c)) open = (byte) c.ordinal();
            lines.add(new Line(edge.a, edge.b, open, (byte) edge.state().ordinal()));
        }
        // In a fixed order: what was sent is compared with what would be sent now.
        lines.sort(java.util.Comparator.comparingLong((Line l) -> l.a().asLong()).thenComparingLong(l -> l.b().asLong()));
        return lines;
    }

    private static RouteMapPayload payload(ServerLevel level, List<Line> lines) {
        return new RouteMapPayload(level.dimension().location(), lines);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVERY != 0) return;
        for (final ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) continue;
            final List<Line> lines = lines(level);
            if (lines.equals(SENT.get(level.dimension()))) continue;
            SENT.put(level.dimension(), lines);
            PacketDistributor.sendToPlayersInDimension(level, payload(level, lines));
        }
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) send(player);
    }

    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) send(player);
    }

    private static void send(ServerPlayer player) {
        final ServerLevel level = player.serverLevel();
        PacketDistributor.sendToPlayer(player, payload(level, lines(level)));
    }

    /** A new server (or world) starts with nothing sent. */
    public static void forget() {
        SENT.clear();
    }
}
