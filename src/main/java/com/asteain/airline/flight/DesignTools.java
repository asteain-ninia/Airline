package com.asteain.airline.flight;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The design tools: players do not change aircraft (an airfield keeps its aircraft as
 * its design has it); a design is changed by bringing out the design's aircraft once, changing it by hand and taking
 * the result in as a design file, which is then finished (devices, levers, the bench's numbers) outside the game. Only
 * in a world whose server config switches them on ({@code saves/<world>/serverconfig/airline-server.toml}), so that a
 * world that is played is never touched by them. This class is the switch alone (it is read without the flying part);
 * what the tools do is {@link Designing}.
 */
public final class DesignTools {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("The design tools (/airline design ...): bring out a design's aircraft to be changed by hand, and take the result in as a design file.",
                    "For changing the mod's designs only: switch it on in a world that is not played.")
            .define("designTools", false);
    public static final ModConfigSpec SPEC = BUILDER.build();

    /** The folder the designs that are taken in are written to, under the game's folder. */
    public static final String FOLDER = "airline-designs";

    private DesignTools() {
    }

    public static boolean enabled() {
        return SPEC.isLoaded() && ENABLED.get();
    }
}
