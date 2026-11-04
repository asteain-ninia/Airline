package com.asteain.airline.pathing;

import com.ldtteam.domumornamentum.block.decorative.PanelBlock;
import com.minecolonies.api.items.ModTags;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Immutable copy of a ship's blocks (plot coordinates), prepared for path finding:
 * block states are turned by the ship's nearest quarter turn, and blocks a citizen cannot operate on a ship are replaced.
 */
final class ShipBlocks {
    /** Cannot be walked through and cannot be stood on (MineColonies treats fences that way). */
    private static final BlockState BLOCKED = Blocks.OAK_FENCE.defaultBlockState();
    /** Cannot be walked through, can be stood on. */
    private static final BlockState SOLID = Blocks.OAK_PLANKS.defaultBlockState();

    final int minX, minY, minZ, sizeX, sizeY, sizeZ;
    /** null = air. Index (y * sizeZ + z) * sizeX + x. Never written after construction. */
    private final BlockState[] states;
    final Rotation rotation;
    final boolean aligned;
    final long capturedAt;

    private ShipBlocks(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, BlockState[] states, Rotation rotation, boolean aligned, long capturedAt) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.states = states;
        this.rotation = rotation;
        this.aligned = aligned;
        this.capturedAt = capturedAt;
    }

    BlockState get(int plotX, int plotY, int plotZ) {
        final int x = plotX - minX, y = plotY - minY, z = plotZ - minZ;
        if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) return null;
        return states[(y * sizeZ + z) * sizeX + x];
    }

    boolean sameBounds(BoundingBox3ic b) {
        return b.minX() == minX && b.minY() == minY && b.minZ() == minZ
                && b.maxX() - b.minX() + 1 == sizeX && b.maxY() - b.minY() + 1 == sizeY && b.maxZ() - b.minZ() + 1 == sizeZ;
    }

    static long volume(BoundingBox3ic b) {
        final long x = (long) b.maxX() - b.minX() + 1, y = (long) b.maxY() - b.minY() + 1, z = (long) b.maxZ() - b.minZ() + 1;
        return x <= 0 || y <= 0 || z <= 0 ? 0 : x * y * z;
    }

    /** Server thread only: reads the ship's plot chunks through Sable's container (never loads or generates a chunk). */
    static ShipBlocks capture(SubLevelContainer container, BoundingBox3ic b, Rotation rotation, boolean aligned, long now) {
        final int sizeX = b.maxX() - b.minX() + 1, sizeY = b.maxY() - b.minY() + 1, sizeZ = b.maxZ() - b.minZ() + 1;
        final BlockState[] states = new BlockState[sizeX * sizeY * sizeZ];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
            for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                final LevelChunk chunk = container.getChunk(new ChunkPos(cx, cz));
                if (chunk == null) continue;
                final int x0 = Math.max(b.minX(), cx << 4), x1 = Math.min(b.maxX(), (cx << 4) + 15);
                final int z0 = Math.max(b.minZ(), cz << 4), z1 = Math.min(b.maxZ(), (cz << 4) + 15);
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            final BlockState state = chunk.getBlockState(pos.set(x, y, z));
                            if (state.isAir()) continue;
                            states[((y - b.minY()) * sizeZ + (z - b.minZ())) * sizeX + (x - b.minX())] = prepare(state, rotation, aligned);
                        }
                    }
                }
            }
        }
        return new ShipBlocks(b.minX(), b.minY(), b.minZ(), sizeX, sizeY, sizeZ, states, rotation, aligned, now);
    }

    /**
     * What path finding should see for one ship block.
     * Citizens cannot open anything on a ship (the door AI reads the ordinary world), so closed doors and gates are barriers and
     * closed trapdoors are plain floor. On a ship that is not close to a quarter turn, ladders, vines and open trapdoors cannot be
     * given a direction, so they become gaps: nothing to climb, nothing special to pass.
     */
    private static BlockState prepare(BlockState state, Rotation rotation, boolean aligned) {
        final Block block = state.getBlock();
        final boolean closed = state.hasProperty(BlockStateProperties.OPEN) && !state.getValue(BlockStateProperties.OPEN);
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            if (closed) return BLOCKED;
        } else if (block instanceof TrapDoorBlock || block instanceof PanelBlock) {
            if (closed) return SOLID;
            if (!aligned) return null;
        } else if (!aligned && (block instanceof LadderBlock || state.is(BlockTags.CLIMBABLE) || state.is(ModTags.freeClimbBlocks))) {
            return null;
        }
        if (rotation == Rotation.NONE) return state;
        try {
            return state.rotate(rotation);
        } catch (RuntimeException e) {
            return state;
        }
    }
}
