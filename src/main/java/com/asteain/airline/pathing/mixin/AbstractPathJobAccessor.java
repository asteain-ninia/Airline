package com.asteain.airline.pathing.mixin;

import com.minecolonies.core.entity.pathfinding.pathjobs.AbstractPathJob;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the world (ChunkCache) a finished search used, to learn which ships its path walks on. */
@Mixin(value = AbstractPathJob.class, remap = false)
public interface AbstractPathJobAccessor {
    @Accessor("world")
    LevelReader airline$getWorld();
}
