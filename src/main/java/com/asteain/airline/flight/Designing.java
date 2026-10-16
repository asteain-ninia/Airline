package com.asteain.airline.flight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** What the design tools do ({@link DesignTools}): needs the flying part, called through {@link Flights} only. */
final class Designing {
    private Designing() {
    }

    /** How far from the aircraft block a design that is taken in is looked for, in blocks (beyond the largest class). */
    private static final int REACH_ACROSS = 24, REACH_DOWN = 8, REACH_UP = 32;

    /** The design's aircraft on the pad, marked as being changed: the airfield does not put it back and it does not fly. */
    static Flights.Answer start(ServerLevel level, BlockPos pad, String design) {
        final Flights.Answer made = FlightController.spawn(level, pad, AircraftType.of(design));
        if (!made.ok()) return made;
        for (final AircraftBlockEntity aircraft : FlightController.loaded(level)) {
            if (!pad.equals(aircraft.home)) continue;
            modifying(aircraft, true);
            return new Flights.Answer(true, "design.started", design);
        }
        return new Flights.Answer(false, "flight.no_aircraft");
    }

    /** Marks an aircraft as being changed by hand, or takes the mark off (the airfield then puts it back as its design has it). */
    static void modifying(AircraftBlockEntity aircraft, boolean on) {
        aircraft.modifying = on;
        aircraft.lacks.clear();
        aircraft.setChanged();
    }

    /**
     * Writes the blocks of the aircraft as they stand now into {@code <game folder>/airline-designs/<name>.json}: the
     * design file it was made from, with its blocks replaced (places relative to the aircraft block, nose to +x). Seat,
     * barrels, devices, the table of levers and the bench's numbers stay as the old design has them, to be put right.
     */
    static Flights.Answer capture(ServerLevel level, AircraftBlockEntity aircraft, String name) {
        final FlightController.Runtime rt = FlightController.runtime(aircraft);
        if (rt == null) return new Flights.Answer(false, "flight.no_aircraft");
        final AircraftType type = rt.craft.type;
        final JsonObject design = bundled(type.name);
        final JsonArray blocks = new JsonArray();
        int kept = 0;
        for (int y = -REACH_DOWN; y <= REACH_UP; y++) for (int x = -REACH_ACROSS; x <= REACH_ACROSS; x++) for (int z = -REACH_ACROSS; z <= REACH_ACROSS; z++) {
            final BlockPos part = new BlockPos(x, y, z);
            if (part.equals(BlockPos.ZERO)) continue;
            final BlockState state = level.getBlockState(rt.craft.inShip(part));
            String text = state.isAir() ? null : BlockStateParser.serialize(state);
            // A propeller's hub and sails are no blocks while its bearing turns them: they are taken as the design has them.
            if (text == null && type.moving().contains(part)) {
                text = type.parts.get(part);
                kept++;
            }
            if (text == null) continue;
            final JsonArray block = new JsonArray();
            block.add(x);
            block.add(y);
            block.add(z);
            block.add(text);
            blocks.add(block);
        }
        design.add("blocks", blocks);
        design.addProperty("title", name);
        final JsonObject from = new JsonObject();
        from.addProperty("design", type.name);
        from.addProperty("gameTime", level.getGameTime());
        from.addProperty("turningPartsTakenFromTheDesign", kept);
        from.addProperty("note", "Taken in with /airline design capture. Seat, cargo, access, burners, engines, levers, drive, throttles and handling are still those of the design it was made from: put them right for the blocks as they are now, then measure it on the bench.");
        design.add("capturedFrom", from);
        final Path out = FMLPaths.GAMEDIR.get().resolve(DesignTools.FOLDER).resolve(name + ".json");
        try {
            Files.createDirectories(out.getParent());
            final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            Files.writeString(out, gson.toJson(design), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Flights.Answer(false, "design.not_written", out.toString(), e.getMessage());
        }
        return new Flights.Answer(true, "design.captured", blocks.size(), out.toString());
    }

    /** The design file in the jar as it is written there (an empty one for the design made in code). */
    private static JsonObject bundled(String name) {
        try (InputStream in = Designing.class.getResourceAsStream("/airline_aircraft/" + name + ".json")) {
            if (in != null) return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            // read as no file
        }
        final JsonObject empty = new JsonObject();
        empty.addProperty("format", 2);
        return empty;
    }
}
