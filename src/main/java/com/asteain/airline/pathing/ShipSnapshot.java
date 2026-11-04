package com.asteain.airline.pathing;

import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Immutable copy of one stopped ship, made on the server thread and read by the MineColonies path finding thread.
 * Holds a private pose copy, the block copy and the world-space cell box the ship can touch. Nothing in here is
 * ever written after construction, so it can be shared between threads without locks.
 */
public final class ShipSnapshot {
    public final UUID id;
    /** Private copy of the ship pose at snapshot time. Never handed out, never mutated. */
    private final Pose3d pose;
    final ShipBlocks blocks;
    /** World-space cell bounds (inclusive, padded) outside of which this ship cannot provide a block. */
    private final int worldMinX, worldMinY, worldMinZ, worldMaxX, worldMaxY, worldMaxZ;

    ShipSnapshot(UUID id, Pose3d poseCopy, ShipBlocks blocks,
                 int worldMinX, int worldMinY, int worldMinZ, int worldMaxX, int worldMaxY, int worldMaxZ) {
        this.id = id;
        this.pose = poseCopy;
        this.blocks = blocks;
        this.worldMinX = worldMinX;
        this.worldMinY = worldMinY;
        this.worldMinZ = worldMinZ;
        this.worldMaxX = worldMaxX;
        this.worldMaxY = worldMaxY;
        this.worldMaxZ = worldMaxZ;
    }

    Pose3d pose() {
        return pose;
    }

    boolean touchesColumns(int minX, int minZ, int maxX, int maxZ) {
        return worldMaxX >= minX && worldMinX <= maxX && worldMaxZ >= minZ && worldMinZ <= maxZ;
    }

    /**
     * The ship block nearest to the centre of the world cell, or null when the ship has no block there.
     * Uses only the pose's own inverse transform (no hand-made matrix).
     */
    public BlockState stateAt(int x, int y, int z) {
        if (x < worldMinX || x > worldMaxX || y < worldMinY || y > worldMaxY || z < worldMinZ || z > worldMaxZ) return null;
        Vector3d p = new Vector3d(x + 0.5, y + 0.5, z + 0.5);
        pose.transformPositionInverse(p);
        return blocks.get(Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z));
    }

    /**
     * World height of the real top face of the ship block shown at this world cell, or NaN when the ship has no block
     * there. A path only knows the top of the world cell; the block itself sits wherever the ship's pose puts it, up to
     * half a block higher or lower.
     */
    public double surfaceY(int x, int y, int z) {
        return surfaceAt(x + 0.5, y + 0.5, z + 0.5);
    }

    /**
     * World height of the real top face of the ship block that contains this exact world point, or NaN when the ship
     * has no block there. Unlike {@link #stateAt} this asks about a point, not about the block shown in a world cell.
     */
    public double surfaceAt(double wx, double wy, double wz) {
        final int x = Mth.floor(wx), y = Mth.floor(wy), z = Mth.floor(wz);
        if (x < worldMinX || x > worldMaxX || y < worldMinY || y > worldMaxY || z < worldMinZ || z > worldMaxZ) return Double.NaN;
        final Vector3d p = new Vector3d(wx, wy, wz);
        pose.transformPositionInverse(p);
        final int blockY = Mth.floor(p.y);
        final BlockState state = blocks.get(Mth.floor(p.x), blockY, Mth.floor(p.z));
        if (state == null) return Double.NaN;
        p.y = blockY + topOf(state);
        pose.transformPosition(p);
        return p.y;
    }

    /** Height of the block's collision shape inside its own cell (1 for a full block, 0.5 for a lower slab). */
    private static double topOf(BlockState state) {
        try {
            final VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        } catch (RuntimeException e) {
            return 1.0;
        }
    }
}
