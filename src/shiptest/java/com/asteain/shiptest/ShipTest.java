package com.asteain.shiptest;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dedicated-server checks of Airline's ship walking and boarding (moved here from ColonyPatch Server). Suite chosen by -Dshiptest.suite=smoke|pathing|... Result: ship-result.txt. */
@Mod("shiptest")
public final class ShipTest {
    private static final String RESULT = "ship-result.txt";
    private Script script;
    private volatile boolean finished;

    public ShipTest() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::tick);
        long seconds = Long.getLong("shiptest.timeoutSeconds", 420L);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(seconds * 1000L); } catch (InterruptedException e) { return; }
            if (finished) return;
            try { Files.writeString(Path.of(RESULT), "FAIL watchdog(wall clock): no result after " + seconds + " s"); } catch (Exception ignored) {}
            Runtime.getRuntime().halt(3);
        }, "shiptest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            // Test worlds only: drop ships left behind by earlier runs so they never pile up.
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                try { LogUtils.getLogger().info("[SHIPTEST] removed {} ship(s) left in the world by earlier runs", Ships.removeNear(server.overworld(), net.minecraft.world.phys.Vec3.ZERO, -1)); }
                catch (Throwable t) { LogUtils.getLogger().warn("[SHIPTEST] could not clear old ships", t); }
            }
            String suite = System.getProperty("shiptest.suite", "");
            script = switch (suite) {
                case "smoke" -> SmokeSuite.build(server);
                case "pathing" -> PathingSuite.build(server);
                case "edge" -> EdgeSuite.build(server);
                case "reach" -> ReachSuite.build(server);
                case "ships" -> ShipsSuite.build(server);
                case "motion" -> MotionSuite.build(server);
                case "landheight" -> LandHeightSuite.build(server);
                case "landwalk" -> LandWalkSuite.build(server);
                case "pathgrid" -> GridPathingSuite.build(server);
                case "burner" -> BurnerSuite.build(server);
                case "landing" -> LandingSuite.build(server);
                case "mooring" -> MooringSuite.build(server);
                case "boarding" -> BoardingSuite.build(server);
                case "lab" -> LabSuite.build(server);
                default -> throw new AssertionError("unknown shiptest.suite '" + suite + "'");
            };
        } catch (Throwable failure) {
            LogUtils.getLogger().error("[SHIPTEST] start-up failure", failure);
            end(server, "FAIL " + failure);
        }
    }

    private void tick(ServerTickEvent.Post event) {
        if (script == null) return;
        String result = script.tick();
        if (result != null) {
            script = null;
            end(event.getServer(), result);
        }
    }

    private void end(MinecraftServer server, String result) {
        try { Files.writeString(Path.of(RESULT), result); } catch (Exception e) { LogUtils.getLogger().error("[SHIPTEST] cannot write result", e); }
        LogUtils.getLogger().info("[SHIPTEST] {}", result);
        finished = true;
        server.halt(false);
    }
}
