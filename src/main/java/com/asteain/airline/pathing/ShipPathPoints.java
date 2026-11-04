package com.asteain.airline.pathing;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import org.joml.Vector3d;

/**
 * The points of one path that rest on one ship: where the path has them in the world, and the same points in the
 * ship's own coordinates as the ship stood when the path was searched. With these, "how far has the deck under the
 * path moved away from the path" can be answered exactly for any later pose of the ship.
 *
 * A turn of a few degrees moves the far end of a long ship by most of a block while its middle hardly moves, so the
 * movement of the ship as a whole says nothing about one path; the points of the path do.
 *
 * Server thread only. Never changed after construction.
 */
public final class ShipPathPoints {
    public final ShipSnapshot ship;
    /** Path node index of each point, ascending. */
    private final int[] nodes;
    /** x, y, z of each point as the path has it. */
    private final double[] world;
    /** The same points in the ship's coordinates. */
    private final double[] local;

    ShipPathPoints(ShipSnapshot ship, int[] nodes, double[] world) {
        this.ship = ship;
        this.nodes = nodes;
        this.world = world;
        this.local = new double[world.length];
        final Vector3d p = new Vector3d();
        for (int i = 0; i < nodes.length; i++) {
            p.set(world[3 * i], world[3 * i + 1], world[3 * i + 2]);
            ship.pose().transformPositionInverse(p);
            local[3 * i] = p.x;
            local[3 * i + 1] = p.y;
            local[3 * i + 2] = p.z;
        }
    }

    /**
     * How far (blocks) the deck under the path has moved since the path was searched, at the point where it moved most.
     * Only the points from path node {@code fromNode} on count: what lies behind the walker no longer matters.
     * A rigid movement moves every point between two points by no more than it moves one of the two, so the nodes
     * are enough; the stretches between them need no check of their own.
     */
    double largestMove(Pose3dc now, int fromNode, Vector3d scratch) {
        double largest = 0.0;
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i] < fromNode) continue;
            scratch.set(local[3 * i], local[3 * i + 1], local[3 * i + 2]);
            now.transformPosition(scratch);
            final double dx = scratch.x - world[3 * i], dy = scratch.y - world[3 * i + 1], dz = scratch.z - world[3 * i + 2];
            final double moved = dx * dx + dy * dy + dz * dz;
            if (moved > largest) largest = moved;
        }
        return Math.sqrt(largest);
    }
}
