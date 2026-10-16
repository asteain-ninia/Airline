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

    public static List<BlockPos> spawns(IBuilding tavern, List<BlockPos> original) {
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
            LoggerFactory.getLogger("Airline").info("[Airline/visitors] a visitor of colony {} arrives at the airfield, at {}", tavern.getColony().getID(), chosen.toShortString());
            return List.of(chosen);
        } catch (RuntimeException e) {
            LoggerFactory.getLogger("Airline").warn("[Airline/visitors] could not look for an airfield for a visitor; it comes the usual way", e);
            return original;
        }
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
