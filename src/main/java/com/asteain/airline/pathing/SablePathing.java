package com.asteain.airline.pathing;

import com.asteain.airline.boarding.BoardingManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

/**
 * Wiring for the ship path finding feature. Only touched when Sable is installed
 * (Airline checks the mod list first), because everything behind it names Sable classes.
 */
public final class SablePathing {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] TARGETS = {
            "com.minecolonies.core.entity.pathfinding.world.ChunkCache",
            "com.minecolonies.core.entity.pathfinding.world.CachingBlockLookup",
            "com.minecolonies.core.entity.pathfinding.pathjobs.AbstractPathJob",
            "com.minecolonies.core.entity.pathfinding.PathfindingUtils",
            "com.minecolonies.core.entity.pathfinding.navigation.MinecoloniesAdvancedPathNavigate",
            "com.minecolonies.core.entity.pathfinding.navigation.PathingStuckHandler",
    };

    private SablePathing() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(SablePathing::onLevelTick);
        gameBus.addListener(SablePathing::onLevelUnload);
        gameBus.addListener(SablePathing::onServerStarted);
        gameBus.addListener(SablePathing::onServerStopping);
    }

    private static void onLevelTick(final LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (PathingHooks.broken()) {
            BoardingManager.tick(level);
            return;
        }
        try {
            ShipTracker.tick(level);
        } catch (RuntimeException | LinkageError e) {
            ShipTracker.forget(level);
            PathingHooks.fail("watching the ships of a level", e);
        }
        // After the ships have been looked at: seats follow their ship, boarding citizens are walked along.
        BoardingManager.tick(level);
    }

    private static void onLevelUnload(final LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) ShipTracker.forget(level);
    }

    /** Before the worlds are saved: seated citizens stand up on their decks, so none is saved on a seat that will not last. */
    private static void onServerStopping(final ServerStoppingEvent event) {
        for (final ServerLevel level : event.getServer().getAllLevels()) BoardingManager.release(level, "the server is stopping");
    }

    /** Fail fast, like the raid filter: load every mixin target now so a broken mixin shows up in the startup log. */
    private static void onServerStarted(final ServerStartedEvent event) {
        try {
            for (final String target : TARGETS) Class.forName(target, true, SablePathing.class.getClassLoader());
            LOGGER.info("[Airline/pathing] Sable found; MineColonies path finding will see stopped ships.");
        } catch (Throwable t) {
            LOGGER.error("[Airline/pathing] Could not load the MineColonies path finding classes; ship path finding is NOT active. Update Airline for this MineColonies version.", t);
        }
    }
}
