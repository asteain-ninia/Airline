package com.asteain.airline.pathing;

import org.jetbrains.annotations.Nullable;

/** Implemented on MineColonies' ChunkCache by mixin: the ships captured for that search (null when there are none). */
public interface ShipOverlayHolder {
    @Nullable
    ShipOverlaySet airline$ships();
}
