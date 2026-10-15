package com.asteain.airlinetest;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dedicated-server checks for Airline. Suite chosen by -Dairlinetest.suite=airfield|reload|removed. Result: airline-result.txt. */
@Mod("airlinetest")
public final class AirlineTest {
    private static final String RESULT = "airline-result.txt";
    private Script script;
    private volatile boolean finished;

    public AirlineTest() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::tick);
        long seconds = Long.getLong("airlinetest.timeoutSeconds", 600L);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(seconds * 1000L); } catch (InterruptedException e) { return; }
            if (finished) return;
            try { Files.writeString(Path.of(RESULT), "FAIL watchdog(wall clock): no result after " + seconds + " s"); } catch (Exception ignored) {}
            Runtime.getRuntime().halt(3);
        }, "airlinetest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            String suite = System.getProperty("airlinetest.suite", "");
            // (The suite "removed" runs without the mod: there is nothing to set.)
            if (!suite.equals("removed")) Fixture.reachAsLaidOut();
            // The world of the airfield checks is opened again: its "other colony" (no airfield) stands for one that can be
            // flown to, as it did there after the first export check (else the export request it left open is withdrawn).
            if (suite.equals("reload")) com.asteain.airline.colony.ExportStockModule.reachAllForChecks(true);
            script = switch (suite) {
                case "airfield" -> AirfieldSuite.build(server);
                case "reload" -> ReloadSuite.build(server);
                case "removed" -> RemovedSuite.build(server);
                case "network" -> NetworkSuite.build(server);
                case "flight" -> FlightSuite.build(server);
                case "bench" -> BenchSuite.build(server);
                default -> throw new AssertionError("unknown airlinetest.suite '" + suite + "'");
            };
            // Faster than real time (2026-10-15): the checks count ticks, not seconds. With -Psprint=on the server ticks
            // as fast as it can (Minecraft's own "tick sprint"); what happens in each tick is the same.
            if (Script.SPRINT) server.tickRateManager().requestGameToSprint(100_000_000);
        } catch (Throwable failure) {
            LogUtils.getLogger().error("[AIRTEST] start-up failure", failure);
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
        try { Files.writeString(Path.of(RESULT), result); } catch (Exception e) { LogUtils.getLogger().error("[AIRTEST] cannot write result", e); }
        LogUtils.getLogger().info("[AIRTEST] {}", result);
        finished = true;
        server.halt(false);
    }
}
