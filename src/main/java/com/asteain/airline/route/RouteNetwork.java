package com.asteain.airline.route;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.AirLinks;
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
        /** A point that stands where it is: a buoy, or a landing pad of a small airfield whose hut is not told (older saves; tests). */
        public Node(BlockPos pos, Kind kind, int tier, int heading) {
            this(pos, kind, tier, heading, pos);
        }

        public Node(BlockPos pos, Kind kind, int tier, int heading, BlockPos home) {
            this(pos, kind, tier, heading, home, kind == Kind.AIRFIELD ? Facility.SMALL_AIRFIELD : null);
        }

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
        /** Where the block is that keeps that class out (the first one the scan met; not saved). */
        @Nullable private BlockPos blockedFromAt;
        /** The smallest class for which the line passes too low over a pad (as {@link #barred} is for the small one). Not saved. */
        @Nullable private ShipClass barredFrom;
        /** Goes up whenever a scan that is under way has become worthless. */
        private int version;
        private boolean queued;
        /** The last scan could not see all of the line (chunks that were not loaded). */
        private boolean gaps;
        /**
         * The landing pad (not one of the line's own ends) this line passes too low over ({@link RouteRules#grazes}).
         * Such a line counts as blocked by that pad whatever a scan finds. Not saved: worked out from the points.
         */
        @Nullable private BlockPos barred;
        /**
         * The largest class of aircraft this line is for: the smaller of what its two ends are made for (a line to a small
         * airfield's pad is only ever flown by small aircraft; between buoys, any). A line that is not blocked is open to
         * the classes up to this one. Not saved: worked out from the points.
         */
        private ShipClass serves = ShipClass.SMALL;

        private Edge(BlockPos a, BlockPos b) {
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

        public State state() { return state; }
        public ShipClass serves() { return serves; }
        /** The smallest class kept off this line although the small one may fly it (something in the way, or too low over a pad); null for none. */
        @Nullable public ShipClass closedFrom() { return blockedFrom == null ? barredFrom : barredFrom == null ? blockedFrom : ShipClass.smaller(blockedFrom, barredFrom); }
        /** Whether an aircraft of this class may fly this line (not blocked, made for that class, nothing in that class's way). */
        public boolean opens(ShipClass ship) {
            final ShipClass closed = closedFrom();
            return state != State.BLOCKED && serves.atLeast(ship) && (closed == null || ship.ordinal() < closed.ordinal());
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
     */
    private static final class Scan {
        final Edge edge;
        final int version;
        final List<Tube> spaces;
        Tube tube;
        long[] chunks;
        /** Which space, which of its chunks, and which of that chunk's 256 columns comes next. */
        int part, chunk, column;
        boolean gaps, done;
        @Nullable BlockPos found;
        /** The smallest class kept out by what was found that does not keep out the small one, and where that was. */
        @Nullable ShipClass blockedFrom;
        @Nullable BlockPos blockedFromAt;

        Scan(Edge edge) {
            this.edge = edge;
            this.version = edge.version;
            this.spaces = edge.spaces;
            this.tube = spaces.get(0);
            this.chunks = tube.chunks();
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
    /**
     * What "clear" means has changed over the versions (2: the shaft over a landing pad belongs to its lines, and the
     * lines of a pad start higher; 3: lines are verified for the class they are for, and pass higher over the points made
     * for larger classes). A network saved by older rules is looked at again from scratch once it is loaded.
     */
    private static final int RULES = 3;
    private boolean recheckDue;
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
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(RouteNetwork::new, RouteNetwork::load), FILE);
    }

    /** The network of this dimension if there is one already (nothing is created). */
    @Nullable
    public static RouteNetwork find(ServerLevel level) {
        return level.getDataStorage().get(new SavedData.Factory<>(RouteNetwork::new, RouteNetwork::load), FILE);
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

    public List<Edge> edgesAt(BlockPos pos) {
        return Collections.unmodifiableList(around.getOrDefault(pos, List.of()));
    }

    /** How many verifications are waiting or under way. */
    public int pending() {
        return queue.size();
    }

    // ---- points -------------------------------------------------------------------------------------------

    public void addBuoy(BlockPos pos, int tier) {
        put(new Node(pos.immutable(), Kind.BUOY, tier, 0));
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
        final ShipClass serves = a == null || b == null ? ShipClass.SMALL : ShipClass.smaller(a.largest(), b.largest());
        final Vec3 from = waypoint(edge.a), to = waypoint(edge.b);
        final List<List<Tube>> byClass = new ArrayList<>();
        final StringBuilder sign = new StringBuilder();
        for (final ShipClass ship : ShipClass.values()) {
            if (!serves.atLeast(ship)) break;
            final List<Tube> spaces = new ArrayList<>();
            spaces.add(new Tube(from, to, RouteRules.tubeRadius(ship), RouteRules.tubeHalfHeight(ship)));
            if (a != null && a.kind() == Kind.AIRFIELD) spaces.add(RouteRules.shaft(edge.a, a.largest(), ship));
            if (b != null && b.kind() == Kind.AIRFIELD) spaces.add(RouteRules.shaft(edge.b, b.largest(), ship));
            byClass.add(spaces);
        }
        sign.append(serves).append(from).append(to);
        final String before = edge.tube == null ? "" : String.valueOf(edge.serves) + edge.tube.from + edge.tube.to;
        edge.serves = serves;
        edge.byClass = byClass;
        edge.tube = byClass.get(0).get(0);
        edge.spaces = byClass.get(byClass.size() - 1);
        return !sign.toString().equals(before);
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
            if (node.kind() == Kind.AIRFIELD) rebar();
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
     * The landing pad (not one of the line's own ends) that a line passes too low over for small aircraft, if there is
     * one; and the smallest class for which it passes too low over any pad (an aircraft of the pad's largest class may
     * stand there) into {@link Edge#barredFrom}.
     */
    @Nullable
    private BlockPos bar(Edge edge) {
        BlockPos small = null;
        ShipClass from = null;
        for (final Node node : nodes.values()) {
            if (node.kind() != Kind.AIRFIELD || node.pos().equals(edge.a) || node.pos().equals(edge.b)) continue;
            for (int c = 0; c < edge.byClass.size(); c++) {
                final ShipClass ship = ShipClass.values()[c];
                if (!RouteRules.grazes(edge.tube, node.pos(), node.largest(), ship)) continue;
                if (c == 0 && small == null) small = node.pos();
                if (from == null || c < from.ordinal()) from = ship;
                break;
            }
        }
        edge.barredFrom = from;
        return small;
    }

    /** Landing pads came or went: which lines pass too low over a pad is worked out again. */
    private void rebar() {
        for (final Edge edge : edges.values()) {
            final ShipClass fromBefore = edge.barredFrom;
            final BlockPos now = bar(edge);
            if (fromBefore != edge.barredFrom) linksDue = true;
            if (java.util.Objects.equals(now, edge.barred)) continue;
            edge.barred = now;
            edge.version++;
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
        edge = new Edge(pair.a(), pair.b());
        shape(edge);
        edges.put(pair, edge);
        around.computeIfAbsent(edge.a, p -> new ArrayList<>()).add(edge);
        around.computeIfAbsent(edge.b, p -> new ArrayList<>()).add(edge);
        lampsDue.add(edge.a);
        lampsDue.add(edge.b);
        index(edge, true);
        edge.barred = bar(edge);
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
        queue.add(new Scan(edge));
    }

    /** A line was flown from end to end without meeting anything: it is clear, even if it could not be scanned before. */
    public void confirmClear(Edge edge) {
        if (edge.state != State.UNVERIFIED) return;
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
     * An aircraft of class {@code ship} met something solid on a line: it blocks the line for the smallest class whose
     * spaces hold it (for the aircraft's own class when none does: it was seen with the aircraft's own tube).
     */
    public void reportBlocked(Edge edge, BlockPos at, ShipClass ship) {
        final ShipClass from = edge.keepsOut(at.getX(), at.getY(), at.getZ());
        keepOut(edge, from == null ? ship : from, at);
    }

    /** Something solid keeps the classes from {@code from} up off a line (all of them: the line is blocked). */
    private void keepOut(Edge edge, ShipClass from, BlockPos at) {
        if (from == ShipClass.SMALL) {
            if (edge.state != State.BLOCKED) reportBlocked(edge, at);
            return;
        }
        if (edge.blockedFrom != null && edge.blockedFrom.ordinal() <= from.ordinal()) return;
        edge.blockedFrom = from;
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
            index(edge, true);
            edge.version++;
            edge.gaps = false;
            edge.blockedFrom = null;
            edge.barred = bar(edge);
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

    /** In the way of a line: solid, and not part of an aircraft that is being built on a landing pad. */
    private boolean inTheWay(ServerLevel level, BlockPos pos, BlockState state) {
        if (!solid(level, pos, state)) return false;
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
        if (recheckDue) {
            recheckDue = false;
            recheckAll();
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
            if (edge.blockedFrom != scan.blockedFrom) {
                edge.blockedFrom = scan.blockedFrom;
                linksDue = true;
            }
            edge.blockedFromAt = scan.blockedFromAt;
            if (scan.found != null) set(edge, State.BLOCKED, scan.found);
            else if (scan.gaps) set(edge, State.UNVERIFIED, null);
            else set(edge, State.CLEAR, null);
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

    /** Looks at as many columns as the budget allows. Only chunks that are loaded right now are read. */
    private int step(ServerLevel level, Scan scan, int budget) {
        final int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0) {
            if (scan.chunk >= scan.chunks.length) {
                if (scan.next()) continue;
                scan.done = true;
                break;
            }
            final long key = scan.chunks[scan.chunk];
            final int chunkX = ChunkPos.getX(key), chunkZ = ChunkPos.getZ(key);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                // Every chunk in the list holds a column of the space, so this part of the line was not seen.
                budget--;
                scan.gaps = true;
                scan.chunk++;
                scan.column = 0;
                continue;
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
                    final BlockState state = chunk.getBlockState(pos.set(x, y, z));
                    if (inTheWay(level, pos, state)) {
                        final ShipClass kept = scan.edge.keepsOut(x, y, z);
                        if (kept == null || kept == ShipClass.SMALL) {
                            scan.found = pos.immutable();
                            scan.done = true;
                            return budget;
                        }
                        // Only the larger classes are kept out: noted, and the scan goes on (the small class may still meet something).
                        if (scan.blockedFrom == null || kept.ordinal() < scan.blockedFrom.ordinal()) {
                            scan.blockedFrom = kept;
                            scan.blockedFromAt = pos.immutable();
                        }
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
     * A block changed. A solid block inside a line's tube blocks that line at once. A block that went away inside the
     * tube of a blocked line sends the line back to "not verified": there may be more than one block in the way.
     */
    public void blockChanged(ServerLevel level, BlockPos pos, BlockState now) {
        final List<Edge> near = byChunk.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (near == null) return;
        final boolean solid = inTheWay(level, pos, now);
        for (final Edge edge : near) {
            if (!edge.needs(pos.getX(), pos.getY(), pos.getZ())) continue;
            if (solid) {
                final ShipClass from = edge.keepsOut(pos.getX(), pos.getY(), pos.getZ());
                keepOut(edge, from == null ? ShipClass.SMALL : from, pos);
            } else if (edge.state == State.BLOCKED || edge.blockedFrom != null) {
                edge.version++;
                set(edge, State.UNVERIFIED, null);
                enqueue(edge);
            }
        }
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
        tag.putInt("rules", RULES);
        return tag;
    }

    public static RouteNetwork load(CompoundTag tag, HolderLookup.Provider registries) {
        final RouteNetwork network = new RouteNetwork();
        final ListTag points = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < points.size(); i++) {
            final CompoundTag point = points.getCompound(i);
            try {
                final BlockPos pos = BlockPos.of(point.getLong("pos"));
                // ("home" is written only where it is not the point itself.)
                final BlockPos home = point.contains("home") ? BlockPos.of(point.getLong("home")) : pos;
                final Kind kind = Kind.valueOf(point.getString("kind"));
                network.nodes.put(pos, new Node(pos, kind, point.getInt("tier"), point.getInt("heading"), home,
                        kind == Kind.AIRFIELD ? Facility.of(point.getString("facility")) : null));
            } catch (IllegalArgumentException unknownKind) {
                // A kind this version does not know: the point is left out.
            }
        }
        // Lines are made by the rules of today (the config may have changed); what was known about a line is kept.
        network.relink();
        final ListTag lines = tag.getList("lines", Tag.TAG_COMPOUND);
        for (int i = 0; i < lines.size(); i++) {
            final CompoundTag line = lines.getCompound(i);
            final Edge edge = network.edges.get(Pair.of(BlockPos.of(line.getLong("a")), BlockPos.of(line.getLong("b"))));
            if (edge == null) continue;
            try {
                edge.state = State.valueOf(line.getString("state"));
            } catch (IllegalArgumentException unknownState) {
                edge.state = State.UNVERIFIED;
            }
            edge.blockedAt = edge.state == State.BLOCKED && line.contains("blockedAt") ? BlockPos.of(line.getLong("blockedAt")) : null;
            edge.blockedFrom = line.contains("blockedFrom") ? ShipClass.of(line.getString("blockedFrom").toLowerCase(java.util.Locale.ROOT)) : null;
            if (edge.blockedFrom == ShipClass.SMALL) edge.blockedFrom = null;
        }
        // (Which lines pass too low over a landing pad was worked out by relink(); what was saved does not change that.)
        for (final Edge edge : network.edges.values()) {
            if (edge.barred == null) continue;
            edge.state = State.BLOCKED;
            edge.blockedAt = edge.barred;
        }
        network.recheckDue = tag.getInt("rules") < RULES;
        final ListTag links = tag.getList("links", Tag.TAG_COMPOUND);
        for (int i = 0; i < links.size(); i++) network.linked.add(Pair.of(BlockPos.of(links.getCompound(i).getLong("a")), BlockPos.of(links.getCompound(i).getLong("b"))));
        // The scans queued by relink() are dropped; the first tick queues what is still not verified.
        for (final Scan scan : network.queue) scan.edge.queued = false;
        network.queue.clear();
        network.sweepDue = true;
        return network;
    }
}
