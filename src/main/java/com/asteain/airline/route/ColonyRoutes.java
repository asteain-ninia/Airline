package com.asteain.airline.route;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.flight.Cargo;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.util.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The routes between colonies that players drew (2026-10-18, the user's ruling: "ワールド中の任意の村を選択して、そこに航路を
 * 引くタブ", the automatic routes no longer used): a route is a pair of colonies, both ways. Flights, the collection for
 * export and the notices go only to colonies a route is drawn to. The lines of the network are still made and looked at
 * behind it (the user's choice "線は裏で持つ"); a route is open for a class when there is a way of clear lines between a
 * place of each colony that takes that class. When there is none, the reason is told, with one place (the user's ruling:
 * "表示する座標は一個限定").
 */
public final class ColonyRoutes extends SavedData {
    private static final String FILE = "airline_colony_routes";
    private static final String MESSAGE = "message." + Airline.MOD_ID + ".route.";
    private static final String STATUS = "com." + Airline.MOD_ID + ".gui.routes.";
    /** Pairs of colonies, "dimension|smaller id|larger id". */
    private final Set<String> pairs = new HashSet<>();
    /** For the development checks: every pair counts as drawn (the scenes of before 2026-10-18 keep their meaning). */
    private static boolean allForChecks;
    /** Whether each drawn route was open (for some class) when last looked at: a change is told to both colonies. */
    private static final Map<String, Boolean> WAS_OPEN = new HashMap<>();

    public static void allForChecks(boolean all) {
        allForChecks = all;
    }

    public static ColonyRoutes get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(ColonyRoutes::new, ColonyRoutes::load), FILE);
    }

    private static String key(ServerLevel level, int a, int b) {
        return level.dimension().location() + "|" + Math.min(a, b) + "|" + Math.max(a, b);
    }

    /** Whether a route is drawn between two colonies of this dimension. */
    public static boolean drawn(ServerLevel level, int a, int b) {
        return a != b && (allForChecks || get(level.getServer()).pairs.contains(key(level, a, b)));
    }

    public void draw(ServerLevel level, int a, int b) {
        if (a != b && pairs.add(key(level, a, b))) setDirty();
    }

    public void remove(ServerLevel level, int a, int b) {
        if (pairs.remove(key(level, a, b))) setDirty();
        WAS_OPEN.remove(key(level, a, b));
    }

    // ---- whether a route can be flown, by class ----------------------------------------------------------

    /** For one class: whether the route is open, how long the way is, and what to say (the reason when it is not open). */
    public record ClassStatus(ShipClass ship, boolean open, double length, Component text) {}

    private static MutableComponent className(ShipClass ship) {
        return Component.translatable("ship_class." + Airline.MOD_ID + "." + ship.key());
    }

    private static Component at(BlockPos pos) {
        return Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
    }

    /** The places of a colony (airfields and anchorages, built) that take aircraft of this class. */
    private static List<BlockPos> padsFor(ServerLevel level, RouteNetwork net, int colony, ShipClass ship) {
        final List<BlockPos> pads = new ArrayList<>();
        for (final BuildingAirfield facility : Cargo.facilitiesOf(level, colony)) {
            for (final BlockPos pad : facility.pads()) {
                final RouteNetwork.Node node = net.node(pad);
                if (node != null && node.berths(ship)) pads.add(pad);
            }
        }
        return pads;
    }

    /** The classes a colony can send: those of its airfields (the largest each one takes, and the smaller ones). */
    private static List<ShipClass> classesOf(ServerLevel level, RouteNetwork net, int colony) {
        final List<ShipClass> out = new ArrayList<>();
        for (final ShipClass ship : ShipClass.values()) if (!padsFor(level, net, colony, ship).isEmpty()) out.add(ship);
        return out;
    }

    /** For every class colony {@code from} can send: whether a route to {@code to} is open, or why not. */
    public static List<ClassStatus> status(ServerLevel level, int from, int to) {
        final RouteNetwork net = RouteNetwork.get(level);
        final List<ShipClass> classes = classesOf(level, net, from);
        final List<ClassStatus> out = new ArrayList<>();
        if (classes.isEmpty()) {
            out.add(new ClassStatus(ShipClass.SMALL, false, 0, Component.translatable(STATUS + "no_own")));
            return out;
        }
        for (final ShipClass ship : classes) out.add(status(level, net, from, to, ship));
        return out;
    }

    private static ClassStatus status(ServerLevel level, RouteNetwork net, int from, int to, ShipClass ship) {
        final MutableComponent name = className(ship);
        final List<BlockPos> mine = padsFor(level, net, from, ship), theirs = padsFor(level, net, to, ship);
        if (theirs.isEmpty()) return new ClassStatus(ship, false, 0, Component.translatable(STATUS + "no_berth", name));
        // Open: the shortest way of clear lines between a place of each.
        List<BlockPos> best = List.of();
        double shortest = Double.MAX_VALUE;
        for (final BlockPos a : mine) for (final BlockPos b : theirs) {
            final List<BlockPos> way = net.route(a, b, ship);
            if (way.size() >= 2 && net.length(way) < shortest) {
                shortest = net.length(way);
                best = way;
            }
        }
        if (!best.isEmpty()) return new ClassStatus(ship, true, shortest, Component.translatable(STATUS + "open", name, Math.round(shortest), best.size() - 2));
        // Not open: the shortest way there would be with every line clear; the first line on it this class may not fly says why.
        List<BlockPos> would = List.of();
        double wouldLength = Double.MAX_VALUE;
        for (final BlockPos a : mine) for (final BlockPos b : theirs) {
            final List<BlockPos> way = net.routeIfClear(a, b, ship);
            if (way.size() >= 2 && net.length(way) < wouldLength) {
                wouldLength = net.length(way);
                would = way;
            }
        }
        if (would.isEmpty()) return new ClassStatus(ship, false, 0, Component.translatable(STATUS + "too_far", name));
        for (int i = 1; i < would.size(); i++) {
            final RouteNetwork.Edge edge = net.edge(would.get(i - 1), would.get(i));
            if (edge == null || edge.opens(ship)) continue;
            return new ClassStatus(ship, false, 0, why(level, net, edge, ship, name));
        }
        // Every line opens and still no way (a line of the network not made yet): looked at again soon.
        return new ClassStatus(ship, false, 0, Component.translatable(STATUS + "checking", name));
    }

    /** Why a line may not be flown by this class: something in the way (one place), a pad it crosses, or not looked at yet. */
    private static Component why(ServerLevel level, RouteNetwork net, RouteNetwork.Edge edge, ShipClass ship, MutableComponent name) {
        if (edge.state() == RouteNetwork.State.UNVERIFIED) {
            final BlockPos middle = BlockPos.containing((edge.a.getX() + edge.b.getX()) / 2.0, (edge.a.getY() + edge.b.getY()) / 2.0, (edge.a.getZ() + edge.b.getZ()) / 2.0);
            return edge.hasUnseenParts() ? Component.translatable(STATUS + "unloaded", name, at(middle)) : Component.translatable(STATUS + "checking", name);
        }
        // A landing pad on the way that the line would have to be raised too far to pass over (for the small class, or for this class).
        final BlockPos pad = edge.barredBy() != null ? edge.barredBy() : edge.barredFrom() != null && ship.atLeast(edge.barredFrom()) ? edge.barredFromAt() : null;
        if (pad != null) {
            final BuildingAirfield airfield = Cargo.airfieldAt(level, pad);
            final String colony = airfield == null ? "?" : airfield.getColony().getName();
            return Component.translatable(STATUS + "crosses_pad", name, colony, RouteRules.lineRaiseMax(), at(pad));
        }
        final BlockPos block = edge.state() == RouteNetwork.State.BLOCKED ? edge.blockedAt() : edge.blockedFromAt();
        if (block == null) return Component.translatable(STATUS + "checking", name);
        if (level.isLoaded(block)) {
            final BlockState state = level.getBlockState(block);
            if (state.isAir()) {
                // What closed it is gone (taken away while nobody looked): the line is looked at again.
                net.recheck(edge);
                return Component.translatable(STATUS + "checking", name);
            }
            return Component.translatable(STATUS + "blocked", name, state.getBlock().getName(), at(block));
        }
        return Component.translatable(STATUS + "blocked_unseen", name, at(block));
    }

    /**
     * The lines a map shows (2026-10-18: only the routes players drew): for every drawn route and every class its colonies
     * can send, the lines of its way (the open one, or the one there would be if nothing were in the way). For the checks
     * (every pair counts as drawn) every line.
     */
    public static Set<RouteNetwork.Edge> drawnLines(ServerLevel level) {
        final RouteNetwork net = RouteNetwork.get(level);
        if (allForChecks) return new HashSet<>(net.edges());
        final Set<RouteNetwork.Edge> out = new HashSet<>();
        final String dimension = level.dimension().location().toString();
        for (final String pair : get(level.getServer()).pairs) {
            final String[] part = pair.split("\\|");
            if (part.length != 3 || !part[0].equals(dimension)) continue;
            final int a = Integer.parseInt(part[1]), b = Integer.parseInt(part[2]);
            for (final ShipClass ship : classesOf(level, net, a)) {
                List<BlockPos> best = List.of();
                double shortest = Double.MAX_VALUE;
                for (final BlockPos from : padsFor(level, net, a, ship)) for (final BlockPos to : padsFor(level, net, b, ship)) {
                    List<BlockPos> way = net.route(from, to, ship);
                    if (way.size() < 2) way = net.routeIfClear(from, to, ship);
                    if (way.size() >= 2 && net.length(way) < shortest) {
                        shortest = net.length(way);
                        best = way;
                    }
                }
                for (int i = 1; i < best.size(); i++) {
                    final RouteNetwork.Edge edge = net.edge(best.get(i - 1), best.get(i));
                    if (edge != null) out.add(edge);
                }
            }
        }
        return out;
    }

    /** Whether some class can fly between the two colonies now. */
    private static boolean anyOpen(ServerLevel level, int a, int b) {
        for (final ClassStatus s : status(level, a, b)) if (s.open()) return true;
        return false;
    }

    // ---- telling colonies -----------------------------------------------------------------------------------

    /** Every ten seconds: a drawn route that became open, or stopped being open, is told to both colonies (once for the two). */
    public static void onServerTick(ServerTickEvent.Post event) {
        final MinecraftServer server = event.getServer();
        if (server.getTickCount() % 200 != 37 || allForChecks) return;
        final ColonyRoutes routes = get(server);
        for (final String pair : new ArrayList<>(routes.pairs)) {
            final String[] part = pair.split("\\|");
            if (part.length != 3) continue;
            ServerLevel level = null;
            for (final ServerLevel each : server.getAllLevels()) if (each.dimension().location().toString().equals(part[0])) level = each;
            if (level == null) continue;
            final int a = Integer.parseInt(part[1]), b = Integer.parseInt(part[2]);
            final IColony one = IColonyManager.getInstance().getColonyByDimension(a, level.dimension()), two = IColonyManager.getInstance().getColonyByDimension(b, level.dimension());
            if (one == null || two == null) continue;
            final boolean open = anyOpen(level, a, b);
            final Boolean was = WAS_OPEN.put(pair, open);
            if (was == null || was == open) continue;
            final String key = MESSAGE + (open ? "open" : "closed");
            MessageUtils.format(key, one.getName(), two.getName()).sendTo(one).forManagers();
            MessageUtils.format(key, one.getName(), two.getName()).sendTo(two).forManagers();
        }
    }

    /** A new server (or world): nothing known about the routes yet. */
    public static void forget() {
        WAS_OPEN.clear();
    }

    // ---- saving -------------------------------------------------------------------------------------------

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        final ListTag list = new ListTag();
        for (final String pair : pairs) list.add(StringTag.valueOf(pair));
        tag.put("pairs", list);
        return tag;
    }

    private static ColonyRoutes load(CompoundTag tag, HolderLookup.Provider provider) {
        final ColonyRoutes routes = new ColonyRoutes();
        final ListTag list = tag.getList("pairs", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) routes.pairs.add(list.getString(i));
        return routes;
    }

    /** For the record: the colony a pad belongs to, by name, or null. */
    @Nullable
    public static String colonyOf(ServerLevel level, BlockPos pad) {
        final BuildingAirfield airfield = Cargo.airfieldAt(level, pad);
        return airfield == null ? null : airfield.getColony().getName();
    }
}
