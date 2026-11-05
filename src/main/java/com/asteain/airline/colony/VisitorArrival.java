package com.asteain.airline.colony;

import com.asteain.airline.route.Facility;
import com.asteain.airline.route.RouteNetwork;
import com.minecolonies.api.colony.buildings.IBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where a tavern's visitor appears (TavernBuildingModuleMixin): in a colony with an airfield joined to another colony
 * by a clear line of the air network, at the edge of that airfield's pad (the side of its hut, where pilots step off).
 * Elsewhere where MineColonies puts it. Nothing here may stop a visitor from coming: on any failure, the original places.
 */
public final class VisitorArrival {
    private VisitorArrival() {
    }

    /** What the mixin adds to MineColonies' tavern module (see TavernBuildingModuleMixin). */
    public interface Tavern {
        /** Visitors owed: their time came while the colony was joined to others by air. */
        int airline$owed();

        void airline$setOwed(int owed);

        /** The owed visitors appear now (as many as the tavern has room for); how many did. */
        int airline$deliver();
    }

    /** Where the visitors that come with an aircraft appear (set only while they do). */
    private static final ThreadLocal<BlockPos> ARRIVAL = new ThreadLocal<>();

    public static List<BlockPos> spawns(IBuilding tavern, List<BlockPos> original) {
        final BlockPos with = ARRIVAL.get();
        if (with != null) return List.of(with);
        try {
            if (tavern == null || !(tavern.getColony().getWorld() instanceof ServerLevel level)) return original;
            final RouteNetwork net = RouteNetwork.get(level);
            final List<BlockPos> arrivals = new ArrayList<>();
            for (final IBuilding building : tavern.getColony().getServerBuildingManager().getBuildings().values()) {
                if (!(building instanceof BuildingAirfield airfield) || !airfield.facility().airfield() || airfield.getBuildingLevel() < 1) continue;
                final BlockPos pad = airfield.pad();
                final RouteNetwork.Node node = pad == null ? null : net.node(pad);
                if (node == null || !joined(net, pad, tavern.getColony().getID(), level)) continue;
                final double heading = Math.toRadians(node.heading());
                final int out = airfield.facility().padHalfSize() - 1;
                arrivals.add(pad.offset((int) Math.round(-Math.cos(heading) * out), 1, (int) Math.round(-Math.sin(heading) * out)));
            }
            if (arrivals.isEmpty()) return original;
            final BlockPos chosen = arrivals.get(ThreadLocalRandom.current().nextInt(arrivals.size()));
            LoggerFactory.getLogger("Airline").atLevel(com.asteain.airline.Airline.STEPS).log("[Airline/visitors] a visitor of colony {} arrives at the airfield, at {}", tavern.getColony().getID(), chosen.toShortString());
            return List.of(chosen);
        } catch (RuntimeException e) {
            LoggerFactory.getLogger("Airline").warn("[Airline/visitors] could not look for an airfield for a visitor; it comes the usual way", e);
            return original;
        }
    }

    /**
     * Whether the tavern's colony is joined to others by air (then the tavern stops spawning visitors by itself): an
     * airfield or an anchorage of it, built, whose pad has a clear line toward another colony. Then its visitors come with
     * aircraft. False on any failure (they come the usual way).
     */
    public static boolean byAir(@org.jetbrains.annotations.Nullable IBuilding tavern) {
        try {
            if (tavern == null || !(tavern.getColony().getWorld() instanceof ServerLevel level)) return false;
            final RouteNetwork net = RouteNetwork.get(level);
            for (final IBuilding building : tavern.getColony().getServerBuildingManager().getBuildings().values()) {
                if (!(building instanceof BuildingAirfield place) || place.getBuildingLevel() < 1) continue;
                final BlockPos pad = place.pad();
                if (pad != null && net.node(pad) != null && joined(net, pad, tavern.getColony().getID(), level)) return true;
            }
        } catch (RuntimeException e) {
            LoggerFactory.getLogger("Airline").warn("[Airline/visitors] could not look whether a colony is joined to others by air", e);
        }
        return false;
    }

    /** Noted in the log when a visitor is owed. */
    public static void owed(IBuilding tavern, int owed) {
        LoggerFactory.getLogger("Airline").atLevel(com.asteain.airline.Airline.STEPS).log("[Airline/visitors] colony {}: a visitor's time came; it comes with the next aircraft ({} owed)", tavern.getColony().getID(), owed);
    }

    /**
     * An aircraft from another colony landed in this one: the visitors owed by its taverns appear where its pilot gets off,
     * and walk to their tavern. How many came.
     */
    public static int arrive(com.minecolonies.api.colony.IColony colony, BlockPos where) {
        int came = 0;
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            for (final com.minecolonies.core.colony.buildings.modules.TavernBuildingModule module : building.getModulesByType(com.minecolonies.core.colony.buildings.modules.TavernBuildingModule.class)) {
                if (!(module instanceof Tavern tavern) || tavern.airline$owed() <= 0) continue;
                ARRIVAL.set(where);
                try {
                    came += tavern.airline$deliver();
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger("Airline").warn("[Airline/visitors] the visitors owed could not appear", e);
                } finally {
                    ARRIVAL.remove();
                }
            }
        }
        if (came > 0) LoggerFactory.getLogger("Airline").atLevel(com.asteain.airline.Airline.STEPS).log("[Airline/visitors] colony {}: {} visitor(s) came with the aircraft, at {}", colony.getID(), came, where.toShortString());
        return came;
    }

    /** Whether a clear line leads from this pad to a place of another colony (a buoy on the way counts as one step). */
    private static boolean joined(RouteNetwork net, BlockPos pad, int colony, ServerLevel level) {
        for (final RouteNetwork.Edge edge : net.edges()) {
            if (edge.state() != RouteNetwork.State.CLEAR || !(edge.a.equals(pad) || edge.b.equals(pad))) continue;
            final BlockPos other = edge.a.equals(pad) ? edge.b : edge.a;
            final RouteNetwork.Node node = net.node(other);
            if (node == null) continue;
            if (node.kind() == RouteNetwork.Kind.BUOY) return true;
            final com.minecolonies.api.colony.IColony there = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByPosFromWorld(level, node.home());
            if (there != null && there.getID() != colony) return true;
        }
        return false;
    }
}
