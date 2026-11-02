package com.asteain.airline.route;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.AirLinks;
import com.asteain.airline.flight.AircraftType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The air network of one dimension: points (buoys and airfields) and the lines between points whose reaches overlap.
 * Every line has a state: not verified yet, clear, or blocked (with the block that blocks it). Saved with the dimension.
 * Server thread only.
 *
 * <p>Which points connect and where a line runs is decided in {@link RouteRules}; which blocks lie on a line in
 * {@link Tube}. Verification never loads a chunk: what is not loaded stays "not verified".</p>
 */
public final class RouteNetwork extends SavedData {
    private static final String FILE = Airline.MOD_ID + "_network";

    public enum Kind { BUOY, AIRFIELD }

    public enum State { UNVERIFIED, CLEAR, BLOCKED }

    /**
     * A point. {@code pos} is the buoy block, or the block in the middle of an airfield's landing pad floor; the line
     * passes above it ({@link RouteRules#waypoint}; higher over a landing pad than over a buoy). {@code heading} is only used by airfields: the direction (degrees,
     * a multiple of 90) an aircraft has to face when it rests on the pad. {@code tier} is the grade of a buoy (1 to 3), or
     * the building level of the airfield a landing pad belongs to (1 to 5); either decides how far the point reaches.
     * {@code home} is where the airfield of a landing pad stands (its hut block): the place that says which large map the
     * airfield is on. For a buoy it is the buoy itself. {@code facility} is the kind of place a landing pad belongs to
     * (small, medium or large airfield, or anchorage; null for a buoy): which classes of aircraft may berth there.
     */
    public record Node(BlockPos pos, Kind kind, int tier, int heading, BlockPos home, @Nullable Facility facility) {

        /** The grade of a buoy (1 to 3). */
        public int grade() {
            return tier;
        }

        /** The building level of the airfield (or anchorage) a landing pad belongs to (1 to 5). */
        public int airfieldLevel() {
            return tier;
        }

        /** Whether an aircraft of this class may berth at this point (a landing pad whose facility takes the class). */
        public boolean berths(ShipClass ship) {
            return kind == Kind.AIRFIELD && facility != null && facility.berths(ship);
        }

        /**
         * The largest class this point is made for: a pad, its facility's; a buoy, by its grade (the user's ruling of
         * 2026-10-15: grade 1 small, grade 2 medium, grade 3 large).
         */
        public ShipClass largest() {
            if (kind == Kind.AIRFIELD) return facility != null ? facility.largest() : ShipClass.SMALL;
            return switch (tier) {
                case 1 -> ShipClass.SMALL;
                case 2 -> ShipClass.MEDIUM;
                default -> ShipClass.LARGE;
            };
        }

        public Vec3 waypoint() {
            return RouteRules.waypoint(pos, kind, largest());
        }

        /** How far this point reaches: two points connect when they are closer than the sum of their reaches. */
        public int reach() {
            return RouteRules.reach(kind, tier);
        }
    }

    /** A line between two points. {@code a} sorts before {@code b}. */
    public static final class Edge {
        public final BlockPos a, b;
        private State state = State.UNVERIFIED;
        @Nullable private BlockPos blockedAt;
        private Tube tube;
        /**
         * All that has to be free of solid blocks for this line, for the largest class it is for: its tube, and the shaft over
         * each end that is a landing pad. {@link #byClass} has the same for each class up to that one (each holds the one before).
         */
        private List<Tube> spaces = List.of();
        private List<List<Tube>> byClass = List.of();
        /**
         * The smallest class something solid in the line's spaces keeps out, when that is not the small class (then the
         * line is blocked): a large aircraft may not fly a line a small one may. Null: nothing found. Saved.
         */
        @Nullable private ShipClass blockedFrom;
        /** Where the block is that keeps that class out (the first one the scan met). Saved. */
        @Nullable private BlockPos blockedFromAt;
        /**
         * The smallest class that cannot pass over a landing pad on the way without the line being raised more than lines
         * are raised ({@link RouteRules#lineRaiseMax}), and that pad (as {@link #barred} is for the small one). Not saved.
         */
        @Nullable private ShipClass barredFrom;
        @Nullable private BlockPos barredFromAt;
        /**
         * Lines are level (2026-10-18): at the height of the higher of their two ends ({@link #base}), raised by
         * {@link #lift} blocks so that they pass over the landing pads on their way ({@link #padLift}, worked out from the
         * points) and over the blocks below them (found by the scan). Saved.
         */
        private double base;
        private int lift, padLift;
        /** Where the line passes over its two ends (for telling whether its shape changed). */
        private String over = "";
        /** Something changed in the line's way while it was being looked at: once that look is over, it is looked at again. */
        private boolean again;
        /** The look under way (while {@link #queued}). */
        @Nullable private Scan scan;
        /** Goes up whenever a scan that is under way has become worthless. */
        private int version;
        private boolean queued;
        /** The last scan could not see all of the line (chunks that were not loaded). */
        private boolean gaps;
        /**
         * The landing pad (not one of the line's own ends) this line cannot pass over for small aircraft, even raised as far as
         * lines are raised. Such a line counts as blocked by that pad whatever a scan finds. Not saved: worked out from the points.
         */
        @Nullable private BlockPos barred;
        /**
         * The largest class of aircraft this line is for: the smaller of what its two ends are made for (a line to a small
         * airfield's pad is only ever flown by small aircraft; between buoys, any). A line that is not blocked is open to
         * the classes up to this one. Not saved: worked out from the points.
         */
        private ShipClass serves = ShipClass.SMALL;
        /**
         * The classes (bit 1 << ordinal) an aircraft of which was found unable to climb to this line's height although the
         * ceiling worked out for its class lies higher ({@link #reportTooHigh}). Cleared when the line's height changes. Not saved.
         */
        private int stalled;
        private final RouteNetwork network;

        private Edge(RouteNetwork network, BlockPos a, BlockPos b) {
            this.network = network;
            this.a = a;
            this.b = b;
        }

        /** Whether a block position lies in the space this line needs (for the largest class it is for). */
        public boolean needs(int x, int y, int z) {
            for (final Tube space : spaces) if (space.contains(x, y, z)) return true;
            return false;
        }

        /** The smallest class whose spaces hold this block position; null when none does. */
        @Nullable
        public ShipClass keepsOut(int x, int y, int z) {
            for (int c = 0; c < byClass.size(); c++) for (final Tube space : byClass.get(c)) if (space.contains(x, y, z)) return ShipClass.values()[c];
            return null;
        }

        /** The smallest class whose shafts (over the line's ends, not the line itself) hold this block position; null when none does. */
        @Nullable
        public ShipClass shaftKeepsOut(int x, int y, int z) {
            for (int c = 0; c < byClass.size(); c++) {
                final List<Tube> spaces = byClass.get(c);
                for (int i = 1; i < spaces.size(); i++) if (spaces.get(i).contains(x, y, z)) return ShipClass.values()[c];
            }
            return null;
        }

        public State state() { return state; }
        public ShipClass serves() { return serves; }
        /** The height the line is flown at, from end to end (lines are level). */
        public double height() { return tube.from.y; }
        /** How far the line is raised above the higher of its two ends, to pass over landing pads and blocks. */
        public int lift() { return lift; }
        @Nullable public ShipClass barredFrom() { return barredFrom; }
        @Nullable public BlockPos barredFromAt() { return barredFromAt; }
        /** The smallest class kept off this line although the small one may fly it (something in the way, or too low over a pad); null for none. */
        @Nullable public ShipClass closedFrom() { return blockedFrom == null ? barredFrom : barredFrom == null ? blockedFrom : ShipClass.smaller(blockedFrom, barredFrom); }
        /** Whether an aircraft of this class may fly this line (not blocked, made for that class, nothing in that class's way, not too high for it). */
        public boolean opens(ShipClass ship) {
            final ShipClass closed = closedFrom();
            return state != State.BLOCKED && serves.atLeast(ship) && (closed == null || ship.ordinal() < closed.ordinal()) && !tooHigh(ship);
        }
        /**
         * Whether the line lies higher than aircraft of this class can climb: above the class's ceiling ({@link #ceiling}),
         * or an aircraft of the class was found unable to climb to it.
         */
        public boolean tooHigh(ShipClass ship) {
            return height() > network.ceiling(ship) || (stalled & 1 << ship.ordinal()) != 0;
        }
        @Nullable public BlockPos blockedAt() { return blockedAt; }
        public boolean hasUnseenParts() { return gaps; }
        @Nullable public BlockPos barredBy() { return barred; }
        /** Where the block is that keeps the larger classes out ({@link #closedFrom}); null when nothing does or it is not known. */
        @Nullable public BlockPos blockedFromAt() { return blockedFromAt; }
        public Tube tube() { return tube; }
        public double length() { return tube.length(); }
        public BlockPos other(BlockPos end) { return end.equals(a) ? b : a; }
    }

    private record Pair(BlockPos a, BlockPos b) implements Comparable<Pair> {
        static Pair of(BlockPos x, BlockPos y) {
            return x.compareTo(y) <= 0 ? new Pair(x, y) : new Pair(y, x);
        }

        @Override
        public int compareTo(Pair other) {
            final int first = a.compareTo(other.a);
            return first != 0 ? first : b.compareTo(other.b);
        }
    }

    /**
     * One verification under way: walks the columns of the line's spaces (tube, then shafts), a few per tick, chunk by
     * chunk along the line. A chunk that is not loaded is passed over at the price of one look.
     *
     * <p>It starts with the line as high as the landing pads on its way ask for. Something solid in the line itself is
     * passed over: how much higher each class would have to fly there is noted, and once the whole line was looked at it
     * is looked at again that much higher (overhangs can be met then). Something solid over one of the line's ends (in a
     * shaft) cannot be passed over, nor can what lies higher than lines are raised.</p>
     */
    private static final class Scan {
        final Edge edge;
        final int version;
        final double base;
        List<List<Tube>> byClass;
        List<Tube> spaces;
        int lift;
        /** The classes still looked at: those below this one (the others are kept off the line). */
        int limit;
        /** For each class, how high the line has to be raised for what was met so far, and where that was. */
        final int[] need;
        final BlockPos[] needAt;
        Tube tube;
        long[] chunks;
        /** Which space, which of its chunks, and which of that chunk's 256 columns comes next. */
        int part, chunk, column;
        boolean gaps, done;
        @Nullable BlockPos found;
        /** The smallest class kept out by what was found that does not keep out the small one, and where that was. */
        @Nullable ShipClass blockedFrom;
        @Nullable BlockPos blockedFromAt;

        Scan(Edge edge, List<List<Tube>> byClass, int lift, int limit) {
            this.edge = edge;
            this.version = edge.version;
            this.base = edge.base;
            this.limit = limit;
            this.need = new int[byClass.size()];
            this.needAt = new BlockPos[byClass.size()];
            restart(byClass, lift);
        }

        /** Once more along the whole line, raised by {@code lift}. */
        void restart(List<List<Tube>> spacesByClass, int raised) {
            final List<Tube> before = spaces;
            byClass = spacesByClass;
            lift = raised;
            spaces = byClass.get(limit - 1);
            // (A line that only went up takes up the columns it took up: their lists of chunks are not made again.)
            if (before != null) for (final Tube now : spaces) for (final Tube was : before) now.sameColumnsAs(was);
            part = 0;
            tube = spaces.get(0);
            chunks = tube.chunks();
            chunk = 0;
            column = 0;
            java.util.Arrays.fill(need, raised);
            java.util.Arrays.fill(needAt, null);
        }

        /** The smallest class still looked at whose shafts hold this block position; {@link #limit} when none does. */
        int shaftFrom(int x, int y, int z) {
            for (int c = 0; c < limit; c++) {
                final List<Tube> of = byClass.get(c);
                for (int i = 1; i < of.size(); i++) if (of.get(i).contains(x, y, z)) return c;
            }
            return limit;
        }

        /** Whether a block position lies in the spaces this look is looking at (the line as high as this look has it). */
        boolean holds(int x, int y, int z) {
            for (final Tube space : spaces) if (space.contains(x, y, z)) return true;
            return false;
        }

        /** The classes from {@code c} up are kept off the line by what is at {@code at}. */
        void close(int c, BlockPos at) {
            limit = c;
            if (c > 0) {
                blockedFrom = ShipClass.values()[c];
                blockedFromAt = at;
            }
        }

        /** On to the next space; false when there is none. */
        boolean next() {
            if (++part >= spaces.size()) return false;
            tube = spaces.get(part);
            chunks = tube.chunks();
            chunk = 0;
            column = 0;
            return true;
        }
    }

    private final Map<BlockPos, Node> nodes = new TreeMap<>();
    private final Map<Pair, Edge> edges = new TreeMap<>();
    private final Map<BlockPos, List<Edge>> around = new HashMap<>();
    /** Chunk (as a long) to the lines whose tube comes near that chunk; for the re-check after a block change. */
    private final Map<Long, List<Edge>> byChunk = new HashMap<>();
    private final ArrayDeque<Scan> queue = new ArrayDeque<>();
    private int age;
    private boolean sweepDue = true;
    /** Who set down a buoy and is told what its lines came to once they have been looked at. Not saved. */
    private final Map<BlockPos, UUID> watchers = new HashMap<>();
    /** The pairs of landing pads between which there is a way, as last told to their colonies. Saved. */
    private final TreeSet<Pair> linked = new TreeSet<>();
    private boolean linksDue = true;
    /** Buoys whose lamp may have to change (a line of theirs came, went or changed its state). Not saved. */
    private final java.util.Set<BlockPos> lampsDue = new java.util.HashSet<>();
    /**
     * The aircraft being built on landing pads (pad to the space of its outline, and the game time until which that is
     * known; the building site renews it every few seconds while it stands). What stands there is the aircraft itself,
     * not something in the way of the pad's lines. Not saved: a site renews it within seconds of being loaded.
     */
    private final Map<BlockPos, Site> sites = new HashMap<>();

    private record Site(net.minecraft.world.level.levelgen.structure.BoundingBox box, long until) {}

    // ---- access -------------------------------------------------------------------------------------------

    public static RouteNetwork get(ServerLevel level) {
        return withCeilings(level, level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(RouteNetwork::new, RouteNetwork::load), FILE));
    }

    /** The network of this dimension if there is one already (nothing is created). */
    @Nullable
    public static RouteNetwork find(ServerLevel level) {
        final RouteNetwork network = level.getDataStorage().get(new SavedData.Factory<>(RouteNetwork::new, RouteNetwork::load), FILE);
        return network == null ? null : withCeilings(level, network);
    }

    // ---- ceilings -----------------------------------------------------------------------------------------

    /**
     * For each class, the highest a line may lie for its aircraft to climb to it (the height of the aircraft's own block):
     * where the air is still as thick as every design of the class needs ({@link AircraftType#thinnestAirFor}). Infinite
     * when that is not known (no design of the class measured, or no Sable: nothing flies then). Worked out once for the
     * dimension; not saved.
     */
    @Nullable private double[] ceilings;

    private static RouteNetwork withCeilings(ServerLevel level, RouteNetwork network) {
        if (network.ceilings != null) return network;
        final double[] ceilings = new double[ShipClass.values().length];
        for (final ShipClass ship : ShipClass.values()) {
            final double need = AircraftType.thinnestAirFor(ship);
            ceilings[ship.ordinal()] = Double.isNaN(need) || !ModList.get().isLoaded(Airline.SABLE_ID) ? Double.POSITIVE_INFINITY : SableOnly.ceiling(level, need);
        }
        network.ceilings = ceilings;
        return network;
    }

    /** The highest a line may lie for aircraft of this class (see {@link #ceilings}). */
    public double ceiling(ShipClass ship) {
        return ceilings == null || !ceilingsKept ? Double.POSITIVE_INFINITY : ceilings[ship.ordinal()];
    }

    private static boolean ceilingsKept = true;

    /** For the checks: lines higher than the ceilings are offered all the same (so that an aircraft finds out in the air). */
    public static void ceilingsForChecks(boolean kept) {
        ceilingsKept = kept;
    }

    /**
     * An aircraft of this class could not climb to the line's height (its balloon full and the line still above it): the
     * line is kept from the class until its height changes, or the server is started again.
     */
    public void reportTooHigh(Edge edge, ShipClass ship) {
        edge.stalled |= 1 << ship.ordinal();
        linksDue = true;
        lampsDue.add(edge.a);
        lampsDue.add(edge.b);
    }

    /** Only loaded when Sable is installed. */
    private static final class SableOnly {
        /** The highest whole height (from the top of the dimension down) where the air pressure is at least {@code need}. */
        static double ceiling(ServerLevel level, double need) {
            final org.joml.Vector3d at = new org.joml.Vector3d();
            for (int y = level.getMaxBuildHeight(); y >= level.getMinBuildHeight(); y--) {
                at.set(0.0, y, 0.0);
                if (dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData.getAirPressure(level, at) >= need) return y;
            }
            return level.getMinBuildHeight() - 1;
        }
    }

    public Collection<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Collection<Edge> edges() {
        return Collections.unmodifiableCollection(edges.values());
    }

    @Nullable
    public Node node(BlockPos pos) {
        return nodes.get(pos);
    }

    @Nullable
    public Edge edge(BlockPos a, BlockPos b) {
        return edges.get(Pair.of(a, b));
    }

    /**
     * What the points that left the network were (not saved): an aircraft that was on its way to a landing pad when the
     * pad left still flies over it at the height of a pad's lines, and turns back there.
     */
    private final Map<BlockPos, Node> left = new HashMap<>();

    /** Where the line passes over a point of the network (for a place that is no point: as over what it was, else a buoy). */
    public Vec3 waypoint(BlockPos pos) {
        final Node node = nodes.containsKey(pos) ? nodes.get(pos) : left.get(pos);
        return node == null ? RouteRules.waypoint(pos, Kind.BUOY) : RouteRules.waypoint(pos, node.kind(), node.largest());
    }

    /** The height a line between two points is flown at (lines are level); without a line, the higher of where lines pass over them. */
    public double height(BlockPos a, BlockPos b) {
        final Edge edge = edge(a, b);
        return edge != null ? edge.height() : Math.max(waypoint(a).y, waypoint(b).y);
    }

    public List<Edge> edgesAt(BlockPos pos) {
        return Collections.unmodifiableList(around.getOrDefault(pos, List.of()));
    }

    /** How many verifications are waiting or under way. */
    public int pending() {
        return queue.size();
    }

    // ---- points -------------------------------------------------------------------------------------------

    public void addBuoy(BlockPos pos, int tier) {
        put(new Node(pos.immutable(), Kind.BUOY, tier, 0, pos.immutable(), null));
    }

    /**
     * An airfield's landing pad: {@code pad} is the middle block of its floor, {@code heading} a multiple of 90 degrees,
     * {@code level} the building level of the airfield (it decides the reach).
     */
    public void setAirfield(BlockPos pad, int heading, int level) {
        setAirfield(pad, heading, level, pad);
    }

    /** ... and {@code hut} the hut block of the airfield: where the airfield stands, for the large maps. */
    public void setAirfield(BlockPos pad, int heading, int level, BlockPos hut) {
        setAirfield(pad, heading, level, hut, Facility.SMALL_AIRFIELD);
    }

    /** ... and {@code facility} the kind of place the pad belongs to (which classes of aircraft may berth there). */
    public void setAirfield(BlockPos pad, int heading, int level, BlockPos hut, Facility facility) {
        put(new Node(pad.immutable(), Kind.AIRFIELD, Math.max(1, Math.min(RouteRules.AIRFIELD_LEVELS, level)), Math.floorMod(heading, 360), hut.immutable(), facility));
    }

    /**
     * What a line is made for and the spaces it needs, from its two points as they are now: the largest class it serves
     * is the smaller of what its two ends are made for ({@link Edge#serves}); for each class up to that one, its tube and
     * the shafts over its pads. True when that changed.
     */
    private boolean shape(Edge edge) {
        final Node a = nodes.get(edge.a), b = nodes.get(edge.b);
        // (Where the line passes over its two ends is part of it: a buoy of another grade has its shaft begin elsewhere.)
        final String before = edge.tube == null ? "" : String.valueOf(edge.serves) + edge.tube.from + edge.tube.to + edge.over;
        edge.serves = a == null || b == null ? ShipClass.SMALL : ShipClass.smaller(a.largest(), b.largest());
        edge.base = Math.max(waypoint(edge.a).y, waypoint(edge.b).y);
        edge.over = String.valueOf(waypoint(edge.a)) + waypoint(edge.b);
        edge.byClass = build(edge, edge.lift);
        final double was = edge.tube == null ? Double.NaN : edge.tube.from.y;
        edge.tube = edge.byClass.get(0).get(0);
        edge.spaces = edge.byClass.get(edge.byClass.size() - 1);
        // At another height the line is another matter for the aircraft that could not climb to it.
        if (edge.tube.from.y != was) edge.stalled = 0;
        return !(String.valueOf(edge.serves) + edge.tube.from + edge.tube.to + edge.over).equals(before);
    }

    /**
     * The spaces of a line for each class it serves, with the line raised by {@code lift}: the level tube of the line
     * first, then the shafts over its ends (over a pad from the pad up to the line; over a buoy from where the lines pass
     * over it up to this line, where an aircraft goes up or down from one line to the next).
     */
    private List<List<Tube>> build(Edge edge, int lift) {
        final Node a = nodes.get(edge.a), b = nodes.get(edge.b);
        final Vec3 overA = waypoint(edge.a), overB = waypoint(edge.b);
        final double y = edge.base + lift;
        final Vec3 from = new Vec3(overA.x, y, overA.z), to = new Vec3(overB.x, y, overB.z);
        final List<List<Tube>> byClass = new ArrayList<>();
        for (final ShipClass ship : ShipClass.values()) {
            if (!edge.serves.atLeast(ship)) break;
            final List<Tube> spaces = new ArrayList<>();
            spaces.add(new Tube(from, to, RouteRules.tubeRadius(ship), RouteRules.tubeHalfHeight(ship)));
            end(spaces, edge.a, a, overA, y, ship);
            end(spaces, edge.b, b, overB, y, ship);
            // A tube that only went up or down takes up the same columns: the list of its chunks is not made again
            // (for a long line that is a walk along all of it).
            if (ship.ordinal() < edge.byClass.size()) for (final Tube now : spaces) for (final Tube was : edge.byClass.get(ship.ordinal())) now.sameColumnsAs(was);
            byClass.add(spaces);
        }
        return byClass;
    }

    private static void end(List<Tube> spaces, BlockPos pos, @Nullable Node node, Vec3 over, double top, ShipClass ship) {
        if (node == null) return;
        final Tube shaft = node.kind() == Kind.AIRFIELD ? RouteRules.shaft(pos, node.largest(), ship, top) : RouteRules.buoyShaft(over, top, ship);
        if (shaft != null) spaces.add(shaft);
    }

    /** The line is raised by {@code lift} (its spaces are made anew). */
    private void reshape(Edge edge, int lift) {
        index(edge, false);
        edge.lift = lift;
        shape(edge);
        index(edge, true);
        // (Raised over what is in its way, it may now lie too high for a class.)
        linksDue = true;
        lampsDue.add(edge.a);
        lampsDue.add(edge.b);
        setDirty();
    }

    private void put(Node node) {
        final Node before = nodes.get(node.pos());
        if (node.equals(before)) return;
        left.remove(node.pos());
        if (before != null && before.kind() == node.kind() && before.heading() == node.heading()) {
            // Only the reach is different (an airfield was built up or taken down a level): the lines that still connect
            // stay as they are, with what is known about them; lines that do not reach any more go, new ones come.
            nodes.put(node.pos(), node);
            linksDue = true;
            for (final Edge edge : around.getOrDefault(node.pos(), List.of())) {
                index(edge, false);
                final boolean changed = shape(edge);
                index(edge, true);
                if (changed) {
                    edge.version++;
                    set(edge, State.UNVERIFIED, null);
                    enqueue(edge);
                }
            }
            // (A buoy of another grade serves other classes: how high its lines have to pass over the pads changes as well.)
            rebar();
            for (final Edge edge : new ArrayList<>(around.getOrDefault(node.pos(), List.of()))) {
                final Node other = nodes.get(edge.other(node.pos()));
                if (other == null || !RouteRules.connects(node, other)) unlink(edge);
            }
            for (final Node other : new ArrayList<>(nodes.values())) {
                if (RouteRules.connects(node, other)) link(node.pos(), other.pos());
            }
            setDirty();
            return;
        }
        if (before != null) remove(node.pos());
        linksDue = true;
        nodes.put(node.pos(), node);
        for (final Node other : nodes.values()) {
            if (RouteRules.connects(node, other)) link(node.pos(), other.pos());
        }
        if (node.kind() == Kind.AIRFIELD) rebar();
        setDirty();
    }

    public boolean remove(BlockPos pos) {
        final Node gone = nodes.remove(pos);
        if (gone == null) return false;
        left.put(pos.immutable(), gone);
        linksDue = true;
        watchers.remove(pos);
        for (final Edge edge : new ArrayList<>(around.getOrDefault(pos, List.of()))) unlink(edge);
        around.remove(pos);
        if (gone.kind() == Kind.AIRFIELD) rebar();
        setDirty();
        return true;
    }

    /**
     * How high a line has to be raised to pass over the landing pads on its way (not its own ends) with an aircraft of the
     * pad's largest class standing there, into {@link Edge#padLift}: as high as every class it serves asks for, up to the
     * first class that would ask for more than lines are raised ({@link Edge#barredFrom}, and the pad). Returns that pad
     * when it is the small class (the line is never flown then).
     */
    @Nullable
    private BlockPos bar(Edge edge) {
        final Vec3 overA = waypoint(edge.a), overB = waypoint(edge.b);
        final Tube level = new Tube(new Vec3(overA.x, edge.base, overA.z), new Vec3(overB.x, edge.base, overB.z), 1, 1);
        final int classes = edge.serves.ordinal() + 1, cap = RouteRules.lineRaiseMax();
        final double[] need = new double[classes];
        final BlockPos[] by = new BlockPos[classes];
        for (final Node node : nodes.values()) {
            if (node.kind() != Kind.AIRFIELD || node.pos().equals(edge.a) || node.pos().equals(edge.b)) continue;
            for (int c = 0; c < classes; c++) {
                final double up = RouteRules.padLift(level, node.pos(), node.largest(), ShipClass.values()[c]);
                if (up > need[c]) {
                    need[c] = up;
                    by[c] = node.pos();
                }
            }
        }
        int lift = 0;
        edge.barredFrom = null;
        edge.barredFromAt = null;
        for (int c = 0; c < classes; c++) {
            final int up = (int) Math.ceil(need[c] - 1.0e-9);
            if (up > cap) {
                edge.barredFrom = ShipClass.values()[c];
                edge.barredFromAt = by[c];
                break;
            }
            lift = Math.max(lift, up);
        }
        edge.padLift = lift;
        return edge.barredFrom == ShipClass.SMALL ? edge.barredFromAt : null;
    }

    /** Landing pads came or went: how high each line has to pass over the pads on its way is worked out again. */
    private void rebar() {
        for (final Edge edge : edges.values()) {
            final ShipClass fromBefore = edge.barredFrom;
            final int liftBefore = edge.padLift;
            final BlockPos now = bar(edge);
            if (fromBefore != edge.barredFrom) linksDue = true;
            if (java.util.Objects.equals(now, edge.barred) && fromBefore == edge.barredFrom && liftBefore == edge.padLift) continue;
            edge.barred = now;
            edge.version++;
            // At once as high as the pads ask for; whether it may come down again is for the scan to find.
            if (edge.lift < edge.padLift) reshape(edge, edge.padLift);
            if (now != null) {
                set(edge, State.BLOCKED, now);
            } else {
                set(edge, State.UNVERIFIED, null);
                enqueue(edge);
            }
        }
    }

    private Edge link(BlockPos x, BlockPos y) {
        final Pair pair = Pair.of(x, y);
        Edge edge = edges.get(pair);
        if (edge != null) return edge;
        edge = new Edge(this, pair.a(), pair.b());
        shape(edge);
        edges.put(pair, edge);
        around.computeIfAbsent(edge.a, p -> new ArrayList<>()).add(edge);
        around.computeIfAbsent(edge.b, p -> new ArrayList<>()).add(edge);
        lampsDue.add(edge.a);
        lampsDue.add(edge.b);
        edge.barred = bar(edge);
        if (edge.padLift > 0) {
            edge.lift = edge.padLift;
            shape(edge);
        }
        index(edge, true);
        if (edge.barred != null) {
            edge.state = State.BLOCKED;
            edge.blockedAt = edge.barred;
        }
        enqueue(edge);
        return edge;
    }

    private void unlink(Edge edge) {
        edges.remove(Pair.of(edge.a, edge.b));
        final List<Edge> atA = around.get(edge.a), atB = around.get(edge.b);
        if (atA != null) atA.remove(edge);
        if (atB != null) atB.remove(edge);
        lampsDue.add(edge.a);
        lampsDue.add(edge.b);
        index(edge, false);
        edge.version++;
    }

    private void index(Edge edge, boolean add) {
        // Every chunk that holds a column of one of the line's spaces (the tube; the shafts over its ends).
        final java.util.Set<Long> keys = new java.util.HashSet<>();
        for (final Tube space : edge.spaces) for (final long key : space.chunks()) keys.add(key);
        for (final long key : keys) {
            if (add) {
                byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
            } else {
                final List<Edge> list = byChunk.get(key);
                if (list != null && list.remove(edge) && list.isEmpty()) byChunk.remove(key);
            }
        }
    }

    // ---- states -------------------------------------------------------------------------------------------

    private void set(Edge edge, State wanted, @Nullable BlockPos wantedAt) {
        // A line that passes too low over a landing pad is blocked by that pad, whatever else is known about it.
        final State state = edge.barred != null ? State.BLOCKED : wanted;
        final BlockPos blockedAt = edge.barred != null ? edge.barred : wantedAt;
        if (edge.state == state && java.util.Objects.equals(edge.blockedAt, blockedAt)) return;
        if (edge.state != state) {
            linksDue = true;
            lampsDue.add(edge.a);
            lampsDue.add(edge.b);
        }
        edge.state = state;
        edge.blockedAt = blockedAt;
        setDirty();
    }

    private void enqueue(Edge edge) {
        if (edge.queued || edge.barred != null) return;      // nothing to look for on a line that is never flown
        edge.queued = true;
        // The classes kept off by a pad on the way are not looked for.
        final int limit = edge.barredFrom == null ? edge.serves.ordinal() + 1 : Math.min(edge.serves.ordinal() + 1, edge.barredFrom.ordinal());
        edge.scan = new Scan(edge, build(edge, edge.padLift), edge.padLift, limit);
        edge.again = false;
        queue.add(edge.scan);
    }

    /**
     * Something changed in a line's way that a new look may settle (it may be passed over higher, or lower again): looked
     * at again; when a look is under way, once more after it (starting it over with every block a builder sets could keep
     * a long line from ever being looked at to its end).
     */
    private void lookAgain(Edge edge) {
        if (edge.queued && edge.scan != null && edge.scan.version == edge.version) {
            edge.again = true;
            return;
        }
        // (A look that has become worthless is dropped by the next tick, which queues a new one for a line not verified.)
        edge.version++;
        set(edge, State.UNVERIFIED, null);
        enqueue(edge);
    }

    /** A line was flown from end to end without meeting anything: it is clear, even if it could not be scanned before. */
    public void confirmClear(Edge edge) {
        if (edge.state != State.UNVERIFIED) return;
        // (Not while a look has the line at another height than it was flown: that look decides.)
        if (edge.queued && edge.scan != null && edge.scan.lift != edge.lift) return;
        edge.version++;
        edge.gaps = false;
        set(edge, State.CLEAR, null);
    }

    /** Something solid was met on a line (seen from an aircraft, or reported by a block change). */
    public void reportBlocked(Edge edge, BlockPos at) {
        edge.version++;
        set(edge, State.BLOCKED, at.immutable());
    }

    /**
     * An aircraft of class {@code ship} met something solid on a line. Over one of the line's ends (in a shaft) it keeps
     * the classes off whose shaft holds it. Anywhere else the line is looked at again, as it may be passed over higher up
     * (or the aircraft flew the line at the height it had before it was raised); at once blocked only when its column
     * reaches higher than lines are raised.
     */
    public void reportBlocked(ServerLevel level, Edge edge, BlockPos at) {
        solidIn(level, edge, at.immutable());
    }

    /** Something solid is at {@code pos} in a line's spaces: what that does to the line (see {@link #reportBlocked}). */
    private void solidIn(ServerLevel level, Edge edge, BlockPos pos) {
        final ShipClass shaft = edge.shaftKeepsOut(pos.getX(), pos.getY(), pos.getZ());
        if (shaft != null) keepOut(edge, shaft, pos);
        if (shaft == ShipClass.SMALL) return;
        // The smallest class whose spaces hold it: when that is smaller than the one whose shaft holds it, it lies in the
        // line itself for that class (beside a pad: in the larger shaft, and in the small aircraft's line).
        final ShipClass kept = edge.keepsOut(pos.getX(), pos.getY(), pos.getZ());
        if (shaft != null && (kept == null || kept.ordinal() >= shaft.ordinal())) return;
        final LevelChunk chunk = kept == null ? null : level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && passOver(level, chunk::getBlockState, false, edge.byClass.get(kept.ordinal()).get(0), edge.base, edge.lift, pos.getX(), pos.getZ(), new int[1]) > RouteRules.lineRaiseMax()) {
            keepOut(edge, kept, pos);
        } else {
            lookAgain(edge);
        }
    }

    /** Something solid keeps the classes from {@code from} up off a line (all of them: the line is blocked). */
    private void keepOut(Edge edge, ShipClass from, BlockPos at) {
        if (from == ShipClass.SMALL) {
            if (edge.state != State.BLOCKED) reportBlocked(edge, at);
            return;
        }
        // A look under way may have passed that place already: its end must not open the line again (see the tick). Also
        // when the class is kept off already: what keeps it off now may be gone by the end of that look.
        if (edge.queued) edge.again = true;
        if (edge.blockedFrom != null && edge.blockedFrom.ordinal() <= from.ordinal()) return;
        edge.blockedFrom = from;
        edge.blockedFromAt = at.immutable();
        linksDue = true;
        setDirty();
    }

    /** The lines of one point go back to "not verified" and are looked at again. */
    public void recheck(BlockPos point) {
        for (final Edge edge : around.getOrDefault(point, List.of())) {
            edge.version++;
            set(edge, State.UNVERIFIED, null);
            enqueue(edge);
        }
    }

    /**
     * Looks at everything again: the rules may have changed (config), so lines are made and dropped by the current rules,
     * every tube is rebuilt and every line goes back to "not verified".
     */
    public int recheckAll() {
        for (final Edge edge : new ArrayList<>(edges.values())) {
            final Node a = nodes.get(edge.a), b = nodes.get(edge.b);
            if (a == null || b == null || !RouteRules.connects(a, b)) {
                unlink(edge);
                continue;
            }
            index(edge, false);
            shape(edge);
            edge.barred = bar(edge);
            edge.lift = edge.padLift;
            shape(edge);
            index(edge, true);
            edge.version++;
            edge.gaps = false;
            edge.again = false;
            edge.blockedFrom = null;
            set(edge, State.UNVERIFIED, null);
        }
        relink();
        // Scans that were waiting are worthless now; fresh ones are queued for everything.
        for (final Scan scan : queue) scan.edge.queued = false;
        queue.clear();
        for (final Edge edge : edges.values()) enqueue(edge);
        setDirty();
        return edges.size();
    }

    private void relink() {
        final List<Node> all = new ArrayList<>(nodes.values());
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                final Node a = all.get(i), b = all.get(j);
                if (RouteRules.connects(a, b)) link(a.pos(), b.pos());
            }
        }
    }

    // ---- verification -------------------------------------------------------------------------------------

    /** What counts as solid for an aircraft: anything with a collision shape, except buoys. */
    public static boolean solid(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof BuoyBlock) && !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * In the way of a line: solid, and not part of an aircraft that is being built on a landing pad. For a block read
     * from the save ({@code saved}) its shape is taken without asking the world (asking about a place that is not loaded
     * would load it).
     */
    private boolean inTheWay(ServerLevel level, BlockPos pos, BlockState state, boolean saved) {
        if (saved ? state.isAir() || state.getBlock() instanceof BuoyBlock || state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, pos).isEmpty() : !solid(level, pos, state)) return false;
        if (sites.isEmpty()) return true;
        for (final Site site : sites.values()) if (site.box().isInside(pos)) return false;
        return true;
    }

    /**
     * An aircraft is being built on this pad, in the space {@code box}, for some seconds more (the site renews this while
     * it stands). The blocks put up there do not block the pad's lines (they did, and the colonies were told of lines
     * lost and found again with every block the builders set or took away). When a site comes, the lines it blocked are
     * looked at again; when it goes (finished, or called off with blocks left standing), all the pad's lines are.
     */
    public void building(BlockPos pad, net.minecraft.world.level.levelgen.structure.BoundingBox box, long until) {
        if (sites.put(pad.immutable(), new Site(box, until)) == null) lookAgain(pad, false);
    }

    private void lookAgain(BlockPos pad, boolean all) {
        for (final Edge edge : new ArrayList<>(around.getOrDefault(pad, List.of()))) {
            if (!all && edge.state != State.BLOCKED) continue;
            if (edge.state != State.UNVERIFIED) {
                edge.version++;
                set(edge, State.UNVERIFIED, null);
            }
            enqueue(edge);
        }
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final RouteNetwork network = find(level);
        if (network != null) network.tick(level);
    }

    private void tick(ServerLevel level) {
        if (!sites.isEmpty() && level.getGameTime() % 20 == 0) {
            final long now = level.getGameTime();
            for (final java.util.Iterator<Map.Entry<BlockPos, Site>> it = sites.entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<BlockPos, Site> site = it.next();
                if (site.getValue().until() >= now) continue;
                it.remove();
                lookAgain(site.getKey(), true);
            }
        }
        if (sweepDue || ++age % RouteRules.retryTicks() == 0) {
            sweepDue = false;
            sweep(level);
        }
        int budget = RouteRules.verifyBlocksPerTick();
        while (budget > 0 && !queue.isEmpty()) {
            final Scan scan = queue.peek();
            final Edge edge = scan.edge;
            if (edges.get(Pair.of(edge.a, edge.b)) != edge) {
                queue.poll();
                continue;
            }
            if (scan.version != edge.version) {
                // The line changed while it was being looked at: start over, unless a block change already settled it.
                queue.poll();
                edge.queued = false;
                if (edge.state == State.UNVERIFIED) enqueue(edge);
                continue;
            }
            budget = step(level, scan, budget);
            if (!scan.done) break;
            queue.poll();
            edge.queued = false;
            edge.gaps = scan.gaps;
            // What was learnt while the look was under way (a block over a pad, for the larger classes) stays until the next look.
            final boolean stricter = edge.again && edge.blockedFrom != null && (scan.blockedFrom == null || edge.blockedFrom.ordinal() < scan.blockedFrom.ordinal());
            final ShipClass from = stricter ? edge.blockedFrom : scan.blockedFrom;
            if (edge.blockedFrom != from) {
                edge.blockedFrom = from;
                linksDue = true;
            }
            edge.blockedFromAt = stricter ? edge.blockedFromAt : scan.blockedFromAt;
            if (scan.lift != edge.lift) reshape(edge, scan.lift);
            if (scan.found != null) set(edge, State.BLOCKED, scan.found);
            else if (scan.gaps || edge.again) set(edge, State.UNVERIFIED, null);
            else set(edge, State.CLEAR, null);
            if (edge.again) {
                edge.again = false;
                enqueue(edge);
            }
        }
        if (!lampsDue.isEmpty()) lamps(level);
        if (!watchers.isEmpty() && age % 10 == 0) report(level);
        if (linksDue && queue.isEmpty()) {
            linksDue = false;
            tellLinks(level);
        }
    }

    // ---- telling people -----------------------------------------------------------------------------------

    /** Lights or puts out the lamps of buoys whose lines changed: it burns while at least one line can be flown. */
    private void lamps(ServerLevel level) {
        for (final BlockPos pos : lampsDue) {
            final Node node = nodes.get(pos);
            if (node == null || node.kind() != Kind.BUOY) continue;
            final LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) continue;      // looked at again with the next sweep
            final BlockState state = chunk.getBlockState(pos);
            if (!(state.getBlock() instanceof BuoyBlock)) continue;
            boolean open = false;
            for (final Edge edge : around.getOrDefault(pos, List.of())) open |= edge.state != State.BLOCKED;
            // Without telling the neighbours: nothing else needs to know, and this must not look like a block change on a line.
            if (state.getValue(BuoyBlock.LIT) != open) level.setBlock(pos, state.setValue(BuoyBlock.LIT, open), 2);
        }
        lampsDue.clear();
    }

    private static final String MESSAGE = "message." + Airline.MOD_ID + ".buoy.";

    /** A player set down a buoy: told what its lines came to when they have been looked at (as a colony sign tells its colony). */
    public void watch(BlockPos buoy, UUID player) {
        watchers.put(buoy.immutable(), player);
    }

    private void report(ServerLevel level) {
        for (final Map.Entry<BlockPos, UUID> watcher : new ArrayList<>(watchers.entrySet())) {
            final List<Edge> lines = around.getOrDefault(watcher.getKey(), List.of());
            boolean busy = false;
            for (final Edge edge : lines) busy |= edge.queued;
            if (busy) continue;
            watchers.remove(watcher.getKey());
            if (level.getServer().getPlayerList().getPlayer(watcher.getValue()) instanceof ServerPlayer player) {
                for (final Component line : describe(level, watcher.getKey(), true)) player.sendSystemMessage(line);
            }
        }
    }

    /**
     * What a point is and how its lines stand, for a player who asks (uses the buoy) or has just set it down:
     * a line of summary, the blocked lines with the block that is in the way, and the airfields it leads to.
     */
    public List<Component> describe(ServerLevel level, BlockPos point, boolean fresh) {
        final List<Component> lines = new ArrayList<>();
        final Node node = nodes.get(point);
        if (node == null) return lines;
        int clear = 0, blocked = 0, open = 0;
        for (final Edge edge : around.getOrDefault(point, List.of())) {
            if (edge.state == State.CLEAR) clear++;
            else if (edge.state == State.BLOCKED) blocked++;
            else open++;
        }
        lines.add(Component.translatable(MESSAGE + (fresh ? "placed" : "info"), node.tier(), node.reach(), clear, blocked, open));
        for (final Edge edge : around.getOrDefault(point, List.of())) {
            if (edge.state != State.BLOCKED || edge.blockedAt == null) continue;
            final BlockPos other = edge.other(point), at = edge.blockedAt;
            lines.add(Component.translatable(MESSAGE + (edge.barred != null ? "barred" : "blocked"), other.getX(), other.getY(), other.getZ(), at.getX(), at.getY(), at.getZ()));
        }
        // The airfields this point leads to, over lines that are not blocked.
        final StringBuilder fields = new StringBuilder();
        for (final Node other : nodes.values()) {
            if (other.kind() != Kind.AIRFIELD || other.pos().equals(point)) continue;
            final List<BlockPos> way = route(point, other.pos(), other.largest());
            if (way.size() < 2) continue;
            fields.append(fields.isEmpty() ? "" : ", ").append(AirLinks.name(level, other.pos())).append(" (").append(Math.round(length(way))).append(")");
        }
        lines.add(fields.isEmpty() ? Component.translatable(MESSAGE + "leads_nowhere") : Component.translatable(MESSAGE + "leads_to", fields.toString()));
        return lines;
    }

    /** Whether the colonies of these two landing pads were last told that there is a way between them. */
    public boolean linked(BlockPos a, BlockPos b) {
        return linked.contains(Pair.of(a, b));
    }

    /** The largest class of aircraft that may berth at both these pads; null when there is none (a small and a large airfield). */
    @Nullable
    public static ShipClass common(Node a, Node b) {
        for (int i = ShipClass.values().length - 1; i >= 0; i--) {
            final ShipClass ship = ShipClass.values()[i];
            if (a.berths(ship) && b.berths(ship)) return ship;
        }
        return null;
    }

    /** Looks which landing pads have a way between them now, and tells the colonies what changed since the last look. */
    private void tellLinks(ServerLevel level) {
        final List<Node> fields = new ArrayList<>();
        for (final Node node : nodes.values()) if (node.kind() == Kind.AIRFIELD) fields.add(node);
        final TreeSet<Pair> now = new TreeSet<>();
        final Map<Pair, Double> lengths = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            for (int j = i + 1; j < fields.size(); j++) {
                final ShipClass both = common(fields.get(i), fields.get(j));
                if (both == null) continue;
                final List<BlockPos> way = route(fields.get(i).pos(), fields.get(j).pos(), both);
                if (way.size() < 2) continue;
                final Pair pair = Pair.of(fields.get(i).pos(), fields.get(j).pos());
                now.add(pair);
                lengths.put(pair, length(way));
            }
        }
        if (now.equals(linked)) return;
        // (Colonies are told about the routes their players drew, by ColonyRoutes, once for two colonies: 2026-10-18. The
        // notices for every pair of airfields that came near each other, in the colonies' names only, were confusing.)
        linked.clear();
        linked.addAll(now);
        setDirty();
    }

    /** Looks at as many columns as the budget allows. Chunks that are not loaded are read from the save (see {@link Saved}). */
    private int step(ServerLevel level, Scan scan, int budget) {
        final int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0) {
            if (scan.chunk >= scan.chunks.length) {
                if (scan.next()) continue;
                // The whole line was looked at: when something in it can be passed over higher up, once more that high.
                if (raise(scan)) continue;
                scan.done = true;
                break;
            }
            final long key = scan.chunks[scan.chunk];
            final int chunkX = ChunkPos.getX(key), chunkZ = ChunkPos.getZ(key);
            final LevelChunk loaded = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            final View chunk;
            if (loaded != null) {
                chunk = loaded::getBlockState;
            } else {
                // Not loaded: read from the save (2026-10-18), without loading it into the world.
                final Saved saved = fromSave(level, key);
                if (saved == WAITING) {
                    // Being read: the chunks after it that are not loaded either are asked for as well (a long line over land
                    // nobody is near is read many chunks at a time, not one a tick), and the line is looked at again next tick.
                    for (int i = scan.chunk + 1, asked = 0; i < scan.chunks.length && asked < READ_AHEAD; i++) {
                        final long next = scan.chunks[i];
                        if (level.getChunkSource().getChunkNow(ChunkPos.getX(next), ChunkPos.getZ(next)) == null && fromSave(level, next) == WAITING) asked++;
                    }
                    return budget;
                }
                if (saved.none) {
                    // Never made (or not readable): this part of the line was not seen (every chunk in the list holds a column of the space).
                    budget--;
                    scan.gaps = true;
                    scan.chunk++;
                    scan.column = 0;
                    continue;
                }
                chunk = saved;
            }
            final Tube tube = scan.tube;
            while (budget > 0 && scan.column < 256) {
                final int x = (chunkX << 4) + (scan.column >> 4), z = (chunkZ << 4) + (scan.column & 15);
                scan.column++;
                budget--;
                if (!tube.touchesColumn(x, z)) continue;
                final double low = tube.low(x, z), high = tube.high(x, z);
                final int from = Math.max(minY, (int) Math.ceil(low - 0.5)), to = Math.min(maxY, (int) Math.floor(high - 0.5));
                for (int y = from; y <= to; y++) {
                    budget--;
                    final BlockState state = chunk.state(pos.set(x, y, z));
                    if (!inTheWay(level, pos, state, loaded == null)) continue;
                    // Over one of the line's ends (in a shaft) nothing is passed over: it keeps off the classes whose shaft holds it.
                    final int shaft = scan.shaftFrom(x, y, z);
                    if (shaft < scan.limit) {
                        if (shaft == 0) {
                            scan.found = pos.immutable();
                            scan.done = true;
                            return budget;
                        }
                        // Only the larger classes are kept out: noted, and the scan goes on (the small class may still meet something).
                        scan.close(shaft, pos.immutable());
                    }
                    if (scan.part == 0) {
                        // In the line itself: how much higher each class would pass over this column (the rest of it is looked at for that).
                        budget -= over(level, chunk, loaded == null, scan, x, z, pos.immutable());
                        break;
                    }
                }
            }
            if (scan.column >= 256) {
                scan.chunk++;
                scan.column = 0;
            }
        }
        return budget;
    }

    /**
     * Something solid at {@code hit} in the line itself, in the column x, z: for each class still looked at whose line
     * passes through the column, how far the line would have to be raised to pass over everything in the column (it is
     * raised over the highest block in its way, and looked at again there, until it is free or higher than lines are
     * raised). Returns how many block positions were read.
     */
    private int over(ServerLevel level, View chunk, boolean saved, Scan scan, int x, int z, BlockPos hit) {
        final int[] reads = new int[1];
        for (int c = 0; c < scan.limit; c++) {
            final Tube line = scan.byClass.get(c).get(0);
            if (!line.touchesColumn(x, z)) continue;
            final int lift = passOver(level, chunk, saved, line, scan.base, scan.lift, x, z, reads);
            if (lift > scan.need[c]) {
                scan.need[c] = lift;
                scan.needAt[c] = hit;
            }
        }
        return reads[0];
    }

    /**
     * How far a line (its level tube {@code line}, at {@code base} raised by {@code lift}) has to be raised to pass over
     * everything in the column x, z: raised over the highest block in its way and looked at again there, until it is
     * free or higher than lines are raised. {@code reads[0]} counts the block positions read.
     */
    private int passOver(ServerLevel level, View chunk, boolean saved, Tube line, double base, int lift, int x, int z, int[] reads) {
        final int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1, cap = RouteRules.lineRaiseMax();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (lift <= cap) {
            final double y = base + lift;
            // The blocks of the column inside the line's tube at this height (as Tube#contains counts them).
            final int from = Math.max(minY, (int) Math.ceil(y - line.halfHeight - 1.0)), to = Math.min(maxY, (int) Math.floor(y + line.halfHeight));
            int top = Integer.MIN_VALUE;
            for (int at = to; at >= from; at--) {
                reads[0]++;
                pos.set(x, at, z);
                if (inTheWay(level, pos, chunk.state(pos), saved)) {
                    top = at;
                    break;
                }
            }
            if (top == Integer.MIN_VALUE) break;
            lift = (int) Math.floor(top + 1.0 + line.halfHeight - base) + 1;
        }
        return lift;
    }

    /**
     * A look along the whole line is over. A class that would have to be raised more than lines are raised is kept off
     * the line (the small class: the line is blocked by what it met first there). When the others need the line higher
     * than it was looked at, true: it is looked at again from the start, that high.
     */
    private boolean raise(Scan scan) {
        final int cap = RouteRules.lineRaiseMax();
        for (int c = 0; c < scan.limit; c++) {
            if (scan.need[c] <= cap) continue;
            if (c == 0) {
                scan.found = scan.needAt[0];
                return false;
            }
            scan.close(c, scan.needAt[c]);
            break;
        }
        int lift = scan.lift;
        for (int c = 0; c < scan.limit; c++) lift = Math.max(lift, scan.need[c]);
        if (lift <= scan.lift) return false;
        scan.restart(build(scan.edge, lift), lift);
        return true;
    }

    /** The blocks of one chunk as a scan reads them: a loaded chunk, or one read from the save. */
    @FunctionalInterface
    private interface View {
        BlockState state(BlockPos pos);
    }

    /**
     * A chunk as it was saved (2026-10-18, the user: lines stayed "being looked at"; lines over land nobody was near
     * could not be seen at all): its blocks, read from the region file without loading the chunk into the world (no
     * ticking, nothing made). {@code none}: there is nothing to read (never made, or saved by another version).
     */
    private static final class Saved implements View {
        final boolean none;
        final long readAt;
        final int minSection;
        final net.minecraft.world.level.chunk.PalettedContainer<BlockState>[] sections;

        @SuppressWarnings("unchecked")
        Saved(boolean none, long readAt, int minSection, int count) {
            this.none = none;
            this.readAt = readAt;
            this.minSection = minSection;
            this.sections = new net.minecraft.world.level.chunk.PalettedContainer[none ? 0 : count];
        }

        @Override
        public BlockState state(BlockPos pos) {
            final int i = (pos.getY() >> 4) - minSection;
            final net.minecraft.world.level.chunk.PalettedContainer<BlockState> section = i < 0 || i >= sections.length ? null : sections[i];
            return section == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
    }

    private static final Saved WAITING = new Saved(true, 0, 0, 0);
    /** How many chunks ahead are asked for at once while one is being read. */
    private static final int READ_AHEAD = 64;
    /** How long a chunk read from the save is used (it may be loaded, changed and saved again meanwhile). */
    private static final int SAVED_TICKS = 1200;
    private static final com.mojang.serialization.Codec<net.minecraft.world.level.chunk.PalettedContainer<BlockState>> SECTION_STATES =
            net.minecraft.world.level.chunk.PalettedContainer.codecRW(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY, BlockState.CODEC,
                    net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    /** Chunks read from the save (the last few hundred), and the reads under way. Not saved. */
    private final Map<Long, Saved> saved = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Saved> eldest) {
            return size() > 512;
        }
    };
    private final Map<Long, java.util.concurrent.CompletableFuture<java.util.Optional<CompoundTag>>> reading = new HashMap<>();

    /** A chunk that is not loaded, as it was saved; {@link #WAITING} while it is being read (the region files are read on their own thread). */
    private Saved fromSave(ServerLevel level, long key) {
        final long now = level.getGameTime();
        final Saved known = saved.get(key);
        if (known != null && now - known.readAt < SAVED_TICKS) return known;
        final java.util.concurrent.CompletableFuture<java.util.Optional<CompoundTag>> read = reading.get(key);
        if (read == null) {
            reading.put(key, level.getChunkSource().chunkMap.read(new ChunkPos(key)));
            return WAITING;
        }
        if (!read.isDone()) return WAITING;
        reading.remove(key);
        Saved got;
        try {
            got = decode(level, read.getNow(java.util.Optional.empty()).orElse(null), now);
        } catch (RuntimeException unreadable) {
            got = new Saved(true, now, 0, 0);
        }
        saved.put(key, got);
        return got;
    }

    /** The blocks of a saved chunk; "none" unless it was saved complete by this version of the game. */
    private static Saved decode(ServerLevel level, @Nullable CompoundTag chunk, long now) {
        if (chunk == null || chunk.getInt("DataVersion") != net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion()
                || !chunk.getString("Status").endsWith("full")) return new Saved(true, now, 0, 0);
        final Saved out = new Saved(false, now, level.getMinSection(), level.getSectionsCount());
        final ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            final CompoundTag section = sections.getCompound(i);
            final int index = section.getByte("Y") - out.minSection;
            if (index < 0 || index >= out.sections.length || !section.contains("block_states", Tag.TAG_COMPOUND)) continue;
            out.sections[index] = SECTION_STATES.parse(net.minecraft.nbt.NbtOps.INSTANCE, section.getCompound("block_states")).result().orElse(null);
        }
        return out;
    }

    /** Now and then: lines that could not be seen completely are tried again, and buoys whose block is gone are dropped. */
    private void sweep(ServerLevel level) {
        for (final Node node : new ArrayList<>(nodes.values())) {
            if (node.kind() != Kind.BUOY) continue;
            final LevelChunk chunk = level.getChunkSource().getChunkNow(node.pos().getX() >> 4, node.pos().getZ() >> 4);
            if (chunk == null) continue;
            if (!(chunk.getBlockState(node.pos()).getBlock() instanceof BuoyBlock buoy) || buoy.tier() != node.tier()) remove(node.pos());
            else lampsDue.add(node.pos());
        }
        for (final Edge edge : edges.values()) {
            if (edge.state == State.UNVERIFIED) enqueue(edge);
        }
    }

    // ---- re-check after a block change --------------------------------------------------------------------

    /** Fires for every block change that tells its neighbours (placing, breaking, pistons, explosions, most mods). */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final RouteNetwork network = find(level);
        if (network != null && !network.byChunk.isEmpty()) network.blockChanged(level, event.getPos(), event.getState());
    }

    /**
     * A block changed. A solid block in the shaft over one of a line's ends blocks that line at once (for the classes
     * whose shaft holds it); one in the line itself has the line looked at again, as it may be passed over higher up (at
     * once blocked when its column reaches higher than lines are raised). A
     * block that went away inside the spaces of a line that something keeps closed has the line looked at again: there
     * may be more than one block in the way. (A line that was raised over a block is not brought down when the block
     * goes; it may come down the next time it is looked at.)
     */
    public void blockChanged(ServerLevel level, BlockPos pos, BlockState now) {
        final List<Edge> near = byChunk.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (near == null) return;
        final boolean solid = inTheWay(level, pos, now, false);
        for (final Edge edge : new ArrayList<>(near)) {
            // A look under way that has raised the line looks at places the line does not take up yet: what changes there
            // has the line looked at once more.
            if (edge.queued && edge.scan != null && edge.scan.lift != edge.lift && edge.scan.holds(pos.getX(), pos.getY(), pos.getZ())) edge.again = true;
            // A block taken away in the column of what keeps the line closed (a column too high to pass over may have come lower).
            if (!solid && ((edge.state == State.BLOCKED && edge.barred == null && column(edge.blockedAt, pos)) || column(edge.blockedFromAt, pos))) {
                lookAgain(edge);
                continue;
            }
            if (!edge.needs(pos.getX(), pos.getY(), pos.getZ())) continue;
            if (solid) {
                solidIn(level, edge, pos.immutable());
            } else if (edge.state == State.BLOCKED || edge.blockedFrom != null) {
                lookAgain(edge);
            }
        }
    }

    private static boolean column(@Nullable BlockPos a, BlockPos b) {
        return a != null && a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    // ---- routes -------------------------------------------------------------------------------------------

    /**
     * The shortest way from one point to another over lines that are clear or not verified yet. The list starts with
     * {@code from} and ends with {@code to}; it is empty when there is no way. A way never leads through a landing pad:
     * the space over a pad belongs to the aircraft that go up and down there (proposal of the assistant).
     */
    public List<BlockPos> route(BlockPos from, BlockPos to, ShipClass ship) {
        return route(from, to, ship, List.of());
    }

    /**
     * The same, leaving out some lines (those an aircraft found another ship on: they are not blocked for anybody else).
     * Only lines that this class may fly are used ({@link Edge#opens}); whether it may berth at the far end is the
     * caller's question.
     */
    public List<BlockPos> route(BlockPos from, BlockPos to, ShipClass ship, Collection<Edge> without) {
        return search(from, to, edge -> edge.opens(ship) && !without.contains(edge));
    }

    /**
     * The way there would be if no line were blocked: over every line made for this class (whatever was found on it).
     * For telling why there is no way (2026-10-17: a colony's routes are drawn by players, and when one cannot be flown
     * the reason is shown): the first line on it that this class may not fly is what stands in the way.
     */
    public List<BlockPos> routeIfClear(BlockPos from, BlockPos to, ShipClass ship) {
        return search(from, to, edge -> edge.serves().atLeast(ship));
    }

    /** A single line goes back to "not verified" and is looked at again (a block that kept it closed may be gone). */
    public void recheck(Edge edge) {
        edge.version++;
        set(edge, State.UNVERIFIED, null);
        enqueue(edge);
    }

    private List<BlockPos> search(BlockPos from, BlockPos to, java.util.function.Predicate<Edge> usable) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) return List.of();
        final Map<BlockPos, Double> best = new HashMap<>();
        final Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        record Reached(BlockPos pos, double distance) {}
        final PriorityQueue<Reached> open = new PriorityQueue<>((x, y) -> Double.compare(x.distance(), y.distance()));
        best.put(from, 0.0);
        open.add(new Reached(from, 0.0));
        while (!open.isEmpty()) {
            final Reached here = open.poll();
            if (here.distance() > best.getOrDefault(here.pos(), Double.MAX_VALUE)) continue;
            if (here.pos().equals(to)) break;
            if (!here.pos().equals(from) && nodes.get(here.pos()).kind() == Kind.AIRFIELD) continue;
            for (final Edge edge : around.getOrDefault(here.pos(), List.of())) {
                if (!usable.test(edge)) continue;
                final BlockPos next = edge.other(here.pos());
                final double distance = here.distance() + edge.length();
                if (distance < best.getOrDefault(next, Double.MAX_VALUE)) {
                    best.put(next, distance);
                    cameFrom.put(next, here.pos());
                    open.add(new Reached(next, distance));
                }
            }
        }
        if (!best.containsKey(to)) return List.of();
        final List<BlockPos> way = new ArrayList<>();
        for (BlockPos at = to; at != null; at = cameFrom.get(at)) way.add(at);
        Collections.reverse(way);
        return way;
    }

    /** Length of a route as returned by {@link #route}, in blocks. */
    public double length(List<BlockPos> way) {
        double sum = 0.0;
        for (int i = 1; i < way.size(); i++) {
            final Edge edge = edge(way.get(i - 1), way.get(i));
            if (edge != null) sum += edge.length();
        }
        return sum;
    }

    // ---- saving -------------------------------------------------------------------------------------------

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        final ListTag points = new ListTag();
        for (final Node node : nodes.values()) {
            final CompoundTag point = new CompoundTag();
            point.putLong("pos", node.pos().asLong());
            point.putString("kind", node.kind().name());
            point.putInt("tier", node.tier());
            point.putInt("heading", node.heading());
            if (!node.home().equals(node.pos())) point.putLong("home", node.home().asLong());
            if (node.facility() != null && node.facility() != Facility.SMALL_AIRFIELD) point.putString("facility", node.facility().name());
            points.add(point);
        }
        tag.put("points", points);
        final ListTag lines = new ListTag();
        for (final Edge edge : edges.values()) {
            final CompoundTag line = new CompoundTag();
            line.putLong("a", edge.a.asLong());
            line.putLong("b", edge.b.asLong());
            line.putString("state", edge.state.name());
            if (edge.blockedAt != null) line.putLong("blockedAt", edge.blockedAt.asLong());
            if (edge.blockedFrom != null) line.putString("blockedFrom", edge.blockedFrom.name());
            if (edge.blockedFrom != null && edge.blockedFromAt != null) line.putLong("blockedFromAt", edge.blockedFromAt.asLong());
            if (edge.lift > edge.padLift) line.putInt("lift", edge.lift);
            lines.add(line);
        }
        tag.put("lines", lines);
        final ListTag links = new ListTag();
        for (final Pair pair : linked) {
            final CompoundTag link = new CompoundTag();
            link.putLong("a", pair.a().asLong());
            link.putLong("b", pair.b().asLong());
            links.add(link);
        }
        tag.put("links", links);
        return tag;
    }

    public static RouteNetwork load(CompoundTag tag, HolderLookup.Provider registries) {
        final RouteNetwork network = new RouteNetwork();
        final ListTag points = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < points.size(); i++) {
            final CompoundTag point = points.getCompound(i);
            final BlockPos pos = BlockPos.of(point.getLong("pos"));
            // ("home" is written only where it is not the point itself.)
            final BlockPos home = point.contains("home") ? BlockPos.of(point.getLong("home")) : pos;
            final Kind kind = Kind.valueOf(point.getString("kind"));
            network.nodes.put(pos, new Node(pos, kind, point.getInt("tier"), point.getInt("heading"), home,
                    kind == Kind.AIRFIELD ? Facility.of(point.getString("facility")) : null));
        }
        // Lines are made by the rules of today (the config may have changed); what was known about a line is kept.
        network.relink();
        final ListTag lines = tag.getList("lines", Tag.TAG_COMPOUND);
        for (int i = 0; i < lines.size(); i++) {
            final CompoundTag line = lines.getCompound(i);
            final Edge edge = network.edges.get(Pair.of(BlockPos.of(line.getLong("a")), BlockPos.of(line.getLong("b"))));
            if (edge == null) continue;
            edge.state = State.valueOf(line.getString("state"));
            edge.blockedAt = edge.state == State.BLOCKED && line.contains("blockedAt") ? BlockPos.of(line.getLong("blockedAt")) : null;
            edge.blockedFrom = line.contains("blockedFrom") ? ShipClass.valueOf(line.getString("blockedFrom")) : null;
            edge.blockedFromAt = edge.blockedFrom != null && line.contains("blockedFromAt") ? BlockPos.of(line.getLong("blockedFromAt")) : null;
            // As high as it was found to pass over the blocks on its way (relink() made it as high as the pads ask for).
            if (line.getInt("lift") > edge.lift) network.reshape(edge, line.getInt("lift"));
        }
        // (Which lines cannot pass over a landing pad was worked out by relink(); what was saved does not change that.)
        for (final Edge edge : network.edges.values()) {
            if (edge.barred == null) continue;
            edge.state = State.BLOCKED;
            edge.blockedAt = edge.barred;
        }
        final ListTag links = tag.getList("links", Tag.TAG_COMPOUND);
        for (int i = 0; i < links.size(); i++) network.linked.add(Pair.of(BlockPos.of(links.getCompound(i).getLong("a")), BlockPos.of(links.getCompound(i).getLong("b"))));
        // The scans queued by relink() are dropped; the first tick queues what is still not verified.
        for (final Scan scan : network.queue) scan.edge.queued = false;
        network.queue.clear();
        network.sweepDue = true;
        return network;
    }
}
