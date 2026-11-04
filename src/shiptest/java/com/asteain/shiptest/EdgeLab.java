package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks across ships of several shapes and headings and checks that nobody falls off. Two modes:
 * HELD (zero gravity, the pose is restored every tick; the ship is kept clear of the ground so physics never pushes it, and the
 * push is measured and must stay below 0.005 blocks) and LANDED (real gravity, free ship dropped onto an isolated 3x3 pad, so
 * falling off means falling into the void; the pose is not held).
 */
final class EdgeLab {
    private EdgeLab() {}

    /** A deck shape: cells (x, z) with x in 13..17 and z in -2..2 relative to the site. */
    record Shape(String name, int[][] cells, List<Walk> walks) {
        boolean has(int x, int z) { for (int[] c : cells) if (c[0] == x && c[1] == z) return true; return false; }
    }

    /** A walk from one deck cell to another; tip = arrival is only recorded (corner tips and walks along an edge may be unreachable by design); a fall always fails. */
    record Walk(String label, int sx, int sz, int dx, int dz, boolean tip) {}

    private static int[][] box(int x0, int x1, int z0, int z1, int... skipXZ) {
        List<int[]> l = new ArrayList<>();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            boolean skip = false;
            for (int i = 0; i + 1 < skipXZ.length; i += 2) if (skipXZ[i] == x && skipXZ[i + 1] == z) skip = true;
            if (!skip) l.add(new int[]{x, z});
        }
        return l.toArray(new int[0][]);
    }

    static final Map<String, Shape> SHAPES = new HashMap<>();
    static {
        SHAPES.put("s5", new Shape("5x5 deck", box(13, 17, -2, 2), List.of(
                new Walk("across to the far edge", 13, 0, 17, 0, false),
                new Walk("to a corner", 13, 0, 17, -2, true),
                new Walk("along the south edge", 13, -2, 17, -2, true),
                new Walk("along the east edge", 17, -2, 17, 2, true),
                new Walk("along the west edge", 13, 2, 13, -2, true))));
        SHAPES.put("s2", new Shape("2-wide deck", box(13, 17, 0, 1), List.of(
                new Walk("along the strip", 13, 0, 17, 0, false),
                new Walk("along the other side of the strip", 13, 1, 17, 1, true),
                new Walk("back across the end", 17, 0, 13, 1, false))));
        SHAPES.put("s1", new Shape("1-wide plank", box(13, 17, 0, 0), List.of(
                new Walk("along the plank", 13, 0, 17, 0, true),
                new Walk("back along the plank", 17, 0, 13, 0, true))));
        SHAPES.put("l", new Shape("L-shaped deck", concat(box(13, 17, -2, -1), box(13, 14, 0, 2)), List.of(
                new Walk("along the long bar", 17, -1, 13, -1, false),
                new Walk("down the short bar to its end", 13, -1, 13, 2, false),
                new Walk("across the short bar", 14, -2, 14, 2, false))));
        SHAPES.put("ring", new Shape("deck with a hole in the middle", box(13, 17, -2, 2, 15, 0), List.of(
                new Walk("round the hole", 13, 0, 17, 0, false),
                new Walk("corner to corner", 13, -2, 17, 2, true),
                new Walk("other diagonal", 17, -2, 13, 2, true))));
    }

    private static int[][] concat(int[][] a, int[][] b) {
        int[][] r = new int[a.length + b.length][];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static Vec3 world(Object ship, Vec3 plot) {
        try { return Ships.toWorld(ship, plot); } catch (Exception e) { throw new AssertionError(e); }
    }

    static void add(Script s, Rig rig, BlockPos site, double yaw, Shape shape, boolean landed, boolean strict) {
        ServerLevel level = rig.level;
        String name = (landed ? "landed " : "held ") + shape.name() + " yaw " + (int) yaw;
        Object[] ship = new Object[1];
        Map<Long, Vec3> plotOf = new HashMap<>();                  // cell -> plot centre, captured BEFORE the ship is rotated
        Quaterniond[] decided = new Quaterniond[1];
        double[] maxPush = new double[1], maxTurn = new double[1];
        double[] topY = new double[1];
        BlockPos b0 = landed ? site.offset(40, 0, 0) : site;       // landed: far from the ground platform, so the only support is the pad
        s.step(name + ": build", () -> {
            Colony.platform(level, site, 12, 8);
            Rig.force(level, b0.offset(15, 0, 0), 12, 10);
            List<BlockPos> b = new ArrayList<>();
            for (int[] c : shape.cells()) {
                BlockPos p = b0.offset(c[0], landed ? 1 : -1, c[1]);
                level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
                b.add(p);
            }
            if (landed) {                                           // isolated 3x3 pad under the middle of the deck: edges hang over the void
                for (int x = 14; x <= 16; x++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(b0.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
            ship[0] = rig.assemble(b);
            for (int[] c : shape.cells()) {
                BlockPos orig = b0.offset(c[0], landed ? 1 : -1, c[1]);
                plotOf.put(key(c[0], c[1]), Vec3.atCenterOf(BlockPos.containing(Ships.toPlot(ship[0], Vec3.atCenterOf(orig)))));
            }
            Vec3 pos = Ships.position(ship[0]);
            decided[0] = Ships.orientation(ship[0]).rotateY(Math.toRadians(yaw));
            // held: rotate well clear of the ground. landed: rotate in place (nothing to dig into, the pad is a block lower)
            Ships.teleport(ship[0], new Vector3d(pos.x, pos.y + (landed ? 0 : 8), pos.z), decided[0]);
        });
        s.settle(name + ": rotate", 3);
        if (!landed) {
            s.step(name + ": move beside the ground edge (clear of it) and hold the decided pose", () -> {
                double minX = 1e18;
                for (Vec3 pc : plotOf.values())
                    for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) minX = Math.min(minX, world(ship[0], pc.add(0.5 * sx, 0, 0.5 * sz)).x);
                Vec3 pos = Ships.position(ship[0]);
                double gap = Double.parseDouble(System.getProperty("shiptest.edgeGap", "0.6"));   // negative: dig into the ground (the old, wrong placement)
                Vector3d hp = new Vector3d(pos.x + (site.getX() + 13.0 + gap - minX), pos.y - 8, pos.z);
                Quaterniond hq = new Quaterniond(decided[0]);
                Ships.teleport(ship[0], hp, hq);
                s.note(String.format("OBS %s: the deck corner nearest the ground is placed %.2f blocks from the ground edge line (positive = clear, negative = digging in)", name, gap));
                s.monitor(() -> {
                    try {
                        Vec3 now = Ships.position(ship[0]);
                        maxPush[0] = Math.max(maxPush[0], now.distanceTo(new Vec3(hp.x, hp.y, hp.z)));
                        maxTurn[0] = Math.max(maxTurn[0], PoseRecorder.angleBetween(Ships.orientation(ship[0]), hq));
                        Ships.teleport(ship[0], hp, hq);
                        Ships.zeroVelocity(ship[0]);
                    } catch (Exception e) { throw new AssertionError(e); }
                });
            });
            s.settle(name + ": settle", 20);
        } else {
            s.settle(name + ": land and settle", 220);
        }
        s.step(name + ": measured pose", () -> {
            Quaterniond q = Ships.orientation(ship[0]);
            double tilt = PoseRecorder.tilt(q);
            Vec3 any = world(ship[0], plotOf.values().iterator().next());
            topY[0] = any.y + 0.5;
            s.note(String.format("OBS %s: yaw deviation %.2f deg, TILT %.3f deg, deck top y=%.3f, pose %s, speed %.5f", name, PoseRecorder.yawDev(q), tilt, topY[0], Ships.position(ship[0]), Ships.velocity(ship[0]).length()));
            s.soft(name + ": the ship is level (tilt below 1 degree)", tilt < 1.0, String.format("tilt=%.3f", tilt));
        });
        for (Walk w : shape.walks()) {
            String label = name + " / " + w.label() + " (" + w.sx() + "," + w.sz() + ")->(" + w.dx() + "," + w.dz() + ")";
            rig.walkLive2(s, label,
                    () -> { Vec3 c = world(ship[0], plotOf.get(key(w.sx(), w.sz()))); return new Vec3(c.x, topY[0] + 0.05, c.z); },
                    () -> { Vec3 c = world(ship[0], plotOf.get(key(w.dx(), w.dz()))); return BlockPos.containing(c.x, topY[0] + 0.1, c.z); },
                    true, 300, t -> {},
                    walk -> {
                        boolean safe = walk.minY > topY[0] - 0.6 && walk.mob.isAlive() && walk.teleportTick < 0;
                        String detail = walk.describe() + String.format(" deckTop=%.2f", topY[0]);
                        if (!safe) detail += " TRACE " + walk.trace;
                        s.soft(label + ": never falls, steps off or teleports", safe, detail);
                        if (w.tip()) s.note("REACH " + label + ": arrived=" + walk.arrived + " in " + walk.ticks + " ticks, pathLen=" + walk.maxPathLen + ", distToDest=" + String.format("%.2f", walk.mob.position().distanceTo(Vec3.atBottomCenterOf(walk.dest))));
                        else s.soft(label + ": arrives by walking along a path", walk.walkedForReal(), "arrived=" + walk.arrived + " in " + walk.ticks + " ticks, pathLen=" + walk.maxPathLen);
                    });
        }
        s.step(name + ": hold check and remove", () -> {
            s.clearMonitors();
            if (!landed) {
                s.note(String.format("OBS %s: the held ship was pushed by physics at most %.5f blocks and %.4f deg per tick", name, maxPush[0], maxTurn[0]));
                s.soft(name + ": hold guard (the held ship was never pushed more than 0.005 blocks, so the carrying of passengers is not distorted)", maxPush[0] <= 0.005, String.format("maxPush=%.5f", maxPush[0]));
            }
            rig.removeShipSafely(ship[0], site.offset(-8, 0, 0));
        });
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
