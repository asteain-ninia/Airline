package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stage 2 lab (aircraft devices and device-only autopilot). Exploration, not a regression suite: every scenario records
 * what it measured; little is asserted. The scenario is chosen with -Plab=name (system property shiptest.lab).
 */
final class LabSuite {
    private LabSuite() {}

    static Script build(MinecraftServer server) {
        Script s = new Script();
        String which = System.getProperty("shiptest.lab", "probe");
        s.label = "stage 2 lab: " + which + " (gravity " + (Boolean.getBoolean("shiptest.gravity") ? "on" : "OFF - wrong run") + ")";
        ServerLevel level = server.overworld();
        BlockPos origin = Rig.site(level, 0);
        switch (which) {
            case "probe" -> probe(s);
            default -> LabFlight.add(s, server, level, origin, which, System.getProperty("shiptest.labArg", ""));
        }
        return s;
    }

    /** Lists the blocks of the aircraft mods with their default states, so that later scenarios can be written against real names. */
    private static void probe(Script s) {
        s.step("probe: blocks", () -> {
            for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
                String ns = id.getNamespace();
                String path = id.getPath();
                boolean wanted = ns.equals("aeronautics") || ns.equals("simulated") || ns.equals("offroad")
                        || (ns.equals("create") && (path.contains("lever") || path.equals("shaft") || path.contains("cogwheel") || path.contains("clutch")
                        || path.contains("gearshift") || path.contains("gearbox") || path.contains("chain_drive") || path.contains("seat") && path.equals("white_seat")
                        || path.contains("creative_motor") || path.contains("speedometer") || path.contains("stressometer")));
                if (!wanted) continue;
                Block block = BuiltInRegistries.BLOCK.get(id);
                BlockState state = block.defaultBlockState();
                s.note("BLOCK " + id + " " + state.getValues() + " class=" + block.getClass().getSimpleName());
            }
        });
    }
}
