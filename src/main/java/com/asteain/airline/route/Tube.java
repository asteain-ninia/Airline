package com.asteain.airline.route;

import net.minecraft.world.phys.Vec3;

/**
 * The space an aircraft needs around one line of the network: an upright cylinder (flat radius, half height) swept
 * along the line from one passing point to the other. This is the only place that decides whether a block position
 * lies in that space; the verification scan and the re-check after a block change both ask here.
 *
 * <p>A block counts as inside when its centre is within the radius plus half a block of the line (flat distance) and
 * within the half height plus half a block of the line's height at the nearest place.</p>
 */
public final class Tube {
    public final Vec3 from, to;
    public final double radius, halfHeight;
    private final double lx, lz, flatLengthSquared;

    public Tube(Vec3 from, Vec3 to, double radius, double halfHeight) {
        this.from = from;
        this.to = to;
        this.radius = radius;
        this.halfHeight = halfHeight;
        this.lx = to.x - from.x;
        this.lz = to.z - from.z;
        this.flatLengthSquared = lx * lx + lz * lz;
    }

    public double length() {
        return from.distanceTo(to);
    }

    /** How far along the line (0 to 1) the place nearest to the column x, z lies, seen from above. */
    private double along(double x, double z) {
        if (flatLengthSquared < 1.0e-6) return 0.0;
        final double t = ((x - from.x) * lx + (z - from.z) * lz) / flatLengthSquared;
        return t < 0.0 ? 0.0 : Math.min(t, 1.0);
    }

    /** How far along the line (0 = start, 1 = end) the place nearest to x, z lies, seen from above. */
    public double progress(double x, double z) {
        return along(x, z);
    }

    /** Flat distance from the place x, z to the line. */
    public double flatDistance(double x, double z) {
        final double t = along(x, z);
        return Math.hypot(x - (from.x + lx * t), z - (from.z + lz * t));
    }

    /**
     * The lowest height of the line itself while it is within {@code reach} of the place x, z (seen from above);
     * positive infinity when it never comes that near.
     */
    public double lowestNear(double x, double z, double reach) {
        if (flatLengthSquared < 1.0e-6) return Math.hypot(x - from.x, z - from.z) <= reach ? Math.min(from.y, to.y) : Double.POSITIVE_INFINITY;
        final double length = Math.sqrt(flatLengthSquared);
        // Along the line in blocks (not cut off at its ends), and how far to the side of it the place lies.
        final double along = ((x - from.x) * lx + (z - from.z) * lz) / length;
        final double across = Math.hypot(x - (from.x + lx * along / length), z - (from.z + lz * along / length));
        if (across > reach) return Double.POSITIVE_INFINITY;
        final double half = Math.sqrt(reach * reach - across * across);
        final double first = Math.max(0.0, along - half), last = Math.min(length, along + half);
        if (first > last) return Double.POSITIVE_INFINITY;
        return Math.min(from.y + (to.y - from.y) * first / length, from.y + (to.y - from.y) * last / length);
    }

    /** Whether the column of blocks at x, z (block coordinates) touches the tube at all. */
    public boolean touchesColumn(int x, int z) {
        final double cx = x + 0.5, cz = z + 0.5, t = along(cx, cz);
        final double dx = cx - (from.x + lx * t), dz = cz - (from.z + lz * t);
        final double reach = radius + 0.5;
        return dx * dx + dz * dz <= reach * reach;
    }

    /** Lowest and highest height (world y, not rounded) the tube takes up in the column x, z. Only for columns that touch it. */
    public double low(int x, int z) {
        return heightRange(x, z, true);
    }

    public double high(int x, int z) {
        return heightRange(x, z, false);
    }

    private double heightRange(int x, int z, boolean low) {
        if (flatLengthSquared < 1.0e-6) {
            // One point straight above the other: the tube is one upright cylinder over the whole height.
            return low ? Math.min(from.y, to.y) - halfHeight - 0.5 : Math.max(from.y, to.y) + halfHeight + 0.5;
        }
        final double y = from.y + (to.y - from.y) * along(x + 0.5, z + 0.5);
        return low ? y - halfHeight - 0.5 : y + halfHeight + 0.5;
    }

    /** Whether the block at x, y, z lies in the tube. */
    public boolean contains(int x, int y, int z) {
        if (!touchesColumn(x, z)) return false;
        final double centre = y + 0.5;
        return centre >= low(x, z) && centre <= high(x, z);
    }

    private long[] chunks;

    /**
     * The chunks that hold at least one column of this tube, in order from one end of the line to the other. They are
     * found by walking along the line, so a line of several thousand blocks costs what its own length is worth and not
     * what the square around it would (a direct line between two airfields can cross many large maps).
     */
    public long[] chunks() {
        if (chunks != null) return chunks;
        final java.util.LinkedHashSet<Long> found = new java.util.LinkedHashSet<>();
        // A chunk can only hold a column of the tube when its middle is this near the line (11.4: its own half diagonal).
        final double near = radius + 0.5 + 11.4;
        final int span = (int) Math.ceil((near + 4.0) / 16.0) + 1;
        final int steps = (int) Math.ceil(Math.sqrt(flatLengthSquared) / 8.0);
        for (int i = 0; i <= steps; i++) {
            final double t = steps == 0 ? 0.0 : (double) i / steps;
            final int cx = (int) Math.floor(from.x + lx * t) >> 4, cz = (int) Math.floor(from.z + lz * t) >> 4;
            for (int x = cx - span; x <= cx + span; x++) {
                for (int z = cz - span; z <= cz + span; z++) {
                    final long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
                    if (!found.contains(key) && flatDistance((x << 4) + 8.0, (z << 4) + 8.0) <= near && touchesChunk(x, z)) found.add(key);
                }
            }
        }
        final long[] all = new long[found.size()];
        int n = 0;
        for (final long key : found) all[n++] = key;
        return chunks = all;
    }

    private boolean touchesChunk(int chunkX, int chunkZ) {
        for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
            for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) if (touchesColumn(x, z)) return true;
        }
        return false;
    }

    public int minX() { return (int) Math.floor(Math.min(from.x, to.x) - radius - 0.5); }
    public int maxX() { return (int) Math.floor(Math.max(from.x, to.x) + radius + 0.5); }
    public int minZ() { return (int) Math.floor(Math.min(from.z, to.z) - radius - 0.5); }
    public int maxZ() { return (int) Math.floor(Math.max(from.z, to.z) + radius + 0.5); }
}
