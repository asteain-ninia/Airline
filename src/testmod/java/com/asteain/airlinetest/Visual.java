package com.asteain.airlinetest;

import com.asteain.airline.Airline;
import com.asteain.airline.client.RouteMapClient;
import com.asteain.airline.route.RouteMap;
import com.mojang.logging.LogUtils;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Starts a real client with JourneyMap (run "visual"), makes a flat world, sets down five buoys of the
 * three grades and a block of stone in one line's way, and takes screenshots of the fullscreen map and the minimap with
 * the lines of the air network drawn on them (2026-10-17). The pictures are judged by eye; the result says how many
 * lines the client was sent and of which classes. Never shipped.
 */
@EventBusSubscriber(modid = "airlinetest", value = Dist.CLIENT)
public final class Visual {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD = "AirlineVisual";
    /** The flat world's ground is at -61; the buoys float well above it. */
    private static final int Y = -40;

    private static boolean creating, prepared, finished, linesOk;
    private static int tabs;
    /** The airfield of the player's colony, whose window is opened. */
    private static BlockPos airfield;
    private static int ticks;
    private static final List<String> NOTES = new ArrayList<>();

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("airlinetest.visual")) return;
        final Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.level == null) {
                if (mc.screen instanceof TitleScreen && !creating) {
                    creating = true;
                    final LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261017L, false, false),
                            registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
                }
                return;
            }
            if (mc.player == null || mc.getSingleplayerServer() == null) return;
            script(mc);
        } catch (Throwable t) {
            LOGGER.error("[AIRLINEVISUAL] failed", t);
            finish(mc, "FAIL " + t);
        }
    }

    private static void script(Minecraft mc) {
        if (!prepared) {
            prepared = true;
            mc.options.pauseOnLostFocus = false;
            final UUID player = mc.player.getUUID();
            final MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> prepare(server, player));
        }
        ticks++;
        if (ticks == 380) {
            final MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (final com.asteain.airline.route.RouteNetwork.Edge edge : com.asteain.airline.route.RouteNetwork.get(server.overworld()).edges()) {
                    if (edge.state() == com.asteain.airline.route.RouteNetwork.State.BLOCKED || edge.closedFrom() != null)
                        NOTES.add("line " + edge.a.toShortString() + " - " + edge.b.toShortString() + ": " + edge.state() + ", closed from " + edge.closedFrom() + ", blocked at " + edge.blockedAt() + ", barred by " + edge.barredBy());
                }
            });
        }
        if (ticks == 400) capture(mc, "airline-01-world-and-minimap.png");
        if (ticks == 420) fullscreen(mc);
        // The first opening of a session shows JourneyMap's "about" page instead of the map: close it and open again.
        if (ticks == 430) {
            mc.setScreen(null);
        }
        if (ticks == 440) fullscreen(mc);
        if (ticks == 500) capture(mc, "airline-02-fullscreen.png");
        if (ticks == 520) {
            mc.setScreen(null);
            final List<RouteMap.Line> lines = RouteMapClient.lines(mc.level.dimension().location());
            final int[] byOpen = new int[4];
            int unverified = 0;
            for (final RouteMap.Line line : lines) {
                byOpen[line.open() + 1]++;
                if (line.state() == 0) unverified++;
            }
            final String counts = String.format("lines sent to the client: %d (blocked %d, small %d, medium %d, large %d; still unverified %d)",
                    lines.size(), byOpen[0], byOpen[1], byOpen[2], byOpen[3], unverified);
            NOTES.add(counts);
            linesOk = lines.size() >= 5 && byOpen[0] >= 1 && byOpen[1] >= 1 && byOpen[2] >= 1 && byOpen[3] >= 1;
        }
        // The airfield's window (2026-10-18): the routes tab and the requests tab.
        if (ticks == 530) {
            // Into the colony: MineColonies sends its buildings to the players near it.
            final UUID player = mc.player.getUUID();
            final MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> server.getPlayerList().getPlayer(player).connection.teleport(airfield.getX() - 4.5, airfield.getY() + 2, airfield.getZ() + 0.5, 0f, 10f));
        }
        if (ticks == 640) tab(mc, com.asteain.airline.colony.RoutesModuleView.class);
        if (ticks == 700) capture(mc, "airline-03-routes-tab.png");
        if (ticks == 720) tab(mc, com.asteain.airline.colony.WantsModuleView.class);
        if (ticks == 780) capture(mc, "airline-04-wants-tab.png");
        if (ticks == 800) {
            mc.setScreen(null);
            finish(mc, (linesOk && tabs == 2 ? "PASS " : "FAIL ") + "four screenshots taken; the pictures need a look. " + String.join(" | ", NOTES));
        }
    }

    /** On the server: five buoys (grades 1, 1, 2, 3, 3) and a block of stone across the line from the first to the fourth. */
    private static void prepare(MinecraftServer server, UUID playerId) {
        final ServerLevel level = server.overworld();
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        final BlockPos a = new BlockPos(0, Y, 0), b = new BlockPos(40, Y, 0), c = new BlockPos(40, Y, 40), d = new BlockPos(100, Y, 20), e = new BlockPos(100, Y, -40);
        level.setBlockAndUpdate(a, Airline.BUOYS.get(0).get().defaultBlockState());
        level.setBlockAndUpdate(b, Airline.BUOYS.get(0).get().defaultBlockState());
        level.setBlockAndUpdate(c, Airline.BUOYS.get(1).get().defaultBlockState());
        level.setBlockAndUpdate(d, Airline.BUOYS.get(2).get().defaultBlockState());
        level.setBlockAndUpdate(e, Airline.BUOYS.get(2).get().defaultBlockState());
        // In the way of the line from a to d, half way and at its height (9 above a grade 1 buoy, 13 above a grade 3 one): that line is blocked.
        for (int x = 48; x <= 52; x++) for (int y = Y + 7; y <= Y + 15; y++) for (int z = 8; z <= 12; z++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        final ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.connection.teleport(50.5, Y + 30, 20.5, 0f, 80f);
        }
        colonies(server, level, player);
        NOTES.add("buoys at " + a.toShortString() + " (1), " + b.toShortString() + " (1), " + c.toShortString() + " (2), " + d.toShortString() + " (3), " + e.toShortString() + " (3); stone in the way of a-d");
    }

    /**
     * Two colonies with an airfield each: the player's own (its window is opened) and another 120 blocks away that wants
     * some iron and bread; a third one without an airfield.
     */
    private static void colonies(MinecraftServer server, ServerLevel level, ServerPlayer player) {
        try {
            final BlockPos own = new BlockPos(30, -60, 100), other = new BlockPos(150, -60, 100), third = new BlockPos(30, -60, 260);
            final com.minecolonies.api.colony.IColony a = com.minecolonies.api.colony.IColonyManager.getInstance().createColony(level, own, player, "空路の村", "Medieval Oak");
            Fixture.townHall(a, own);
            airfield = own.offset(8, 0, 0);
            final net.minecraft.world.level.block.state.BlockState hut = Airline.AIRFIELD_HUT.get().defaultBlockState();
            level.setBlockAndUpdate(airfield, hut);
            Airline.AIRFIELD_HUT.get().setPlacedBy(level, airfield, hut, player, new net.minecraft.world.item.ItemStack(Airline.AIRFIELD_HUT.get()));
            a.getServerBuildingManager().getBuilding(airfield).setBuildingLevel(1);
            final com.minecolonies.api.colony.IColony b = Fixture.create(level, other, "となり村");
            Fixture.townHall(b, other);
            Fixture.placeHut(level, b, Airline.AIRFIELD_HUT.get(), other.offset(8, 0, 0)).setBuildingLevel(1);
            final com.minecolonies.api.colony.IColony c = Fixture.create(level, third, "遠い村");
            Fixture.townHall(c, third);
            final com.asteain.airline.ledger.WantLedger ledger = com.asteain.airline.ledger.WantLedger.get(server);
            ledger.set(com.asteain.airline.ledger.WantLedger.Key.of(b), net.minecraft.world.item.Items.IRON_INGOT, 64);
            ledger.set(com.asteain.airline.ledger.WantLedger.Key.of(b), net.minecraft.world.item.Items.BREAD, 32);
            NOTES.add("colonies: own " + a.getID() + ", other " + b.getID() + ", far " + c.getID());
        } catch (Throwable t) {
            LOGGER.error("[AIRLINEVISUAL] the colonies could not be made", t);
            NOTES.add("colonies not made: " + t);
        }
    }

    /** Opens a tab of the airfield's window. */
    private static void tab(Minecraft mc, Class<? extends com.minecolonies.api.colony.buildings.modules.IBuildingModuleView> type) {
        try {
            final com.minecolonies.api.colony.buildings.views.IBuildingView view = com.minecolonies.api.colony.IColonyManager.getInstance().getBuildingView(mc.level.dimension(), airfield);
            if (view == null) {
                NOTES.add("no view of the airfield");
                return;
            }
            final com.minecolonies.api.colony.buildings.modules.IBuildingModuleView module = view.getModuleViewByType(type);
            if (module == null) {
                NOTES.add("no " + type.getSimpleName());
                return;
            }
            mc.setScreen(null);
            module.getWindow().open();
            tabs++;
            NOTES.add("opened " + type.getSimpleName());
        } catch (Throwable t) {
            LOGGER.error("[AIRLINEVISUAL] the tab could not be opened", t);
            NOTES.add("tab not opened: " + t);
        }
    }

    /** Opens the fullscreen map (or leaves it open) centred over the buoys. */
    private static void fullscreen(Minecraft mc) {
        try {
            if (mc.screen instanceof IFullscreen open) {
                open.centerOn(50.5, 20.5);
                return;
            }
            final Object ui = Class.forName("journeymap.client.ui.UIManager").getField("INSTANCE").get(null);
            final IFullscreen screen = (IFullscreen) ui.getClass().getMethod("getOrOpenFullscreenMap").invoke(ui);
            screen.zoomIn();
            screen.centerOn(50.5, 20.5);
            NOTES.add("fullscreen zoom " + screen.getUiState().zoom);
        } catch (Throwable t) {
            LOGGER.warn("[AIRLINEVISUAL] could not open the fullscreen map", t);
            NOTES.add("fullscreen map not opened: " + t);
        }
    }

    private static void capture(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), result -> LOGGER.info("[AIRLINEVISUAL] {}", result.getString()));
    }

    private static void finish(Minecraft mc, String result) {
        if (finished) return;
        finished = true;
        try {
            Files.writeString(Path.of(mc.gameDirectory.toString(), "airline-visual-result.txt"), result);
        } catch (Exception e) {
            LOGGER.error("[AIRLINEVISUAL] could not write the result", e);
        }
        mc.stop();
    }
}
