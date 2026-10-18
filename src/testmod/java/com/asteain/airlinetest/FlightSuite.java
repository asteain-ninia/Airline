package com.asteain.airlinetest;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.flight.AircraftBlockEntity;
import com.asteain.airline.flight.AircraftBlockEntity.Phase;
import com.asteain.airline.flight.Flights;
import com.asteain.airline.route.Facility;
import com.asteain.airline.route.RouteNetwork;
import com.asteain.airline.route.ShipClass;
import com.asteain.airline.route.RouteNetwork.Edge;
import com.asteain.airline.route.RouteNetwork.State;
import com.asteain.colonypatchserver.boarding.Boarding;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.asteain.airline.colony.ExportStockModule;
import com.asteain.airline.ledger.WantLedger;
import com.minecolonies.api.blocks.ModBlocks;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.resolver.player.IPlayerRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.tileentities.TileEntityRack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4, second half, on a dedicated server with Sable, Create Aeronautics and ColonyPatchServer: a pilot boards the
 * aircraft, it flies the lines of the network to another landing pad and back; blocked lines; a long flight; stopping
 * and starting the server. The scene is chosen with -Pscene.
 */
final class FlightSuite {
    private FlightSuite() {}

    private static final int Y = 100;
    /** Colony A's hut stands where its landing pad comes to lie at (0, Y - 1, -10), as the scenes were written for. */
    private static final BlockPos HUT_A = new BlockPos(0, Y, 0).subtract(com.asteain.airline.route.RouteRules.PAD_FROM_HUT).offset(0, -1, -10);
    /** The lowest of three layers of a wall across a line between two landing pads (the line passes 18 above a pad's floor; the wall is in its tube). */
    private static final int WALL = Y + 13;

    /**
     * A wall across the line of the scenes (z -11 to -9) at {@code x}, from 3 below WALL up so high that a line cannot be
     * raised over it (2026-10-18: lines pass over what is lower, by 32 at most), listed from the top down: set in that
     * order, the column above a block stands already when the block comes into the line's tube.
     */
    private static BlockPos[] tallWall(int x) {
        final List<BlockPos> all = new ArrayList<>();
        for (int y = WALL + 36; y >= WALL - 3; y--) for (int z = -11; z <= -9; z++) all.add(new BlockPos(x, y, z));
        return all.toArray(new BlockPos[0]);
    }
    /** The berth (ticks on a pad after every landing) the checks run with, and the longer one of the cargo scene. */
    private static final int BERTH = 300, BERTH_LONG = 1200;
    private static final String STATE = "airline-flight-state.txt";

    /** One colony with an airfield, a pilot and an aircraft on the pad; filled in step by step. */
    private static final class Field {
        final BlockPos hut;
        IColony colony;
        BuildingAirfield airfield;
        ICitizenData pilotData;
        Mob pilot;
        AircraftBlockEntity aircraft;
        BlockPos pad;
        /** The scene makes the aircraft itself (it is built on the pad). */
        boolean noAircraft;
        // what the watch records
        Phase seen;
        int clock, since;
        double pilotMinY = 1e9, speedMax, wallGap = 1e9;
        /** Cargo flights: how many times it took off, and the shortest time it had stood on a pad when it did. */
        int cargoTakeoffs;
        long shortestBerth = Long.MAX_VALUE;
        final List<String> log = new ArrayList<>();

        Field(BlockPos hut) {
            this.hut = hut;
        }
    }

    private static void pad(ServerLevel level, BlockPos floor, int heading) {
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) level.setBlockAndUpdate(floor.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        RouteNetwork.get(level).setAirfield(floor, heading, 1);
    }

    private static String at(double[] r) {
        return r == null ? "(not loaded)" : String.format("(%.1f, %.1f, %.1f) heading %.1f", r[0], r[1], r[2], r[3]);
    }

    /** Colony, airfield hut (placed by hand), pilot, aircraft. The floor under hut and pad is stone, kept loaded. */
    private static void addField(Script s, ServerLevel level, Field f, String name) {
        addField(s, level, f, name, Facility.SMALL_AIRFIELD);
    }

    /** The same with another kind of airfield (its floor sized for its pad). */
    private static void addField(Script s, ServerLevel level, Field f, String name, Facility facility) {
        s.step(name + ": colony and airfield", () -> {
            if (facility == Facility.SMALL_AIRFIELD) Fixture.platform(level, f.hut.offset(1, 0, -8), 10, 16);
            else floorFor(level, f.hut, facility);
            f.colony = Fixture.create(level, f.hut.offset(4, 0, 4), "Airline " + name);
            Fixture.townHall(f.colony, f.hut.offset(4, 0, 4));
            f.airfield = (BuildingAirfield) Fixture.placeHut(level, f.colony, hutOf(facility), f.hut);
            f.pad = f.airfield.pad();
            RouteNetwork.Node node = RouteNetwork.get(level).node(f.pad);
            s.soft(name + " / placing the airfield puts its landing pad on the network", node != null && node.kind() == RouteNetwork.Kind.AIRFIELD && node.heading() == f.airfield.padHeading(),
                    "pad " + f.pad.toShortString() + " heading " + f.airfield.padHeading() + " node " + node);
            f.pilotData = Fixture.spawn(f.colony, level, f.hut.offset(2, 0, 2));
        });
        s.until(name + ": pilot entity", () -> {}, () -> (f.pilot = Fixture.entity(f.pilotData)) != null, 200);
        s.step(name + ": hire the pilot, make the aircraft", () -> {
            s.check(f.airfield.getFirstModuleOccurance(WorkerBuildingModule.class).assignCitizen(f.pilotData), "the pilot is hired");
            s.soft(name + " / the flying part is available", Flights.available(), "available=" + Flights.available());
            if (f.noAircraft) return;
            // -Paircraft=<design> flies the scene with another aircraft design than the first one.
            Flights.Answer made = spawn(level, f.pad);
            s.soft(name + " / an aircraft is made on the pad", made.ok(), made.text().getString());
            List<AircraftBlockEntity> all = Flights.aircraft(level);
            s.check(!all.isEmpty(), "the aircraft block is found on the new ship");
            // (With several aircraft: the one whose home is this pad.)
            for (AircraftBlockEntity each : all) if (f.pad.equals(each.home)) f.aircraft = each;
            s.check(f.aircraft != null, "the new aircraft's home is this pad");
            double[] weight = Flights.weight(level, f.aircraft);
            s.note(String.format("OBS aircraft design %s: mass %.2f, centre of mass %.2f toward the nose, %.2f up, %.2f to the right of the aircraft block",
                    f.aircraft.type, weight[0], weight[1], weight[2], weight[3]));
            Flights.Answer again = Flights.spawn(level, f.pad);
            s.soft(name + " / a second aircraft on the same pad is refused", !again.ok(), again.text().getString());
        });
        if (f.noAircraft) return;
        loaded(s, name, () -> f.aircraft);
        s.settle(name + ": the balloon fills while the aircraft stands fixed", 320);
        s.step(name + ": parked", () -> {
            double[] r = Flights.readings(f.aircraft);
            s.soft(name + " / the parked aircraft is fixed to the world, at the pad, in the pad's heading", r != null && r[4] > 0 && Math.hypot(r[0] - (f.pad.getX() + 0.5), r[2] - (f.pad.getZ() + 0.5)) < 0.3
                    && Math.abs(wrap(r[3] - f.airfield.padHeading())) < 1.0 && f.aircraft.phase == Phase.PARKED, at(r) + " rest offset " + f.aircraft.restOffset);
        });
    }

    /** For the scene yard: a deck plank of that design with nothing over it, two of its cogwheels, and the free place over the plank (null where there is none). */
    private static BlockPos[] yardParts(String design) {
        final java.util.Map<BlockPos, String> parts = com.asteain.airline.flight.AircraftType.of(design).parts;
        final BlockPos[] found = new BlockPos[4];
        for (final java.util.Map.Entry<BlockPos, String> part : parts.entrySet()) {
            final BlockPos at = part.getKey();
            if (found[0] == null && part.getValue().equals("minecraft:oak_planks") && at.getY() == 0 && !parts.containsKey(at.above()) && !at.above().equals(BlockPos.ZERO)) {
                found[0] = at;
                found[3] = at.above();
            } else if (part.getValue().startsWith("create:cogwheel")) {
                if (found[1] == null) found[1] = at;
                else if (found[2] == null) found[2] = at;
            }
        }
        if (found[0] == null) {
            // A design whose deck has no plank with nothing on it (koeda, 2026-10-15): another deck block with nothing on it.
            for (final java.util.Map.Entry<BlockPos, String> part : parts.entrySet()) {
                final BlockPos at = part.getKey();
                final String name = part.getValue().split("\\[")[0];
                if (at.getY() == 0 && (name.endsWith("_planks") || name.endsWith("_slab") || name.endsWith("_stairs")) && !parts.containsKey(at.above()) && !at.above().equals(BlockPos.ZERO)) {
                    found[0] = at;
                    found[3] = at.above();
                    break;
                }
            }
        }
        return found;
    }

    /** The design this run flies: -Paircraft=<design>, else the one an airfield gets when nothing else is asked for. */
    private static String design() {
        final String named = System.getProperty("airlinetest.aircraft", "");
        return named.isEmpty() ? Flights.designs().get(0) : named;
    }

    /** A new aircraft of this run's design on a pad. */
    private static Flights.Answer spawn(ServerLevel level, BlockPos pad) {
        return Flights.spawn(level, pad, design());
    }

    /** For finding out why a reloaded aircraft does not get on (2026-10-15, koeda's belts): how fast each part of its drive turns. */
    private static String turning(ServerLevel level, AircraftBlockEntity aircraft) {
        final StringBuilder out = new StringBuilder();
        for (final java.util.Map.Entry<BlockPos, String> part : com.asteain.airline.flight.AircraftType.of(aircraft.type).parts.entrySet()) {
            final String name = part.getValue();
            if (!(name.contains("propeller") || name.contains("belt") || name.contains("transmission") || name.contains("gearshift") || name.contains("engine"))) continue;
            final BlockPos at = Flights.partOf(aircraft, part.getKey());
            final net.minecraft.world.level.block.entity.BlockEntity be = at == null ? null : level.getBlockEntity(at);
            Object speed = "-";
            String more = "";
            if (be != null) {
                try {
                    speed = be.getClass().getMethod("getSpeed").invoke(be);
                    if (name.contains("engine")) more = " burn " + be.getClass().getMethod("getCurrentBurnTime").invoke(be) + " " + be.getBlockState();
                } catch (ReflectiveOperationException e) {
                    speed = "?";
                }
            }
            out.append(part.getKey().toShortString()).append(' ').append(name.split("\\[")[0].replaceAll(".*:", "")).append(' ').append(speed).append(more).append("; ");
        }
        out.append("tank ").append(Flights.fuelTicks(aircraft)).append(" ticks");
        return out.toString();
    }

    /** Waits until a newly made aircraft has been loaded and has ticked (it has its runtime). Its ship's land loads beside the ticks: at the
     * normal speed within a tick or two, under -Psprint=on it can take hundreds of ticks (2026-10-15). */
    private static void loaded(Script s, String name, java.util.function.Supplier<AircraftBlockEntity> aircraft) {
        s.until(name + ": the new aircraft is loaded", () -> {}, () -> {
            final AircraftBlockEntity a = aircraft.get();
            return a != null && Flights.readings(a) != null;
        }, 100);
    }

    private static double wrap(double d) {
        d %= 360.0;
        return d > 180 ? d - 360 : d < -180 ? d + 360 : d;
    }

    /** Every tick: notes each change of what the aircraft is doing, with where it is; follows the pilot. */
    private static void watch(Script s, Field f, String name) {
        s.monitor(() -> {
            f.clock++;
            if (f.aircraft == null) return;
            if (f.pilot != null && f.pilot.isAlive()) f.pilotMinY = Math.min(f.pilotMinY, f.pilot.getY());
            double[] r = Flights.readings(f.aircraft);
            if (r != null) f.speedMax = Math.max(f.speedMax, r[9]);
            if (f.aircraft.phase != f.seen) {
                String line = String.format("%s t=%d (+%d) %s -> %s at %s | pilot %s | %s", name, f.clock, f.clock - f.since, f.seen, f.aircraft.phase, at(r),
                        f.pilot == null ? "none" : Boarding.state(f.pilot) + (f.pilot.isAlive() ? "" : " DEAD"), f.aircraft.note);
                f.log.add(line);
                s.note("FLT " + line);
                if (f.aircraft.phase == Phase.CLIMB && f.aircraft.cargo && f.aircraft.getLevel() != null) {
                    f.cargoTakeoffs++;
                    f.shortestBerth = Math.min(f.shortestBerth, f.aircraft.getLevel().getGameTime() - f.aircraft.landed);
                }
                f.seen = f.aircraft.phase;
                f.since = f.clock;
            }
        });
    }

    static Script build(MinecraftServer server) throws Exception {
        String scene = System.getProperty("airlinetest.scene", "trip");
        Script s = new Script();
        s.label = "flight: " + scene;
        ServerLevel level = server.overworld();
        // Scheduled flights: a day on every pad is far too long for a check. The cargo scene looks at the berth itself.
        com.asteain.airline.flight.Cargo.berthForChecks(scene.equals("cargo") ? BERTH_LONG : BERTH);
        // The pilot carries the goods in the two scenes that are about that; in the others they move by themselves, as
        // they did when those scenes were written (what they look at is the flying, the ledger and the commands).
        com.asteain.airline.flight.Cargo.pilotCarriesForChecks(List.of("cargo", "carry", "yard", "fuel", "fetch").contains(scene));
        // The older scenes were written when no flight left home with an empty hold: flights that only fetch are made in their own scene.
        com.asteain.airline.flight.Cargo.fetchingForChecks(scene.equals("fetch"));
        switch (scene) {
            case "trip" -> trip(s, level);
            case "yard" -> yard(s, level);
            case "fuel" -> fuel(s, level);
            case "fetch" -> fetch(s, level);
            case "far" -> far(s, level);
            case "direct" -> direct(s, level);
            case "faults" -> faults(s, level);
            case "crash" -> crash(s, level);
            case "cargo" -> cargo(s, level);
            case "carry" -> carry(s, level);
            case "tour" -> tour(s, level);
            case "routes" -> routes(s, level);
            case "traffic" -> traffic(s, level);
            case "three" -> three(s, level);
            case "detour" -> detour(s, level);
            case "ground" -> ground(s, level);
            case "lost" -> lost(s, level);
            case "hindered" -> hindered(s, level);
            case "reopenc1" -> reopenc1(s, level);
            case "reopenc2" -> reopenc2(s, level);
            case "reopenc3" -> reopenc3(s, level);
            case "reopenc4" -> reopenc4(s, level);
            case "reopenprobe" -> reopenprobe(s, level);
            case "reopen1" -> reopen1(s, level);
            case "reopen2" -> reopen2(s, level);
            case "reopen3" -> reopen3(s, level);
            case "classes" -> classes(s, level);
            case "facilities" -> facilities(s, level);
            case "items" -> items(s, level);
            default -> throw new AssertionError("unknown scene " + scene);
        }
        return s;
    }

    // ---- the new facilities, built from their blueprints (the user approved the nine designs on 2026-10-15) --------

    /** A facility built from a blueprint, and what the scene learns of it. */
    private static final class Site {
        final String name, schematic;
        final Facility facility;
        final BlockPos hut;
        final com.ldtteam.structurize.api.RotationMirror turn;
        final java.util.concurrent.CompletableFuture<?>[] blueprints = new java.util.concurrent.CompletableFuture<?>[6];
        final int[] racks = new int[6];
        IColony colony;

        Site(String name, Facility facility, String schematic, BlockPos hut, com.ldtteam.structurize.api.RotationMirror turn) {
            this.name = name;
            this.facility = facility;
            this.schematic = schematic;
            this.hut = hut;
            this.turn = turn;
        }

        String path(int lv) {
            return "infrastructure/" + schematic + lv + ".blueprint";
        }

        BuildingAirfield building() {
            return colony.getServerBuildingManager().getBuilding(hut) instanceof BuildingAirfield field ? field : null;
        }
    }

    /**
     * The medium and the large airfield are pasted from their level 1 blueprints, then each level over the one before, up
     * to 5 (Structurize places them at once, as the build tool does for a player in creative). The wooden and the stone
     * anchorage stand beside them, and a wooden one placed mirrored. Each is checked as the kind of facility it is: the
     * level of its blueprint, its racks and import stash (the airfields), its pads where the code expects them and on the
     * network, a floor under each pad or a pier beside the berth. Then the medium aircraft (-Paircraft, a medium design)
     * flies a round trip from the medium airfield to the wooden anchorage and from the large airfield to the stone one; the
     * pilot steps onto the pier. (The user saw the small airfield built and working in the game.)
     */
    /**
     * What the colony's builders are asked for, for the blocks of each design (2026-10-17: a belt has no item of its own;
     * is a mechanical belt asked for, and is any block put up for nothing?). Structurize's own reckoning, the one that a
     * builder's work order goes by (MineColonies' placement handlers are in it).
     */
    private static void items(Script s, ServerLevel level) {
        s.step("items: what each design's blocks cost a builder", () -> {
            final com.ldtteam.structurize.placement.IPlacementContext context = new com.ldtteam.structurize.placement.IPlacementContext() {
                @Override
                public com.ldtteam.structurize.api.RotationMirror getRotationMirror() {
                    return com.ldtteam.structurize.api.RotationMirror.NONE;
                }

                @Override
                public boolean fancyPlacement() {
                    return true;
                }

                @Override
                public net.minecraft.world.level.block.state.BlockState getSolidBlockForPos(BlockPos pos, java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> world) {
                    return Blocks.STONE.defaultBlockState();
                }

                @Override
                public BlockPos getCenterPos() {
                    return BlockPos.ZERO;
                }

                @Override
                public com.ldtteam.structurize.blueprints.v1.Blueprint getBluePrint() {
                    return null;
                }
            };
            final BlockPos at = new BlockPos(0, 250, 0);
            for (final String name : Flights.designs()) {
                final com.asteain.airline.flight.AircraftType type = com.asteain.airline.flight.AircraftType.of(name);
                final java.util.Map<String, String> asked = new java.util.TreeMap<>();
                final java.util.List<String> free = new java.util.ArrayList<>(), belts = new java.util.ArrayList<>(), failed = new java.util.ArrayList<>();
                for (final String text : new java.util.TreeSet<>(type.parts.values())) {
                    final net.minecraft.world.level.block.state.BlockState state;
                    try {
                        state = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), text, false).blockState();
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                        failed.add(text);
                        continue;
                    }
                    final java.util.List<ItemStack> items;
                    try {
                        items = com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers.getRequiredItemsForState(level, at, state, null, context);
                    } catch (RuntimeException e) {
                        failed.add(text + " (" + e + ")");
                        continue;
                    }
                    final String block = text.split("\\[")[0];
                    final String what = items.stream().filter(i -> !i.isEmpty()).map(i -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i.getItem()) + " x" + i.getCount()).collect(java.util.stream.Collectors.joining(", "));
                    asked.merge(block, what.isEmpty() ? "(nothing)" : what, (a, b) -> a.equals(b) ? a : a + " | " + b);
                    if (what.isEmpty()) free.add(text);
                    if (block.equals("create:belt") && !what.contains("create:belt_connector")) belts.add(text + " -> " + (what.isEmpty() ? "(nothing)" : what));
                }
                s.note("OBS items " + name + ": " + asked);
                s.soft("items / " + name + ": every belt is asked for as a mechanical belt", belts.isEmpty() && failed.isEmpty(), "belts not asked as belts: " + belts + "; not read: " + failed);
                s.soft("items / " + name + ": no block is put up for nothing (each asks for an item)", free.isEmpty(), "asked for nothing: " + free);
            }
        });
    }

    private static void facilities(Script s, ServerLevel level) {
        final RouteNetwork net = RouteNetwork.get(level);
        final com.ldtteam.structurize.api.RotationMirror none = com.ldtteam.structurize.api.RotationMirror.NONE;
        final BlockPos centreM = new BlockPos(-20, Y, 25), centreL = new BlockPos(140, Y, 30);
        final Site medium = new Site("medium airfield", Facility.MEDIUM_AIRFIELD, "mediumairfield", new BlockPos(0, Y, 0), none);
        final Site large = new Site("large airfield", Facility.LARGE_AIRFIELD, "largeairfield", new BlockPos(160, Y, 0), none);
        final Site wood = new Site("wooden anchorage", Facility.ANCHORAGE, "anchorage", new BlockPos(-40, Y, 0), none);
        final Site mirrored = new Site("mirrored wooden anchorage", Facility.ANCHORAGE, "anchorage", new BlockPos(-40, Y, 75), com.ldtteam.structurize.api.RotationMirror.MIR_NONE);
        final Site stone = new Site("stone anchorage", Facility.ANCHORAGE, "anchoragestone", new BlockPos(110, Y, 0), none);
        final List<Site> all = List.of(medium, wood, mirrored, large, stone);

        s.until("facilities: the structure pack is there", () -> {}, () -> com.ldtteam.structurize.storage.StructurePacks.hasPack(Airline.PACK_NAME), 1200);
        s.step("facilities: two colonies; the blueprints are read", () -> {
            forceChunks(level, -80, -60, 220, 90);
            final IColony m = Fixture.create(level, centreM, "Airline facilities M"), l = Fixture.create(level, centreL, "Airline facilities L");
            Fixture.townHall(m, centreM);
            Fixture.townHall(l, centreL);
            for (Site site : all) {
                site.colony = site == large || site == stone ? l : m;
                for (int lv = 1; lv <= site.facility.maxLevel(); lv++)
                    site.blueprints[lv] = com.ldtteam.structurize.storage.StructurePacks.getBlueprintFuture(Airline.PACK_NAME, site.path(lv), level.registryAccess());
            }
        });
        s.until("facilities: the blueprints are read", () -> {}, () -> {
            for (Site site : all) for (int lv = 1; lv <= site.facility.maxLevel(); lv++) if (!site.blueprints[lv].isDone()) return false;
            return true;
        }, 1200);
        s.step("facilities: what the blueprints hold", () -> {
            for (Site site : all) {
                for (int lv = 1; lv <= site.facility.maxLevel(); lv++) {
                    final com.ldtteam.structurize.blueprints.v1.Blueprint bp = (com.ldtteam.structurize.blueprints.v1.Blueprint) site.blueprints[lv].get();
                    s.check(bp != null, site.name + ": the level " + lv + " blueprint " + site.path(lv) + " is read from the pack");
                    final net.minecraft.world.level.block.state.BlockState anchor = bp.getBlockState(bp.getPrimaryBlockOffset());
                    s.check(anchor.getBlock() == hutOf(site.facility), site.name + " level " + lv + ": its anchor is the facility's hut block (" + anchor + ")");
                    for (var info : bp.getBlockInfoAsList()) if (info.getState() != null && info.getState().getBlock() == ModBlocks.blockRack) site.racks[lv]++;
                }
                s.note("OBS facilities: " + site.name + " racks by level " + java.util.Arrays.toString(java.util.Arrays.copyOfRange(site.racks, 1, site.facility.maxLevel() + 1)));
            }
        });
        for (Site site : all) {
            s.step("facilities: " + site.name + ": level 1 is placed", () -> pasteBlueprint(s, level, site, 1, true));
            s.attempt("facilities: " + site.name + ": level 1 stands", () -> {}, () -> site.building() != null && site.building().getBuildingLevel() == 1
                    && site.building().getContainers().size() >= site.racks[1], 1200, () -> {});
            s.settle("facilities: " + site.name + ": a moment", 20);
            s.step("facilities: " + site.name + ": level 1", () -> checkSite(s, level, net, site, 1));
            for (int lv = 2; lv <= site.facility.maxLevel(); lv++) {
                final int target = lv;
                s.step("facilities: " + site.name + ": level " + target + " is placed over it", () -> {
                    final BuildingAirfield field = site.building();
                    final String asked = com.minecolonies.core.colony.workorders.WorkOrderBuilding.create(com.minecolonies.api.colony.workorders.WorkOrderType.UPGRADE, field).getStructurePath();
                    s.soft(site.name + " / at level " + (target - 1) + " an upgrade asks for the level " + target + " blueprint", asked.endsWith(site.schematic + target + ".blueprint"), asked);
                    pasteBlueprint(s, level, site, target, false);
                });
                s.attempt("facilities: " + site.name + ": level " + target + " stands", () -> {}, () -> site.building().getBuildingLevel() == target
                        && site.building().getContainers().size() >= site.racks[target], 1200, () -> {});
                s.settle("facilities: " + site.name + ": a moment at level " + target, 20);
                s.step("facilities: " + site.name + ": level " + target, () -> checkSite(s, level, net, site, target));
            }
        }

        // ---- flights: the medium aircraft to the anchorages; the pilot steps onto the pier
        if (Flights.classOf(design()) != ShipClass.MEDIUM) {
            s.step("facilities: no flights", () -> s.note("OBS facilities: the design " + design() + " is not a medium aircraft (-Paircraft): the flights to the anchorages are left out"));
            return;
        }
        final Field fm = new Field(medium.hut), fl = new Field(large.hut);
        flyToPier(s, level, medium, wood, fm, "medium", design());
        // From the large airfield the large aircraft (2026-10-15, Codex's design; the medium one where there is none).
        flyToPier(s, level, large, stone, fl, "large", Flights.designs().contains("oonagi") ? "oonagi" : design());
        fleet(s, level, medium, wood, mirrored, large, fm);
    }

    /**
     * An airfield's aircraft beyond its pad (2026-10-17, the user's ruling "判断は一旦全部推奨で"): a medium or large aircraft
     * built by the builders moves to the nearest free anchorage of its colony, which becomes its home, and the airfield's pad
     * is free for the next one. An airfield hires a pilot for its pad and one for each anchorage of its colony.
     */
    private static void fleet(Script s, ServerLevel level, Site medium, Site wood, Site mirrored, Site large, Field fm) {
        final BlockPos[] was = new BlockPos[1];
        final AircraftBlockEntity[] second2 = new AircraftBlockEntity[1];
        final com.minecolonies.api.colony.ICitizenData[] secondPilot = new com.minecolonies.api.colony.ICitizenData[1];
        s.step("facilities: fleet: pilots; the medium aircraft counts as built by the builders", () -> {
            final int mediumPilots = medium.building().getFirstModuleOccurance(WorkerBuildingModule.class).getModuleMax();
            final int largePilots = large.building().getFirstModuleOccurance(WorkerBuildingModule.class).getModuleMax();
            s.soft("facilities / fleet: an airfield hires a pilot for its pad and one for each anchorage of its colony (medium 1 + 2, large 1 + 1)", mediumPilots == 3 && largePilots == 2,
                    "medium airfield " + mediumPilots + ", large airfield " + largePilots);
            was[0] = fm.aircraft.home;
            Flights.markBuiltForChecks(fm.aircraft);
        });
        s.until("facilities: fleet: the medium aircraft moves to an anchorage", () -> {}, () -> fm.aircraft.home != null && !fm.aircraft.home.equals(was[0])
                && fm.aircraft.phase == Phase.PARKED && fm.aircraft.home.equals(fm.aircraft.at), 12000);
        s.step("facilities: fleet: result", () -> {
            final BlockPos woodPad = wood.building().pad(), mirroredPad = mirrored.building().pad();
            s.soft("facilities / fleet: built, the medium aircraft took the nearest free anchorage of its colony (the wooden one) as its home and flew there; its pilot got off onto the pier",
                    woodPad.equals(fm.aircraft.home) && Boarding.state(fm.pilot) == Boarding.State.NONE && fm.pilot.isAlive(),
                    "home " + fm.aircraft.home + " (wooden " + woodPad + ", mirrored " + mirroredPad + "); pilot " + Boarding.state(fm.pilot) + " | " + fm.aircraft.note);
            final Flights.Answer second = Flights.spawn(level, was[0], design());
            AircraftBlockEntity made = null;
            for (AircraftBlockEntity each : Flights.aircraft(level)) if (was[0].equals(each.home)) made = each;
            s.soft("facilities / fleet: the airfield's pad is free for the next one: a second medium aircraft is made there and keeps it as its home (it was made by command, not built)",
                    second.ok() && made != null && made != fm.aircraft && !made.newlyBuilt, second.text().getString());
            s.soft("facilities / fleet: the airfield counts one aircraft whose home is one of its colony's anchorages", Flights.anchoredFleet(level, medium.building()) == 1,
                    "counted " + Flights.anchoredFleet(level, medium.building()));
            for (String l : fm.log) if (l.contains("->")) s.note("OBS facilities fleet: " + l);
            second2[0] = made;
            secondPilot[0] = Fixture.spawn(medium.colony, level, medium.hut.offset(3, 0, 2));
        });
        // ---- the buttons of the aircraft tab (2026-10-18, the user's ruling "作る"): to an anchorage, and back to the airfield
        s.until("facilities: fleet: a second pilot", () -> {}, () -> Fixture.entity(secondPilot[0]) != null, 200);
        s.step("facilities: fleet: hire it; the button: the second aircraft to an anchorage", () -> {
            s.check(medium.building().getFirstModuleOccurance(WorkerBuildingModule.class).assignCitizen(secondPilot[0]), "the second pilot is hired");
            final boolean[] can = Flights.fleetMoves(level, medium.building());
            s.soft("facilities / fleet: the tab offers to move the airfield's aircraft to an anchorage (one is free), not to bring one back (the pad is taken)", can[0] && !can[1], can[0] + ", " + can[1]);
            final String said = Flights.toAnchorage(level, medium.building(), null);
            s.soft("facilities / fleet: by the button the airfield's aircraft is sent to a free anchorage", "moving".equals(said), said);
        });
        s.until("facilities: fleet: the second aircraft at the other anchorage", () -> {}, () -> second2[0].home != null && second2[0].home.equals(mirrored.building().pad())
                && second2[0].phase == Phase.PARKED && second2[0].home.equals(second2[0].at), 12000);
        s.step("facilities: fleet: the button: one back to the airfield", () -> {
            s.soft("facilities / fleet: at the mirrored anchorage (its pier on the other side) the aircraft's other connector joined the pier's", Flights.docked(second2[0]),
                    "home " + second2[0].home + " | " + second2[0].note);
            final boolean[] can = Flights.fleetMoves(level, medium.building());
            s.soft("facilities / fleet: with the pad free the tab offers to bring one back from an anchorage", !can[0] && can[1], can[0] + ", " + can[1]);
            final String said = Flights.toAirfield(level, medium.building(), null);
            s.soft("facilities / fleet: by the button an aircraft at an anchorage is sent back to the airfield", "returning".equals(said), said);
        });
        s.until("facilities: fleet: one back at the airfield", () -> {}, () -> {
            for (AircraftBlockEntity each : Flights.aircraft(level)) if (was[0].equals(each.home) && was[0].equals(each.at) && each.phase == Phase.PARKED) return true;
            return false;
        }, 12000);
        s.step("facilities: fleet: buttons, result", () -> {
            int atAnchorages = Flights.anchoredFleet(level, medium.building());
            s.soft("facilities / fleet: one aircraft is home at the airfield again, the other still at an anchorage", atAnchorages == 1, "at anchorages " + atAnchorages);
        });
    }

    /** Places the blueprint of that level at the site (Structurize, at once); the first placement registers the building. */
    private static void pasteBlueprint(Script s, ServerLevel level, Site site, int lv, boolean first) throws Exception {
        final com.ldtteam.structurize.blueprints.v1.Blueprint bp = (com.ldtteam.structurize.blueprints.v1.Blueprint) site.blueprints[lv].get();
        bp.setRotationMirror(site.turn, level);
        final com.minecolonies.api.blocks.AbstractBlockHut<?> hut = (com.minecolonies.api.blocks.AbstractBlockHut<?>) hutOf(site.facility);
        final net.minecraft.server.level.ServerPlayer owner = (net.minecraft.server.level.ServerPlayer) Fixture.owner(level);
        if (first) {
            owner.setPos(site.hut.getX() + 0.5, site.hut.getY(), site.hut.getZ() + 0.5);
            s.check(hut.setup(owner, level, site.hut, bp, site.turn, true, Airline.PACK_NAME, site.path(lv)), site.name + ": the hut accepts the placement (permission, colony, building registered)");
        }
        com.ldtteam.structurize.management.Manager.addToQueue(new com.ldtteam.structurize.operations.PlaceStructureOperation(
                new com.ldtteam.structurize.placement.StructurePlacer(hut.getStructureHandler(level, site.hut, bp, site.turn, true)), owner));
    }

    /** What a placed facility has to be at that level. */
    private static void checkSite(Script s, ServerLevel level, RouteNetwork net, Site site, int lv) {
        final BuildingAirfield field = site.building();
        s.check(field != null, site.name + ": the building is an airfield building");
        field.registerPad();
        final String name = site.name + " level " + lv;
        int racks = 0;
        for (BlockPos spot : field.getContainers()) if (level.getBlockState(spot).getBlock() == ModBlocks.blockRack) racks++;
        s.soft(name + " / it is the facility it was built as, at the level of its blueprint, with the racks of its blueprint as its containers",
                field.facility() == site.facility && field.getBuildingLevel() == lv && field.getMaxBuildingLevel() == site.facility.maxLevel() && racks == site.racks[lv]
                        && level.getBlockEntity(site.hut) instanceof com.asteain.airline.block.AirfieldBlockEntity,
                field.facility() + " level " + field.getBuildingLevel() + " of " + field.getMaxBuildingLevel() + ", racks " + racks + " of " + site.racks[lv]);
        final StringBuilder pads = new StringBuilder();
        boolean good = field.pads().size() == (site.facility.airfield() && lv >= com.asteain.airline.route.RouteRules.GUEST_PAD_LEVEL ? 2 : 1);
        for (BlockPos pad : field.pads()) {
            final RouteNetwork.Node node = net.node(pad);
            good &= node != null && node.facility() == site.facility && !Flights.berthMissing(level, pad);
            pads.append(pad.toShortString()).append(node == null ? " not on the network" : " " + node.facility()).append(Flights.berthMissing(level, pad) ? " NOTHING TO BERTH AT" : "").append("; ");
        }
        s.soft(name + " / its pads are where the code expects them, on the network as this facility, each with a floor (an anchorage: a pier beside its berth)", good, pads.toString());
        if (site.facility.airfield()) {
            s.soft(name + " / it has an import stash", com.asteain.airline.flight.Cargo.importStore(level, field) != null, "stash " + (com.asteain.airline.flight.Cargo.importStore(level, field) != null));
        } else {
            final int[] side = Flights.pierSide(level, field.pad());
            // The hut's own direction to the pier: west of the berth as built (x -1), east when mirrored.
            final int expected = site.turn == com.ldtteam.structurize.api.RotationMirror.NONE ? -1 : 1;
            s.soft(name + " / the pier is found on the side the building has it" + (site.turn == com.ldtteam.structurize.api.RotationMirror.NONE ? "" : " (mirrored: the other side)"),
                    side != null && side[0] == expected && side[1] == 0, side == null ? "no pier" : side[0] + ", " + side[1]);
        }
    }

    /** A pilot and the medium aircraft at the airfield; a round trip by command to the anchorage: held beside the pier, the pilot gets off onto it. */
    private static void flyToPier(Script s, ServerLevel level, Site home, Site anchorage, Field f, String name, String designName) {
        s.step("facilities: " + name + ": a pilot and the aircraft", () -> {
            f.colony = home.colony;
            f.airfield = home.building();
            f.pad = f.airfield.pad();
            f.pilotData = Fixture.spawn(f.colony, level, home.hut.offset(2, 0, 2));
            final Flights.Answer made = Flights.spawn(level, f.pad, designName);
            s.soft("facilities / " + name + ": the aircraft (" + designName + ", " + Flights.classOf(designName) + ") is made on the airfield's pad", made.ok(), made.text().getString());
            for (AircraftBlockEntity each : Flights.aircraft(level)) if (f.pad.equals(each.home)) f.aircraft = each;
            s.check(f.aircraft != null, name + ": the new aircraft's home is this pad");
        });
        s.until("facilities: " + name + ": pilot entity", () -> {}, () -> (f.pilot = Fixture.entity(f.pilotData)) != null, 200);
        s.step("facilities: " + name + ": hire the pilot", () -> s.check(f.airfield.getFirstModuleOccurance(WorkerBuildingModule.class).assignCitizen(f.pilotData), "the pilot is hired"));
        loaded(s, "facilities " + name, () -> f.aircraft);
        s.settle("facilities: " + name + ": the balloon fills", 320);
        final double[] onPier = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        final boolean[] fixedThere = new boolean[1], joinedThere = new boolean[1];
        final int[] redoneBefore = new int[1];
        s.step("facilities: " + name + ": a round trip to the anchorage", () -> {
            watch(s, f, "facilities " + name);
            s.monitor(() -> {
                if (Double.isNaN(onPier[0]) && f.aircraft.phase == Phase.WAIT && anchorage.building().pad().equals(f.aircraft.at)) {
                    onPier[0] = f.pilot.getX();
                    onPier[1] = f.pilot.getY();
                    onPier[2] = f.pilot.getZ();
                    final double[] r = Flights.readings(f.aircraft);
                    fixedThere[0] = r != null && r[4] > 0;
                    onPier[3] = r == null ? Double.NaN : r[6];
                    joinedThere[0] = Flights.docked(f.aircraft);
                }
            });
            final RouteNetwork routes = RouteNetwork.get(level);
            final Edge line = routes.edge(f.pad, anchorage.building().pad());
            s.note("OBS facilities " + name + ": the line to the " + anchorage.name + ": " + (line == null ? "none" : line.state() + ", serves " + line.serves()
                    + ", closed from " + line.closedFrom() + " (by a block at " + line.blockedFromAt() + ", or a pad at " + line.barredBy() + "), blocked at " + line.blockedAt()) + "; route for " + Flights.classOf(designName) + ": "
                    + routes.route(f.pad, anchorage.building().pad(), Flights.classOf(designName)));
            final Flights.Answer sent = Flights.send(level, f.pad, anchorage.building().pad(), true, f.pilot);
            s.soft("facilities / " + name + ": a round trip to the " + anchorage.name + " is ordered", sent.ok(), sent.text().getString());
            // The large aircraft is made to go across its home pad again once (2026-10-17: never seen over a large pad yet).
            if (name.equals("large")) {
                redoneBefore[0] = Flights.acrossRedoneForChecks();
                Flights.redoAcrossOnceForChecks();
            }
        });
        s.until("facilities: " + name + ": there, the pilot off and on again, and back", () -> {}, () -> f.clock > 100 && !Double.isNaN(onPier[0]) && f.aircraft.phase == Phase.PARKED && f.pad.equals(f.aircraft.at), 16000);
        s.step("facilities: " + name + ": result", () -> {
            final BlockPos pad = anchorage.building().pad();
            final double across = Math.hypot(onPier[0] - (pad.getX() + 0.5), onPier[2] - (pad.getZ() + 0.5));
            final boolean onTheDeck = onPier[1] > pad.getY() + 1.9 && onPier[1] < pad.getY() + 2.6 && across > 6.5 && across < 12;
            s.soft("facilities / " + name + ": at the " + anchorage.name + " the aircraft was held fixed in the air beside the pier, and the pilot got off onto the pier",
                    fixedThere[0] && onTheDeck && f.pilot.isAlive(), String.format("fixed %s; the pilot stood at (%.1f, %.1f, %.1f), %.1f across from the berth %s",
                            fixedThere[0], onPier[0], onPier[1], onPier[2], across, pad.toShortString()));
            s.soft("facilities / " + name + ": at the " + anchorage.name + " the aircraft's connector was joined to the pier's (built from the blueprint), its own block within 0.1 of where it belongs",
                    joinedThere[0] && onPier[3] < 0.1, "joined " + joinedThere[0] + ", " + onPier[3] + " from where its own block belongs");
            s.soft("facilities / " + name + ": then it came home with its pilot", f.pad.equals(f.aircraft.at) && f.pilot.isAlive(), "at " + f.aircraft.at);
            if (name.equals("large")) {
                final double[] r = Flights.readings(f.aircraft);
                final int redone = Flights.acrossRedoneForChecks() - redoneBefore[0];
                s.soft("facilities / large: over its home pad (a large airfield's) it went across again (made to, for the check), still came down within 0.5 of the middle, and its pilot got off",
                        redone >= 1 && r != null && r[6] <= 0.5 && Boarding.state(f.pilot) == Boarding.State.NONE,
                        "went across again " + redone + " times; " + (r == null ? "?" : String.format("landed %.2f from the middle", r[6])) + "; pilot " + Boarding.state(f.pilot));
            }
            for (String l : f.log) if (l.contains("->")) s.note("OBS facilities " + name + ": " + l);
            s.clearMonitors();
        });
    }

    // ---- size classes: which aircraft may berth where (the user's ruling of 2026-10-14) ---------------------------

    /**
     * A bare landing pad of that kind of place (no building), on the network: a stone floor of the place's size; for an
     * anchorage no floor but a pier beside it (3 wide, as long as a large aircraft and more, a block higher than the pad).
     */
    private static void pad(ServerLevel level, BlockPos floor, int heading, Facility facility) {
        if (facility.airborne()) {
            final double h = Math.toRadians(heading);
            final int ax = (int) Math.round(Math.cos(h)), az = (int) Math.round(Math.sin(h));
            final int[] side = {az, -ax};
            for (int along = -14; along <= 14; along++) for (int across = Facility.PIER_FROM_PAD; across <= Facility.PIER_FROM_PAD + 2; across++)
                level.setBlockAndUpdate(floor.offset(ax * along + side[0] * across, 1, az * along + side[1] * across), Blocks.OAK_PLANKS.defaultBlockState());
            pierConnector(level, floor.offset(side[0] * Facility.PIER_FROM_PAD, 1, side[1] * Facility.PIER_FROM_PAD), net.minecraft.core.Direction.fromDelta(-side[0], 0, -side[1]),
                    floor.offset(side[0] * (Facility.PIER_FROM_PAD + 1), 1, side[1] * (Facility.PIER_FROM_PAD + 1)));
        } else {
            final int half = facility.padHalfSize();
            for (int x = -half; x <= half; x++) for (int z = -half; z <= half; z++) level.setBlockAndUpdate(floor.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        }
        RouteNetwork.get(level).setAirfield(floor, heading, 1, floor, facility);
    }

    /** An anchorage's ground and pier (its hut not turned): a little ground at the hut, the pier north from it into the air, west of the berth. */
    private static void pier(ServerLevel level, BlockPos hut) {
        Fixture.platform(level, hut.offset(2, 0, 4), 5, 4);
        for (int z = hut.getZ() - 33; z <= hut.getZ() - 1; z++) for (int x = hut.getX() - 1; x <= hut.getX() + 1; x++)
            level.setBlockAndUpdate(new BlockPos(x, hut.getY() - 1, z), Blocks.OAK_PLANKS.defaultBlockState());
        // The pier's connector, on its east edge beside the berth, always out (as the anchorage's blueprint has it).
        final BlockPos berth = hut.offset(Facility.ANCHORAGE_BERTH);
        pierConnector(level, new BlockPos(hut.getX() + 1, hut.getY() - 1, berth.getZ()), net.minecraft.core.Direction.EAST, new BlockPos(hut.getX(), hut.getY() - 1, berth.getZ()));
    }

    /**
     * A pier's connector (2026-10-18): Simulated's connector facing the berth, and a lever (on) on the pier's floor block
     * {@code inside} it, so that it is always out (as in the anchorage's blueprint).
     */
    private static void pierConnector(ServerLevel level, BlockPos at, net.minecraft.core.Direction facing, BlockPos inside) {
        level.setBlockAndUpdate(inside.above(), Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(net.minecraft.world.level.block.LeverBlock.FACING, facing).setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true));
        net.minecraft.world.level.block.state.BlockState connector = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("simulated:docking_connector")).defaultBlockState();
        for (final net.minecraft.world.level.block.state.properties.Property<?> property : connector.getProperties()) {
            if (property.getName().equals("facing") && property instanceof net.minecraft.world.level.block.state.properties.DirectionProperty direction) connector = connector.setValue(direction, facing);
            if (property.getName().equals("powered") && property instanceof net.minecraft.world.level.block.state.properties.BooleanProperty on) connector = connector.setValue(on, true);
        }
        level.setBlockAndUpdate(at, connector);
    }

    /** The hut block of each kind of place. */
    private static com.minecolonies.api.blocks.AbstractColonyBlock<?> hutOf(Facility facility) {
        return switch (facility) {
            case SMALL_AIRFIELD -> Airline.AIRFIELD_HUT.get();
            case MEDIUM_AIRFIELD -> Airline.MEDIUM_AIRFIELD_HUT.get();
            case LARGE_AIRFIELD -> Airline.LARGE_AIRFIELD_HUT.get();
            case ANCHORAGE -> Airline.ANCHORAGE_HUT.get();
        };
    }

    /** Stone under a place's hut and its first landing pad, and between them (the hut not turned: the pad lies north of it). */
    private static void floorFor(ServerLevel level, BlockPos hut, Facility facility) {
        final int half = facility.padHalfSize();
        Fixture.platform(level, hut.offset(1, 0, -(half + 2)), Math.max(half, 8), half + 6);
    }

    /** The place's export list; null for one that keeps none (an anchorage). */
    private static ExportStockModule exportOf(BuildingAirfield place) {
        return place.hasModule(ExportStockModule.class) ? place.getFirstModuleOccurance(ExportStockModule.class) : null;
    }

    private static void forceChunks(ServerLevel level, int x0, int z0, int x1, int z1) {
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
    }

    /**
     * The size classes (run with the medium stand-in: classes@<kokaze_medium.json>). First the whole table on bare pads:
     * every class sent to every kind of place, the class each line is for, the ways that are open to each class, what a
     * pad and a design say about the class, and aircraft made by command where their class may not berth. Then colonies:
     * a medium airfield with its own medium aircraft and a pilot; colony N with an anchorage only (not on the network
     * without an airfield), then with a small airfield as well; where the dispatcher sends goods for N; what the windows
     * offer and refuse to build; a small aircraft refused at the anchorage; the medium aircraft's round trip to the
     * anchorage; and a flight to the anchorage while N loses its airfield, which turns back home.
     */
    private static void classes(Script s, ServerLevel level) {
        final RouteNetwork net = RouteNetwork.get(level);
        final WantLedger ledger = WantLedger.get(level.getServer());
        final Facility[] places = Facility.values();
        final ShipClass[] sizes = ShipClass.values();
        final String small = com.asteain.airline.flight.AircraftType.STANDARD;

        // ---- 1. the table, on bare pads around a grade 3 buoy (far from the colonies of part 2: no line between them)
        final BlockPos hub = new BlockPos(5000, Y, 5000);
        final BlockPos[] bare = new BlockPos[places.length];
        s.step("classes: a grade 3 buoy and a bare pad of each kind of place around it", () -> {
            s.check(Flights.classOf(design()) == ShipClass.MEDIUM && Flights.classOf(small) == ShipClass.SMALL,
                    "this run flies a medium aircraft (" + design() + ": " + Flights.classOf(design()) + "), and " + small + " is a small one (" + Flights.classOf(small) + ")");
            forceChunks(level, hub.getX() - 80, hub.getZ() - 80, hub.getX() + 80, hub.getZ() + 80);
            level.setBlockAndUpdate(hub, Airline.BUOYS.get(2).get().defaultBlockState());
            final int[][] around = {{-60, 0}, {0, -60}, {60, 0}, {0, 60}};
            for (int i = 0; i < places.length; i++) {
                bare[i] = hub.offset(around[i][0], -1, around[i][1]);
                pad(level, bare[i], 0, places[i]);
            }
            s.check(net.node(hub) != null && net.node(hub).kind() == RouteNetwork.Kind.BUOY, "the buoy is a point of the network");
        });
        s.until("classes: the lines around the buoy are looked at", () -> {}, () -> net.pending() == 0, 6000);
        s.step("classes: the table, the lines, the ways", () -> {
            for (int i = 0; i < places.length; i++) {
                final Edge line = net.edge(hub, bare[i]);
                s.soft("classes / the line from a buoy to a pad of a " + places[i] + " is for aircraft up to " + places[i].largest() + ", and clear",
                        line != null && line.serves() == places[i].largest() && line.state() == State.CLEAR, line == null ? "no line" : line.serves() + ", " + line.state() + " " + line.blockedAt());
                for (final ShipClass size : sizes) {
                    final boolean may = places[i].berths(size);
                    final String got = Flights.landingFor(level, hub, bare[i], size);
                    s.soft("classes / table: a " + size + " aircraft sent to a pad of a " + places[i] + (may ? " lands there" : " is refused: no berth for its class"),
                            may ? "pad".equals(got) : "flight.no_berth".equals(got), got);
                }
            }
            final BlockPos sm = bare[0], md = bare[1], lg = bare[2], an = bare[3];
            s.soft("classes / to a medium airfield's pad a large aircraft has no way (its lines are for medium aircraft at most); a medium one has",
                    net.route(hub, md, ShipClass.LARGE).isEmpty() && net.route(hub, md, ShipClass.MEDIUM).equals(List.of(hub, md)),
                    net.route(hub, md, ShipClass.LARGE) + " / " + net.route(hub, md, ShipClass.MEDIUM));
            s.soft("classes / out of a small airfield's pad only small aircraft have a way (its lines are for them)",
                    net.route(sm, lg, ShipClass.MEDIUM).isEmpty() && net.route(sm, an, ShipClass.LARGE).isEmpty() && net.route(sm, lg, ShipClass.SMALL).size() >= 2,
                    net.route(sm, lg, ShipClass.MEDIUM) + " / " + net.route(sm, an, ShipClass.LARGE) + " / " + net.route(sm, lg, ShipClass.SMALL));
            s.soft("classes / between the medium airfield and the anchorage medium aircraft have a way, large ones none; between the large airfield and the anchorage large ones have",
                    net.route(md, an, ShipClass.MEDIUM).size() >= 2 && net.route(md, an, ShipClass.LARGE).isEmpty() && net.route(lg, an, ShipClass.LARGE).size() >= 2,
                    net.route(md, an, ShipClass.MEDIUM) + " / " + net.route(md, an, ShipClass.LARGE) + " / " + net.route(lg, an, ShipClass.LARGE));
            final java.util.function.BiFunction<BlockPos, BlockPos, ShipClass> common = (x, y) -> RouteNetwork.common(net.node(x), net.node(y));
            s.soft("classes / the largest class two pads have in common: small-large none, small-anchorage none, small-medium small, medium-anchorage medium, large-anchorage large",
                    common.apply(sm, lg) == null && common.apply(sm, an) == null && common.apply(sm, md) == ShipClass.SMALL && common.apply(md, an) == ShipClass.MEDIUM && common.apply(lg, an) == ShipClass.LARGE,
                    common.apply(sm, lg) + " " + common.apply(sm, an) + " " + common.apply(sm, md) + " " + common.apply(md, an) + " " + common.apply(lg, an));
            // What each kind of place is (the user's matrix): connectors and building.
            boolean right = true;
            final StringBuilder seen = new StringBuilder();
            for (final Facility place : places) {
                for (int lv = 1; lv <= 5; lv++) {
                    final boolean floor = place.floorConnector(lv), side = place.sideConnector();
                    right &= switch (place) {
                        case SMALL_AIRFIELD -> floor == (lv >= 3) && !side;
                        case MEDIUM_AIRFIELD -> floor && !side;
                        case LARGE_AIRFIELD, ANCHORAGE -> !floor && side;
                    };
                    seen.append(place).append(lv).append(floor ? "F" : "-").append(side ? "S" : "-").append(' ');
                }
            }
            s.soft("classes / connectors: a small airfield has one in the floor from level 3, a medium airfield always; a large airfield and an anchorage have side connectors and none in the floor", right, seen.toString());
            final String builds = java.util.Arrays.stream(places).map(p -> p + ":" + java.util.Arrays.stream(sizes).filter(p::builds).map(Enum::name).toList()).toList().toString();
            s.soft("classes / what each kind of place builds: small airfield small; medium airfield small and medium; large airfield medium and large; anchorage nothing",
                    builds.equals("[SMALL_AIRFIELD:[SMALL], MEDIUM_AIRFIELD:[SMALL, MEDIUM], LARGE_AIRFIELD:[MEDIUM, LARGE], ANCHORAGE:[]]"), builds);
            // Saved and read back: the kind of place and the class each line is for.
            final RouteNetwork copy = RouteNetwork.load(net.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess()), level.registryAccess());
            boolean kept = true;
            final StringBuilder after = new StringBuilder();
            for (int i = 0; i < places.length; i++) {
                final RouteNetwork.Node node = copy.node(bare[i]);
                final Edge line = copy.edge(hub, bare[i]);
                kept &= node != null && node.facility() == places[i] && line != null && line.serves() == places[i].largest();
                after.append(node == null ? "none" : node.facility()).append('/').append(line == null ? "no line" : line.serves()).append(' ');
            }
            s.soft("classes / saved and read back, each pad is still the kind of place it was and its line is for the same classes", kept, after.toString());
            // Made by command where the class may not berth: refused, and nothing is made.
            final Flights.Answer mediumAtSmall = Flights.spawn(level, sm, design()), smallAtLarge = Flights.spawn(level, lg, small), smallAtAnchorage = Flights.spawn(level, an, small),
                    mediumAtAnchorage = Flights.spawn(level, an, design());
            s.soft("classes / an aircraft made by command on a pad that does not take its class is refused (medium on a small airfield's pad, small on a large airfield's); none is made at an anchorage (no home there)",
                    !mediumAtSmall.ok() && !smallAtLarge.ok() && !smallAtAnchorage.ok() && !mediumAtAnchorage.ok() && "flight.no_berth".equals(mediumAtSmall.key()) && "flight.no_berth".equals(smallAtLarge.key())
                            && "flight.anchorage_no_home".equals(smallAtAnchorage.key()) && "flight.anchorage_no_home".equals(mediumAtAnchorage.key()) && Flights.aircraft(level).isEmpty(),
                    mediumAtSmall.key() + " / " + smallAtLarge.key() + " / " + smallAtAnchorage.key() + " / " + mediumAtAnchorage.key() + ", " + Flights.aircraft(level).size() + " aircraft");
        });

        // ---- 1b. lines are verified for the class they are for (the user's ruling of 2026-10-15); buoys by grade
        final BlockPos smallBuoy = hub.offset(0, 0, -100);
        final BlockPos onlyLarge = hub.offset(30, 20, 15);
        final BlockPos[] shafts = new BlockPos[2];
        s.step("classes: the heights of the lines; a grade 1 buoy; something in the large aircraft's tube only", () -> {
            // Over a pad: 18 (small airfield), 23 (medium), 29 (large airfield, anchorage); over a buoy: 9, 11, 13 by grade.
            // (16, 22, 28 and 8, 10, 12 until the small aircraft became 10 high, 2026-10-15.)
            final double[] heights = new double[places.length];
            for (int i = 0; i < places.length; i++) heights[i] = net.waypoint(bare[i]).y - bare[i].getY() - 0.5;
            final double overHub = net.waypoint(hub).y - hub.getY() - 0.5;
            s.soft("classes / the lines pass 18, 23, 29 and 29 above the pads of a small, medium and large airfield and an anchorage, and 13 above a grade 3 buoy",
                    heights[0] == 18 && heights[1] == 23 && heights[2] == 29 && heights[3] == 29 && overHub == 13,
                    java.util.Arrays.toString(heights) + ", buoy " + overHub);
            forceChunks(level, smallBuoy.getX() - 16, smallBuoy.getZ() - 16, smallBuoy.getX() + 16, smallBuoy.getZ() + 16);
            level.setBlockAndUpdate(smallBuoy, Airline.BUOYS.get(0).get().defaultBlockState());
            final Edge toMedium = net.edge(smallBuoy, bare[1]);
            s.soft("classes / a grade 1 buoy's lines are for small aircraft only (its line to the medium airfield too), and it is passed 9 above (8 until the small aircraft became 10 high)",
                    toMedium != null && toMedium.serves() == ShipClass.SMALL && net.waypoint(smallBuoy).y - smallBuoy.getY() - 0.5 == 9,
                    toMedium == null ? "no line" : toMedium.serves() + ", " + (net.waypoint(smallBuoy).y - smallBuoy.getY() - 0.5));
            // On the line from the buoy to the large airfield's pad, half way, 15 to the side: in the large aircraft's tube (17), not in the medium one's (12).
            // (2026-10-18: lines are level and pass over what is in their way: the line is raised for every class.)
            level.setBlockAndUpdate(onlyLarge, Blocks.STONE.defaultBlockState());
            final Edge line = net.edge(hub, bare[2]);
            s.note("OBS classes: the line buoy - large airfield passes " + line.tube().from + " to " + line.tube().to + "; the stone is at " + onlyLarge.toShortString());
            s.soft("classes / a block in the large aircraft's tube has the line looked at again at once", line.state() == State.UNVERIFIED, line.state() + ", closed from " + line.closedFrom());
        });
        s.until("classes: the line is looked at with the block", () -> {}, () -> net.pending() == 0, 6000);
        s.step("classes: raised over the block; blocks over the large airfield's pad", () -> {
            final Edge line = net.edge(hub, bare[2]);
            // The line is 28.5 over the buoy's block, the stone 20: the large tube takes in blocks to 11 below the line, so the line goes 4 higher.
            s.soft("classes / the line is raised over the block for every class (4) and large aircraft may fly it", line.state() == State.CLEAR && line.lift() == 4 && line.opens(ShipClass.LARGE) && line.closedFrom() == null,
                    line.state() + ", raised " + line.lift() + ", closed from " + line.closedFrom());
            // In the shaft over the large airfield's pad: 15 to the side (in the large aircraft's shaft, 17, not in the medium one's, 12), and 3.
            shafts[0] = bare[2].offset(0, 10, 15);
            shafts[1] = bare[2].offset(0, 10, 3);
            level.setBlockAndUpdate(shafts[0], Blocks.STONE.defaultBlockState());
            s.soft("classes / a block in the large aircraft's shaft over the pad only keeps large aircraft off the line at once; medium ones may still fly it",
                    line.state() != State.BLOCKED && !line.opens(ShipClass.LARGE) && line.opens(ShipClass.MEDIUM) && line.closedFrom() == ShipClass.LARGE,
                    line.state() + ", closed from " + line.closedFrom());
            level.setBlockAndUpdate(shafts[1], Blocks.STONE.defaultBlockState());
            s.soft("classes / a block in the small aircraft's shaft over the pad blocks the line for every class", line.state() == State.BLOCKED && !line.opens(ShipClass.MEDIUM),
                    line.state() + " at " + line.blockedAt());
            level.removeBlock(shafts[1], false);
            level.removeBlock(shafts[0], false);
            level.removeBlock(onlyLarge, false);
        });
        s.until("classes: the line is looked at again", () -> {}, () -> net.pending() == 0, 6000);
        s.step("classes: the line is open again", () -> {
            final Edge line = net.edge(hub, bare[2]);
            s.soft("classes / with the blocks gone and the line looked at again, large aircraft may fly it again, and it is down again", line.state() == State.CLEAR && line.opens(ShipClass.LARGE) && line.closedFrom() == null && line.lift() == 0,
                    line.state() + ", raised " + line.lift() + ", closed from " + line.closedFrom());
        });

        // ---- 2. colonies: M has a medium airfield with its own medium aircraft; N an anchorage, later a small airfield too
        final Field m = new Field(new BlockPos(0, Y, 40));
        addField(s, level, m, "classes M", Facility.MEDIUM_AIRFIELD);
        final BlockPos hutN = new BlockPos(130, Y, 40), hutS = new BlockPos(100, Y, 40), between = new BlockPos(65, Y, 0);
        final IColony[] colonyN = new IColony[1];
        final BuildingAirfield[] anchorage = new BuildingAirfield[1], smallN = new BuildingAirfield[1];
        final AbstractBuilding[] stashN = new AbstractBuilding[1];
        final TileEntityRack[] rack = new TileEntityRack[1];
        s.step("classes: colony N with an anchorage and no airfield; a buoy between the colonies", () -> {
            pier(level, hutN);
            colonyN[0] = Fixture.create(level, hutN.offset(4, 0, 4), "Airline classes N");
            Fixture.townHall(colonyN[0], hutN.offset(4, 0, 4));
            anchorage[0] = (BuildingAirfield) Fixture.placeHut(level, colonyN[0], Airline.ANCHORAGE_HUT.get(), hutN);
            anchorage[0].setBuildingLevel(1);
            anchorage[0].setCorners(hutN.offset(-3, 0, -3), hutN.offset(3, 2, 3));
            anchorage[0].registerPad();
            forceChunks(level, -16, -16, 160, 47);
            level.setBlockAndUpdate(between, Airline.BUOYS.get(2).get().defaultBlockState());
            s.soft("classes / the anchorage's hut is an anchorage; its pad lies where an anchorage's lies", anchorage[0].facility() == Facility.ANCHORAGE && anchorage[0].pad().equals(hutN.offset(Facility.ANCHORAGE.padFromHut())),
                    anchorage[0].facility() + " pad " + anchorage[0].pad().toShortString());
            s.soft("classes / an anchorage in a colony without an airfield is not on the network", net.node(anchorage[0].pad()) == null, String.valueOf(net.node(anchorage[0].pad())));
            final String asked = Flights.requestAircraft(level, anchorage[0], small, null), askedMedium = Flights.requestAircraft(level, anchorage[0], design(), null);
            s.soft("classes / an anchorage builds nothing: its window offers no design, and asked for one it says that class is not built here",
                    Flights.designCards(anchorage[0]).isEmpty() && "class_not_here".equals(asked) && "class_not_here".equals(askedMedium), Flights.designCards(anchorage[0]) + " / " + asked + " / " + askedMedium);
            final List<String> offered = Flights.designCards(m.airfield).stream().map(Flights.Design::name).toList();
            s.soft("classes / a medium airfield offers small and medium designs", offered.contains(small) && offered.contains(design()), String.valueOf(offered));
        });
        s.settle("classes: a colony tick or two", 100);
        s.step("classes: goods for colony N while it has no place that takes the medium aircraft", () -> {
            s.soft("classes / still: the anchorage is not on the network without an airfield in its colony", net.node(anchorage[0].pad()) == null, String.valueOf(net.node(anchorage[0].pad())));
            rack[0] = equip(level, m);
            Fixture.stock(rack[0], Items.IRON_INGOT, 16);
            final WantLedger.Key keyN = WantLedger.Key.of(colonyN[0]);
            ledger.set(keyN, Items.IRON_INGOT, 16);
            final List<String> barred = new ArrayList<>();
            final BlockPos choice = Flights.cargoChoice(level, m.aircraft, barred);
            ledger.set(keyN, Items.IRON_INGOT, 0);
            s.soft("classes / cargo: for a colony with no place on the network that takes medium aircraft, no flight; the dispatcher says why",
                    choice == null && barred.stream().anyMatch(l -> l.contains("colony " + colonyN[0].getID()) && l.contains("no place there takes medium aircraft")), choice + " | " + barred);
            ledger.set(keyN, Items.IRON_INGOT, 1);
            final java.util.Set<WantLedger.Key> reach = exportOf(m.airfield).reachableForChecks();
            ledger.set(keyN, Items.IRON_INGOT, 0);
            s.soft("classes / export collection: the medium airfield does not count colony N as one it can send goods to", !reach.contains(keyN), String.valueOf(reach));
        });
        s.step("classes: colony N gets a small airfield", () -> {
            Fixture.platform(level, hutS.offset(1, 0, -8), 10, 16);
            smallN[0] = (BuildingAirfield) Fixture.placeHut(level, colonyN[0], Airline.AIRFIELD_HUT.get(), hutS);
            smallN[0].setBuildingLevel(1);
            smallN[0].setCorners(hutS.offset(-3, 0, -3), hutS.offset(3, 2, 3));
            // The airfield's import store (a MineColonies stash near its pad), as the cargo scenes have it.
            stashN[0] = Fixture.placeHut(level, colonyN[0], ModBlocks.blockStash, hutS.offset(5, 0, 3));
            stashN[0].setBuildingLevel(1);
            smallN[0].registerPad();
            s.soft("classes / the anchorage comes onto the network as soon as its colony has an airfield", net.node(anchorage[0].pad()) != null, String.valueOf(net.node(anchorage[0].pad())));
        });
        s.until("classes: with an airfield in colony N the anchorage is on the network", () -> {}, () -> net.node(anchorage[0].pad()) != null, 1200);
        s.until("classes: the lines are looked at", () -> {}, () -> net.pending() == 0, 2400);
        s.step("classes: the anchorage on the network; where goods for N go; a small aircraft and the anchorage", () -> {
            final RouteNetwork.Node node = net.node(anchorage[0].pad());
            s.soft("classes / the anchorage's pad is on the network as an anchorage: medium and large aircraft berth there, small ones do not",
                    node != null && node.facility() == Facility.ANCHORAGE && node.berths(ShipClass.MEDIUM) && node.berths(ShipClass.LARGE) && !node.berths(ShipClass.SMALL), String.valueOf(node));
            final List<String> offered = Flights.designCards(smallN[0]).stream().map(Flights.Design::name).toList();
            final String asked = Flights.requestAircraft(level, smallN[0], design(), null);
            s.soft("classes / a small airfield does not offer the medium design, and asked for it, says that class is not built here",
                    offered.contains(small) && !offered.contains(design()) && "class_not_here".equals(asked), offered + " / " + asked);
            final WantLedger.Key keyN = WantLedger.Key.of(colonyN[0]);
            ledger.set(keyN, Items.IRON_INGOT, 16);
            final List<String> barred = new ArrayList<>();
            final BlockPos choice = Flights.cargoChoice(level, m.aircraft, barred);
            ledger.set(keyN, Items.IRON_INGOT, 0);
            s.soft("classes / cargo: goods for colony N go with the medium aircraft to N's anchorage (N's small airfield does not take it)",
                    anchorage[0].pad().equals(choice) && barred.isEmpty(), choice + " | " + barred);
            ledger.set(keyN, Items.IRON_INGOT, 1);
            final java.util.Set<WantLedger.Key> reachM = exportOf(m.airfield).reachableForChecks();
            ledger.set(keyN, Items.IRON_INGOT, 0);
            s.soft("classes / export collection: now the medium airfield counts colony N (its aircraft can unload at N's anchorage); the anchorage keeps no export list",
                    reachM.contains(keyN) && exportOf(anchorage[0]) == null, reachM + " / anchorage's list " + exportOf(anchorage[0]));
            final BuildingAirfield store = com.asteain.airline.flight.Cargo.storeOf(anchorage[0]);
            final com.minecolonies.api.colony.buildings.IBuilding importAt = com.asteain.airline.flight.Cargo.importBuilding(level, anchorage[0]);
            s.soft("classes / the anchorage has no racks or import store of its own: it uses those of N's airfield",
                    store == smallN[0] && importAt == stashN[0], "racks of " + (store == null ? null : store.getPosition().toShortString()) + ", import store " + (importAt == null ? null : importAt.getPosition().toShortString()));
            final String toSmallN = Flights.landingFor(level, m.pad, smallN[0].pad(), ShipClass.MEDIUM), toAnchorage = Flights.landingFor(level, m.pad, anchorage[0].pad(), ShipClass.MEDIUM);
            s.soft("classes / sent by command from M, the medium aircraft is refused at N's small airfield and may go to the anchorage",
                    "flight.no_berth".equals(toSmallN) && "pad".equals(toAnchorage), toSmallN + " / " + toAnchorage);
            final Flights.Answer made = Flights.spawn(level, smallN[0].pad(), small);
            s.soft("classes / a small aircraft is made on N's small airfield", made.ok(), made.key());
        });
        loaded(s, "classes", () -> {
            for (AircraftBlockEntity each : Flights.aircraft(level)) if (smallN[0].pad().equals(each.home)) return each;
            return null;
        });
        s.settle("classes: the small aircraft settles on its pad", 60);
        s.step("classes: the small aircraft is sent to the anchorage", () -> {
            final Flights.Answer refused = Flights.send(level, smallN[0].pad(), anchorage[0].pad(), false, m.pilot);
            s.soft("classes / a small aircraft at N's small airfield, sent to the anchorage beside it, is refused: no berth for its class",
                    !refused.ok() && "flight.no_berth".equals(refused.key()), refused.key());
        });
        final boolean[] atAnchorage = new boolean[1];
        final int[] ironAtStart = new int[1];
        s.step("classes: colony N wants the iron on M's racks; the dispatcher sends the medium aircraft to N's anchorage", () -> {
            watch(s, m, "classes");
            s.monitor(() -> {
                if (!m.aircraft.flying() && m.aircraft.phase != Phase.PARKED && anchorage[0].pad().equals(m.aircraft.at)) atAnchorage[0] = true;
            });
            ironAtStart[0] = Fixture.count(m.airfield, Items.IRON_INGOT);
            ledger.set(WantLedger.Key.of(colonyN[0]), Items.IRON_INGOT, 16);
            s.note("OBS classes: iron on M's racks " + ironAtStart[0] + ", in N's import store " + Fixture.count(stashN[0], Items.IRON_INGOT));
        });
        s.until("classes: the cargo flight, there and back", () -> {}, () -> atAnchorage[0] && m.aircraft.phase == Phase.PARKED && m.pad.equals(m.aircraft.at), 20000);
        s.step("classes: result of the cargo flight", () -> {
            final double[] r = Flights.readings(m.aircraft);
            final long landings = m.log.stream().filter(l -> l.contains("-> UNLOAD")).count();
            final WantLedger.Key keyN = WantLedger.Key.of(colonyN[0]);
            final int there = Fixture.count(stashN[0], Items.IRON_INGOT), left = Fixture.count(m.airfield, Items.IRON_INGOT);
            s.soft("classes / the medium aircraft landed at the anchorage and came home, landing twice", atAnchorage[0] && landings == 2 && m.pad.equals(m.aircraft.at),
                    "at the anchorage " + atAnchorage[0] + ", landings " + landings + ", " + at(r));
            s.soft("classes / unloaded at the anchorage, the 16 iron went into the import store of N's airfield (the anchorage has none of its own); none is left on M's racks",
                    there == 16 && left == ironAtStart[0] - 16, "N's import store " + there + ", M's racks " + left + " (at the start " + ironAtStart[0] + ")");
            s.soft("classes / the ledger counts the iron as delivered: colony N wants no more of it", ledger.net(keyN, Items.IRON_INGOT) == 0 && ledger.covered(keyN, Items.IRON_INGOT) == 0,
                    "net " + ledger.net(keyN, Items.IRON_INGOT) + ", on the way " + ledger.covered(keyN, Items.IRON_INGOT));
            s.soft("classes / the pilot is alive and off the aircraft", m.pilot.isAlive() && Boarding.state(m.pilot) == Boarding.State.NONE, "state " + Boarding.state(m.pilot));
            for (String l : m.log) if (l.contains("->")) s.note("OBS classes: " + l);
        });

        // ---- the berth in the air: by command, a round trip to the anchorage; the pilot gets off onto the pier
        final double[] onPier = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        final boolean[] fixedThere = new boolean[1], joinedThere = new boolean[1];
        s.step("classes: by command, a round trip to the anchorage (the pilot gets off there)", () -> {
            m.log.clear();
            m.clock = 0;
            m.since = 0;
            s.monitor(() -> {
                if (Double.isNaN(onPier[0]) && m.aircraft.phase == Phase.WAIT && anchorage[0].pad().equals(m.aircraft.at)) {
                    onPier[0] = m.pilot.getX();
                    onPier[1] = m.pilot.getY();
                    onPier[2] = m.pilot.getZ();
                    final double[] r = Flights.readings(m.aircraft);
                    fixedThere[0] = r != null && r[4] > 0;
                    onPier[3] = r == null ? Double.NaN : r[6];
                    joinedThere[0] = Flights.docked(m.aircraft);
                }
            });
            final Flights.Answer sent = Flights.send(level, m.pad, anchorage[0].pad(), true, m.pilot);
            s.soft("classes / a round trip to the anchorage is ordered by command", sent.ok(), sent.text().getString());
        });
        s.until("classes: there, the pilot off and on again, and back", () -> {}, () -> m.clock > 100 && !Double.isNaN(onPier[0]) && m.aircraft.phase == Phase.PARKED && m.pad.equals(m.aircraft.at), 16000);
        s.step("classes: result of the round trip to the anchorage", () -> {
            final BlockPos pad = anchorage[0].pad();
            // The hut was not turned: the pier runs west of the berth, its deck a block higher than the pad, 8 to 10 blocks west.
            final boolean onTheDeck = onPier[1] > pad.getY() + 1.9 && onPier[1] < pad.getY() + 2.6 && onPier[0] >= pad.getX() - 10 && onPier[0] < pad.getX() - 7;
            s.soft("classes / at the anchorage the aircraft was held in the air beside the pier, fixed, its own block within 1.5 blocks of where it belongs",
                    fixedThere[0] && onPier[3] < 1.5, "fixed " + fixedThere[0] + ", " + onPier[3] + " from where its own block belongs");
            // (2026-10-18: the pier's connector pulls it in, so that its deck lies right against the pier.)
            s.soft("classes / at the anchorage the aircraft's connector was joined to the pier's, its own block within 0.1 of where it belongs",
                    joinedThere[0] && onPier[3] < 0.1, "joined " + joinedThere[0] + ", " + onPier[3] + " from where its own block belongs");
            s.soft("classes / the pilot got off onto the pier (not into the air) and is alive", onTheDeck && m.pilot.isAlive(),
                    String.format("the pilot stood at (%.1f, %.1f, %.1f) while the aircraft waited; the pad is %s", onPier[0], onPier[1], onPier[2], pad.toShortString()));
            s.soft("classes / then it came home", m.pad.equals(m.aircraft.at) && m.pilot.isAlive(), "at " + m.aircraft.at);
            for (String l : m.log) if (l.contains("->")) s.note("OBS classes: " + l);
        });

        // ---- 3. on the way to the anchorage, colony N loses its airfield: the anchorage leaves the network
        final boolean[] cut = new boolean[1], goneAtOnce = new boolean[1];
        final int[] cutAt = new int[1];
        s.step("classes: a one-way flight to the anchorage; on the way colony N's airfield is taken down", () -> {
            m.log.clear();
            m.clock = 0;
            m.since = 0;
            atAnchorage[0] = false;
            s.monitor(() -> {
                if (cut[0] || m.aircraft.phase != Phase.CRUISE) return;
                final double[] r = Flights.readings(m.aircraft);
                if (r == null || Math.hypot(r[0] - (m.pad.getX() + 0.5), r[2] - (m.pad.getZ() + 0.5)) < 30) return;
                cut[0] = true;
                cutAt[0] = m.clock;
                s.note(String.format("FLT classes: colony N's small airfield is taken down at t=%d, the aircraft at %s", m.clock, at(r)));
                smallN[0].destroy();
                level.removeBlock(hutS, false);
                goneAtOnce[0] = net.node(anchorage[0].pad()) == null;
            });
            final Flights.Answer sent = Flights.send(level, m.pad, anchorage[0].pad(), false, m.pilot);
            s.soft("classes / the one-way flight to the anchorage is ordered", sent.ok(), sent.text().getString());
        });
        s.until("classes: back home after the anchorage left the network", () -> {}, () -> cut[0] && m.clock > cutAt[0] + 100 && m.aircraft.phase == Phase.PARKED, 16000);
        s.step("classes: result of the flight whose destination left the network", () -> {
            final double[] r = Flights.readings(m.aircraft);
            s.soft("classes / when colony N's last airfield was taken down, its anchorage left the network at once (the same tick)", goneAtOnce[0], "at once " + goneAtOnce[0]);
            s.soft("classes / without an airfield in colony N the anchorage stays off the network", net.node(anchorage[0].pad()) == null, String.valueOf(net.node(anchorage[0].pad())));
            final WantLedger.Key keyN = WantLedger.Key.of(colonyN[0]);
            ledger.set(keyN, Items.IRON_INGOT, 1);
            final java.util.Set<WantLedger.Key> reach = exportOf(m.airfield).reachableForChecks();
            ledger.set(keyN, Items.IRON_INGOT, 0);
            s.soft("classes / export collection: with the anchorage gone the medium airfield no longer counts colony N", !reach.contains(keyN), String.valueOf(reach));
            s.soft("classes / the aircraft did not land at the anchorage that left the network; it went back and stands at home", !atAnchorage[0] && m.pad.equals(m.aircraft.at),
                    "at the anchorage " + atAnchorage[0] + ", at " + m.aircraft.at + ", " + at(r) + " | " + m.aircraft.note);
            s.soft("classes / within 1.5 blocks of the middle of the home pad, and the pilot is alive and off", r != null && r[6] <= 1.5 && m.pilot.isAlive() && Boarding.state(m.pilot) == Boarding.State.NONE,
                    r == null ? "?" : String.format("miss %.2f, state %s", r[6], Boarding.state(m.pilot)));
            for (String l : m.log) if (l.contains("->")) s.note("OBS classes: " + l);
            s.note("OBS classes: last note " + m.aircraft.note);
            s.clearMonitors();
        });
    }

    // ---- an aircraft is built on its pad ------------------------------------------------------------------------

    private static void yard(Script s, ServerLevel level) {
        RouteNetwork routes = RouteNetwork.get(level);
        Field a = new Field(HUT_A);
        a.noAircraft = true;
        addField(s, level, a, "yard");
        BlockPos padB = new BlockPos(80, Y - 1, -10);
        @SuppressWarnings("unchecked")
        java.util.concurrent.Future<com.ldtteam.structurize.blueprints.v1.Blueprint>[] blueprint = new java.util.concurrent.Future[1];
        TileEntityRack[] rack = new TileEntityRack[1];
        s.step("yard: a pad to fly to; building is asked for from the airfield's window", () -> {
            for (int cx = -2; cx <= 6; cx++) for (int cz = -5; cz <= 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            pad(level, padB, 0);
            final BlockPos middle = a.pad.above();
            final List<String> designs = Flights.designs();
            final String first = designs.get(0), second = designs.get(1 % designs.size());
            final java.util.function.Supplier<List<com.minecolonies.core.colony.workorders.WorkOrderDecoration>> orders =
                    () -> a.colony.getWorkManager().getWorkOrdersOfType(com.minecolonies.core.colony.workorders.WorkOrderDecoration.class);
            // Something in the way on the middle of the pad: nothing is begun.
            level.setBlockAndUpdate(middle, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            String said = Flights.requestAircraft(level, a.airfield, first, null);
            s.soft("yard / with a block on the middle of the pad, building is not begun and the block is not touched",
                    "pad_blocked".equals(said) && orders.get().isEmpty() && level.getBlockState(middle).is(net.minecraft.world.level.block.Blocks.STONE), "said " + said);
            level.removeBlock(middle, false);
            s.soft("yard / a design that does not exist is not taken", "".equals(Flights.requestAircraft(level, a.airfield, "no-such-design", null)) && orders.get().isEmpty(), "orders " + orders.get().size());
            // Asked for a design that is not the first one: the aircraft's own block is set down on the middle of the pad and the site begins with that design.
            said = Flights.requestAircraft(level, a.airfield, second, null);
            AircraftBlockEntity site = level.getBlockEntity(middle) instanceof AircraftBlockEntity be ? be : null;
            List<com.minecolonies.core.colony.workorders.WorkOrderDecoration> now = orders.get();
            s.soft("yard / asked for in the window, the aircraft's own block is set down on the middle of the airfield's own pad and a building site begins: one work order for the builders, with the blueprint of the design asked for, where the block stands",
                    "started".equals(said) && site != null && a.pad.equals(site.yard) && second.equals(site.type) && now.size() == 1 && Airline.PACK_NAME.equals(now.get(0).getStructurePack())
                            && Flights.blueprintOf(second).equals(now.get(0).getStructurePath()) && middle.equals(now.get(0).getLocation()) && site.workOrder == now.get(0).getID() && site.workOrder != 0,
                    "said " + said + ", site " + (site == null ? "none" : site.type) + ", orders " + now.size() + (now.isEmpty() ? "" : " " + now.get(0).getStructurePack() + "/" + now.get(0).getStructurePath() + " at " + now.get(0).getLocation()));
            if (site == null) return;
            s.soft("yard / the pad with a site on it is not free for another aircraft, and every block of the design is still to be put up",
                    !Flights.padFree(level, a.pad) && Flights.buildingLacks(level, site) == Flights.blocksOf(second),
                    "free " + Flights.padFree(level, a.pad) + ", lacks " + Flights.buildingLacks(level, site) + " of " + Flights.blocksOf(second) + " (" + second + ")");
            said = Flights.requestAircraft(level, a.airfield, second, null);
            s.soft("yard / asked again for the design that is being built, nothing changes", "same".equals(said) && orders.get().size() == 1 && second.equals(site.type), "said " + said + ", orders " + orders.get().size());
            said = Flights.requestAircraft(level, a.airfield, first, null);
            now = orders.get();
            s.soft("yard / asked for another design while one is being built, the site builds that one instead: still one work order, now with the other blueprint",
                    "design".equals(said) && first.equals(site.type) && now.size() == 1 && Flights.blueprintOf(first).equals(now.get(0).getStructurePath()) && site.workOrder == now.get(0).getID(),
                    "said " + said + ", design " + site.type + ", orders " + now.size() + (now.isEmpty() ? "" : " " + now.get(0).getStructurePath()));
            // A block of the aircraft being built, in the shaft over the pad (inside the aircraft's outline).
            level.setBlockAndUpdate(middle.offset(2, 4, 0), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
        });
        s.settle("yard: the site stands with a block of it in the shaft over the pad", 60);
        s.step("yard: the site's block does not block the pad's lines; the site is called off", () -> {
            final BlockPos middle = a.pad.above();
            final var line = routes.edge(a.pad, padB);
            s.soft("yard / a block of the aircraft being built, in the shaft over the pad, does not block the pad's lines",
                    line != null && line.state() != State.BLOCKED, "line " + (line == null ? null : line.state() + " at " + line.blockedAt()));
            // Fuel put into the site's fuel store (by a player) is dropped where the site stood when it is called off.
            final AircraftBlockEntity standing = level.getBlockEntity(middle) instanceof AircraftBlockEntity be ? be : null;
            if (standing != null) standing.store.addItem(new ItemStack(Items.COAL, 5));
            final int coalBefore = lying(level, Items.COAL);
            // Called off from the window: no order, no block on the middle of the pad (the plank stays where it was put).
            final String said = Flights.stopAircraft(level, a.airfield, null);
            s.soft("yard / fuel put into the site's fuel store is dropped where the site stood when the site is called off (not lost)",
                    standing != null && lying(level, Items.COAL) - coalBefore == 5, "coal lying there " + (lying(level, Items.COAL) - coalBefore));
            for (Entity entity : level.getAllEntities())
                if (entity instanceof net.minecraft.world.entity.item.ItemEntity drop && drop.getItem().is(Items.COAL)) drop.discard();
            s.soft("yard / stopped from the window, the builders are not asked any more and the aircraft's own block is taken away",
                    "stopped".equals(said) && a.colony.getWorkManager().getWorkOrdersOfType(com.minecolonies.core.colony.workorders.WorkOrderDecoration.class).isEmpty()
                            && level.getBlockState(middle).isAir() && Flights.padFree(level, a.pad), "said " + said + ", middle " + level.getBlockState(middle));
            s.soft("yard / with nothing being built, stopping says so", "no_site".equals(Flights.stopAircraft(level, a.airfield, null)), Flights.lastBuilding);
        });
        s.settle("yard: the site is gone for good", 160);
        s.until("yard: the pad's lines are looked at again after the site", () -> {}, () -> routes.pending() == 0, 600);
        s.step("yard: what the site left blocks the lines; a block set down by hand", () -> {
            final BlockPos middle = a.pad.above(), plank = middle.offset(2, 4, 0);
            final var line = routes.edge(a.pad, padB);
            s.soft("yard / called off with blocks left standing, those blocks block the pad's lines again (they are no aircraft any more)",
                    line != null && line.state() == State.BLOCKED && plank.equals(line.blockedAt()), "line " + (line == null ? null : line.state() + " at " + line.blockedAt()));
            level.removeBlock(plank, false);
            final java.util.function.Supplier<List<com.minecolonies.core.colony.workorders.WorkOrderDecoration>> orders =
                    () -> a.colony.getWorkManager().getWorkOrdersOfType(com.minecolonies.core.colony.workorders.WorkOrderDecoration.class);
            // An aircraft's own block set down by hand on the middle of the pad begins nothing by itself; asked for in the window, it becomes the site.
            level.setBlockAndUpdate(middle, Airline.AIRCRAFT.get().defaultBlockState());
            final boolean idle = level.getBlockEntity(middle) instanceof AircraftBlockEntity loose && loose.yard == null && orders.get().isEmpty();
            final String said = Flights.requestAircraft(level, a.airfield, design(), null);
            final AircraftBlockEntity site = level.getBlockEntity(middle) instanceof AircraftBlockEntity be ? be : null;
            s.soft("yard / the aircraft's own block on the middle of the pad begins nothing by itself; asked for in the window, that block becomes the site",
                    idle && "started".equals(said) && site != null && a.pad.equals(site.yard) && design().equals(site.type) && orders.get().size() == 1,
                    "idle " + idle + ", said " + said + ", orders " + orders.get().size());
            s.check(site != null && design().equals(site.type), "the site is on the design of this scene (" + design() + ")");
            // A player puts fuel into the site's fuel store while it is being built: it stays with the aircraft.
            site.store.addItem(new ItemStack(Items.CHARCOAL, 3));
        });
        s.settle("yard: the site is looked at; nothing is built yet", 100);
        s.step("yard: still a site; load the blueprint", () -> {
            s.soft("yard / a site with blocks missing stays a site", level.getBlockEntity(a.pad.above()) instanceof AircraftBlockEntity site && a.pad.equals(site.yard) && Flights.aircraft(level).isEmpty(),
                    Flights.aircraft(level).size() + " aircraft");
            blueprint[0] = com.ldtteam.structurize.storage.StructurePacks.getBlueprintFuture(Airline.PACK_NAME, Flights.blueprintOf(design()), level.registryAccess());
        });
        s.until("yard: blueprint loaded", () -> {}, () -> blueprint[0].isDone(), 400);
        // What a builder does block by block is MineColonies' own work and is not run here: the blueprint the work order
        // names is placed whole, where the work order has it.
        s.step("yard: the blueprint of the work order is put up", () -> {
            com.ldtteam.structurize.blueprints.v1.Blueprint bp = blueprint[0].get();
            s.check(bp != null, "the blueprint of the design is in the mod's pack");
            // Turned to the pad's heading, as the builders' work order has it: the aircraft is built the way it will stand.
            final com.ldtteam.structurize.api.RotationMirror turned = com.ldtteam.structurize.api.RotationMirror.of(Flights.buildTurn(a.airfield.padHeading()), net.minecraft.world.level.block.Mirror.NONE);
            s.note("OBS yard: the pad faces " + a.airfield.padHeading() + " degrees; the blueprint is placed turned " + turned);
            bp.setRotationMirror(turned, level);
            com.ldtteam.structurize.management.Manager.addToQueue(new com.ldtteam.structurize.operations.PlaceStructureOperation(new com.ldtteam.structurize.placement.StructurePlacer(
                    new com.ldtteam.structurize.placement.structure.CreativeStructureHandler(level, a.pad.above(), bp, turned, true)),
                    (net.minecraft.server.level.ServerPlayer) Fixture.owner(level)));
        });
        s.until("yard: the aircraft is a ship", () -> {}, () -> {
            for (AircraftBlockEntity each : Flights.aircraft(level)) if (a.pad.equals(each.home)) a.aircraft = each;
            return a.aircraft != null;
        }, 1200);
        s.settle("yard: the balloon fills while the aircraft stands fixed", 320);
        s.until("yard: the pad's lines are looked at again", () -> {}, () -> routes.pending() == 0, 600);
        s.step("yard: built", () -> {
            double[] r = Flights.readings(a.aircraft);
            int orders = a.colony.getWorkManager().getWorkOrdersOfType(com.minecolonies.core.colony.workorders.WorkOrderDecoration.class).size();
            s.soft("yard / when every block of the design stands, the whole becomes a ship: of the design that was built, parked on its pad, fixed, in the pad's heading; the work order is gone and no block is left on the pad",
                    r != null && r[4] > 0 && Math.hypot(r[0] - (a.pad.getX() + 0.5), r[2] - (a.pad.getZ() + 0.5)) < 0.3 && Math.abs(wrap(r[3] - a.airfield.padHeading())) < 1.0 && a.aircraft.phase == Phase.PARKED
                            && design().equals(a.aircraft.type) && a.aircraft.yard == null && orders == 0 && level.getBlockState(a.pad.above()).isAir() && level.getBlockState(a.pad.offset(0, 9, 0)).isAir(),
                    at(r) + ", design " + a.aircraft.type + ", work orders " + orders + ", on the pad " + level.getBlockState(a.pad.above()));
            double[] weight = Flights.weight(level, a.aircraft);
            s.note(String.format("OBS yard: the built aircraft has mass %.2f, centre of mass %.2f toward the nose, %.2f up, %.2f to the right", weight[0], weight[1], weight[2], weight[3]));
            s.soft("yard / the pad is the new aircraft's home, and its line to the other pad is clear", a.pad.equals(a.aircraft.home) && routes.edge(a.pad, padB) != null && routes.edge(a.pad, padB).state() == State.CLEAR,
                    "home " + a.aircraft.home + ", line " + (routes.edge(a.pad, padB) == null ? null : routes.edge(a.pad, padB).state()));
            final long kept = Flights.fuelTicks(a.aircraft);
            s.soft("yard / fuel put into the fuel store while the aircraft was a site is still there when it has become a ship (3 charcoal, less what the parked aircraft may have burnt)",
                    kept > 2 * 1600 && kept <= 3 * 1600, "fuel " + kept + " ticks of one engine, charcoal in the store " + a.aircraft.store.countItem(Items.CHARCOAL));
            // The checks below begin with an empty tank, as they were written.
            Flights.emptyTank(a.aircraft);
            Flights.Answer dry = Flights.send(level, a.pad, padB, true, a.pilot);
            s.soft("yard / an aircraft that was built starts with an empty tank, and no flight is begun without fuel", Flights.fuelTicks(a.aircraft) == 0 && !dry.ok(), "tank " + Flights.fuelTicks(a.aircraft) + "; a flight: " + dry.text().getString());
            a.airfield.setCorners(a.hut.offset(-3, 0, -3), a.hut.offset(3, 2, 3));
            rack[0] = Fixture.rack(level, a.airfield, a.hut.offset(-2, 0, 0));
            Fixture.stock(rack[0], Items.CHARCOAL, 64);
        });
        s.until("yard: the pilot fuels the aircraft", () -> {}, () -> Fixture.count(a.airfield, Items.CHARCOAL) == 0 && a.aircraft.carryCount == 0, 2400);
        s.step("yard: fuelled; a round trip", () -> {
            s.soft("yard / the pilot carries the fuel from the airfield's racks to the aircraft", Flights.fuelTicks(a.aircraft) == 64L * 1600, "tank " + Flights.fuelTicks(a.aircraft));
            watch(s, a, "yard");
            Flights.Answer sent = Flights.send(level, a.pad, padB, true, a.pilot);
            s.soft("yard / a round trip is ordered for the aircraft that was built", sent.ok(), sent.text().getString());
        });
        s.until("yard: out and back", () -> {}, () -> a.aircraft.phase == Phase.PARKED && a.clock > 100, 12000);
        s.step("yard: result", () -> {
            double[] r = Flights.readings(a.aircraft);
            long landings = a.log.stream().filter(l -> l.contains("-> DISEMBARK")).count();
            s.soft("yard / the aircraft that was built flew to the other pad and came back, landing twice", a.pad.equals(a.aircraft.at) && landings == 2, "at " + a.aircraft.at + ", landings " + landings + ", " + at(r));
            s.soft("yard / back home it is fixed, within 1.5 of the middle of the pad, within 3 degrees of the pad's heading",
                    r != null && r[4] > 0 && r[6] <= 1.5 && Math.abs(r[7]) <= 3.0, r == null ? "?" : String.format("miss %.2f, heading off %.2f", r[6], r[7]));
            s.note("OBS yard: " + a.aircraft.note);
        });

        // ---- back at home the aircraft is kept as it was built ----
        // The parts the checks work on are looked up in the design that was built: a plank of the deck with nothing on it, two
        // cogwheels, and the free place over that plank.
        final BlockPos[] found = yardParts(design());
        final BlockPos plank = found[0], cog = found[1], turned = found[2], added = found[3];
        // (A plank of the deck for the first designs; koeda's deck has none free, so its tail stair: what is taken out and put back.)
        final String deckKey = plank == null ? "minecraft:oak_planks" : com.asteain.airline.flight.AircraftType.of(design()).parts.get(plank).split("\\[")[0];
        final net.minecraft.world.level.block.Block deckBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(deckKey));
        final net.minecraft.world.item.Item deckItem = deckBlock.asItem();
        net.minecraft.world.item.Item cogwheel = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("create:cogwheel"));
        com.minecolonies.api.colony.buildings.IBuilding[] stash = new com.minecolonies.api.colony.buildings.IBuilding[1];
        s.step("yard: a plank and a cogwheel are taken out, a cogwheel is turned, a block is added; the racks have a plank and a cogwheel", () -> {
            stash[0] = Fixture.placeHut(level, a.colony, ModBlocks.blockStash, a.hut.offset(5, 0, 3));
            Fixture.stock(rack[0], deckItem, 1);
            Fixture.stock(rack[0], cogwheel, 1);
            net.minecraft.world.level.block.state.BlockState there = level.getBlockState(Flights.partOf(a.aircraft, turned));
            s.check(plank != null && cog != null && turned != null && level.getBlockState(Flights.partOf(a.aircraft, plank)).is(deckBlock) && there.getBlock().asItem() == cogwheel
                    && level.getBlockState(Flights.partOf(a.aircraft, added)).isAir(), "the design has a plank with nothing on it and two cogwheels (" + design() + ": " + java.util.Arrays.toString(found) + ")");
            final net.minecraft.core.Direction.Axis asBuilt = there.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS);
            level.removeBlock(Flights.partOf(a.aircraft, plank), false);
            level.removeBlock(Flights.partOf(a.aircraft, cog), false);
            level.setBlock(Flights.partOf(a.aircraft, turned), there.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS,
                    asBuilt == net.minecraft.core.Direction.Axis.Y ? net.minecraft.core.Direction.Axis.X : net.minecraft.core.Direction.Axis.Y), 3);
            level.setBlock(Flights.partOf(a.aircraft, added), Blocks.DIRT.defaultBlockState(), 3);
            int[] done = Flights.maintain(level, a.aircraft);
            net.minecraft.world.level.block.state.BlockState again = level.getBlockState(Flights.partOf(a.aircraft, turned));
            s.soft("yard / standing at home, the aircraft is put back the way its design has it: the plank and the cogwheel are put in again from the airfield's racks, the turned cogwheel is turned back, the added block is taken out",
                    done[0] == 3 && done[1] == 1 && level.getBlockState(Flights.partOf(a.aircraft, plank)).is(deckBlock) && level.getBlockState(Flights.partOf(a.aircraft, cog)).getBlock().asItem() == cogwheel
                            && again.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS) == asBuilt && level.getBlockState(Flights.partOf(a.aircraft, added)).isAir()
                            && a.aircraft.lacks.isEmpty(),
                    "put right " + done[0] + ", taken out " + done[1] + ", lacks " + a.aircraft.lacks);
            s.soft("yard / the plank and the cogwheel came off the racks, and the block that was taken out lies in the airfield's import store",
                    Fixture.count(a.airfield, deckItem) == 0 && Fixture.count(a.airfield, cogwheel) == 0 && Fixture.count(stash[0], Items.DIRT) == 1,
                    "racks: planks " + Fixture.count(a.airfield, deckItem) + ", cogwheels " + Fixture.count(a.airfield, cogwheel) + "; store: dirt " + Fixture.count(stash[0], Items.DIRT));
            // A part the racks do not have.
            level.removeBlock(Flights.partOf(a.aircraft, plank), false);
            done = Flights.maintain(level, a.aircraft);
            Flights.Answer refused = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("yard / a part the racks do not have is remembered, and the aircraft does not fly until it is put in again; the refusal names the part as players read it",
                    done[0] == 0 && Integer.valueOf(1).equals(a.aircraft.lacks.get(deckKey)) && !refused.ok() && refused.text().getString().contains(deckBlock.getName().getString())
                            && !refused.text().getString().contains("minecraft:"),
                    "lacks " + a.aircraft.lacks + "; a flight: " + refused.text().getString());
            // Asked for like anything a building asks for (2026-10-18): one request for the part, not two when asked again.
            final int exportBefore = partRequests(a.airfield, false).size();
            a.airfield.askForParts();
            a.airfield.askForParts();
            final java.util.List<Stack> asked = partRequests(a.airfield, true);
            s.soft("yard / the missing part is asked for like anything a building asks for: one request for it, also when asked again; the export table's requests are not touched",
                    asked.size() == 1 && asked.get(0).getStack().is(deckItem) && asked.get(0).getCount() == 1 && partRequests(a.airfield, false).size() == exportBefore,
                    "part requests " + asked.stream().map(r -> r.getCount() + " x " + r.getStack().getItem()).toList() + ", export " + exportBefore + " -> " + partRequests(a.airfield, false).size());
            Fixture.stock(rack[0], deckItem, 1);
            done = Flights.maintain(level, a.aircraft);
            a.airfield.askForParts();
            s.soft("yard / once the part is in, no request for parts is left open", partRequests(a.airfield, true).isEmpty(),
                    "part requests " + partRequests(a.airfield, true).stream().map(r -> r.getCount() + " x " + r.getStack().getItem()).toList());
            s.soft("yard / with the part on the racks it is put in at the next look, and nothing is lacking any more", done[0] == 1 && a.aircraft.lacks.isEmpty() && level.getBlockState(Flights.partOf(a.aircraft, plank)).is(deckBlock),
                    "put right " + done[0] + ", lacks " + a.aircraft.lacks);
            // Being changed by hand with the design tools (2026-10-16; it was self-repair switched off at the airfield before):
            // what is changed stays, it does not fly, and taken in as a design file the change is in it.
            Flights.modifying(a.aircraft, true);
            level.removeBlock(Flights.partOf(a.aircraft, plank), false);
            level.setBlock(Flights.partOf(a.aircraft, added), Blocks.DIRT.defaultBlockState(), 3);
            done = Flights.maintain(level, a.aircraft);
            final Flights.Answer whileChanged = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("yard / while it is being changed by hand, nothing is put back: the missing plank stays missing, the added block stays, nothing is counted as lacking, and it does not fly",
                    done[0] == 0 && done[1] == 0 && level.getBlockState(Flights.partOf(a.aircraft, plank)).isAir() && level.getBlockState(Flights.partOf(a.aircraft, added)).is(Blocks.DIRT) && a.aircraft.lacks.isEmpty()
                            && !whileChanged.ok(),
                    "put right " + done[0] + ", taken out " + done[1] + ", lacks " + a.aircraft.lacks + "; a flight: " + whileChanged.text().getString());
            final Flights.Answer taken = Flights.designCapture(level, a.aircraft, "yard-check");
            String file = "";
            try {
                if (taken.ok()) file = java.nio.file.Files.readString(java.nio.file.Path.of(String.valueOf(taken.args()[1])));
            } catch (java.io.IOException e) {
                file = "";
            }
            final String addedRow = "[" + added.getX() + "," + added.getY() + "," + added.getZ() + ",\"minecraft:dirt\"]", plankRow = "[" + plank.getX() + "," + plank.getY() + "," + plank.getZ() + ",";
            s.soft("yard / taken in as a design file: the added block is in it, the missing plank is not, and it says what it was made from",
                    taken.ok() && file.contains(addedRow) && !file.contains(plankRow) && file.contains("\"capturedFrom\""),
                    taken.text().getString() + "; dirt row " + file.contains(addedRow) + ", plank row " + file.contains(plankRow));
            Flights.modifying(a.aircraft, false);
            Fixture.stock(rack[0], deckItem, 1);
            done = Flights.maintain(level, a.aircraft);
            s.soft("yard / the mark taken off, the aircraft is put back the way its design has it", done[0] == 1 && done[1] == 1 && level.getBlockState(Flights.partOf(a.aircraft, plank)).is(deckBlock)
                    && level.getBlockState(Flights.partOf(a.aircraft, added)).isAir() && a.aircraft.lacks.isEmpty(), "put right " + done[0] + ", taken out " + done[1] + ", lacks " + a.aircraft.lacks);
            double[] weight = Flights.weight(level, a.aircraft);
            s.note(String.format("OBS yard: after the work the aircraft has mass %.2f", weight[0]));
        });
        s.settle("yard: the aircraft stands as it was", 60);
        s.step("yard: still parked and fixed", () -> {
            double[] r = Flights.readings(a.aircraft);
            s.soft("yard / after the work the aircraft still stands fixed on its pad", r != null && r[4] > 0 && a.aircraft.phase == Phase.PARKED && Math.hypot(r[0] - (a.pad.getX() + 0.5), r[2] - (a.pad.getZ() + 0.5)) < 0.5, at(r));
            // "Leave now" from the timetable tab, with no goods anywhere to carry: it says so, and the aircraft stays.
            final String said = Flights.departNow(level, a.airfield, null);
            s.soft("yard / asked to leave now with nothing to carry, the aircraft says there is nowhere to go and stays parked", "nothing".equals(said) && a.aircraft.phase == Phase.PARKED, "said " + said + ", " + a.aircraft.phase);
        });
    }

    // ---- fuel: the tank, the pilot's walk with it, distress and rescue -------------------------------------------

    private static void fuel(Script s, ServerLevel level) {
        RouteNetwork routes = RouteNetwork.get(level);
        Field a = new Field(HUT_A);
        addField(s, level, a, "fuel");
        BlockPos padB = new BlockPos(80, Y - 1, -10);
        TileEntityRack[] rack = new TileEntityRack[1];
        long[] tank = new long[1];
        // How many engines the design has (the first design two, koeda one): the tank is counted in ticks of one engine.
        int[] engines = {2};
        com.google.common.reflect.TypeToken<com.minecolonies.api.colony.requestsystem.requestable.StackList> asked = com.google.common.reflect.TypeToken.of(com.minecolonies.api.colony.requestsystem.requestable.StackList.class);
        s.step("fuel: a pad to fly to; the tank is emptied", () -> {
            for (int cx = -2; cx <= 6; cx++) for (int cz = -5; cz <= 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            pad(level, padB, 0);
            a.airfield.setCorners(a.hut.offset(-3, 0, -3), a.hut.offset(3, 2, 3));
            rack[0] = Fixture.rack(level, a.airfield, a.hut.offset(-2, 0, 0));
            int[] burning = Flights.engineBurn(a.aircraft);
            engines[0] = burning.length;
            s.soft("fuel / an aircraft made by command comes with a full tank (160 minutes of one engine: 80 minutes of flight with two), and standing on its pad its engines burn nothing",
                    Flights.fuelMinutes(a.aircraft) == 160 / engines[0] && java.util.Arrays.stream(burning).allMatch(b -> b == 0),
                    Flights.fuelMinutes(a.aircraft) + " minutes; engines " + java.util.Arrays.toString(burning));
            Flights.emptyTank(a.aircraft);
            a.airfield.onColonyTick(a.colony);
            int requests = a.airfield.getOpenRequestsOfType(-1, asked).size();
            a.airfield.onColonyTick(a.colony);
            s.soft("fuel / an airfield whose aircraft stands at home with little in the tank, and with little fuel on its racks, asks its colony for fuel: one request, not one more with every look",
                    requests == 1 && a.airfield.getOpenRequestsOfType(-1, asked).size() == 1, requests + " request(s), then " + a.airfield.getOpenRequestsOfType(-1, asked).size());
        });
        s.until("fuel: lines verified", () -> {}, () -> routes.pending() == 0, 600);
        s.step("fuel: no flight without fuel; fuel on the racks", () -> {
            Flights.Answer refused = Flights.send(level, a.pad, padB, true, a.pilot);
            s.soft("fuel / with an empty tank a flight is refused", !refused.ok(), refused.text().getString());
            Fixture.stock(rack[0], Items.CHARCOAL, 64);
            Fixture.stock(rack[0], Items.OAK_PLANKS, 8);
        });
        s.until("fuel: the pilot fills the tank", () -> {}, () -> Fixture.count(a.airfield, Items.CHARCOAL) == 0 && a.aircraft.carryCount == 0, 2400);
        s.step("fuel: filled; a flight", () -> {
            s.soft("fuel / the fuel lies in the aircraft's fuel store as items: 64 pieces of charcoal", a.aircraft.store.countItem(Items.CHARCOAL) == 64,
                    "charcoal in the store " + a.aircraft.store.countItem(Items.CHARCOAL));
            s.soft("fuel / at home the pilot carries the fuel from the racks to the aircraft: 64 pieces of charcoal are in the tank (1600 ticks of one engine each: 42 minutes of flight with two engines, 85 with one); what is not fuel stays on the racks",
                    Flights.fuelTicks(a.aircraft) == 64L * 1600 && Flights.fuelMinutes(a.aircraft) == 64L * 1600 / engines[0] / 1200 && Fixture.count(a.airfield, Items.OAK_PLANKS) == 8,
                    "tank " + Flights.fuelTicks(a.aircraft) + " (" + Flights.fuelMinutes(a.aircraft) + " minutes), planks on the racks " + Fixture.count(a.airfield, Items.OAK_PLANKS));
            int taken = Flights.refuel(level, a.aircraft, new ItemStack(Items.COAL, 10), null);
            int notFuel = Flights.refuel(level, a.aircraft, new ItemStack(Items.OAK_PLANKS, 4), null);
            s.soft("fuel / fuel put in by hand goes into the tank too; what airfields do not take as fuel does not", taken == 10 && notFuel == 0 && Flights.fuelTicks(a.aircraft) == 74L * 1600, "taken " + taken + " / " + notFuel + ", tank " + Flights.fuelTicks(a.aircraft));
            tank[0] = Flights.fuelTicks(a.aircraft);
            watch(s, a, "fuel");
            Flights.Answer sent = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("fuel / with fuel in the tank the flight is ordered", sent.ok(), sent.text().getString());
        });
        s.until("fuel: landed at pad B", () -> {}, () -> a.aircraft.phase == Phase.PARKED && a.clock > 100, 9000);
        s.settle("fuel: on the pad", 40);
        s.step("fuel: after the flight; back, and the tank is emptied on the way", () -> {
            long used = tank[0] - Flights.fuelTicks(a.aircraft);
            int[] burning = Flights.engineBurn(a.aircraft);
            s.soft("fuel / the flight burnt fuel for its engines while the aircraft was in the air (not more than one tick of fuel for every engine and tick of the flight), and on the pad the engines stand still again",
                    padB.equals(a.aircraft.at) && used > 1000L * engines[0] && used <= (long) engines[0] * a.clock && java.util.Arrays.stream(burning).allMatch(b -> b == 0),
                    "used " + used + " in " + a.clock + " ticks from the order to the end; engines " + java.util.Arrays.toString(burning) + "; tank " + Flights.fuelTicks(a.aircraft));
            s.note("OBS fuel: a flight over 80 blocks used " + used + " ticks of fuel (" + (used / 1600.0) + " pieces of charcoal)");
            a.log.clear();
            a.clock = 0;
            a.since = 0;
            Flights.Answer sent = Flights.send(level, padB, a.pad, false, a.pilot);
            s.soft("fuel / the flight home is ordered", sent.ok(), sent.text().getString());
        });
        s.until("fuel: on its way", () -> {}, () -> a.aircraft.phase == Phase.CRUISE, 4000);
        s.settle("fuel: a little way along the line", 160);
        s.step("fuel: the tank is emptied in the air", () -> Flights.emptyTank(a.aircraft));
        s.until("fuel: distress", () -> {}, () -> a.aircraft.phase == Phase.DISTRESS, 800);
        s.settle("fuel: it stays where it is", 200);
        s.step("fuel: in distress; a little fuel", () -> {
            double[] r = Flights.readings(a.aircraft);
            int[] burning = Flights.engineBurn(a.aircraft);
            s.soft("fuel / with nothing left to burn in the air the aircraft is in distress: it stays where it is, fixed, off every pad, its pilot still on the seat, and it says where it is",
                    r != null && r[4] > 0 && r[9] < 0.05 && a.aircraft.at == null && Boarding.state(a.pilot) == Boarding.State.SEATED && java.util.Arrays.stream(burning).allMatch(b -> b == 0)
                            && a.aircraft.note.contains(String.valueOf((int) Math.floor(r[0]))) && a.aircraft.note.contains(String.valueOf((int) Math.floor(r[1]))),
                    at(r) + ", speed " + (r == null ? "?" : String.format("%.2f", r[9])) + ", pilot " + Boarding.state(a.pilot) + " | " + a.aircraft.note);
            Flights.refuel(level, a.aircraft, new ItemStack(Items.COAL, 1), null);
        });
        s.settle("fuel: one piece of coal", 60);
        s.step("fuel: more fuel is brought", () -> {
            s.soft("fuel / a little fuel is not enough for the rest of the way: the aircraft still waits", a.aircraft.phase == Phase.DISTRESS && Flights.fuelTicks(a.aircraft) == 1600, a.aircraft.phase + ", tank " + Flights.fuelTicks(a.aircraft));
            Flights.refuel(level, a.aircraft, new ItemStack(Items.COAL, 32), null);
        });
        s.until("fuel: home", () -> {}, () -> a.aircraft.phase == Phase.PARKED && a.pad.equals(a.aircraft.at) && a.clock > 100, 9000);
        s.step("fuel: rescued", () -> {
            double[] r = Flights.readings(a.aircraft);
            s.soft("fuel / with enough fuel brought to it the aircraft takes up its flight again and lands where it was going; the pilot gets off there alive",
                    a.pad.equals(a.aircraft.at) && r != null && r[4] > 0 && r[6] <= 1.5 && a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE,
                    "at " + a.aircraft.at + ", " + at(r) + ", pilot " + Boarding.state(a.pilot) + " | " + a.aircraft.note);
            s.note("OBS fuel: " + a.log.size() + " changes of phase on the flight home: " + String.join(" / ", a.log.stream().map(l -> l.replaceAll(".*?([A-Z_]+ -> [A-Z_]+).*", "$1")).toList()));
        });
    }

    // ---- a flight that only fetches -------------------------------------------------------------------------------

    private static void fetch(Script s, ServerLevel level) {
        RouteNetwork routes = RouteNetwork.get(level);
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        addField(s, level, a, "fetch A");
        BlockPos hutB = new BlockPos(208, Y, 0), buoy = new BlockPos(104, Y, -10);
        IColony[] colonyB = new IColony[1];
        BuildingAirfield[] airfieldB = new BuildingAirfield[1];
        AbstractBuilding[] stash = new AbstractBuilding[1];
        boolean[] leftEmpty = new boolean[1];
        s.step("fetch: colony A wants 12 coal and has nothing anybody wants; colony B, 208 blocks east, has 20 coal on its airfield's racks", () -> {
            for (int cx = -2; cx <= 15; cx++) for (int cz = -3; cz <= 2; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            a.airfield.setBuildingLevel(1);
            a.airfield.setCorners(a.hut.offset(-3, 0, -3), a.hut.offset(3, 2, 3));
            Fixture.rack(level, a.airfield, a.hut.offset(-2, 0, 0));
            stash[0] = Fixture.placeHut(level, a.colony, ModBlocks.blockStash, a.hut.offset(5, 0, 3));
            stash[0].setBuildingLevel(1);
            Fixture.platform(level, hutB.offset(1, 0, -8), 10, 16);
            colonyB[0] = Fixture.create(level, hutB.offset(4, 0, 4), "Airline fetch B");
            Fixture.townHall(colonyB[0], hutB.offset(4, 0, 4));
            airfieldB[0] = (BuildingAirfield) Fixture.placeHut(level, colonyB[0], Airline.AIRFIELD_HUT.get(), hutB);
            airfieldB[0].setBuildingLevel(1);
            airfieldB[0].setCorners(hutB.offset(-3, 0, -3), hutB.offset(3, 2, 3));
            Fixture.stock(Fixture.rack(level, airfieldB[0], hutB.offset(-2, 0, 0)), Items.COAL, 20);
            level.setBlockAndUpdate(buoy, Airline.BUOYS.get(1).get().defaultBlockState());
            ledger.set(WantLedger.Key.of(a.colony), Items.COAL, 12);
            s.soft("fetch / the two airfields are joined, and nothing lies on the home racks", routes.route(a.pad, airfieldB[0].pad(), ShipClass.SMALL).size() >= 2 && com.asteain.airline.flight.Cargo.contents(a.airfield.getItemHandlerCap()).isEmpty(),
                    "way " + routes.route(a.pad, airfieldB[0].pad(), ShipClass.SMALL));
            watch(s, a, "fetch");
            s.monitor(() -> {
                if (a.aircraft.phase == Phase.CLIMB && a.aircraft.at == null && a.aircraft.origin != null && a.aircraft.origin.equals(a.pad) && !a.aircraft.returning)
                    leftEmpty[0] = a.aircraft.fetch && a.aircraft.manifest.isEmpty() && com.asteain.airline.flight.Flights.holdContents(a.aircraft).isEmpty();
            });
        });
        s.until("fetch: out, a berth there, and back", () -> {}, () -> a.aircraft.phase == Phase.PARKED && a.pad.equals(a.aircraft.at) && a.log.stream().filter(l -> l.contains("-> DISEMBARK") || l.contains("-> UNLOAD")).count() >= 2, 24000);
        s.settle("fetch: a moment at home", 200);
        s.step("fetch: result", () -> {
            int home = com.asteain.airline.flight.Cargo.count(stash[0].getItemHandlerCap(), Items.COAL), there = com.asteain.airline.flight.Cargo.count(airfieldB[0].getItemHandlerCap(), Items.COAL);
            s.soft("fetch / with nothing to bring, the aircraft left home with an empty hold to fetch what home wants from the other airfield's racks", leftEmpty[0], "left empty " + leftEmpty[0] + " | " + a.aircraft.note);
            s.soft("fetch / it came back with the 12 coal home wanted: they lie in home's import store, 8 are left on the other airfield's racks, and the hold is empty",
                    home == 12 && there == 8 && com.asteain.airline.flight.Flights.holdContents(a.aircraft).isEmpty(), "home " + home + ", there " + there + ", hold " + com.asteain.airline.flight.Flights.holdContents(a.aircraft));
            s.soft("fetch / the ledger knows that home's want is met", ledger.net(WantLedger.Key.of(a.colony), Items.COAL) == 0, "still wanted " + ledger.net(WantLedger.Key.of(a.colony), Items.COAL));
            a.log.clear();
            a.clock = 0;
        });
        s.settle("fetch: nothing more is wanted", 900);
        s.step("fetch: no second flight", () -> s.soft("fetch / with home's want met no further flight is made", a.aircraft.phase == Phase.PARKED && a.pad.equals(a.aircraft.at) && a.log.stream().noneMatch(l -> l.contains("-> CLIMB")),
                a.aircraft.phase + " at " + a.aircraft.at + ", " + a.log.size() + " changes of phase"));
    }

    // ---- round trip, blocked lines ----------------------------------------------------------------------------

    private static void trip(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        Field a = new Field(HUT_A);
        addField(s, level, a, "A");
        BlockPos padB = new BlockPos(80, Y - 1, -10), buoy = new BlockPos(40, Y, -60);
        BlockPos[] wall = tallWall(40);
        // The lines of this scene are kept loaded, so that the network can verify them without an aircraft.
        s.step("scene: keep the area loaded", () -> { for (int cx = -2; cx <= 6; cx++) for (int cz = -5; cz <= 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); } });
        s.step("scene: pad B 80 blocks east, a buoy off to the side", () -> {
            pad(level, padB, 0);
            level.setBlockAndUpdate(buoy, Airline.BUOYS.get(0).get().defaultBlockState());
            s.soft("trip / pad A, pad B and the buoy are three points with three lines", net.nodes().size() == 3 && net.edge(a.pad, padB) != null && net.edge(a.pad, buoy) != null && net.edge(buoy, padB) != null,
                    net.nodes().size() + " points, " + net.edges().size() + " lines");
            for (Edge e : net.edges()) s.note(String.format("OBS line %s - %s: %s, %.1f long", e.a.toShortString(), e.b.toShortString(), e.state(), e.length()));
        });

        // ---- a buoy that becomes part of a ship leaves the network
        BlockPos loose = new BlockPos(0, Y, 60);
        SubLevel[] small = new SubLevel[1];
        s.step("a buoy is taken up into a ship", () -> {
            level.setBlockAndUpdate(loose, Airline.BUOYS.get(0).get().defaultBlockState());
            level.setBlockAndUpdate(loose.above(), Blocks.OAK_PLANKS.defaultBlockState());
            s.soft("ship / the loose buoy is a point first", net.node(loose) != null, net.nodes().size() + " points");
            small[0] = (SubLevel) (Object) SubLevelAssemblyHelper.assembleBlocks(level, loose, List.of(loose, loose.above()), new BoundingBox3i(loose, loose.above()));
            s.note("OBS right after the buoy was assembled into a ship: point still there = " + (net.node(loose) != null) + ", block in the world now " + level.getBlockState(loose));
        });
        s.settle("ship: a moment", 5);
        s.step("ship: the buoy is no point any more", () -> {
            s.soft("ship / a buoy that was assembled into a Sable ship leaves the network, and its copy inside the ship does not join it", net.node(loose) == null && net.nodes().size() == 3,
                    net.nodes().size() + " points; point at the old place: " + net.node(loose));
            // The little ship is left alone: it has nothing to hold it up and falls out of the world.
        });

        // ---- round trip over the buoy (the direct line is blocked before the flight)
        s.step("trip: a wall on the direct line, then the order", () -> {
            for (BlockPos pos : wall) level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            s.soft("trip / the wall blocks the direct line at once", net.edge(a.pad, padB).state() == State.BLOCKED, String.valueOf(net.edge(a.pad, padB).state()));
            s.soft("trip / the route goes over the buoy", net.route(a.pad, padB, ShipClass.SMALL).equals(List.of(a.pad, buoy, padB)), String.valueOf(net.route(a.pad, padB, ShipClass.SMALL)));
            watch(s, a, "trip");
            Flights.Answer sent = Flights.send(level, a.pad, padB, true, a.pilot);
            s.soft("trip / the round trip is ordered", sent.ok(), sent.text().getString());
            Flights.Answer second = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("trip / a second order while the aircraft is busy is refused", !second.ok(), second.text().getString());
        });
        s.until("trip: out and back", () -> {}, () -> a.aircraft.phase == Phase.PARKED && a.clock > 100, 12000);
        s.step("trip: result", () -> {
            double[] r = Flights.readings(a.aircraft);
            boolean home = a.pad.equals(a.aircraft.at);
            long landings = a.log.stream().filter(l -> l.contains("-> DISEMBARK")).count();
            s.soft("trip / the aircraft flew to pad B and came back to pad A, landing twice", home && landings == 2, "at " + a.aircraft.at + ", landings " + landings + ", " + at(r));
            s.soft("trip / the pilot is alive, off the aircraft on the pad, and never fell", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && a.pilotMinY > Y - 1.5 && a.pilot.getY() < Y + 0.6,
                    "state " + Boarding.state(a.pilot) + ", lowest " + a.pilotMinY + ", now " + a.pilot.position());
            s.soft("trip / boarding had no internal failure", Boarding.failures() == 0, "failures=" + Boarding.failures());
            s.soft("trip / back home the aircraft is fixed, within 1.5 of the middle of the pad, within 3 degrees of the pad's heading",
                    r != null && r[4] > 0 && r[6] <= 1.5 && Math.abs(r[7]) <= 3.0, r == null ? "?" : String.format("miss %.2f, heading off %.2f", r[6], r[7]));
            s.soft("trip / the aircraft never touched the buoy", r != null && r[5] > 0.5 && r[5] < 100, r == null ? "?" : String.format("closest %.2f blocks", r[5]));
            s.note(String.format("OBS trip: %d ticks in all, top speed %.2f, lever writes %.0f, closest to a buoy %.2f", a.clock, a.speedMax, r == null ? -1 : r[8], r == null ? -1 : r[5]));
            s.note("OBS trip: " + a.aircraft.note);
        });

        // ---- blocked on the way: back to the last point, another way
        BlockPos[] late = tallWall(55);
        boolean[] placed = new boolean[1];
        s.step("blocked: the old wall goes, the direct line is clear again", () -> {
            for (BlockPos pos : wall) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        });
        s.until("blocked: verified", () -> {}, () -> net.pending() == 0, 400);
        s.step("blocked: order a one-way flight over the direct line", () -> {
            s.soft("blocked / the direct line is clear and is the route", net.edge(a.pad, padB).state() == State.CLEAR && net.route(a.pad, padB, ShipClass.SMALL).size() == 2, net.edge(a.pad, padB).state() + " " + net.route(a.pad, padB, ShipClass.SMALL));
            a.log.clear();
            a.clock = 0;
            a.since = 0;
            s.monitor(() -> {
                double[] r = Flights.readings(a.aircraft);
                if (r == null) return;
                if (!placed[0] && a.aircraft.phase == Phase.CRUISE && r[0] > 20.5) {
                    // Placed without telling anybody (flag 2): only the aircraft's own look ahead can find it.
                    for (BlockPos pos : late) level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
                    placed[0] = true;
                    s.note(String.format("FLT blocked: wall placed at x=55 when the aircraft was at x=%.1f, speed %.2f; the network says %s", r[0], r[9], net.edge(a.pad, padB).state()));
                }
                // Only while it is on the blocked line, on the near side of the wall.
                if (placed[0] && r[0] < 55 && Math.abs(r[2] + 9.5) < 6) a.wallGap = Math.min(a.wallGap, 55 - (r[0] + 4.5));
            });
            Flights.Answer sent = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("blocked / the flight is ordered", sent.ok(), sent.text().getString());
        });
        s.until("blocked: the flight ends", () -> {}, () -> a.aircraft.phase == Phase.PARKED && a.clock > 100, 9000);
        s.step("blocked: result", () -> {
            Edge direct = net.edge(a.pad, padB);
            double[] r = Flights.readings(a.aircraft);
            boolean wentBack = a.log.stream().anyMatch(l -> l.contains("-> RETREAT"));
            s.soft("blocked / the aircraft found the wall itself and the network now knows the block", placed[0] && wentBack && direct.state() == State.BLOCKED && direct.blockedAt() != null && direct.blockedAt().getX() == 55,
                    "placed=" + placed[0] + " wentBack=" + wentBack + " line " + direct.state() + " at " + direct.blockedAt());
            s.soft("blocked / it stopped before the wall", a.wallGap > 0.5 && a.wallGap < 100, String.format("nearest the nose came to the wall: %.2f blocks", a.wallGap));
            s.soft("blocked / it went back, took the way over the buoy and landed at pad B, within 1.5 of its middle", padB.equals(a.aircraft.at) && r != null && r[4] > 0 && r[6] <= 1.5,
                    "at " + a.aircraft.at + " " + at(r) + (r == null ? "" : String.format(", miss %.2f", r[6])) + " | " + a.aircraft.note);
            s.soft("blocked / the pilot got off at pad B alive and stands on the pad", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && a.pilot.position().distanceTo(padB.getCenter()) < 12
                    && a.pilot.getY() < Y + 0.6, "pilot at " + a.pilot.position() + " state " + Boarding.state(a.pilot));
            s.note(String.format("OBS blocked: %d ticks, stopped %.2f blocks short of the wall", a.clock, a.wallGap));
        });

        // ---- blocked with no other way: back to where it started, cancelled
        boolean[] placed2 = new boolean[1];
        BlockPos[] last = tallWall(30);
        s.step("cancel: no buoy any more, the wall goes, a new wall will come", () -> {
            level.setBlockAndUpdate(buoy, Blocks.AIR.defaultBlockState());
            for (BlockPos pos : late) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        });
        s.until("cancel: verified", () -> {}, () -> net.pending() == 0 && net.edge(a.pad, padB).state() == State.CLEAR, 600);
        s.step("cancel: order the flight back", () -> {
            s.soft("cancel / only the direct line is left", net.nodes().size() == 2 && net.edges().size() == 1, net.nodes().size() + " points, " + net.edges().size() + " lines");
            a.log.clear();
            a.clock = 0;
            a.since = 0;
            s.monitor(() -> {
                double[] r = Flights.readings(a.aircraft);
                if (r != null && !placed2[0] && a.aircraft.phase == Phase.CRUISE && r[0] < 62) {
                    for (BlockPos pos : last) level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
                    placed2[0] = true;
                }
            });
            Flights.Answer sent = Flights.send(level, padB, a.pad, false, a.pilot);
            s.soft("cancel / the flight back is ordered (the pilot boards at pad B)", sent.ok(), sent.text().getString());
        });
        s.until("cancel: the flight ends", () -> {}, () -> a.aircraft.phase == Phase.PARKED && a.clock > 100, 7000);
        s.step("cancel: result", () -> {
            double[] r = Flights.readings(a.aircraft);
            // (Within 1.5 of the middle of the pad as well: a ship that gave up placing itself came down 2.3 off, and that passed.)
            s.soft("cancel / with no other way the aircraft returned to pad B, landed within 1.5 of its middle, and the flight counts as cancelled", placed2[0] && padB.equals(a.aircraft.at) && r != null && r[4] > 0
                    && r[6] <= 1.5 && a.log.stream().anyMatch(l -> l.contains("-> RETREAT")), "at " + a.aircraft.at + " " + at(r) + (r == null ? "" : String.format(", miss %.2f", r[6])) + " | " + a.aircraft.note);
            s.soft("cancel / the pilot is alive and off the aircraft, on the pad", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && a.pilot.getY() < Y + 0.6, "pilot at " + a.pilot.position());
            s.soft("cancel / boarding had no internal failure in the whole run", Boarding.failures() == 0, "failures=" + Boarding.failures());
            s.note("OBS cancel: " + a.clock + " ticks; last note: " + a.aircraft.note);
            s.clearMonitors();
        });
    }

    // ---- two aircraft at once: pads, and keeping apart in the air -------------------------------------------------

    /** Makes a field's airfield level 2: the floor of the pad for visitors, and the pad on the network. */
    private static BlockPos upgrade(ServerLevel level, Field f) {
        f.airfield.setBuildingLevel(2);
        BlockPos guest = f.airfield.guestPad();
        Fixture.platform(level, guest.above(), 6, 6);
        f.airfield.registerPad();
        return guest;
    }

    private static void traffic(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        // Colony B's hut stands 88 blocks east (outside colony A's claim). The buoy between them is as high as the lines
        // over the pads, so the lines are level: they run right above the neighbouring pads.
        Field a = new Field(HUT_A), b = new Field(new BlockPos(88, Y, 0));
        BlockPos buoy = new BlockPos(53, Y + 7, -12), padC = new BlockPos(53, Y - 1, -70);
        BlockPos[] guest = new BlockPos[2];
        double[] nearest = {1e9, 1e9};      // while both were in the air: nearest seen from above, and how far apart in height then
        int[] seatedWaiting = new int[2];
        Phase[] otherAtTakeOff = new Phase[2];
        s.step("traffic: keep the area loaded", () -> { for (int cx = -2; cx <= 8; cx++) for (int cz = -6; cz <= 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); } });
        addField(s, level, a, "A");
        addField(s, level, b, "B");

        // ---- level 1: one pad, and that is the home of the airfield's own aircraft
        s.step("traffic: level 1", () -> {
            a.airfield.setBuildingLevel(1);
            b.airfield.setBuildingLevel(1);
            a.airfield.registerPad();
            b.airfield.registerPad();
            s.soft("traffic / at level 1 an airfield has one pad", a.airfield.pads().size() == 1 && net.node(a.airfield.guestPad()) == null, a.airfield.pads() + ", point at the place of the second pad: " + net.node(a.airfield.guestPad()));
            s.soft("traffic / the two home pads are joined by a line", net.route(a.pad, b.pad, ShipClass.SMALL).size() == 2, String.valueOf(net.route(a.pad, b.pad, ShipClass.SMALL)));
            s.soft("traffic / a pad that is the home of an aircraft is not free for others", !Flights.padFree(level, a.pad) && !Flights.padFree(level, b.pad), "A free=" + Flights.padFree(level, a.pad) + ", B free=" + Flights.padFree(level, b.pad));
            Flights.Answer refused = Flights.send(level, a.pad, b.pad, false, a.pilot);
            s.soft("traffic / a flight to a level 1 airfield that has an aircraft of its own is refused: no pad is free there", !refused.ok() && refused.key().equals("flight.no_free_pad") && a.aircraft.phase == Phase.PARKED,
                    refused.key() + ": " + refused.text().getString());
        });

        // ---- level 2: a pad for visitors
        s.step("traffic: both airfields become level 2; a buoy between them; a bare pad to the north", () -> {
            guest[0] = upgrade(level, a);
            guest[1] = upgrade(level, b);
            level.setBlockAndUpdate(buoy, Airline.BUOYS.get(1).get().defaultBlockState());
            pad(level, padC, 0);
            RouteNetwork.Node node = net.node(guest[0]);
            s.soft("traffic / at level 2 the pad for visitors is a point of the network, 17 blocks beside the first, with the same heading",
                    a.airfield.pads().size() == 2 && node != null && node.kind() == RouteNetwork.Kind.AIRFIELD && node.heading() == a.airfield.padHeading() && guest[0].equals(a.pad.offset(17, 0, 0)),
                    a.airfield.pads() + " point " + node);
            s.soft("traffic / a way never leads through a landing pad: from A's own pad to B's pad for visitors it goes over the buoy",
                    net.edge(a.pad, guest[1]) == null && net.route(a.pad, guest[1], ShipClass.SMALL).equals(List.of(a.pad, buoy, guest[1])), String.valueOf(net.route(a.pad, guest[1], ShipClass.SMALL)));
            s.soft("traffic / the pads for visitors are free, the home pads are not", Flights.padFree(level, guest[0]) && Flights.padFree(level, guest[1]) && !Flights.padFree(level, a.pad) && !Flights.padFree(level, b.pad),
                    "visitors' pads free: " + Flights.padFree(level, guest[0]) + ", " + Flights.padFree(level, guest[1]));
        });
        s.until("traffic: the lines are verified", () -> {}, () -> net.pending() == 0, 2400);

        // ---- both fly at once, toward each other
        s.step("traffic: both aircraft are sent at the same moment, each to the other's airfield", () -> {
            for (Edge e : net.edges()) s.note(String.format("OBS line %s - %s: %s, %.1f long", e.a.toShortString(), e.b.toShortString(), e.state(), e.length()));
            watch(s, a, "A");
            watch(s, b, "B");
            Field[] both = {a, b};
            s.monitor(() -> {
                double[] ra = Flights.readings(a.aircraft), rb = Flights.readings(b.aircraft);
                if (ra == null || rb == null) return;
                if (a.aircraft.flying() && b.aircraft.flying()) {
                    double flat = Math.hypot(ra[0] - rb[0], ra[2] - rb[2]);
                    if (flat < nearest[0]) {
                        nearest[0] = flat;
                        nearest[1] = Math.abs(ra[1] - rb[1]);
                    }
                }
                for (int i = 0; i < 2; i++) {
                    Field f = both[i];
                    if (f.aircraft.phase == Phase.BOARDING && f.pilot != null && Boarding.state(f.pilot) == Boarding.State.SEATED) seatedWaiting[i]++;
                    if (f.aircraft.phase == Phase.CLIMB && otherAtTakeOff[i] == null) otherAtTakeOff[i] = both[1 - i].aircraft.phase;
                }
            });
            Flights.Answer first = Flights.send(level, a.pad, b.pad, false, a.pilot), second = Flights.send(level, b.pad, a.pad, false, b.pilot);
            s.soft("traffic / both flights are ordered, each to the other airfield's pad for visitors", first.ok() && second.ok() && guest[1].equals(a.aircraft.destination) && guest[0].equals(b.aircraft.destination),
                    first.text().getString() + " -> " + a.aircraft.destination + " | " + second.text().getString() + " -> " + b.aircraft.destination);
            s.soft("traffic / the pads they will land on count as taken at once", !Flights.padFree(level, guest[0]) && !Flights.padFree(level, guest[1]),
                    "free: " + Flights.padFree(level, guest[0]) + ", " + Flights.padFree(level, guest[1]));
            Flights.Answer which = Flights.cancel(level);
            s.soft("traffic / with two flights under way, calling one off needs to be told which", !which.ok() && which.key().equals("flight.cancel.which") && a.aircraft.phase == Phase.BOARDING && b.aircraft.phase == Phase.BOARDING,
                    which.key() + ", A " + a.aircraft.phase + ", B " + b.aircraft.phase);
        });
        s.until("traffic: both have landed", () -> {}, () -> a.aircraft.phase == Phase.PARKED && b.aircraft.phase == Phase.PARKED && a.clock > 100, 18000);
        s.step("traffic: result of the two flights", () -> {
            double[] ra = Flights.readings(a.aircraft), rb = Flights.readings(b.aircraft);
            s.soft("traffic / each aircraft stands on the other airfield's pad for visitors, fixed, within 1.5 of its middle", guest[1].equals(a.aircraft.at) && guest[0].equals(b.aircraft.at)
                    && ra != null && rb != null && ra[4] > 0 && rb[4] > 0 && ra[6] <= 1.5 && rb[6] <= 1.5, "A at " + a.aircraft.at + " " + at(ra) + " | B at " + b.aircraft.at + " " + at(rb));
            s.soft("traffic / one of them waited, seated on its pad, until the other was out of the way", (seatedWaiting[0] > 200) != (seatedWaiting[1] > 200),
                    "ticks seated before take-off: A " + seatedWaiting[0] + ", B " + seatedWaiting[1] + "; what the other was doing at take-off: A's saw " + otherAtTakeOff[0] + ", B's saw " + otherAtTakeOff[1]);
            s.soft("traffic / while both were in the air they were never nearer than 12 blocks, seen from above", nearest[0] >= 12, String.format("nearest %.1f blocks (%.1f apart in height then)", nearest[0], nearest[1]));
            boolean stopped = a.log.stream().anyMatch(l -> l.contains("-> HOLD") || l.contains("-> RETREAT")) || b.log.stream().anyMatch(l -> l.contains("-> HOLD") || l.contains("-> RETREAT"));
            s.soft("traffic / neither had to stop in the air for the other (each flew right over an aircraft standing on a pad)", !stopped, "stopped=" + stopped);
            s.soft("traffic / both pilots are alive and off their aircraft", a.pilot.isAlive() && b.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && Boarding.state(b.pilot) == Boarding.State.NONE,
                    "A " + Boarding.state(a.pilot) + " at " + a.pilot.position() + ", B " + Boarding.state(b.pilot) + " at " + b.pilot.position());
            s.soft("traffic / no plan is left in the air, and boarding had no internal failure", Flights.plans(level) == 0 && Boarding.failures() == 0, "plans " + Flights.plans(level) + ", failures " + Boarding.failures());
            s.note("OBS traffic: A " + a.clock + " ticks, B " + b.clock + " ticks; A: " + a.aircraft.note + " | B: " + b.aircraft.note);
        });

        // ---- a pad takes one aircraft
        s.settle("traffic: a moment (the timetable is written once a second)", 40);
        s.step("traffic: a pad takes one aircraft", () -> {
            s.soft("traffic / A's own pad is kept for A's aircraft while that is away; the pads the visitors stand on are taken; the bare pad is free",
                    !Flights.padFree(level, a.pad) && !Flights.padFree(level, guest[0]) && !Flights.padFree(level, guest[1]) && Flights.padFree(level, padC),
                    "free: A's own " + Flights.padFree(level, a.pad) + ", visitors' " + Flights.padFree(level, guest[0]) + " " + Flights.padFree(level, guest[1]) + ", bare " + Flights.padFree(level, padC));
            Flights.Answer made = Flights.spawn(level, a.pad);
            s.soft("traffic / no new aircraft can be made on the home pad of one that is away", !made.ok() && Flights.aircraft(level).size() == 2, made.key() + ", aircraft " + Flights.aircraft(level).size());
            Flights.Answer toC = Flights.send(level, guest[1], padC, false, a.pilot), alsoToC = Flights.send(level, guest[0], padC, false, b.pilot);
            s.soft("traffic / of two flights to a place with one pad the second is refused", toC.ok() && !alsoToC.ok() && alsoToC.key().equals("flight.no_free_pad") && b.aircraft.phase == Phase.PARKED,
                    toC.key() + " / " + alsoToC.key() + ": " + alsoToC.text().getString());
            Flights.Answer wrong = Flights.cancel(level, b.pad), off = Flights.cancel(level, a.pad);
            s.soft("traffic / a flight is called off by naming the home of its aircraft", !wrong.ok() && off.ok() && a.aircraft.phase == Phase.PARKED, wrong.key() + " / " + off.key() + ", A " + a.aircraft.phase);
        });
        s.settle("traffic: a moment", 40);
        s.step("traffic: the end", () -> {
            s.soft("traffic / the pad of a flight that was called off is free again", Flights.padFree(level, padC), "free=" + Flights.padFree(level, padC));
            s.clearMonitors();
        });
    }

    // ---- three aircraft at once ---------------------------------------------------------------------------------------

    private static void three(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        // Three colonies in a triangle, each with a level 2 airfield, an aircraft and a pilot. One buoy in the middle, as
        // high as the lines over the pads. A's and C's ways lead over the buoy; B has a direct line to C.
        Field a = new Field(HUT_A), b = new Field(new BlockPos(88, Y, 0)), c = new Field(new BlockPos(44, Y, -90));
        Field[] all = {a, b, c};
        String[] names = {"A", "B", "C"};
        BlockPos hub = new BlockPos(53, Y + 7, -50);
        BlockPos[] guest = new BlockPos[3];
        double[] nearest = {1e9, 0};
        int[] seatedWaiting = new int[3];
        s.step("three: keep the area loaded", () -> { for (int cx = -2; cx <= 8; cx++) for (int cz = -9; cz <= 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); } });
        for (int i = 0; i < 3; i++) addField(s, level, all[i], names[i]);
        s.step("three: level 2 everywhere, a buoy in the middle", () -> {
            for (int i = 0; i < 3; i++) guest[i] = upgrade(level, all[i]);
            level.setBlockAndUpdate(hub, Airline.BUOYS.get(1).get().defaultBlockState());
            for (int i = 0; i < 3; i++) {
                List<BlockPos> way = net.route(all[i].pad, guest[(i + 1) % 3], ShipClass.SMALL);
                s.note("OBS three: way of " + names[i] + " to the next airfield's pad for visitors: " + way + " (" + Math.round(net.length(way)) + " blocks)");
            }
        });
        s.until("three: the lines are verified", () -> {}, () -> net.pending() == 0, 3000);
        s.step("three: all three are sent at the same moment, each to the next airfield", () -> {
            long barred = net.edges().stream().filter(e -> e.barredBy() != null).count(), blocked = net.edges().stream().filter(e -> e.state() == State.BLOCKED).count();
            s.soft("three / no line of this network is blocked or passes too low over a pad", barred == 0 && blocked == 0, net.edges().size() + " lines, barred " + barred + ", blocked " + blocked);
            for (int i = 0; i < 3; i++) watch(s, all[i], names[i]);
            s.monitor(() -> {
                for (int i = 0; i < 3; i++) {
                    Field f = all[i];
                    if (f.aircraft.phase == Phase.BOARDING && f.pilot != null && Boarding.state(f.pilot) == Boarding.State.SEATED) seatedWaiting[i]++;
                    for (int j = i + 1; j < 3; j++) {
                        if (!f.aircraft.flying() || !all[j].aircraft.flying()) continue;
                        double[] ra = Flights.readings(f.aircraft), rb = Flights.readings(all[j].aircraft);
                        if (ra == null || rb == null) continue;
                        double flat = Math.hypot(ra[0] - rb[0], ra[2] - rb[2]);
                        if (flat < nearest[0]) {
                            nearest[0] = flat;
                            nearest[1] = Math.abs(ra[1] - rb[1]);
                        }
                    }
                }
            });
            boolean ordered = true;
            StringBuilder said = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                Flights.Answer sent = Flights.send(level, all[i].pad, all[(i + 1) % 3].pad, false, all[i].pilot);
                ordered &= sent.ok() && guest[(i + 1) % 3].equals(all[i].aircraft.destination);
                said.append(names[i]).append(": ").append(sent.key()).append(" -> ").append(all[i].aircraft.destination).append(" over ").append(all[i].aircraft.route.size()).append(" points; ");
            }
            s.soft("three / all three flights are ordered, each to the next airfield's pad for visitors", ordered, said.toString());
        });
        s.until("three: all have landed", () -> {}, () -> a.aircraft.phase == Phase.PARKED && b.aircraft.phase == Phase.PARKED && c.aircraft.phase == Phase.PARKED && a.clock > 100, 26000);
        s.step("three: result", () -> {
            boolean landed = true, stopped = false, pilots = true;
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                Field f = all[i];
                double[] r = Flights.readings(f.aircraft);
                landed &= guest[(i + 1) % 3].equals(f.aircraft.at) && r != null && r[4] > 0 && r[6] <= 1.5;
                stopped |= f.log.stream().anyMatch(l -> l.contains("-> HOLD") || l.contains("-> RETREAT"));
                pilots &= f.pilot.isAlive() && Boarding.state(f.pilot) == Boarding.State.NONE;
                where.append(names[i]).append(" at ").append(f.aircraft.at).append(" ").append(at(r)).append(", seated before take-off ").append(seatedWaiting[i]).append(" ticks; ");
            }
            s.soft("three / each aircraft stands on the next airfield's pad for visitors, fixed, within 1.5 of its middle", landed, where.toString());
            s.soft("three / at least one of them waited, seated on its pad, for the air to be free", seatedWaiting[0] > 200 || seatedWaiting[1] > 200 || seatedWaiting[2] > 200,
                    "ticks seated before take-off: " + seatedWaiting[0] + ", " + seatedWaiting[1] + ", " + seatedWaiting[2]);
            s.soft("three / while two were in the air they were never nearer than 12 blocks, seen from above", nearest[0] >= 12, String.format("nearest %.1f blocks (%.1f apart in height then)", nearest[0], nearest[1]));
            s.soft("three / none of them had to stop in the air for another", !stopped, "stopped=" + stopped);
            s.soft("three / all pilots are alive and off their aircraft; no plan is left in the air; boarding had no internal failure", pilots && Flights.plans(level) == 0 && Boarding.failures() == 0,
                    "pilots " + pilots + ", plans " + Flights.plans(level) + ", failures " + Boarding.failures());
            s.note("OBS three: " + a.clock + " ticks in all");
            s.clearMonitors();
        });
    }

    // ---- going round a ship that is in the way --------------------------------------------------------------------------

    private static void detour(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        // As in the first scene: pad B 80 blocks east over a direct line, and a buoy off to the side as the other way.
        // The first aircraft is given up in the air on the direct line (a failure made on purpose): fixed there, it is a
        // ship in the way that the network knows nothing of. A second aircraft of the same airfield then flies to pad B.
        Field a = new Field(HUT_A), b = new Field(HUT_A);
        addField(s, level, a, "detour");
        BlockPos padB = new BlockPos(80, Y - 1, -10), buoy = new BlockPos(40, Y, -60);
        ICitizenData[] second = new ICitizenData[1];
        boolean[] done = new boolean[1];
        s.step("detour: pad B, a buoy to the side, a second citizen", () -> {
            for (int cx = -2; cx <= 6; cx++) for (int cz = -5; cz <= 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            pad(level, padB, 0);
            level.setBlockAndUpdate(buoy, Airline.BUOYS.get(0).get().defaultBlockState());
            second[0] = Fixture.spawn(a.colony, level, a.hut.offset(-2, 0, 2));
            watch(s, a, "first");
        });
        s.until("detour: second citizen, lines verified", () -> {}, () -> (b.pilot = Fixture.entity(second[0])) != null && net.pending() == 0, 1200);
        s.step("detour: the first aircraft flies the direct line and is given up on it", () -> {
            s.soft("detour / the direct line is clear and is the way; there is another way over the buoy", net.edge(a.pad, padB).state() == State.CLEAR && net.route(a.pad, padB, ShipClass.SMALL).size() == 2
                    && net.route(a.pad, padB, ShipClass.SMALL, List.of(net.edge(a.pad, padB))).equals(List.of(a.pad, buoy, padB)), net.route(a.pad, padB, ShipClass.SMALL) + " / " + net.route(a.pad, padB, ShipClass.SMALL, List.of(net.edge(a.pad, padB))));
            s.monitor(() -> {
                double[] r = Flights.readings(a.aircraft);
                if (r != null && !done[0] && a.aircraft.phase == Phase.CRUISE && r[0] > 35) {
                    Flights.failOnceForChecks();
                    done[0] = true;
                }
            });
            Flights.Answer sent = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("detour / the first flight is ordered", sent.ok(), sent.text().getString());
        });
        s.until("detour: the first aircraft is given up in the air", () -> {}, () -> a.aircraft.phase == Phase.STRANDED, 4000);
        s.step("detour: a second aircraft on pad A", () -> {
            s.note("OBS detour: the first aircraft stands fixed in the air at " + at(Flights.readings(a.aircraft)));
            Flights.Answer made = spawn(level, a.pad);
            s.soft("detour / with the first aircraft given up, its home pad is free: a second aircraft is made there", made.ok(), made.text().getString());
            for (AircraftBlockEntity each : Flights.aircraft(level)) if (each != a.aircraft && a.pad.equals(each.at)) b.aircraft = each;
            s.check(b.aircraft != null, "the second aircraft is found on pad A");
            watch(s, b, "second");
        });
        loaded(s, "detour", () -> b.aircraft);
        s.settle("detour: the second aircraft's balloon fills", 320);
        s.step("detour: the second aircraft is sent the same way", () -> {
            Flights.Answer sent = Flights.send(level, a.pad, padB, false, b.pilot);
            s.soft("detour / the second flight is ordered over the direct line (the network knows nothing of the ship on it)", sent.ok() && b.aircraft.route.size() == 2, sent.text().getString() + " " + b.aircraft.route);
        });
        s.until("detour: the second flight ends", () -> {}, () -> b.aircraft.phase == Phase.PARKED && b.clock > 100, 14000);
        s.step("detour: result", () -> {
            double[] r = Flights.readings(b.aircraft);
            long holds = b.log.stream().filter(l -> l.contains("-> HOLD")).count();
            boolean wentBack = b.log.stream().anyMatch(l -> l.contains("-> RETREAT"));
            // (It looks, waits, looks again: at the third look, or the sixth for the one with the higher id, it goes round instead of waiting once more.)
            s.soft("detour / it waited for the ship ahead at least twice, then went back to the last point", holds >= 2 && wentBack, "waited " + holds + " times, went back " + wentBack);
            s.soft("detour / it went round over the buoy and landed at pad B; the flight was not cancelled", padB.equals(b.aircraft.at) && !b.aircraft.cancelled && r != null && r[4] > 0 && r[6] <= 1.5
                    && b.aircraft.route.contains(buoy), "at " + b.aircraft.at + " " + at(r) + ", way " + b.aircraft.route + " | " + b.aircraft.note);
            s.soft("detour / the line with the ship on it is not blocked for the network", net.edge(a.pad, padB).state() == State.CLEAR, String.valueOf(net.edge(a.pad, padB).state()));
            s.soft("detour / the pilot is alive and off the aircraft at pad B", b.pilot.isAlive() && Boarding.state(b.pilot) == Boarding.State.NONE && b.pilot.getY() < Y + 0.6
                    && b.pilot.position().distanceTo(padB.getCenter()) < 12, "pilot at " + b.pilot.position() + " state " + Boarding.state(b.pilot));
            s.soft("detour / no plan is left in the air", Flights.plans(level) == 0, "plans " + Flights.plans(level));
            s.note("OBS detour: the second flight took " + b.clock + " ticks");
            // The given-up aircraft is taken away (its pilot comes back to the ground).
            List<String> said = command(level, "airline aircraft clear");
            s.note("OBS detour: aircraft clear: " + said);
            s.clearMonitors();
        });
    }

    // ---- a long flight: is a seated pilot taken back by its colony? ---------------------------------------------

    /**
     * A direct flight between two airfields on different large maps, with no buoy anywhere: the line is 1800 blocks long,
     * runs through country that is not loaded and is flown while it is "not verified" (ruling of 2026-10-13: airfields
     * reach each other by large maps). Meant for a fast design (direct@koeda); the first design
     * needs twenty.
     */
    private static void direct(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        Field a = new Field(HUT_A);
        // The scenes of this suite run without the rule of the large maps; this one is about it.
        s.step("direct: large maps as the mod comes", () -> Fixture.largeMapChunks(128));
        addField(s, level, a, "direct");
        // Pad A is at (0, 99, -10), its hut at (-1, 100, 4). The other pad lies 1815 blocks away, north and a little east.
        // The large maps (2048 blocks a side) are counted from where the airfields stand, their hut blocks: A's hut is
        // on the map south of z = 0, the other pad (set by hand: it stands for its own hut) on the map north of it.
        BlockPos padC = new BlockPos(300, Y - 1, -1800);
        double[] farthest = new double[1];
        long[] fuel = new long[1];
        boolean[] seatedAllTheWay = {true};
        s.step("direct: a level 2 pad on the next large map, and the flight", () -> {
            pad(level, padC, 270);
            net.setAirfield(padC, 270, 2);
            List<BlockPos> way = net.route(a.pad, padC, ShipClass.SMALL);
            Edge line = net.edge(a.pad, padC);
            s.soft("direct / two airfields on large maps that touch (level 1 and level 2) have a direct line, and it is the route",
                    com.asteain.airline.route.RouteRules.mapsApart(net.node(a.pad).home(), net.node(padC).home()) == 1 && line != null && way.equals(List.of(a.pad, padC)) && net.nodes().size() == 2,
                    way + ", " + net.nodes().size() + " points, line " + (line == null ? "none" : String.format("%s, %.0f blocks", line.state(), line.length()))
                            + ", maps apart " + com.asteain.airline.route.RouteRules.mapsApart(net.node(a.pad).home(), net.node(padC).home()) + " (from " + net.node(a.pad).home().toShortString() + " and " + net.node(padC).home().toShortString() + ")");
            watch(s, a, "direct");
            s.monitor(() -> {
                if (a.aircraft.flying()) {
                    farthest[0] = Math.max(farthest[0], Math.hypot(a.pilot.getX() - a.hut.getX(), a.pilot.getZ() - a.hut.getZ()));
                    if (Boarding.state(a.pilot) != Boarding.State.SEATED || !a.pilot.isPassenger()) {
                        if (seatedAllTheWay[0]) s.note(String.format("FLT direct: the pilot is NOT on the seat at t=%d, at %s, state %s", a.clock, a.pilot.position(), Boarding.state(a.pilot)));
                        seatedAllTheWay[0] = false;
                    }
                }
            });
            fuel[0] = Flights.fuelTicks(a.aircraft);
            Flights.Answer sent = Flights.send(level, a.pad, padC, false, a.pilot);
            s.soft("direct / the flight is ordered (the tank holds enough for it)", sent.ok(), sent.text().getString() + " | tank: " + Flights.fuelMinutes(a.aircraft) + " minutes");
        });
        s.until("direct: flown, landed, pilot off", () -> {}, () -> a.clock > 100 && a.aircraft.phase == Phase.PARKED, 26000);
        s.step("direct: result", () -> {
            double[] r = Flights.readings(a.aircraft);
            double miss = r == null ? 1e9 : Math.hypot(r[0] - (padC.getX() + 0.5), r[2] - (padC.getZ() + 0.5));
            Edge line = net.edge(a.pad, padC);
            s.soft("direct / the aircraft landed on the far pad, within 1.5 blocks of its middle", padC.equals(a.aircraft.at) && miss < 1.5,
                    String.format("at %s, %.2f from the middle of the far pad | %s", at(r), miss, a.aircraft.note));
            s.soft("direct / the pilot stayed on the seat all the way (more than 1700 blocks from its colony) and got off alive",
                    seatedAllTheWay[0] && farthest[0] > 1700 && a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE,
                    String.format("farthest from the hut while flying %.0f, state %s, alive %s", farthest[0], Boarding.state(a.pilot), a.pilot.isAlive()));
            s.soft("direct / the line, flown from end to end, counts as clear now", line != null && line.state() == State.CLEAR, line == null ? "no line" : line.state().name());
            long used = fuel[0] - Flights.fuelTicks(a.aircraft);
            s.note(String.format("OBS direct: %d ticks from the order to parked on the far pad (%.0f seconds), top speed %.2f, fuel used %d ticks of one engine (%.1f pieces of charcoal), tank now %d minutes",
                    a.clock, a.clock / 20.0, a.speedMax, used, used / 1600.0, Flights.fuelMinutes(a.aircraft)));
            for (String l : a.log) if (l.contains("->")) s.note("OBS direct: " + l);
            s.clearMonitors();
        });
    }

    private static void far(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        Field a = new Field(HUT_A);
        addField(s, level, a, "far");
        // Pad A is at (0, 99, -10) with the nose to the north. Two grade 3 buoys carry the line 700 blocks north.
        BlockPos b1 = new BlockPos(0, Y, -240), b2 = new BlockPos(0, Y, -610), padC = new BlockPos(0, Y - 1, -720);
        double[] farthest = new double[2];
        boolean[] seatedAllTheWay = {true}, landed = new boolean[1];
        int[] gone = new int[1];
        s.step("far: two grade 3 buoys and a pad 710 blocks away", () -> {
            level.setBlockAndUpdate(b1, Airline.BUOYS.get(2).get().defaultBlockState());
            level.setBlockAndUpdate(b2, Airline.BUOYS.get(2).get().defaultBlockState());
            pad(level, padC, 270);
            List<BlockPos> way = net.route(a.pad, padC, ShipClass.SMALL);
            s.soft("far / the route is pad, buoy, buoy, pad", way.equals(List.of(a.pad, b1, b2, padC)), way + " length " + net.length(way));
            watch(s, a, "far");
            s.monitor(() -> {
                if (a.aircraft.flying()) {
                    double away = Math.hypot(a.pilot.getX() - a.hut.getX(), a.pilot.getZ() - a.hut.getZ());
                    farthest[0] = Math.max(farthest[0], away);
                    if (Boarding.state(a.pilot) != Boarding.State.SEATED || !a.pilot.isPassenger()) {
                        if (seatedAllTheWay[0]) s.note(String.format("FLT far: the pilot is NOT on the seat at t=%d, %.0f blocks from the hut, at %s, state %s", a.clock, away, a.pilot.position(), Boarding.state(a.pilot)));
                        seatedAllTheWay[0] = false;
                    }
                    double[] r = Flights.readings(a.aircraft);
                    if (r != null) farthest[1] = Math.max(farthest[1], Math.hypot(a.pilot.getX() - r[0], a.pilot.getZ() - r[2]));
                }
            });
            s.monitor(() -> {
                if (gone[0] == 0 && a.pilot.isRemoved()) {
                    gone[0] = a.clock;
                    s.note(String.format("FLT far: the pilot's entity was removed at t=%d (%s) while the aircraft was %s, aircraft loaded=%s, at %s", a.clock, a.pilot.getRemovalReason(), a.aircraft.phase,
                            Flights.readings(a.aircraft) != null, a.pilot.position()));
                }
                if (a.aircraft.phase == Phase.DISEMBARK) landed[0] = true;
            });
            // Ordered as a round trip: after landing the aircraft keeps its ticket, waits ten seconds and calls the pilot back.
            Flights.Answer sent = Flights.send(level, a.pad, padC, true, a.pilot);
            s.soft("far / the flight is ordered", sent.ok(), sent.text().getString());
        });
        s.until("far: out, landed, and the pilot seated again for the way back", () -> {},
                () -> a.clock > 100 && (a.aircraft.phase == Phase.PARKED || (landed[0] && a.aircraft.phase == Phase.CLIMB)), 14000);
        s.step("far: result", () -> {
            double[] r = Flights.readings(a.aircraft);
            double away = Math.hypot(a.pilot.getX() - a.hut.getX(), a.pilot.getZ() - a.hut.getZ());
            s.soft("far / the pilot stayed on the seat all the way, more than 600 blocks from its colony", seatedAllTheWay[0] && farthest[0] > 600,
                    String.format("farthest from the hut while flying %.0f; largest distance between pilot and ship %.2f", farthest[0], farthest[1]));
            s.soft("far / the aircraft landed on the far pad", landed[0] && a.log.stream().anyMatch(l -> l.contains("-> DISEMBARK")), a.aircraft.note);
            s.soft("far / the pilot got off, waited ten seconds on the far pad, was not removed or taken home, and sits on the seat again for the way back",
                    gone[0] == 0 && a.pilot.isAlive() && away > 600 && a.aircraft.phase == Phase.CLIMB && Boarding.state(a.pilot) == Boarding.State.SEATED,
                    String.format("%.0f blocks from the hut, state %s, aircraft %s, removed at t=%d", away, Boarding.state(a.pilot), a.aircraft.phase, gone[0]));
            s.note(String.format("OBS far: %d ticks, top speed %.2f, lines %s", a.clock, a.speedMax, net.edges().stream().map(e -> e.state().name()).toList()));
        });
        s.settle("far: the first seconds of the way back", 100);
        s.step("far: a little later", () -> {
            double away = Math.hypot(a.pilot.getX() - a.hut.getX(), a.pilot.getZ() - a.hut.getZ());
            // The flight is over, the ticket is gone, nobody keeps the far pad loaded: the entity may have been unloaded with the place.
            boolean unloaded = a.pilot.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK;
            Mob now = Fixture.entity(a.pilotData);
            s.soft("far / the pilot is still seated a few seconds into the way back", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.SEATED && (now == null || now == a.pilot) && !unloaded,
                    String.format("%.0f blocks from the hut, at %s", away, a.pilot.position()));
            s.note("OBS far: a minute after the flight ended: pilot entity still loaded=" + !a.pilot.isRemoved() + ", removal reason " + a.pilot.getRemovalReason()
                    + ", the colony has made a new entity for the citizen=" + (now != null && now != a.pilot) + ", aircraft still loaded=" + (Flights.readings(a.aircraft) != null && !a.aircraft.isRemoved()));
            s.clearMonitors();
        });
    }

    // ---- stage 5: cargo ---------------------------------------------------------------------------------------------

    /** A warehouse with two racks, and a courier who serves it. The courier's entity is fetched later. */
    private static AbstractBuilding warehouse(ServerLevel level, IColony colony, BlockPos at, ICitizenData[] courier, int slot) throws Exception {
        AbstractBuilding warehouse = Fixture.placeHut(level, colony, ModBlocks.blockHutWareHouse, at);
        warehouse.setBuildingLevel(1);
        warehouse.setCorners(at.offset(-3, 0, -3), at.offset(3, 2, 3));
        Fixture.rack(level, warehouse, at.offset(2, 0, 0));
        Fixture.rack(level, warehouse, at.offset(2, 0, 2));
        AbstractBuilding hut = Fixture.placeHut(level, colony, ModBlocks.blockHutDeliveryman, at.offset(0, 0, 6));
        hut.setBuildingLevel(1);
        hut.setCorners(at.offset(-2, 0, 4), at.offset(2, 2, 8));
        courier[slot] = Fixture.spawn(colony, level, at.offset(0, 0, 3));
        return warehouse;
    }

    private static int iron(net.neoforged.neoforge.items.IItemHandler in) {
        return com.asteain.airline.flight.Cargo.count(in, Items.IRON_INGOT);
    }

    private static void cargo(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        MinecraftServer server = level.getServer();
        WantLedger ledger = WantLedger.get(server);
        Field a = new Field(HUT_A);
        addField(s, level, a, "cargo A");
        BlockPos hutB = new BlockPos(208, Y, 0), buoy = new BlockPos(104, Y, -10);
        IColony[] colonyB = new IColony[1];
        BuildingAirfield[] airfieldB = new BuildingAirfield[1];
        AbstractBuilding[] store = new AbstractBuilding[4];      // warehouse A, warehouse B, stash A, stash B
        ICitizenData[] courier = new ICitizenData[2];
        com.minecolonies.api.colony.buildings.IBuilding[] hallB = new com.minecolonies.api.colony.buildings.IBuilding[1];
        int[] n = new int[8];
        boolean[] flag = new boolean[8];
        long[] t = new long[2];
        String dim = level.dimension().location().toString();
        s.step("cargo: colony A gets a warehouse with 192 iron, a courier, export racks, an import stash", () -> {
            a.airfield.setBuildingLevel(1);
            a.airfield.setCorners(a.hut.offset(-3, 0, -3), a.hut.offset(3, 2, 3));
            Fixture.rack(level, a.airfield, a.hut.offset(-2, 0, 0));
            Fixture.rack(level, a.airfield, a.hut.offset(2, 0, 0));
            Fixture.platform(level, a.hut.offset(0, 0, 14), 10, 8);
            store[0] = warehouse(level, a.colony, a.hut.offset(-4, 0, 12), courier, 0);
            Fixture.stock((TileEntityRack) level.getBlockEntity(a.hut.offset(-2, 0, 12)), Items.IRON_INGOT, 192);
            // Well away from the lines over the pad: anything two blocks high within 8.5 blocks of a line blocks it.
            store[2] = Fixture.placeHut(level, a.colony, ModBlocks.blockStash, a.hut.offset(5, 0, 3));
            store[2].setBuildingLevel(1);
            // Keep one stack at home; export the rest when somebody wants it.
            a.airfield.getFirstModuleOccurance(ExportStockModule.class).addMinimumStock(new ItemStack(Items.IRON_INGOT), 1);
        });
        s.step("cargo: colony B, 208 blocks east: airfield with racks (20 coal), stash, warehouse, courier; a buoy between", () -> {
            Fixture.platform(level, hutB.offset(1, 0, -8), 10, 16);
            Fixture.platform(level, hutB.offset(0, 0, 14), 10, 8);
            colonyB[0] = Fixture.create(level, hutB.offset(4, 0, 4), "Airline cargo B");
            // With its block: B's courier delivers the iron to this town hall in the end (finish, scene 7).
            hallB[0] = Fixture.townHallWithBlock(level, colonyB[0], hutB.offset(4, 0, 4));
            airfieldB[0] = (BuildingAirfield) Fixture.placeHut(level, colonyB[0], Airline.AIRFIELD_HUT.get(), hutB);
            airfieldB[0].setBuildingLevel(1);
            airfieldB[0].setCorners(hutB.offset(-3, 0, -3), hutB.offset(3, 2, 3));
            Fixture.stock(Fixture.rack(level, airfieldB[0], hutB.offset(-2, 0, 0)), Items.COAL, 20);
            Fixture.rack(level, airfieldB[0], hutB.offset(2, 0, 0));
            store[3] = Fixture.placeHut(level, colonyB[0], ModBlocks.blockStash, hutB.offset(5, 0, 3));
            store[3].setBuildingLevel(1);
            store[1] = warehouse(level, colonyB[0], hutB.offset(-4, 0, 12), courier, 1);
            level.setBlockAndUpdate(buoy, Airline.BUOYS.get(1).get().defaultBlockState());
            List<BlockPos> way = net.route(a.pad, airfieldB[0].pad(), ShipClass.SMALL);
            s.soft("cargo / the two airfields are joined over a grade 2 buoy", way.size() == 3, way + " length " + net.length(way));
            s.soft("cargo / each airfield finds its import store (a MineColonies stash near the pad)", com.asteain.airline.flight.Cargo.importStore(level, a.airfield) != null
                    && com.asteain.airline.flight.Cargo.importStore(level, airfieldB[0]) != null, "A " + com.asteain.airline.flight.Cargo.importStore(level, a.airfield) + ", B " + com.asteain.airline.flight.Cargo.importStore(level, airfieldB[0]));
        });
        s.until("cargo: couriers", () -> {}, () -> Fixture.entity(courier[0]) != null && Fixture.entity(courier[1]) != null, 300);
        s.step("cargo: hire the couriers; colony B is short of 64 iron, colony A would like 20 coal", () -> {
            for (int i = 0; i < 2; i++) {
                IColony colony = i == 0 ? a.colony : colonyB[0];
                BlockPos hut = (i == 0 ? a.hut : hutB).offset(-4, 0, 18);
                AbstractBuilding courierHut = (AbstractBuilding) colony.getServerBuildingManager().getBuilding(hut);
                s.check(courierHut.getFirstModuleOccurance(WorkerBuildingModule.class).assignCitizen(courier[i]), "courier " + i + " is hired");
                s.check(store[i].getModule(BuildingModules.WAREHOUSE_COURIERS).assignCitizen(courier[i]), "courier " + i + " serves the warehouse");
            }
            // B's want is not typed in: its town hall asks for 64 iron, nobody in B can supply it, B's airfield reports it.
            IRequestManager requests = colonyB[0].getRequestManager();
            IToken<?> unmet = colonyB[0].getServerBuildingManager().getTownHall().createRequest(new Stack(new ItemStack(Items.IRON_INGOT), 64, 64), true);
            int rounds = 0;
            while (rounds < 8000 && !(requests.getResolverForRequest(unmet) instanceof IPlayerRequestResolver)) { requests.tick(); rounds++; }
            airfieldB[0].getFirstModuleOccurance(ExportStockModule.class).onColonyTick(colonyB[0]);
            WantLedger.Key keyA = WantLedger.Key.of(a.colony), keyB = WantLedger.Key.of(colonyB[0]);
            s.soft("cargo / colony B's shortage is in the ledger without a command", ledger.wantedAutomatically(keyB, Items.IRON_INGOT) == 64, "wanted " + ledger.wanted(keyB, Items.IRON_INGOT));
            // The way back: colony A's want is entered the way the command would (this part is not the command-free one).
            ledger.set(keyA, Items.COAL, 20);
            // Colony A's airfield looks at the ledger (the colony tick does this every 25 seconds when a player is near).
            a.airfield.getFirstModuleOccurance(ExportStockModule.class).onColonyTick(a.colony);
            n[0] = Fixture.count(store[0], Items.IRON_INGOT) + iron(a.airfield.getItemHandlerCap());
            s.note("OBS cargo: iron in colony A at the start " + n[0] + " (warehouse " + Fixture.count(store[0], Items.IRON_INGOT) + ")");
            watch(s, a, "cargo");
            // Couriers empty a stash within seconds, so the most that was ever seen is kept: iron in B's warehouse, coal in A's stash and warehouse.
            s.monitor(() -> {
                n[6] = Math.max(n[6], Fixture.count(store[1], Items.IRON_INGOT));
                n[7] = Math.max(n[7], com.asteain.airline.flight.Cargo.count(store[2].getItemHandlerCap(), Items.COAL) + Fixture.count(store[0], Items.COAL));
            });
        });
        s.attempt("cargo: A's courier brings 64 iron to the export racks", () -> {}, () -> flag[0] = iron(a.airfield.getItemHandlerCap()) >= 64, 3000, () -> {
            // Stage 3 showed the courier doing this; if this one does not, the goods are put there by hand so that the flight can be checked.
            s.note("OBS cargo: the courier did not bring the iron within 3000 ticks; 64 are moved by hand");
            com.asteain.airline.flight.Cargo.move(store[0].getItemHandlerCap(), a.airfield.getItemHandlerCap(), Items.IRON_INGOT, 64);
        });
        s.step("cargo: on the racks", () -> {
            a.clock = 0;
            a.since = 0;
            s.soft("cargo / the surplus was carried from A's warehouse to A's export racks by A's courier", flag[0], "by courier=" + flag[0] + ", racks " + iron(a.airfield.getItemHandlerCap()) + ", warehouse " + Fixture.count(store[0], Items.IRON_INGOT));
        });
        s.until("cargo: the flight starts by itself, flies, unloads, comes back", () -> {}, () -> {
            com.asteain.airline.flight.Timetable table = com.asteain.airline.flight.Timetable.get(server);
            if (!flag[5] && a.aircraft.phase == Phase.CRUISE && !a.aircraft.returning) {
                // On the way out: B's airfield knows that an aircraft is coming, and about when.
                com.asteain.airline.flight.Timetable.Entry coming = table.coming(dim, airfieldB[0].pad());
                if (coming != null && coming.flying() && coming.arrives() > level.getGameTime()) {
                    flag[5] = true;
                    t[0] = coming.arrives() - level.getGameTime();
                }
            }
            if (a.aircraft.phase == Phase.UNLOAD && !flag[1] && airfieldB[0].pad().equals(a.aircraft.at) && a.aircraft.settled) {
                flag[1] = true;
                n[1] = iron(store[3].getItemHandlerCap());
                n[2] = ledger.net(WantLedger.Key.of(colonyB[0]), Items.IRON_INGOT);
                // Counted now, with everything at rest: later B's own couriers carry the iron on to whoever asked for it.
                n[5] = Fixture.count(store[0], Items.IRON_INGOT) + iron(a.airfield.getItemHandlerCap()) + n[1] + Fixture.count(store[1], Items.IRON_INGOT);
                flag[4] = colonyB[0].isActive();
                // Berthed at B: B's airfield knows when it leaves, A's airfield knows when it comes home.
                com.asteain.airline.flight.Timetable.Entry standing = table.standing(dim, airfieldB[0].pad()), home = table.coming(dim, a.pad);
                flag[6] = standing != null && a.pad.equals(standing.to()) && standing.leaves() == a.aircraft.landed + BERTH_LONG && home != null && home.ship().equals(standing.ship())
                        && home.arrives() > standing.leaves();
                t[1] = standing == null ? -1 : standing.leaves() - level.getGameTime();
                // The same, as the airfields' windows get it: B's tab has a departure (bound for colony A), A's tab an arrival (its own aircraft, berthed).
                net.minecraft.network.RegistryFriendlyByteBuf tabB = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
                net.minecraft.network.RegistryFriendlyByteBuf tabA = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
                airfieldB[0].getFirstModuleOccurance(com.asteain.airline.colony.TimetableModule.class).serializeToView(tabB);
                a.airfield.getFirstModuleOccurance(com.asteain.airline.colony.TimetableModule.class).serializeToView(tabA);
                boolean standsAtB = tabB.readBoolean();
                String stateAtB = standsAtB ? tabB.readUtf() : "";
                long leavesB = standsAtB ? tabB.readLong() : 0;
                String boundFor = standsAtB ? tabB.readUtf() : "";
                boolean standsAtA = tabA.readBoolean(), comesToA = tabA.readBoolean(), flyingToA = comesToA && tabA.readBoolean();
                long arrivesA = comesToA ? tabA.readLong() : 0;
                String whose = comesToA ? tabA.readUtf() : "";
                flag[7] = standsAtB && stateAtB.equals("UNLOAD") && leavesB == a.aircraft.landed + BERTH_LONG && boundFor.equals(a.colony.getName())
                        && !standsAtA && comesToA && !flyingToA && arrivesA > leavesB && whose.equals(a.colony.getName());
                s.note("OBS cargo: tab at B: stands=" + standsAtB + " " + stateAtB + " bound for '" + boundFor + "'; tab at A: stands=" + standsAtA + " comes=" + comesToA + " flying=" + flyingToA + " whose='" + whose + "'");
                s.note("OBS cargo: timetable at B: " + standing + "; expected at home in " + (home == null ? "?" : com.asteain.airline.flight.Timetable.clock(home.arrives() - level.getGameTime())));
                s.note("OBS cargo: while the goods were unloaded colony B was " + colonyB[0].getState() + " (active=" + flag[4] + "; nobody is near it)");
                s.note(String.format("FLT cargo: at B after unloading: stash holds %d iron, B still wants (net) %d, pilot %s, manifest back %s", n[1], n[2], Boarding.state(a.pilot), a.aircraft.returnManifest));
            }
            return a.clock > 200 && a.aircraft.phase == Phase.PARKED && flag[1];
        }, 14000);
        s.step("cargo: result", () -> {
            WantLedger.Key keyA = WantLedger.Key.of(a.colony), keyB = WantLedger.Key.of(colonyB[0]);
            boolean flew = a.log.stream().anyMatch(l -> l.contains("-> LOAD")) && a.log.stream().filter(l -> l.contains("-> UNLOAD")).count() == 2;
            s.soft("cargo / without any command the aircraft loaded, flew to B, unloaded, and came home", flew && a.pad.equals(a.aircraft.at), "at " + a.aircraft.at + " | " + a.aircraft.note);
            s.soft("cargo / 64 iron arrived in B's import store, exactly what B wanted", n[1] == 64 && n[2] == 0, "stash " + n[1] + ", net want afterwards " + n[2]);
            s.soft("cargo / scheduled: it took off twice, and never before it had stood a whole berth (" + BERTH_LONG + " ticks) on the pad", a.cargoTakeoffs == 2 && a.shortestBerth >= BERTH_LONG && a.shortestBerth < 100000,
                    a.cargoTakeoffs + " take-offs, the shortest stay before one " + a.shortestBerth + " ticks");
            com.asteain.airline.flight.Timetable.Entry parked = com.asteain.airline.flight.Timetable.get(server).standing(dim, a.pad);
            s.soft("cargo / timetable: B's airfield knew of the aircraft on its way, then when it would leave; A's airfield knew when it would be home; now it stands at home with nothing planned",
                    flag[5] && flag[6] && flag[7] && parked != null && parked.to() == null && com.asteain.airline.flight.Timetable.get(server).coming(dim, airfieldB[0].pad()) == null,
                    "coming to B seen=" + flag[5] + " (expected in " + t[0] + " ticks), berthed at B right=" + flag[6] + " (leaving in " + t[1] + " ticks), the windows' tabs right=" + flag[7] + ", at home " + parked);
            int coalHome = com.asteain.airline.flight.Cargo.count(store[2].getItemHandlerCap(), Items.COAL);
            s.soft("cargo / on the way back it carried the 20 coal colony A wanted, into A's import store; A's want is met", n[7] == 20 && ledger.wanted(keyA, Items.COAL) == 0
                    && com.asteain.airline.flight.Cargo.count(airfieldB[0].getItemHandlerCap(), Items.COAL) == 0 && ledger.history().stream().anyMatch(l -> l.contains("brought 20")),
                    "most coal seen in A's stash and warehouse " + n[7] + " (stash now " + coalHome + ", warehouse now " + Fixture.count(store[0], Items.COAL) + "), A still wants " + ledger.wanted(keyA, Items.COAL));
            int ironAll = Fixture.count(store[0], Items.IRON_INGOT) + iron(a.airfield.getItemHandlerCap()) + iron(store[3].getItemHandlerCap()) + Fixture.count(store[1], Items.IRON_INGOT);
            Mob carrierB = Fixture.entity(courier[1]);
            int carried = carrierB instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen c ? iron(c.getInventoryCitizen()) : 0;
            n[3] = ironAll + carried;
            s.soft("cargo / no iron was made or lost (counted when the unloading at B ended)", n[5] == n[0], "then " + n[5] + ", at the start " + n[0] + "; now " + n[3] + " (A warehouse " + Fixture.count(store[0], Items.IRON_INGOT) + ", A racks " + iron(a.airfield.getItemHandlerCap()) + ", B stash "
                    + iron(store[3].getItemHandlerCap()) + ", B warehouse " + Fixture.count(store[1], Items.IRON_INGOT) + ", B courier " + carried + "), at the start " + n[0]);
            int[] walks = Flights.handling(a.aircraft);
            s.soft("cargo / the pilot carried the goods on foot: out of the racks into the hold, out of the hold into B's store, B's coal into the hold, and into A's store at home; no walk had to be given up",
                    walks != null && walks[0] >= 4 && walks[1] == 0 && a.aircraft.carryCount == 0 && iron(((com.minecolonies.api.entity.citizen.AbstractEntityCitizen) a.pilot).getInventoryCitizen()) == 0,
                    walks == null ? "no record" : walks[0] + " walks that put goods down, " + walks[1] + " given up; still in the pilot's hands " + a.aircraft.carryCount);
            s.soft("cargo / the pilot is home, off the aircraft; no ticket is left", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && !Flights.ticketed(level, Flights.shipId(a.aircraft)), "pilot " + a.pilot.position());
            for (String line : ledger.history()) s.note("OBS cargo history: " + line);
            double[] r = Flights.readings(a.aircraft);
            s.note(String.format("OBS cargo: %d ticks from the goods lying ready to the aircraft parked at home again; top speed %.2f, lever writes %.0f", a.clock, a.speedMax, r == null ? -1 : r[8]));
            // Not twice: A's airfield looks again; nothing more is asked of the warehouse, and no second flight starts.
            a.airfield.getFirstModuleOccurance(ExportStockModule.class).onColonyTick(a.colony);
            n[4] = iron(a.airfield.getItemHandlerCap());
            a.log.clear();
        });
        s.attempt("cargo: B's courier carries the iron from the stash to B's warehouse", () -> {}, () -> flag[2] = n[6] >= 64, 3000,
                () -> s.note("OBS cargo: B's courier did not empty the stash within 3000 ticks: stash " + iron(store[3].getItemHandlerCap()) + ", warehouse " + Fixture.count(store[1], Items.IRON_INGOT)));
        // ---- finish, scene 7: the shortage itself goes away
        s.attempt("cargo: B's courier brings the iron on to the town hall that asked for it", () -> {}, () -> flag[3] = iron(hallB[0].getItemHandlerCap()) >= 64, 4000,
                () -> s.note("OBS cargo: the iron did not reach B's town hall within 4000 ticks: town hall " + iron(hallB[0].getItemHandlerCap()) + ", warehouse " + Fixture.count(store[1], Items.IRON_INGOT)));
        s.step("cargo: the shortage is over", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(colonyB[0]);
            int before = ledger.wantedAutomatically(keyB, Items.IRON_INGOT), coveredBefore = ledger.covered(keyB, Items.IRON_INGOT);
            // B's airfield looks at its colony's open requests again (the colony tick does this when a player is near).
            airfieldB[0].getFirstModuleOccurance(ExportStockModule.class).onColonyTick(colonyB[0]);
            Mob carrierB = Fixture.entity(courier[1]);
            int carried = carrierB instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen c ? iron(c.getInventoryCitizen()) : 0;
            int hall = iron(hallB[0].getItemHandlerCap()), lying = lying(level, Items.IRON_INGOT), hold = Flights.holdContents(a.aircraft).getOrDefault(Items.IRON_INGOT, 0);
            int all = Fixture.count(store[0], Items.IRON_INGOT) + iron(a.airfield.getItemHandlerCap()) + hold + iron(store[3].getItemHandlerCap()) + Fixture.count(store[1], Items.IRON_INGOT) + carried + hall + lying;
            s.soft("cargo / 7: inside colony B the iron was carried on to the town hall that had asked for it", flag[3] && hall == 64, "town hall " + hall + ", B warehouse " + Fixture.count(store[1], Items.IRON_INGOT) + ", B courier " + carried);
            s.soft("cargo / 7: B's shortage is gone from the ledger, and nothing counts as on its way to B any more", ledger.wantedAutomatically(keyB, Items.IRON_INGOT) == 0 && ledger.covered(keyB, Items.IRON_INGOT) == 0
                    && ledger.entries().stream().noneMatch(e -> e.key().equals(keyB)), "before B's airfield looked again: short of " + before + ", on the way " + coveredBefore + "; now short of " + ledger.wantedAutomatically(keyB, Items.IRON_INGOT)
                    + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT));
            s.soft("cargo / 7: no iron was made or lost, counted at the very end", all == n[0], "now " + all + " (A warehouse " + Fixture.count(store[0], Items.IRON_INGOT) + ", A racks " + iron(a.airfield.getItemHandlerCap()) + ", hold " + hold
                    + ", B stash " + iron(store[3].getItemHandlerCap()) + ", B warehouse " + Fixture.count(store[1], Items.IRON_INGOT) + ", B courier " + carried + ", B town hall " + hall + ", lying on the ground " + lying + "), at the start " + n[0]);
            // Scene 6: a dedicated server without players never has an active colony, so this is the usual case here.
            if (!flag[4]) s.soft("cargo / 6: the goods were unloaded at a colony that was not active", n[1] == 64, "colony B active=" + flag[4] + ", " + n[1] + " iron went into its import store");
            else s.note("OBS cargo / 6: colony B was active while the goods were unloaded; an inactive destination was NOT checked");
        });
        s.step("cargo: afterwards", () -> {
            s.soft("cargo / B's courier carried the iron from the import stash to B's warehouse", flag[2] && iron(store[3].getItemHandlerCap()) == 0,
                    "most iron seen in B's warehouse " + n[6] + " (now " + Fixture.count(store[1], Items.IRON_INGOT) + ", stash now " + iron(store[3].getItemHandlerCap()) + ")");
            s.soft("cargo / nothing is sent twice: no second flight, nothing more collected at A", a.log.stream().noneMatch(l -> l.contains("-> LOAD")) && iron(a.airfield.getItemHandlerCap()) == n[4],
                    "A racks " + iron(a.airfield.getItemHandlerCap()) + " (was " + n[4] + "), aircraft " + a.aircraft.phase);
            s.clearMonitors();
        });
    }

    // ---- the pilot carries (ruling of 2026-10-11): a flight called off in the middle of a walk, a full import store ----------

    private static int hands(Field a) {
        return a.pilot instanceof com.minecolonies.api.entity.citizen.AbstractEntityCitizen citizen ? iron(citizen.getInventoryCitizen()) : 0;
    }

    /** The translation key of a status line of the routes tab. */
    private static String statusKey(com.asteain.airline.route.ColonyRoutes.ClassStatus status) {
        return status.text().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t ? t.getKey().replace("com.airline.gui.routes.", "") : "?";
    }

    /**
     * Routes drawn by players (2026-10-18, the user's ruling: routes between colonies are drawn in the airfield's window,
     * the automatic ones are not used): a colony with goods wanted elsewhere sends nothing until a route is drawn there;
     * the routes tab tells, for each class, whether a route can be flown or why not (one place): too far with no buoys,
     * no place there that takes the class, something in the way.
     */
    private static void routes(Script s, ServerLevel level) {
        final WantLedger ledger = WantLedger.get(level.getServer());
        final Field a = new Field(HUT_A);
        addField(s, level, a, "routes A");
        final Port b = new Port(HUT_A.offset(88, 0, 0)), c = new Port(HUT_A.offset(300, 0, 0));
        addPort(s, level, b, "routes B", true);
        addPort(s, level, c, "routes C", false);
        final IColony[] d = new IColony[1];
        final BlockPos[] stone = new BlockPos[1];
        s.step("routes: no route drawn yet; 64 iron on A's export racks, B wants them; colony D has no airfield", () -> {
            com.asteain.airline.route.ColonyRoutes.allForChecks(false);
            Fixture.stock(equip(level, a), Items.IRON_INGOT, 64);
            ledger.set(WantLedger.Key.of(b.colony), Items.IRON_INGOT, 64);
            d[0] = Fixture.create(level, HUT_A.offset(0, 0, 88), "Airline routes D");
            Fixture.townHall(d[0], HUT_A.offset(0, 0, 88));
            watch(s, a, "routes");
        });
        s.settle("routes: a while with no route", 600);
        s.step("routes: without a route nothing is flown; the tab tells why each colony can or cannot be flown to", () -> {
            s.soft("routes / with no route drawn to B the aircraft stays parked and loads nothing", a.aircraft.phase == Phase.PARKED && !a.aircraft.cargo, "doing " + a.aircraft.phase + " | " + a.aircraft.note);
            final List<com.asteain.airline.route.ColonyRoutes.ClassStatus> toB = com.asteain.airline.route.ColonyRoutes.status(level, a.colony.getID(), b.colony.getID());
            final List<com.asteain.airline.route.ColonyRoutes.ClassStatus> toC = com.asteain.airline.route.ColonyRoutes.status(level, a.colony.getID(), c.colony.getID());
            final List<com.asteain.airline.route.ColonyRoutes.ClassStatus> toD = com.asteain.airline.route.ColonyRoutes.status(level, a.colony.getID(), d[0].getID());
            s.note("OBS routes: to B " + toB.get(0).text().getString() + " | to C " + toC.get(0).text().getString() + " | to D " + toD.get(0).text().getString());
            s.soft("routes / to B (88 blocks): small aircraft can fly it", toB.size() == 1 && toB.get(0).open() && statusKey(toB.get(0)).equals("open"), statusKey(toB.get(0)));
            s.soft("routes / to C (300 blocks, no buoy): too far to fly straight, and no buoys join them", !toC.get(0).open() && statusKey(toC.get(0)).equals("too_far"), statusKey(toC.get(0)));
            s.soft("routes / to D (no airfield): nothing there takes the aircraft", !toD.get(0).open() && statusKey(toD.get(0)).equals("no_berth"), statusKey(toD.get(0)));
            // Stone in the way of the line A - B, half way, at its height and a block above and below: the line is raised over it
            // (2026-10-18), and the flight later goes over it.
            final net.minecraft.world.phys.Vec3 high = RouteNetwork.get(level).waypoint(a.pad);
            stone[0] = BlockPos.containing((a.pad.getX() + b.pad.getX()) / 2.0, high.y, (a.pad.getZ() + b.pad.getZ()) / 2.0);
            for (int y = -1; y <= 1; y++) level.setBlockAndUpdate(stone[0].above(y), Blocks.STONE.defaultBlockState());
        });
        s.until("routes: the line A - B is looked at with the stone", () -> {}, () -> {
            final Edge edge = RouteNetwork.get(level).edge(a.pad, b.pad);
            return edge != null && edge.state() == State.CLEAR && edge.lift() > 0;
        }, 2400);
        final BlockPos[] inShaft = new BlockPos[1];
        s.step("routes: raised over the stone; a stone over B's pad", () -> {
            final Edge edge = RouteNetwork.get(level).edge(a.pad, b.pad);
            final com.asteain.airline.route.ColonyRoutes.ClassStatus open = com.asteain.airline.route.ColonyRoutes.status(level, a.colony.getID(), b.colony.getID()).get(0);
            s.note("OBS routes: the line A - B is raised by " + edge.lift() + " to " + edge.height() + " (the stone's top at " + stone[0].above().getY() + ")");
            s.soft("routes / with stone half way at the line's height the line is raised over it and B can still be flown to", open.open() && edge.lift() > 0 && !edge.needs(stone[0].getX(), stone[0].getY() + 1, stone[0].getZ()),
                    statusKey(open) + ", raised " + edge.lift());
            // Over B's pad (in its shaft) nothing is passed over.
            inShaft[0] = b.pad.above(6);
            level.setBlockAndUpdate(inShaft[0], Blocks.STONE.defaultBlockState());
            final com.asteain.airline.route.ColonyRoutes.ClassStatus toB = com.asteain.airline.route.ColonyRoutes.status(level, a.colony.getID(), b.colony.getID()).get(0);
            final String text = toB.text().getString();
            s.note("OBS routes: blocked: " + text);
            s.soft("routes / with stone over B's pad the tab tells what is in the way, and where (one place)", !toB.open() && statusKey(toB).equals("blocked") && text.contains(Blocks.STONE.getName().getString()),
                    statusKey(toB) + ": " + text);
            level.setBlockAndUpdate(inShaft[0], Blocks.AIR.defaultBlockState());
        });
        s.until("routes: the line is clear again", () -> {}, () -> {
            final Edge edge = RouteNetwork.get(level).edge(a.pad, b.pad);
            return edge != null && edge.state() == State.CLEAR && RouteNetwork.get(level).pending() == 0;
        }, 2400);
        final double[] highest = {Double.NEGATIVE_INFINITY};
        final boolean[] atB = new boolean[1];
        s.step("routes: a route to B is drawn", () -> {
            com.asteain.airline.route.ColonyRoutes.get(level.getServer()).draw(level, a.colony.getID(), b.colony.getID());
            s.monitor(() -> {
                final double[] r = Flights.readings(a.aircraft);
                if (r != null && a.aircraft.flying()) highest[0] = Math.max(highest[0], r[1]);
                // (A cargo flight does not let its pilot off at B: it is unloaded there and flies back.)
                if (b.pad.equals(a.aircraft.at)) atB[0] = true;
            });
        });
        s.until("routes: with the route drawn, the flight to B is prepared", () -> {}, () -> a.aircraft.cargo && a.aircraft.freightColony == b.colony.getID(), 2400);
        s.step("routes: loaded", () -> s.soft("routes / once the route is drawn, the iron is loaded for B", a.aircraft.cargo && a.aircraft.freightColony == b.colony.getID(), "doing " + a.aircraft.phase + " | " + a.aircraft.note));
        s.until("routes: the aircraft flew to B over the stone and landed there", () -> {}, () -> atB[0], 6000);
        s.step("routes: result", () -> {
            final Edge edge = RouteNetwork.get(level).edge(a.pad, b.pad);
            s.soft("routes / the aircraft flew the raised line to B without turning back, as high as the line, and landed there", atB[0] && a.log.stream().noneMatch(l -> l.contains("-> RETREAT"))
                    && highest[0] >= edge.height() - 2.0, "at " + a.aircraft.at + ", highest " + highest[0] + ", line at " + edge.height() + " | " + a.aircraft.note);
            for (int y = -1; y <= 1; y++) level.setBlockAndUpdate(stone[0].above(y), Blocks.AIR.defaultBlockState());
            com.asteain.airline.route.ColonyRoutes.get(level.getServer()).remove(level, a.colony.getID(), b.colony.getID());
            s.soft("routes / the route can be taken off again", !com.asteain.airline.route.ColonyRoutes.drawn(level, a.colony.getID(), b.colony.getID()), "");
            com.asteain.airline.route.ColonyRoutes.allForChecks(true);
            s.clearMonitors();
        });
    }

    /**
     * A round (2026-10-17, the user's ruling "判断は一旦全部推奨で": up to three colonies, the shortest way round, a whole
     * berth at each, one kind of goods to one colony): A's export racks hold iron, gold and copper; B wants the iron, C
     * the gold, D the copper. B, C and D stand at three corners of a square with A at the fourth. One flight loads for all
     * three, calls at them in the order of the shortest way round, unloads at each only what is for it, and flies home.
     */
    private static void tour(Script s, ServerLevel level) {
        final WantLedger ledger = WantLedger.get(level.getServer());
        final Field a = new Field(HUT_A);
        addField(s, level, a, "tour A");
        // 88 apart: two airfields of the checks reach each other when nearer than 96 (3 chunks each); the diagonals are not joined.
        final Port b = new Port(HUT_A.offset(88, 0, 0)), c = new Port(HUT_A.offset(88, 0, 88)), d = new Port(HUT_A.offset(0, 0, 88));
        addPort(s, level, b, "tour B", true);
        addPort(s, level, c, "tour C", true);
        addPort(s, level, d, "tour D", true);
        final net.minecraft.world.item.Item[] goods = {Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT};
        final Port[] ports = {b, c, d};
        final List<BlockPos> calledAt = new ArrayList<>();
        s.step("tour: the square kept loaded; 64 iron, 64 gold, 64 copper on A's export racks; B wants the iron, C the gold, D the copper", () -> {
            for (int cx = (HUT_A.getX() - 24) >> 4; cx <= (HUT_A.getX() + 128) >> 4; cx++)
                for (int cz = (HUT_A.getZ() - 40) >> 4; cz <= (HUT_A.getZ() + 128) >> 4; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            final TileEntityRack rack = equip(level, a);
            for (final net.minecraft.world.item.Item item : goods) Fixture.stock(rack, item, 64);
            for (int i = 0; i < 3; i++) ledger.set(WantLedger.Key.of(ports[i].colony), goods[i], 64);
            watch(s, a, "tour");
            s.monitor(() -> {
                if (a.aircraft.phase == Phase.UNLOAD && a.aircraft.at != null && !a.aircraft.at.equals(a.pad) && (calledAt.isEmpty() || !calledAt.get(calledAt.size() - 1).equals(a.aircraft.at)))
                    calledAt.add(a.aircraft.at);
            });
        });
        s.settle("tour: the lines are looked at", 600);
        s.step("tour: what the network and the ledger say", () -> {
            final RouteNetwork net = RouteNetwork.get(level);
            final BlockPos[] pads = {a.pad, b.pad, c.pad, d.pad};
            final StringBuilder lines = new StringBuilder();
            for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) {
                final Edge edge = net.edge(pads[i], pads[j]);
                lines.append("ABCD".charAt(i)).append("ABCD".charAt(j)).append('=').append(edge == null ? "none" : edge.state() + "/" + edge.serves()).append(' ');
            }
            s.note("OBS tour: lines " + lines + "; ledger net B iron " + ledger.net(WantLedger.Key.of(b.colony), Items.IRON_INGOT) + ", C gold " + ledger.net(WantLedger.Key.of(c.colony), Items.GOLD_INGOT)
                    + ", D copper " + ledger.net(WantLedger.Key.of(d.colony), Items.COPPER_INGOT) + "; racks iron " + iron(a.airfield.getItemHandlerCap()) + "; aircraft " + a.aircraft.phase + " | " + a.aircraft.note);
        });
        s.until("tour: out, three colonies, home again", () -> {}, () -> calledAt.size() >= 3 && a.aircraft.phase == Phase.PARKED && a.pad.equals(a.aircraft.at) && !a.aircraft.cargo, 40000);
        s.step("tour: result", () -> {
            final RouteNetwork net = RouteNetwork.get(level);
            final int[] stash = new int[3], wanted = new int[3], covered = new int[3], others = new int[3];
            for (int i = 0; i < 3; i++) {
                stash[i] = com.asteain.airline.flight.Cargo.count(ports[i].stash.getItemHandlerCap(), goods[i]);
                for (int j = 0; j < 3; j++) if (j != i) others[i] += com.asteain.airline.flight.Cargo.count(ports[i].stash.getItemHandlerCap(), goods[j]);
                wanted[i] = ledger.wanted(WantLedger.Key.of(ports[i].colony), goods[i]);
                covered[i] = ledger.covered(WantLedger.Key.of(ports[i].colony), goods[i]);
            }
            s.soft("tour / B got the 64 iron, C the 64 gold, D the 64 copper, and none of them anything meant for another",
                    stash[0] == 64 && stash[1] == 64 && stash[2] == 64 && others[0] + others[1] + others[2] == 0,
                    "B " + stash[0] + " iron, C " + stash[1] + " gold, D " + stash[2] + " copper; goods of others " + java.util.Arrays.toString(others));
            s.soft("tour / the ledger knows: nothing more is wanted, nothing is on its way", wanted[0] + wanted[1] + wanted[2] == 0 && covered[0] + covered[1] + covered[2] == 0,
                    "wanted " + java.util.Arrays.toString(wanted) + ", on the way " + java.util.Arrays.toString(covered));
            final java.util.Map<net.minecraft.world.item.Item, Integer> hold = Flights.holdContents(a.aircraft);
            s.soft("tour / home again with an empty hold and the export racks empty of the three", hold.values().stream().mapToInt(Integer::intValue).sum() == 0
                    && com.asteain.airline.flight.Cargo.count(a.airfield.getItemHandlerCap(), Items.IRON_INGOT) + com.asteain.airline.flight.Cargo.count(a.airfield.getItemHandlerCap(), Items.GOLD_INGOT)
                    + com.asteain.airline.flight.Cargo.count(a.airfield.getItemHandlerCap(), Items.COPPER_INGOT) == 0, "hold " + hold);
            final List<BlockPos> loop = List.of(b.pad, c.pad, d.pad), back = List.of(d.pad, c.pad, b.pad);
            s.soft("tour / one flight called at the three in the order of the shortest way round (B, C, D or D, C, B)", calledAt.equals(loop) || calledAt.equals(back),
                    "called at " + calledAt + "; B " + b.pad.toShortString() + ", C " + c.pad.toShortString() + ", D " + d.pad.toShortString());
            s.soft("tour / four take-offs (out, on twice, home), none before a whole berth on the pad", a.cargoTakeoffs == 4 && a.shortestBerth >= BERTH,
                    a.cargoTakeoffs + " take-offs, shortest stay " + a.shortestBerth);
            s.soft("tour / the pilot is alive and off the aircraft at home", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE, "pilot at " + a.pilot.position());
            for (String line : ledger.history()) s.note("OBS tour history: " + line);
            // A tavern's visitor in a colony joined to others by air arrives at its airfield (2026-10-17, 旅客 3 first version).
            final AbstractBuilding tavern = Fixture.placeHut(level, a.colony, ModBlocks.blockHutTavern, a.hut.offset(-12, 0, 8));
            tavern.setBuildingLevel(1);
            final com.minecolonies.api.colony.IVisitorData visitor = tavern.getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.TavernBuildingModule.class).spawnVisitor();
            final Entity seen = visitor == null || visitor.getEntity().isEmpty() ? null : visitor.getEntity().get();
            final double fromPad = seen == null ? -1 : Math.hypot(seen.getX() - (a.pad.getX() + 0.5), seen.getZ() - (a.pad.getZ() + 0.5));
            s.soft("tour / a tavern's visitor in a colony joined to others by air arrives at the edge of its airfield's pad", seen != null && fromPad <= 7.5 && Math.abs(seen.getY() - (a.pad.getY() + 1)) < 2.5,
                    seen == null ? "no visitor entity" : String.format("visitor at (%.1f, %.1f, %.1f), %.1f from the middle of the pad %s", seen.getX(), seen.getY(), seen.getZ(), fromPad, a.pad.toShortString()));
            s.note("OBS tour: lines " + net.route(a.pad, b.pad, ShipClass.SMALL).size() + "/" + net.route(b.pad, c.pad, ShipClass.SMALL).size() + "/" + net.route(c.pad, d.pad, ShipClass.SMALL).size()
                    + "/" + net.route(d.pad, a.pad, ShipClass.SMALL).size() + "; " + a.clock + " ticks");
            s.clearMonitors();
        });
    }

    private static void carry(Script s, ServerLevel level) {
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        addField(s, level, a, "carry A");
        Port b = new Port(new BlockPos(88, Y, 0));
        addPort(s, level, b, "carry B", true);
        int[] n = new int[10];
        boolean[] flag = new boolean[2];
        s.step("carry: 128 iron on A's export racks, B wants 128, B's import stash has room for one stack only", () -> {
            Fixture.stock(equip(level, a), Items.IRON_INGOT, 128);
            ledger.set(WantLedger.Key.of(b.colony), Items.IRON_INGOT, 128);
            net.neoforged.neoforge.items.IItemHandler stash = b.stash.getItemHandlerCap();
            for (int slot = 0; slot < stash.getSlots() - 1; slot++) stash.insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
            n[0] = iron(a.airfield.getItemHandlerCap()) + held(a) + iron(stash) + lying(level, Items.IRON_INGOT) + hands(a);
            watch(s, a, "carry");
        });

        // ---- (1) called off while the pilot is on its way from the racks to the hold
        s.until("carry: the pilot has taken iron from the racks", () -> {}, () -> a.aircraft.phase == Phase.LOAD && a.aircraft.carryCount > 0, 2400);
        s.step("carry: /airline flight cancel while the pilot carries", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            n[1] = a.aircraft.carryCount;
            n[2] = ledger.covered(keyB, Items.IRON_INGOT);
            int inHands = hands(a), racks = iron(a.airfield.getItemHandlerCap());
            s.soft("carry / what the pilot took from the racks is in its hands, is written down on the aircraft, and counts as on its way", n[1] > 0 && inHands == n[1] && n[2] == n[1] + held(a)
                    && racks + inHands + held(a) == 128, "written down " + n[1] + ", in the hands " + inHands + ", racks " + racks + ", hold " + held(a) + ", on the way by the ledger " + n[2]);
            List<String> said = command(level, "airline flight cancel");
            s.soft("carry / flight cancel in the middle of a walk is accepted", a.aircraft.phase == Phase.UNLOAD && a.aircraft.cancelled, said + " | doing " + a.aircraft.phase);
        });
        s.until("carry: the pilot brings it back; parked", () -> {}, () -> a.aircraft.phase == Phase.PARKED, 3000);
        s.step("carry: called off, result", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            int racks = iron(a.airfield.getItemHandlerCap()), all = racks + held(a) + iron(b.stash.getItemHandlerCap()) + lying(level, Items.IRON_INGOT) + hands(a);
            int[] record = Flights.handling(a.aircraft);
            s.soft("carry / (1) after the cancel the pilot carried everything back onto the racks: racks full, hold and hands empty, the ledger as before", racks == 128 && held(a) == 0 && hands(a) == 0
                    && a.aircraft.carryCount == 0 && ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.net(keyB, Items.IRON_INGOT) == 128 && a.aircraft.manifest.isEmpty(),
                    "racks " + racks + ", hold " + held(a) + ", hands " + hands(a) + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT) + ", B wants (net) " + ledger.net(keyB, Items.IRON_INGOT) + ", list " + a.aircraft.manifest);
            s.soft("carry / (1) no iron was made or lost; no walk was given up", all == n[0] && record != null && record[1] == 0, "now " + all + ", at the start " + n[0] + "; record " + java.util.Arrays.toString(record));
            // After a cancel no flight is prepared for ten minutes; the check does not wait that long.
            a.aircraft.quietUntil = 0;
            a.log.clear();
        });

        // ---- (2) the next flight: everything is carried aboard; B's store takes one stack; the rest waits for room, then flies home
        s.until("carry: second flight: out, the pilot unloads what fits, home again", () -> {}, () -> {
            if (!flag[0] && a.aircraft.phase == Phase.UNLOAD && b.pad.equals(a.aircraft.at) && a.aircraft.settled) {
                flag[0] = true;
                n[3] = iron(b.stash.getItemHandlerCap());
                n[4] = held(a);
                n[5] = hands(a);
                n[6] = ledger.covered(WantLedger.Key.of(b.colony), Items.IRON_INGOT);
                n[7] = ledger.wanted(WantLedger.Key.of(b.colony), Items.IRON_INGOT);
                n[8] = (int) (level.getGameTime() - a.aircraft.landed);
                s.note(String.format("FLT carry: at B when the unloading was settled (%d ticks after landing): stash %d iron, hold %d, hands %d, on the way %d, B still wants %d, pilot %s | %s",
                        n[8], n[3], n[4], n[5], n[6], n[7], Boarding.state(a.pilot), a.aircraft.note));
            }
            return flag[0] && a.aircraft.phase == Phase.PARKED;
        }, 16000);
        s.step("carry: full store, result", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            int racks = iron(a.airfield.getItemHandlerCap()), stash = iron(b.stash.getItemHandlerCap()), all = racks + held(a) + stash + lying(level, Items.IRON_INGOT) + hands(a);
            int[] record = Flights.handling(a.aircraft);
            s.soft("carry / (2) the pilot carried all 128 aboard, and at B only what fitted into the store; the rest stayed in the hold", n[3] == 64 && n[4] == 64 && n[5] == 0, "stash " + n[3] + ", hold " + n[4] + ", hands " + n[5]);
            s.soft("carry / (2) with the store full the aircraft waited for room until its berth was over, and only then gave the rest up", n[8] >= BERTH, "settled " + n[8] + " ticks after landing; the berth is " + BERTH);
            s.soft("carry / (2) the ledger knows: 64 are still wanted, nothing is on the way", n[6] == 0 && n[7] == 64 && ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.wanted(keyB, Items.IRON_INGOT) == 64,
                    "at B: on the way " + n[6] + ", wanted " + n[7] + "; at home: on the way " + ledger.covered(keyB, Items.IRON_INGOT) + ", wanted " + ledger.wanted(keyB, Items.IRON_INGOT));
            s.soft("carry / (2) at home the pilot carried what came back onto the export racks again", a.pad.equals(a.aircraft.at) && racks == 64 && held(a) == 0 && hands(a) == 0 && a.aircraft.carryCount == 0,
                    "racks " + racks + ", hold " + held(a) + ", hands " + hands(a) + " | " + a.aircraft.note);
            s.soft("carry / (2) no iron was made or lost; every walk arrived", all == n[0] && stash == 64 && record != null && record[1] == 0 && record[0] >= 4,
                    "now " + all + " (racks " + racks + ", hold " + held(a) + ", B stash " + stash + ", hands " + hands(a) + "), at the start " + n[0] + "; record " + java.util.Arrays.toString(record));
            s.soft("carry / the pilot is alive and off the aircraft at home; no ticket is left", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && !Flights.ticketed(level, Flights.shipId(a.aircraft)), "pilot at " + a.pilot.position());
            s.soft("carry / scheduled: two take-offs (out, home from B), none before a whole berth on the pad", a.cargoTakeoffs == 2 && a.shortestBerth >= BERTH, a.cargoTakeoffs + " take-offs, shortest stay " + a.shortestBerth);
            for (String line : ledger.history()) s.note("OBS carry history: " + line);
            s.note("OBS carry: " + a.clock + " ticks so far");
            a.log.clear();
        });

        // ---- (3) the same goods are sent again (B still wants 64); the flight is called off at B, where the store is full now:
        //          it flies home at once by itself, and at home the pilot clears out the hold (review of 2026-10-11, 1 and 4)
        s.until("carry: third flight has landed at B and waits there for room in the store", () -> {}, () -> a.aircraft.phase == Phase.UNLOAD && b.pad.equals(a.aircraft.at)
                && level.getGameTime() - a.aircraft.landed > 100, 9000);
        s.step("carry: /airline flight cancel at the destination", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            n[1] = held(a) + hands(a);
            n[2] = ledger.covered(keyB, Items.IRON_INGOT);
            List<String> said = command(level, "airline flight cancel");
            s.soft("carry / (3) called off at its destination the cargo flight is settled at once (nothing counts as on its way) and the aircraft wants to fly home", a.aircraft.homeward && a.aircraft.phase == Phase.PARKED
                    && !a.aircraft.cargo && n[1] == 64 && n[2] == 64 && ledger.covered(keyB, Items.IRON_INGOT) == 0 && held(a) + hands(a) == 64,
                    said + " | doing " + a.aircraft.phase + ", homeward=" + a.aircraft.homeward + ", hold and hands before " + n[1] + " now " + (held(a) + hands(a)) + ", on the way before " + n[2] + " now " + ledger.covered(keyB, Items.IRON_INGOT));
        });
        s.until("carry: it flies home by itself; at home the pilot clears out the hold", () -> {}, () -> a.pad.equals(a.aircraft.at) && a.aircraft.phase == Phase.PARKED && held(a) == 0 && a.aircraft.carryCount == 0
                && a.log.stream().anyMatch(l -> l.contains("-> DESCEND")), 9000);
        s.step("carry: called off at the destination, result", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            int racks = iron(a.airfield.getItemHandlerCap()), stash = iron(b.stash.getItemHandlerCap()), all = racks + held(a) + stash + lying(level, Items.IRON_INGOT) + hands(a);
            int[] record = Flights.handling(a.aircraft);
            s.soft("carry / (3) the aircraft came home by itself and is an ordinary parked aircraft again", a.pad.equals(a.aircraft.at) && !a.aircraft.homeward && !a.aircraft.cargo && a.aircraft.manifest.isEmpty()
                    && a.log.stream().anyMatch(l -> l.contains("PARKED -> BOARDING")), "at " + a.aircraft.at + ", homeward=" + a.aircraft.homeward + " | " + a.aircraft.note);
            s.soft("carry / (3) what was left in its hold is back on the export racks (carried there by the pilot), and the ledger is as before the flight", racks == 64 && held(a) == 0 && hands(a) == 0
                    && ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.wanted(keyB, Items.IRON_INGOT) == 64 && a.log.stream().anyMatch(l -> l.contains("PARKED -> UNLOAD")),
                    "racks " + racks + ", hold " + held(a) + ", hands " + hands(a) + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT) + ", wanted " + ledger.wanted(keyB, Items.IRON_INGOT));
            s.soft("carry / (3) no iron was made or lost; no walk was given up", all == n[0] && stash == 64 && record != null && record[1] == 0, "now " + all + " (racks " + racks + ", B stash " + stash + "), at the start " + n[0]
                    + "; record " + java.util.Arrays.toString(record));
            s.soft("carry / (3) the pilot is alive and off the aircraft at home; no ticket is left", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && a.pilot.getY() < Y + 1.5
                    && Math.abs(a.pilot.getX() - a.hut.getX()) < 30 && !Flights.ticketed(level, Flights.shipId(a.aircraft)), "pilot at " + a.pilot.position());
            s.note("OBS carry: " + a.clock + " ticks in all");
            s.clearMonitors();
        });
    }

    // ---- the finish (0.3.1): failures on the pad, flights that end on the way, commands, a blocked line, a full store --------

    /** A second colony for the short cargo scenes: an airfield (level 1, a rack), and an import stash when asked for. No pilot, no aircraft. */
    private static final class Port {
        final BlockPos hut;
        IColony colony;
        BuildingAirfield airfield;
        AbstractBuilding stash;
        BlockPos pad;

        Port(BlockPos hut) {
            this.hut = hut;
        }
    }

    private static void addPort(Script s, ServerLevel level, Port p, String name, boolean stash) {
        s.step(name + ": colony and airfield" + (stash ? ", import stash" : ""), () -> {
            Fixture.platform(level, p.hut.offset(1, 0, -8), 10, 16);
            p.colony = Fixture.create(level, p.hut.offset(4, 0, 4), "Airline " + name);
            Fixture.townHall(p.colony, p.hut.offset(4, 0, 4));
            p.airfield = (BuildingAirfield) Fixture.placeHut(level, p.colony, Airline.AIRFIELD_HUT.get(), p.hut);
            p.airfield.setBuildingLevel(1);
            p.airfield.setCorners(p.hut.offset(-3, 0, -3), p.hut.offset(3, 2, 3));
            Fixture.rack(level, p.airfield, p.hut.offset(-2, 0, 0));
            p.pad = p.airfield.pad();
            if (stash) {
                p.stash = Fixture.placeHut(level, p.colony, ModBlocks.blockStash, p.hut.offset(5, 0, 3));
                p.stash.setBuildingLevel(1);
            }
            // The line between the two pads is kept loaded, so that the network can verify it without an aircraft.
            for (int cx = -1; cx <= (p.hut.getX() >> 4) + 1; cx++) for (int cz = -2; cz <= 0; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
        });
    }

    /** Colony A's airfield becomes a working one: level 1, two export racks. @return the first rack */
    private static TileEntityRack equip(ServerLevel level, Field a) {
        a.airfield.setBuildingLevel(1);
        a.airfield.setCorners(a.hut.offset(-3, 0, -3), a.hut.offset(3, 2, 3));
        TileEntityRack rack = Fixture.rack(level, a.airfield, a.hut.offset(-2, 0, 0));
        Fixture.rack(level, a.airfield, a.hut.offset(2, 0, 0));
        return rack;
    }

    /** Items of one kind lying on the ground (or falling) anywhere in the dimension. */
    private static int lying(ServerLevel level, net.minecraft.world.item.Item item) {
        int sum = 0;
        for (Entity entity : level.getAllEntities())
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity drop && drop.isAlive() && drop.getItem().is(item)) sum += drop.getItem().getCount();
        return sum;
    }

    /** Picks up what lies on the ground of one kind (the entities go away). @return how many items */
    private static int pickUp(ServerLevel level, net.minecraft.world.item.Item item) {
        int sum = 0;
        List<Entity> all = new ArrayList<>();
        level.getAllEntities().forEach(all::add);
        for (Entity entity : all) {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity drop && drop.isAlive() && drop.getItem().is(item)) {
                sum += drop.getItem().getCount();
                drop.discard();
            }
        }
        return sum;
    }

    private static int held(Field a) {
        return Flights.holdContents(a.aircraft).getOrDefault(Items.IRON_INGOT, 0);
    }

    /** Runs a command the way the console would, through the real command tree; returns what was answered, line by line. */
    private static List<String> command(ServerLevel level, String line) {
        List<String> said = new ArrayList<>();
        net.minecraft.commands.CommandSource sink = new net.minecraft.commands.CommandSource() {
            @Override public void sendSystemMessage(net.minecraft.network.chat.Component text) { said.add(text.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        net.minecraft.commands.CommandSourceStack source = new net.minecraft.commands.CommandSourceStack(sink, net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec2.ZERO, level, 4,
                "airtest", net.minecraft.network.chat.Component.literal("airtest"), level.getServer(), null);
        level.getServer().getCommands().performPrefixedCommand(source, line);
        return said;
    }

    /** Scene 1 (a failure on the pad) and scene 8 (calling a flight off, the status command). */
    private static void ground(Script s, ServerLevel level) {
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        addField(s, level, a, "ground A");
        Port b = new Port(new BlockPos(88, Y, 0));
        addPort(s, level, b, "ground B", false);
        int[] n = new int[4];
        s.step("ground: the ledger on its own (review 1-2: what is on the way never outlasts the want)", () -> {
            WantLedger.Key k = new WantLedger.Key("airtest:nowhere", 99);
            ledger.set(k, Items.COAL, 64);
            ledger.cover(k, Items.COAL, 64);
            int stuck = ledger.net(k, Items.COAL);
            ledger.set(k, Items.COAL, 0);
            int afterRemove = ledger.covered(k, Items.COAL);
            ledger.set(k, Items.COAL, 64);
            s.soft("ledger / 1-2: taking a want away and entering it again frees what was counted as on its way", stuck == 0 && afterRemove == 0 && ledger.net(k, Items.COAL) == 64,
                    "net while on the way " + stuck + ", on the way after the want was removed " + afterRemove + ", net after entering it again " + ledger.net(k, Items.COAL));
            ledger.cover(k, Items.COAL, 64);
            ledger.set(k, Items.COAL, 20);
            s.soft("ledger / 1-2: a want that is made smaller trims what is on the way", ledger.covered(k, Items.COAL) == 20, "on the way " + ledger.covered(k, Items.COAL) + " of a want of 20");
            // 128 wanted and loaded; 64 arrive, 64 come back: 64 are still wanted and nothing is on the way.
            ledger.set(k, Items.COAL, 128);
            ledger.cover(k, Items.COAL, 108);
            ledger.cover(k, Items.COAL, -64);
            ledger.delivered(k, Items.COAL, 64);
            s.soft("ledger / a part delivery leaves the rest wanted and nothing on the way", ledger.wanted(k, Items.COAL) == 64 && ledger.covered(k, Items.COAL) == 0,
                    "wanted " + ledger.wanted(k, Items.COAL) + ", on the way " + ledger.covered(k, Items.COAL));
            ledger.set(k, Items.COAL, 0);
            s.soft("ledger / nothing of the made-up colony is left", ledger.entries().stream().noneMatch(e -> e.key().equals(k)) && ledger.covered(k, Items.COAL) == 0, "entries " + ledger.entries().size());
        });
        s.step("ground: 64 iron on A's export racks, colony B wants 64", () -> {
            Fixture.stock(equip(level, a), Items.IRON_INGOT, 64);
            ledger.set(WantLedger.Key.of(b.colony), Items.IRON_INGOT, 64);
            n[0] = iron(a.airfield.getItemHandlerCap()) + held(a) + lying(level, Items.IRON_INGOT);
            s.soft("ground / the two pads are joined by one line", RouteNetwork.get(level).route(a.pad, b.pad, ShipClass.SMALL).size() == 2, String.valueOf(RouteNetwork.get(level).route(a.pad, b.pad, ShipClass.SMALL)));
            watch(s, a, "ground");
        });

        // ---- scene 1: the controller fails while the aircraft stands on its pad, loading
        s.until("ground: the aircraft has begun to load", () -> {}, () -> a.aircraft.phase == Phase.LOAD && !a.aircraft.manifest.isEmpty(), 800);
        s.step("ground: the controller throws on the pad", () -> {
            n[1] = ledger.covered(WantLedger.Key.of(b.colony), Items.IRON_INGOT);
            s.note("FLT ground: failure made while loading; in the hold " + held(a) + ", on the way by the ledger " + n[1]);
            Flights.failOnPadOnceForChecks();
        });
        s.settle("ground: a moment", 5);
        s.step("ground: right after the failure", () -> {
            double[] r = Flights.readings(a.aircraft);
            s.soft("ground / 1-1: after a failure on the pad the aircraft keeps the record of where it stands and is not given up", a.pad.equals(a.aircraft.at) && a.aircraft.phase != Phase.STRANDED
                    && r != null && r[4] > 0, "doing " + a.aircraft.phase + " at " + a.aircraft.at + ", fixed=" + (r != null && r[4] > 0) + " | " + a.aircraft.note);
            s.soft("ground / 1-1: with cargo aboard it goes to the unloading step", a.aircraft.phase == Phase.UNLOAD && a.aircraft.cancelled, "doing " + a.aircraft.phase + ", cancelled=" + a.aircraft.cancelled);
        });
        s.until("ground: it rests, puts the goods back and is parked", () -> {}, () -> a.aircraft.phase == Phase.PARKED, 1600);
        s.step("ground: parked again", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            int racks = iron(a.airfield.getItemHandlerCap()), all = racks + held(a) + lying(level, Items.IRON_INGOT);
            s.soft("ground / 1-1: the goods are back on the export racks and nothing counts as on its way", racks == 64 && held(a) == 0 && ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.net(keyB, Items.IRON_INGOT) == 64,
                    "racks " + racks + ", hold " + held(a) + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT) + ", B still wants " + ledger.net(keyB, Items.IRON_INGOT) + " | " + a.aircraft.note);
            s.soft("ground / scene 1: no iron was made or lost", all == n[0], "now " + all + ", at the start " + n[0]);
            a.log.clear();
        });
        s.until("ground: a flight is sent again by itself and is in the air, 20 blocks along", () -> {}, () -> {
            double[] r = Flights.readings(a.aircraft);
            return r != null && a.aircraft.phase == Phase.CRUISE && r[0] > 20;
        }, 4000);

        // ---- scene 8: the flight is called off by command
        List<String> answers = new ArrayList<>();
        s.step("ground: /airline flight cancel", () -> {
            s.soft("ground / 1-1: after the failure the aircraft was used again: it loaded, was boarded and took off", a.log.stream().anyMatch(l -> l.contains("-> LOAD")) && a.log.stream().anyMatch(l -> l.contains("-> CLIMB"))
                    && held(a) == 64, "hold " + held(a) + ", " + a.log.size() + " changes since it was parked");
            answers.addAll(command(level, "airline flight status"));
            List<String> said = command(level, "airline flight cancel");
            s.soft("command / flight cancel is accepted in the air and the aircraft turns back", a.aircraft.cancelled && a.aircraft.phase == Phase.RETREAT && a.pad.equals(a.aircraft.destination), said + " | doing " + a.aircraft.phase);
            List<String> again = command(level, "airline flight cancel");
            s.soft("command / a second cancel has nothing to call off", a.aircraft.phase == Phase.RETREAT && !again.equals(said), String.valueOf(again));
        });
        s.until("ground: back at the pad it left, parked", () -> {}, () -> a.aircraft.phase == Phase.PARKED, 5000);
        s.step("ground: after the cancel", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            double[] r = Flights.readings(a.aircraft);
            int racks = iron(a.airfield.getItemHandlerCap()), all = racks + held(a) + lying(level, Items.IRON_INGOT);
            s.soft("command / the called-off flight returned to where it started and landed there", a.pad.equals(a.aircraft.at) && r != null && r[4] > 0 && r[6] <= 1.5 && a.log.stream().anyMatch(l -> l.contains("-> DESCEND")),
                    "at " + a.aircraft.at + " " + at(r) + " | " + a.aircraft.note);
            s.soft("command / its cargo is back on the export racks and the ledger is as it was", racks == 64 && held(a) == 0 && ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.net(keyB, Items.IRON_INGOT) == 64,
                    "racks " + racks + ", hold " + held(a) + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT) + ", B still wants " + ledger.net(keyB, Items.IRON_INGOT));
            s.soft("ground / scene 8: no iron was made or lost", all == n[0], "now " + all + ", at the start " + n[0]);
            s.soft("ground / scheduled: the aircraft did not take off before its berth (" + BERTH + " ticks) was over", a.cargoTakeoffs == 1 && a.shortestBerth >= BERTH, a.cargoTakeoffs + " take-off(s), shortest stay " + a.shortestBerth);
            s.soft("command / the pilot is alive and off the aircraft; no ticket is left", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && !Flights.ticketed(level, Flights.shipId(a.aircraft)), "pilot at " + a.pilot.position());
            List<String> status = command(level, "airline flight status");
            s.soft("command / flight status in the air showed the aircraft with its cargo", answers.stream().anyMatch(l -> l.contains("CRUISE") && l.contains("iron_ingot=64")) && answers.stream().anyMatch(l -> l.contains("64 x")),
                    String.valueOf(answers));
            s.soft("command / flight status afterwards shows the parked aircraft and the record of both flights", status.stream().anyMatch(l -> l.contains("PARKED"))
                    && status.stream().filter(l -> l.contains("put back on the export racks 64")).count() == 2, String.valueOf(status));
            List<String> none = command(level, "airline aircraft clear");
            s.soft("command / aircraft clear has nothing to take away when no aircraft was given up", Flights.aircraft(level).size() == 1 && none.size() == 1, String.valueOf(none));
            s.note(String.format("OBS ground: %d ticks in all", a.clock));
            a.log.clear();
        });
        s.settle("ground: fifteen seconds more", 300);
        s.step("ground: no new flight at once", () -> {
            s.soft("command / after a called-off cargo flight no new one is sent at once", a.log.stream().noneMatch(l -> l.contains("-> LOAD")) && a.aircraft.phase == Phase.PARKED, "doing " + a.aircraft.phase + ", " + a.log.size() + " changes");
            s.clearMonitors();
        });
    }

    /** Scene 2 (cargo flights that end on the way: a failure in the air, the pilot gone, the block broken) and scene 8 (taking a given-up aircraft away). */
    private static void lost(Script s, ServerLevel level) {
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        addField(s, level, a, "lost A");
        Port b = new Port(new BlockPos(88, Y, 0));
        addPort(s, level, b, "lost B", false);
        int[] n = new int[4];
        TileEntityRack[] rack = new TileEntityRack[1];
        java.util.UUID[] ship = new java.util.UUID[1];
        s.step("lost: 64 iron on A's export racks, colony B wants 64", () -> {
            rack[0] = equip(level, a);
            Fixture.stock(rack[0], Items.IRON_INGOT, 64);
            ledger.set(WantLedger.Key.of(b.colony), Items.IRON_INGOT, 64);
            n[0] = 64;
            watch(s, a, "lost");
        });

        // ---- (a) the controller fails in the air with cargo aboard
        s.until("lost: first flight, 20 blocks along", () -> {}, () -> {
            double[] r = Flights.readings(a.aircraft);
            return r != null && a.aircraft.phase == Phase.CRUISE && r[0] > 20;
        }, 4000);
        s.step("lost: the controller throws in the air", () -> {
            n[1] = ledger.covered(WantLedger.Key.of(b.colony), Items.IRON_INGOT);
            ship[0] = Flights.shipId(a.aircraft);
            Flights.failOnceForChecks();
        });
        s.settle("lost: a moment", 5);
        s.step("lost: given up in the air; taken away by command", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            s.soft("lost / 1-2: after a failure in the air what the flight carried no longer counts as on its way", a.aircraft.phase == Phase.STRANDED && n[1] == 64 && ledger.covered(keyB, Items.IRON_INGOT) == 0
                    && ledger.net(keyB, Items.IRON_INGOT) == 64, "doing " + a.aircraft.phase + "; on the way before " + n[1] + ", now " + ledger.covered(keyB, Items.IRON_INGOT) + "; B wants (net) " + ledger.net(keyB, Items.IRON_INGOT));
            List<String> status = command(level, "airline flight status");
            int all = iron(a.airfield.getItemHandlerCap()) + held(a) + lying(level, Items.IRON_INGOT);
            s.soft("lost / the goods are in the hold, and the status command says so", held(a) == 64 && status.stream().anyMatch(l -> l.contains("STRANDED")) && status.stream().anyMatch(l -> l.contains("64 x")), "hold " + held(a) + " | " + status);
            s.soft("lost / (a) no iron was made or lost", all == n[0], "now " + all + " (racks " + iron(a.airfield.getItemHandlerCap()) + ", hold " + held(a) + "), at the start " + n[0]);
            // Scene 8: the given-up aircraft is taken away; from the console its cargo falls where it was.
            List<String> said = command(level, "airline aircraft clear");
            n[2] = lying(level, Items.IRON_INGOT);
            s.soft("command / aircraft clear hands the cargo over: it was dropped where the aircraft was", n[2] == 64 && said.size() == 1, n[2] + " iron lying there | " + said);
            // This world has no ground under the line: the check picks the goods up and puts them back on the racks.
            Fixture.stock(rack[0], Items.IRON_INGOT, pickUp(level, Items.IRON_INGOT));
        });
        s.settle("lost: Sable takes the ship away", 40);
        s.step("lost: the aircraft is gone; a new one", () -> {
            var container = SubLevelContainer.getContainer(level);
            s.soft("command / aircraft clear: the given-up aircraft is gone", Flights.aircraft(level).isEmpty() && container != null && container.getSubLevel(ship[0]) == null,
                    Flights.aircraft(level).size() + " aircraft listed, ship still there=" + (container != null && container.getSubLevel(ship[0]) != null));
            s.soft("command / its pilot was brought to the ground alive", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && a.pilot.getY() > Y - 1.5 && a.pilot.getY() < Y + 1.5,
                    "pilot at " + a.pilot.position() + " state " + Boarding.state(a.pilot));
            s.soft("lost / the goods are on the racks again (picked up by the check)", iron(a.airfield.getItemHandlerCap()) == 64 && lying(level, Items.IRON_INGOT) == 0, "racks " + iron(a.airfield.getItemHandlerCap()));
            Flights.Answer made = spawn(level, a.pad);
            s.soft("lost / a second aircraft is made on pad A", made.ok() && Flights.aircraft(level).size() == 1, made.text().getString());
            a.aircraft = Flights.aircraft(level).get(0);
            a.seen = null;
            a.log.clear();
        });
        loaded(s, "lost", () -> a.aircraft);
        s.settle("lost: the second aircraft's balloon fills", 320);

        // ---- (b) the pilot is gone for good in the air, with cargo aboard
        s.until("lost: the same goods are sent again: second flight, 20 blocks along", () -> {}, () -> {
            double[] r = Flights.readings(a.aircraft);
            return r != null && a.aircraft.phase == Phase.CRUISE && r[0] > 20;
        }, 5000);
        s.step("lost: the pilot vanishes", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            s.soft("lost / 1-2: the same goods were loaded and sent again", held(a) == 64 && ledger.covered(keyB, Items.IRON_INGOT) == 64 && a.log.stream().anyMatch(l -> l.contains("-> LOAD")),
                    "hold " + held(a) + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT));
            ship[0] = Flights.shipId(a.aircraft);
            a.pilot.discard();
        });
        s.until("lost: given up after a minute without a pilot", () -> {}, () -> a.aircraft.phase == Phase.STRANDED, 1500);
        s.step("lost: no pilot, result; taken away by command", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            int all = iron(a.airfield.getItemHandlerCap()) + held(a) + lying(level, Items.IRON_INGOT);
            s.soft("lost / 1-2: given up without its pilot, what it carried no longer counts as on its way", ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.net(keyB, Items.IRON_INGOT) == 64
                    && !Flights.ticketed(level, ship[0]), "on the way " + ledger.covered(keyB, Items.IRON_INGOT) + ", B wants (net) " + ledger.net(keyB, Items.IRON_INGOT) + " | " + a.aircraft.note);
            s.soft("lost / (b) the goods are in the hold; no iron was made or lost", held(a) == 64 && all == n[0], "hold " + held(a) + ", now " + all + ", at the start " + n[0]);
            List<String> said = command(level, "airline aircraft clear");
            int dropped = lying(level, Items.IRON_INGOT);
            s.soft("command / aircraft clear works for an aircraft without a pilot too", dropped == 64, dropped + " iron lying there | " + said);
            Fixture.stock(rack[0], Items.IRON_INGOT, pickUp(level, Items.IRON_INGOT));
            for (String line : ledger.history()) s.note("OBS lost history: " + line);
            // The colony nobody is near does not make the pilot's entity anew by itself.
            Fixture.respawn(a.colony, a.pilotData, level, a.hut.offset(2, 0, 2));
        });
        s.until("lost: the pilot's entity again", () -> {}, () -> (a.pilot = Fixture.entity(a.pilotData)) != null && a.pilot.isAlive(), 300);
        s.settle("lost: Sable takes the second ship away", 40);
        s.step("lost: a third aircraft", () -> {
            Flights.Answer made = spawn(level, a.pad);
            s.soft("lost / a third aircraft is made on pad A", made.ok() && Flights.aircraft(level).size() == 1, made.text().getString() + " | " + Flights.aircraft(level).size() + " listed");
            a.aircraft = Flights.aircraft(level).get(0);
            a.seen = null;
            a.log.clear();
        });
        loaded(s, "lost", () -> a.aircraft);
        s.settle("lost: the third aircraft's balloon fills", 320);

        // ---- (c) the aircraft's own block is broken, with cargo aboard
        s.until("lost: third flight has left the pad", () -> {}, () -> {
            double[] r = Flights.readings(a.aircraft);
            return r != null && a.aircraft.phase == Phase.CLIMB && r[1] > Y + a.aircraft.restOffset + 1.5;
        }, 4000);
        s.step("lost: the aircraft block is broken", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            List<BlockPos> barrels = Flights.holdBlocks(a.aircraft);
            ship[0] = Flights.shipId(a.aircraft);
            int before = ledger.covered(keyB, Items.IRON_INGOT);
            level.destroyBlock(a.aircraft.getBlockPos(), false);
            int inBarrels = 0;
            for (BlockPos barrel : barrels)
                if (level.getBlockEntity(barrel) instanceof net.minecraft.world.Container box) inBarrels += iron(new net.neoforged.neoforge.items.wrapper.InvWrapper(box));
            int all = iron(a.airfield.getItemHandlerCap()) + inBarrels + lying(level, Items.IRON_INGOT);
            s.soft("lost / 1-2: with its block broken, what the flight carried no longer counts as on its way", before == 64 && ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.net(keyB, Items.IRON_INGOT) == 64
                    && !Flights.ticketed(level, ship[0]), "on the way before " + before + ", now " + ledger.covered(keyB, Items.IRON_INGOT) + ", B wants (net) " + ledger.net(keyB, Items.IRON_INGOT));
            s.soft("lost / (c) the goods are in the barrels of the hold; no iron was made or lost", inBarrels == 64 && all == n[0], "barrels " + inBarrels + ", now " + all + ", at the start " + n[0]);
            s.note(String.format("OBS lost: %d ticks in all", a.clock));
            s.clearMonitors();
        });
    }

    /** Scene 4 (the line is blocked on the way out: cancelled, goods back on the racks) and scene 5 (the import store is nearly full). */
    private static void hindered(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        addField(s, level, a, "hindered A");
        Port b = new Port(new BlockPos(88, Y, 0));
        addPort(s, level, b, "hindered B", true);
        int[] n = new int[8];
        boolean[] flag = new boolean[3];
        BlockPos[] wall = tallWall(44);
        s.step("hindered: 128 iron on A's export racks, B wants 128, B's import stash has room for one stack only", () -> {
            Fixture.stock(equip(level, a), Items.IRON_INGOT, 128);
            ledger.set(WantLedger.Key.of(b.colony), Items.IRON_INGOT, 128);
            net.neoforged.neoforge.items.IItemHandler stash = b.stash.getItemHandlerCap();
            for (int slot = 0; slot < stash.getSlots() - 1; slot++) stash.insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
            n[0] = iron(a.airfield.getItemHandlerCap()) + held(a) + iron(stash) + lying(level, Items.IRON_INGOT);
            s.note("OBS hindered: B's stash has " + stash.getSlots() + " slots, all but one filled with cobblestone; iron at the start " + n[0]);
            watch(s, a, "hindered");
            s.monitor(() -> {
                double[] r = Flights.readings(a.aircraft);
                if (r != null && !flag[0] && a.aircraft.phase == Phase.CRUISE && r[0] > 16) {
                    // Placed without telling anybody (flag 2): only the aircraft's own look ahead can find it.
                    for (BlockPos pos : wall) level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
                    flag[0] = true;
                    n[1] = ledger.covered(WantLedger.Key.of(b.colony), Items.IRON_INGOT);
                    s.note(String.format("FLT hindered: wall placed at x=44 when the aircraft was at x=%.1f with %d iron in the hold; on the way by the ledger %d", r[0], held(a), n[1]));
                }
            });
        });

        // ---- scene 4
        s.until("hindered: the blocked flight ends", () -> {}, () -> flag[0] && a.aircraft.phase == Phase.PARKED, 8000);
        s.step("hindered: cancelled, result", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            double[] r = Flights.readings(a.aircraft);
            int racks = iron(a.airfield.getItemHandlerCap()), all = racks + held(a) + iron(b.stash.getItemHandlerCap()) + lying(level, Items.IRON_INGOT);
            s.soft("hindered / 4: with its line blocked and no other way the aircraft came back and landed where it started", a.pad.equals(a.aircraft.at) && r != null && r[4] > 0 && a.log.stream().anyMatch(l -> l.contains("-> RETREAT")),
                    "at " + a.aircraft.at + " " + at(r) + " | " + a.aircraft.note);
            s.soft("hindered / 4: the cargo is back on the export racks", racks == 128 && held(a) == 0, "racks " + racks + ", hold " + held(a));
            s.soft("hindered / 4: the ledger is as it was before the flight", n[1] == 128 && ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.wanted(keyB, Items.IRON_INGOT) == 128,
                    "on the way during the flight " + n[1] + ", now " + ledger.covered(keyB, Items.IRON_INGOT) + "; wanted " + ledger.wanted(keyB, Items.IRON_INGOT));
            s.soft("hindered / 4: no iron was made or lost", all == n[0], "now " + all + ", at the start " + n[0]);
            s.soft("hindered / 4: the pilot is alive and off the aircraft; no ticket is left", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && !Flights.ticketed(level, Flights.shipId(a.aircraft)), "pilot at " + a.pilot.position());
            s.note("OBS hindered: the blocked flight took " + a.clock + " ticks");
            for (BlockPos pos : wall) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            a.log.clear();
        });

        // ---- scene 5: the line is clear again; the next flight finds the import store nearly full
        s.until("hindered: the second flight: out, unloads what fits, home again", () -> {}, () -> {
            if (!flag[1] && a.aircraft.phase == Phase.UNLOAD && b.pad.equals(a.aircraft.at) && a.aircraft.settled) {
                flag[1] = true;
                n[2] = iron(b.stash.getItemHandlerCap());
                n[3] = held(a);
                n[4] = ledger.covered(WantLedger.Key.of(b.colony), Items.IRON_INGOT);
                n[5] = ledger.wanted(WantLedger.Key.of(b.colony), Items.IRON_INGOT);
                s.note(String.format("FLT hindered: at B after unloading: stash holds %d iron, hold %d, on the way %d, B still wants %d | %s", n[2], n[3], n[4], n[5], a.aircraft.note));
            }
            return flag[1] && a.aircraft.phase == Phase.PARKED;
        }, 14000);
        s.step("hindered: full store, result", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            int racks = iron(a.airfield.getItemHandlerCap()), stash = iron(b.stash.getItemHandlerCap()), all = racks + held(a) + stash + lying(level, Items.IRON_INGOT);
            s.soft("hindered / 5: only what fitted was unloaded at B, the rest stayed in the hold", n[2] == 64 && n[3] == 64, "stash " + n[2] + ", hold " + n[3]);
            s.soft("hindered / 5: the ledger knows: 64 are still wanted, nothing is on the way", n[4] == 0 && n[5] == 64 && ledger.covered(keyB, Items.IRON_INGOT) == 0 && ledger.wanted(keyB, Items.IRON_INGOT) == 64,
                    "at B: on the way " + n[4] + ", wanted " + n[5] + "; at home: on the way " + ledger.covered(keyB, Items.IRON_INGOT) + ", wanted " + ledger.wanted(keyB, Items.IRON_INGOT));
            s.soft("hindered / 5: what came back is on A's export racks again", a.pad.equals(a.aircraft.at) && racks == 64 && held(a) == 0, "racks " + racks + ", hold " + held(a) + " | " + a.aircraft.note);
            s.soft("hindered / scheduled: three take-offs (out, out again, home from B), none before a whole berth (" + BERTH + " ticks) on the pad", a.cargoTakeoffs == 3 && a.shortestBerth >= BERTH,
                    a.cargoTakeoffs + " take-offs, shortest stay " + a.shortestBerth);
            s.soft("hindered / 5: no iron was made or lost", all == n[0] && stash == 64, "now " + all + " (racks " + racks + ", hold " + held(a) + ", B stash " + stash + "), at the start " + n[0]);
            for (String line : ledger.history()) s.note("OBS hindered history: " + line);
            s.note("OBS hindered: " + a.clock + " ticks in all");
            a.log.clear();
        });

        // ---- scene 8 once more: B still wants 64, so the next flight starts at once; it is called off before it leaves
        s.until("hindered: a third flight begins to load", () -> {}, () -> a.aircraft.phase == Phase.LOAD && held(a) == 64, 1000);
        s.step("hindered: /airline flight cancel before take-off", () -> {
            s.note("OBS hindered: after a part delivery the next flight started by itself " + a.log.stream().filter(l -> l.contains("-> LOAD")).count() + " time(s); on the way " + ledger.covered(WantLedger.Key.of(b.colony), Items.IRON_INGOT));
            List<String> said = command(level, "airline flight cancel");
            s.soft("command / flight cancel before take-off is accepted: the aircraft does not leave", a.aircraft.phase == Phase.UNLOAD && a.aircraft.cancelled && a.pad.equals(a.aircraft.at), said + " | doing " + a.aircraft.phase);
        });
        s.until("hindered: the goods are put back, parked", () -> {}, () -> a.aircraft.phase == Phase.PARKED, 1000);
        s.step("hindered: called off before take-off, result", () -> {
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            int racks = iron(a.airfield.getItemHandlerCap()), all = racks + held(a) + iron(b.stash.getItemHandlerCap()) + lying(level, Items.IRON_INGOT);
            s.soft("command / called off before take-off: the cargo is back on the racks, the ledger as before, no iron made or lost", racks == 64 && held(a) == 0 && ledger.covered(keyB, Items.IRON_INGOT) == 0
                    && ledger.wanted(keyB, Items.IRON_INGOT) == 64 && all == n[0] && a.log.stream().noneMatch(l -> l.contains("-> CLIMB")),
                    "racks " + racks + ", hold " + held(a) + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT) + ", wanted " + ledger.wanted(keyB, Items.IRON_INGOT) + ", iron in all " + all + " | " + a.aircraft.note);
            s.clearMonitors();
        });
    }

    // ---- finish, scene 3: stopping the server while a cargo flight loads, flies, unloads ---------------------------------

    private static final net.minecraft.core.BlockPos REOPEN_B = new BlockPos(88, Y, 0);

    /** Iron in every place a cargo reopen scene can have it: A's export racks, the hold, B's import stash, on the ground. */
    private static int[] ironNow(ServerLevel level, Field a, Port b) {
        int racks = iron(a.airfield.getItemHandlerCap()), hold = held(a), stash = iron(b.stash.getItemHandlerCap()), lying = lying(level, Items.IRON_INGOT);
        return new int[] {racks + hold + stash + lying, racks, hold, stash, lying};
    }

    private static String ironText(int[] c) {
        return c[0] + " (A racks " + c[1] + ", hold " + c[2] + ", B stash " + c[3] + ", on the ground " + c[4] + ")";
    }

    private static void keep(Field a, Port b, int total, String where) throws Exception {
        Files.writeString(Path.of(STATE), String.format("colonyA=%d%ncolonyB=%d%ntotal=%d%nwhere=%s%n", a.colony.getID(), b.colony.getID(), total, where));
    }

    /**
     * Force-loads the chunks around some places, and makes sure that this is saved: setChunkForced clears the "to be saved"
     * mark of the list whenever its last call changed nothing, and a list that was never saved is empty after a restart.
     */
    private static void keepLoaded(ServerLevel level, BlockPos... around) {
        for (BlockPos pos : around)
            for (int cx = (pos.getX() - 24) >> 4; cx <= (pos.getX() + 24) >> 4; cx++) for (int cz = (pos.getZ() - 24) >> 4; cz <= (pos.getZ() + 24) >> 4; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
        level.getDataStorage().computeIfAbsent(net.minecraft.world.level.ForcedChunksSavedData.factory(), "chunks").setDirty();
    }

    /** No chunk is force-loaded any more (and that is saved). */
    private static void letGo(ServerLevel level) {
        for (long pos : level.getForcedChunks().toLongArray()) level.setChunkForced(net.minecraft.world.level.ChunkPos.getX(pos), net.minecraft.world.level.ChunkPos.getZ(pos), false);
        level.getDataStorage().computeIfAbsent(net.minecraft.world.level.ForcedChunksSavedData.factory(), "chunks").setDirty();
    }

    /** The colony's (first) airfield, found among its buildings. */
    /** The airfield's own open Stack requests: those it may fill itself (parts for its aircraft), or the export table's. */
    private static java.util.List<Stack> partRequests(BuildingAirfield airfield, boolean own) {
        final java.util.List<Stack> out = new java.util.ArrayList<>();
        for (var open : airfield.getOpenRequestsOfType(-1, com.google.common.reflect.TypeToken.of(Stack.class)))
            if (open.getRequest().canBeResolvedByBuilding() == own) out.add(open.getRequest());
        return out;
    }

    private static BuildingAirfield airfieldOf(IColony colony) {
        for (final com.minecolonies.api.colony.buildings.IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            if (building instanceof BuildingAirfield airfield && airfield.facility().airfield()) return airfield;
        return null;
    }

    /** After a restart: the colonies, airfields, B's stash, the pilot and the aircraft are found again in the saved world. */
    private static void findAgain(Script s, ServerLevel level, Field a, Port b, java.util.Map<String, String> before, String name) {
        int[] waited = new int[1];
        s.step(name + ": the colonies after the restart", () -> {
            a.colony = IColonyManager.getInstance().getColonyByDimension(Integer.parseInt(before.get("colonyA")), level.dimension());
            b.colony = IColonyManager.getInstance().getColonyByDimension(Integer.parseInt(before.get("colonyB")), level.dimension());
            s.check(a.colony != null && b.colony != null, "both colonies are there after the restart");
            a.airfield = airfieldOf(a.colony);
            b.airfield = airfieldOf(b.colony);
            s.check(a.airfield != null && b.airfield != null, "both airfields are there after the restart");
            a.pad = a.airfield.pad();
            b.pad = b.airfield.pad();
            b.stash = (AbstractBuilding) b.colony.getServerBuildingManager().getBuilding(b.hut.offset(5, 0, 3));
            s.check(b.stash != null, "B's import stash is there after the restart");
            for (ICitizenData citizen : a.colony.getCitizenManager().getCitizens()) if (citizen.getJob() instanceof com.asteain.airline.colony.JobPilot) a.pilotData = citizen;
        });
        s.until(name + ": the aircraft shows up again", () -> {}, () -> {
            waited[0]++;
            List<AircraftBlockEntity> all = Flights.aircraft(level);
            if (all.isEmpty()) return false;
            a.aircraft = all.get(0);
            return Flights.readings(a.aircraft) != null;
        }, 700);
        s.step(name + ": right after the restart", () -> {
            a.pilot = a.pilotData == null ? null : Fixture.entity(a.pilotData);
            s.monitor(() -> { if (a.pilotData != null && (a.pilot == null || !a.pilot.isAlive() || a.pilot.isRemoved())) a.pilot = Fixture.entity(a.pilotData); });
            watch(s, a, name);
            int[] c = ironNow(level, a, b);
            WantLedger ledger = WantLedger.get(level.getServer());
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            s.note(String.format("OBS %s: stopped %s; %d ticks after the restart the aircraft is loaded, doing %s at %s; iron %s; manifest %s; on the way %d, wanted %d; force-loaded chunks %d, players %d", name, before.get("where"), waited[0],
                    a.aircraft.phase, at(Flights.readings(a.aircraft)), ironText(c), a.aircraft.manifest, ledger.covered(keyB, Items.IRON_INGOT), ledger.wanted(keyB, Items.IRON_INGOT),
                    level.getForcedChunks().size(), level.players().size()));
            s.soft(name + " / 3: after the restart no iron was made or lost", c[0] == Integer.parseInt(before.get("total")), "now " + ironText(c) + ", before the stop " + before.get("total"));
            final int[] since = new int[1];
            s.monitor(() -> { if (++since[0] == 100 && a.aircraft != null) s.note("OBS " + name + ": 100 ticks after the restart, " + a.aircraft.phase + ", turning: " + turning(level, a.aircraft)); });
        });
    }

    /** For looking into a reopen world by hand: what the aircraft and its burner say, every five seconds. */
    private static void reopenprobe(Script s, ServerLevel level) throws Exception {
        java.util.Map<String, String> before = state();
        Field a = new Field(HUT_A);
        Port b = new Port(REOPEN_B);
        findAgain(s, level, a, b, before, "probe");
        for (int i = 0; i < 8; i++) {
            s.step("probe: look", () -> {
                double[] r = Flights.readings(a.aircraft);
                BlockPos base = a.aircraft.getBlockPos();
                StringBuilder parts = new StringBuilder();
                for (BlockPos part : new BlockPos[] {new BlockPos(1, 1, 1), new BlockPos(1, 1, 0)}) {
                    net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(base.offset(part));
                    parts.append(" | ").append(level.getBlockState(base.offset(part))).append(" ").append(entity == null ? "NO BLOCK ENTITY" : entity.saveWithoutMetadata(level.registryAccess()).toString());
                }
                String text = parts.toString();
                s.note(String.format("OBS probe t=%d: %s at %s fixed=%s speed=%.2f writes=%.0f pilot=%s%s", a.clock, a.aircraft.phase, at(r), r != null && r[4] > 0, r == null ? -1 : r[9], r == null ? -1 : r[8],
                        a.pilot == null ? "none" : String.valueOf(Boarding.state(a.pilot)), text.length() > 300 ? text.substring(0, 300) : text));
                // Is the aircraft's own block still the one that is ticked?
                List<AircraftBlockEntity> listed = Flights.aircraft(level);
                net.minecraft.world.level.block.entity.BlockEntity now = level.getBlockEntity(base);
                var container = SubLevelContainer.getContainer(level);
                java.util.UUID id = Flights.shipId(a.aircraft);
                SubLevel byId = container == null || id == null ? null : container.getSubLevel(id);
                SubLevel containing = dev.ryanhcode.sable.Sable.HELPER.getContaining(level, base);
                net.minecraft.world.level.chunk.LevelChunk plotChunk = level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4);
                s.note(String.format("OBS probe t=%d: controller steps so far %.0f; entities loaded at the plot chunk=%s; plot chunk status=%s; plot at %s; forced chunks=%d; pilot's age=%d; players=%d", a.clock, r == null || r.length < 11 ? -1 : r[10],
                        level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(base)), plotChunk == null ? "none" : String.valueOf(plotChunk.getFullStatus()), base.toShortString(),
                        level.getForcedChunks().size(), a.pilot == null ? -1 : a.pilot.tickCount, level.players().size()));
                s.note(String.format("OBS probe t=%d: block entity removed=%s, same as the one in the world=%s (%s), listed=%d (same=%s); plot chunk loaded=%s, ticks blocks=%s, entity ticking=%s; ship by id=%s removed=%s, containing=%s same=%s; sub-levels=%d; ticketed=%s",
                        a.clock, a.aircraft.isRemoved(), now == a.aircraft, now == null ? "none" : now.getClass().getSimpleName(), listed.size(), !listed.isEmpty() && listed.get(0) == a.aircraft,
                        level.getChunkSource().getChunkNow(base.getX() >> 4, base.getZ() >> 4) != null, level.shouldTickBlocksAt(base), level.isPositionEntityTicking(base),
                        byId == null ? "none" : "found", byId != null && byId.isRemoved(), containing == null ? "none" : "found", containing == byId,
                        container == null ? -1 : container.getLoadedCount(), id != null && Flights.ticketed(level, id)));
            });
            s.settle("probe: five seconds", 100);
        }
    }

    /** First start: everything is set up; the server stops when the first of two stacks is in the hold. */
    private static void reopenc1(Script s, ServerLevel level) {
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        addField(s, level, a, "reopenc A");
        Port b = new Port(REOPEN_B);
        addPort(s, level, b, "reopenc B", true);
        s.step("reopenc1: 128 iron on A's export racks, colony B wants 128", () -> {
            Fixture.stock(equip(level, a), Items.IRON_INGOT, 128);
            ledger.set(WantLedger.Key.of(b.colony), Items.IRON_INGOT, 128);
            watch(s, a, "reopenc1");
        });
        s.until("reopenc1: one of two stacks is loaded", () -> {}, () -> a.aircraft.phase == Phase.LOAD && held(a) == 64, 800);
        s.step("reopenc1: stop the server in the middle of loading", () -> {
            int[] c = ironNow(level, a, b);
            s.soft("reopenc1 / stopped while loading: half in the hold, half on the racks", c[1] == 64 && c[2] == 64 && c[0] == 128, ironText(c) + ", on the way " + ledger.covered(WantLedger.Key.of(b.colony), Items.IRON_INGOT));
            // Both ports stay loaded over the restart, as they would with players about.
            keepLoaded(level, a.pad, b.pad);
            keep(a, b, c[0], "while loading (64 of 128 in the hold)");
            s.clearMonitors();
        });
    }

    /** Second start: the loading goes on, the aircraft takes off; the server stops in the air. */
    private static void reopenc2(Script s, ServerLevel level) throws Exception {
        java.util.Map<String, String> before = state();
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        Port b = new Port(REOPEN_B);
        findAgain(s, level, a, b, before, "reopenc2");
        s.until("reopenc2: the loading goes on, the aircraft takes off and is 30 blocks along", () -> {}, () -> {
            double[] r = Flights.readings(a.aircraft);
            return r != null && a.aircraft.phase == Phase.CRUISE && r[0] > 30;
        }, 4000);
        s.step("reopenc2: stop the server in the air", () -> {
            int[] c = ironNow(level, a, b);
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            s.soft("reopenc2 / 3: stopped while loading, it went on from there: the second stack was loaded, and it flies", c[2] == 128 && c[1] == 0 && ledger.covered(keyB, Items.IRON_INGOT) == 128
                    && a.aircraft.manifest.getOrDefault("minecraft:iron_ingot", 0) == 128, ironText(c) + ", manifest " + a.aircraft.manifest + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT));
            s.soft("reopenc2 / 3: no iron was made or lost", c[0] == Integer.parseInt(before.get("total")), "now " + ironText(c));
            // The next start finds nobody in the dimension and no chunk force-loaded: only the aircraft's own ticket.
            letGo(level);
            keep(a, b, c[0], "in the air at " + at(Flights.readings(a.aircraft)) + ", with no chunk force-loaded any more");
            s.clearMonitors();
        });
    }

    /** Third start: the flight goes on and lands at B; the server stops when the first of two stacks is unloaded. */
    private static void reopenc3(Script s, ServerLevel level) throws Exception {
        java.util.Map<String, String> before = state();
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        Port b = new Port(REOPEN_B);
        findAgain(s, level, a, b, before, "reopenc3");
        s.until("reopenc3: 330 ticks with nobody in the dimension and no chunk force-loaded", () -> {}, () -> a.clock >= 330, 400);
        s.step("reopenc3: the controller still runs", () -> {
            double[] r = Flights.readings(a.aircraft);
            s.soft("reopenc3 / with no player in the dimension and no force-loaded chunk, the controller of a flight under way keeps running (Minecraft would stop it after 300 ticks)",
                    level.getForcedChunks().isEmpty() && level.players().isEmpty() && r != null && r.length > 10 && r[10] >= 320 && a.aircraft.flying() && a.pilot != null && a.pilot.tickCount >= 320,
                    "force-loaded chunks " + level.getForcedChunks().size() + ", players " + level.players().size() + ", controller steps " + (r == null || r.length < 11 ? -1 : (int) r[10]) + " and pilot's age "
                            + (a.pilot == null ? -1 : a.pilot.tickCount) + " after " + a.clock + " ticks, doing " + a.aircraft.phase + " at " + at(r));
            // From here on both ports are kept loaded again, as they would be with players about.
            keepLoaded(level, a.pad, b.pad);
        });
        s.until("reopenc3: the flight goes on, lands at B, and one of two stacks is unloaded", () -> {}, () -> a.aircraft.phase == Phase.UNLOAD && b.pad.equals(a.aircraft.at) && held(a) == 64, 6000);
        s.step("reopenc3: stop the server in the middle of unloading", () -> {
            int[] c = ironNow(level, a, b);
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            s.soft("reopenc3 / 3: stopped in the air, it went on from there: landed at B and unloads", c[3] == 64 && c[2] == 64 && !a.aircraft.settled, ironText(c) + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT));
            s.soft("reopenc3 / 3: no iron was made or lost", c[0] == Integer.parseInt(before.get("total")), "now " + ironText(c));
            keep(a, b, c[0], "while unloading at B (64 of 128 in B's stash)");
            s.clearMonitors();
        });
    }

    /** Fourth start: the unloading goes on, the aircraft flies home and is parked. */
    private static void reopenc4(Script s, ServerLevel level) throws Exception {
        java.util.Map<String, String> before = state();
        WantLedger ledger = WantLedger.get(level.getServer());
        Field a = new Field(HUT_A);
        Port b = new Port(REOPEN_B);
        findAgain(s, level, a, b, before, "reopenc4");
        s.until("reopenc4: the unloading goes on, the aircraft flies home and is parked", () -> {}, () -> a.aircraft.phase == Phase.PARKED && a.pad.equals(a.aircraft.at), 9000);
        s.step("reopenc4: result", () -> {
            int[] c = ironNow(level, a, b);
            WantLedger.Key keyB = WantLedger.Key.of(b.colony);
            double[] r = Flights.readings(a.aircraft);
            s.soft("reopenc4 / 3: stopped while unloading, it went on from there: everything was unloaded at B", c[3] == 128 && c[2] == 0 && c[1] == 0, ironText(c));
            s.soft("reopenc4 / 3: the ledger is settled: B wants nothing more, nothing is on the way", ledger.wanted(keyB, Items.IRON_INGOT) == 0 && ledger.covered(keyB, Items.IRON_INGOT) == 0
                    && ledger.history().stream().anyMatch(l -> l.contains("delivered 128, carried back 0")), "wanted " + ledger.wanted(keyB, Items.IRON_INGOT) + ", on the way " + ledger.covered(keyB, Items.IRON_INGOT) + " | " + ledger.history());
            s.soft("reopenc4 / 3: the aircraft is home again, fixed, parked; the pilot is alive", r != null && r[4] > 0 && a.pilot != null && a.pilot.isAlive() && !Flights.ticketed(level, Flights.shipId(a.aircraft)),
                    at(r) + " | " + a.aircraft.note);
            s.soft("reopenc4 / 3: no iron was made or lost over three stops", c[0] == Integer.parseInt(before.get("total")), "now " + ironText(c) + ", at the first stop " + before.get("total"));
            s.clearMonitors();
        });
    }

    // ---- the controller itself fails in the air (review 1-1) -----------------------------------------------------

    private static void crash(Script s, ServerLevel level) {
        Field a = new Field(HUT_A);
        addField(s, level, a, "crash");
        BlockPos padB = new BlockPos(70, Y - 1, -10);
        java.util.UUID[] ship = new java.util.UUID[1];
        double[] at = new double[2];
        s.step("crash: pad B, the order", () -> {
            for (int cx = -2; cx <= 6; cx++) for (int cz = -3; cz <= 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            pad(level, padB, 0);
            ship[0] = Flights.shipId(a.aircraft);
            watch(s, a, "crash");
            Flights.Answer sent = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("crash / the flight is ordered", sent.ok(), sent.text().getString());
        });
        s.until("crash: 20 blocks along", () -> {}, () -> {
            double[] r = Flights.readings(a.aircraft);
            return r != null && a.aircraft.phase == Phase.CRUISE && r[0] > 20;
        }, 3000);
        s.step("crash: the controller throws", () -> {
            double[] r = Flights.readings(a.aircraft);
            at[0] = r[0];
            at[1] = r[1];
            s.note(String.format("FLT crash: failure made at x=%.1f, speed %.2f, ticket=%s, fixed=%s", r[0], r[9], Flights.ticketed(level, ship[0]), r[4] > 0));
            Flights.failOnceForChecks();
        });
        s.settle("crash: five seconds", 100);
        s.step("crash: result", () -> {
            double[] r = Flights.readings(a.aircraft);
            s.soft("crash / 1-1: after a failure of the controller the aircraft is stopped and fixed where it was", r != null && r[4] > 0 && r[9] < 0.05 && Math.abs(r[0] - at[0]) < 1.0 && Math.abs(r[1] - at[1]) < 0.5,
                    r == null ? "?" : String.format("fixed=%s speed %.2f, moved %.2f along and %.2f in height since the failure", r[4] > 0, r[9], r[0] - at[0], r[1] - at[1]));
            s.soft("crash / 1-1: it has given its ticket back and is in an end state", !Flights.ticketed(level, ship[0]) && a.aircraft.phase == Phase.STRANDED && !a.aircraft.flying(),
                    "ticketed=" + Flights.ticketed(level, ship[0]) + " doing " + a.aircraft.phase + " | " + a.aircraft.note);
            s.soft("crash / the pilot is alive", a.pilot.isAlive(), "state " + Boarding.state(a.pilot) + " at " + a.pilot.position());
            s.clearMonitors();
        });
    }

    // ---- when something goes missing in the middle of a flight (the review's four defects) -------------------------

    private static void faults(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        Field a = new Field(HUT_A);
        addField(s, level, a, "faults");
        BlockPos padB = new BlockPos(70, Y - 1, -10), padC = new BlockPos(0, Y - 1, -80), bare = new BlockPos(-70, Y - 1, -10);
        ICitizenData[] second = new ICitizenData[1];
        Mob[] other = new Mob[1];
        java.util.UUID[] ship = new java.util.UUID[2];
        boolean[] done = new boolean[3];
        double[] where = new double[3];
        s.step("faults: pads, a second citizen", () -> {
            for (int cx = -6; cx <= 6; cx++) for (int cz = -7; cz <= 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            pad(level, padB, 0);
            pad(level, padC, 270);
            net.setAirfield(bare, 180, 1);      // an airfield that is not built: a point of the network, no floor
            Flights.Answer refused = Flights.send(level, a.pad, bare, false, a.pilot);
            s.soft("faults / 1-4: a flight to a pad that has no floor is refused", !refused.ok() && a.aircraft.phase == Phase.PARKED, refused.text().getString());
            second[0] = Fixture.spawn(a.colony, level, a.hut.offset(-2, 0, 2));
            ship[0] = Flights.shipId(a.aircraft);
            s.soft("faults / a parked aircraft holds no ticket", !Flights.ticketed(level, ship[0]), "ticketed=" + Flights.ticketed(level, ship[0]));
            watch(s, a, "faults");
        });
        s.until("faults: second citizen", () -> {}, () -> (other[0] = Fixture.entity(second[0])) != null, 200);

        // ---- 1-4: the floor of the destination goes away while the aircraft is on its way
        s.step("faults: order to pad B", () -> {
            s.monitor(() -> {
                double[] r = Flights.readings(a.aircraft);
                if (r != null && !done[0] && a.aircraft.phase == Phase.CRUISE && r[0] > 20) {
                    for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) level.setBlockAndUpdate(padB.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                    done[0] = true;
                    s.note("FLT faults: the floor of pad B was taken away; ticket held in the air=" + Flights.ticketed(level, ship[0]));
                }
            });
            Flights.Answer sent = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("faults / the flight to pad B is ordered", sent.ok(), sent.text().getString());
        });
        s.until("faults: the flight ends", () -> {}, () -> (a.aircraft.phase == Phase.PARKED || a.aircraft.phase == Phase.STRANDED) && a.clock > 100, 7000);
        s.step("faults: no floor, result", () -> {
            double[] r = Flights.readings(a.aircraft);
            s.soft("faults / 1-4: over a pad whose floor is gone the aircraft does not go down; it returns to where it started and lands there, cancelled",
                    done[0] && a.aircraft.phase == Phase.PARKED && a.pad.equals(a.aircraft.at) && r != null && r[4] > 0 && a.log.stream().noneMatch(l -> l.contains("-> DESCEND at (7")),
                    "doing " + a.aircraft.phase + " at " + a.aircraft.at + " " + at(r) + " | " + a.aircraft.note);
            s.soft("faults / the pilot is alive and off the aircraft at home", a.pilot.isAlive() && Boarding.state(a.pilot) == Boarding.State.NONE && a.pilot.getY() < Y + 0.6, "pilot at " + a.pilot.position());
            s.soft("faults / no ticket is left after the cancelled flight", !Flights.ticketed(level, ship[0]), "ticketed=" + Flights.ticketed(level, ship[0]));
            pad(level, padB, 0);
        });
        s.until("faults: lines verified again", () -> {}, () -> net.pending() == 0, 600);

        // ---- 1-3: the aircraft's own block is broken in the air
        s.step("faults: order to pad B again; the aircraft block will be broken", () -> {
            a.clock = 0;
            a.since = 0;
            s.monitor(() -> {
                double[] r = Flights.readings(a.aircraft);
                if (r != null && !done[1] && a.aircraft.phase == Phase.CRUISE && r[0] > 20) {
                    boolean before = Flights.ticketed(level, ship[0]);
                    level.destroyBlock(a.aircraft.getBlockPos(), false);
                    done[1] = true;
                    where[0] = a.clock;
                    s.note(String.format("FLT faults: aircraft block broken at x=%.1f, speed %.2f; ticket before=%s, right after=%s", r[0], r[9], before, Flights.ticketed(level, ship[0])));
                }
            });
            Flights.Answer sent = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("faults / the second flight to pad B is ordered", sent.ok(), sent.text().getString());
        });
        s.until("faults: broken, and ten seconds more", () -> {}, () -> done[1] && a.clock > where[0] + 200, 3000);
        s.step("faults: broken block, result", () -> {
            double[] r = Flights.readings(a.aircraft);
            s.soft("faults / 1-3: with its block broken in the air the aircraft gives its ticket back", !Flights.ticketed(level, ship[0]), "ticketed=" + Flights.ticketed(level, ship[0]));
            s.soft("faults / 1-3: and its drives are off (it has slowed to a stop)", r != null && r[9] < 0.3, r == null ? "?" : String.format("speed %.2f at %s", r[9], at(r)));
            s.soft("faults / it is no longer listed as an aircraft", Flights.aircraft(level).isEmpty(), Flights.aircraft(level).size() + " listed");
            // A second aircraft on pad A, flown by the second citizen, for the last case.
            Flights.Answer made = spawn(level, a.pad);
            s.soft("faults / a second aircraft is made on pad A", made.ok(), made.text().getString());
            a.aircraft = Flights.aircraft(level).get(0);
            a.pilot = other[0];
            a.seen = null;
        });
        loaded(s, "faults", () -> a.aircraft);
        s.settle("faults: the second aircraft's balloon fills", 320);

        // ---- 1-2: the pilot is gone for good in the air
        s.step("faults: order to pad C; the pilot will vanish", () -> {
            a.clock = 0;
            a.since = 0;
            ship[1] = Flights.shipId(a.aircraft);
            s.monitor(() -> {
                double[] r = Flights.readings(a.aircraft);
                if (r != null && !done[2] && a.aircraft.phase == Phase.CRUISE && r[2] < -35) {
                    other[0].discard();
                    done[2] = true;
                    where[0] = a.clock;
                    where[1] = r[1];
                    s.note(String.format("FLT faults: the pilot was removed at z=%.1f, speed %.2f, ticket=%s", r[2], r[9], Flights.ticketed(level, ship[1])));
                }
                if (r != null && done[2] && where[2] == 0 && r[4] > 0) where[2] = a.clock - where[0];
            });
            Flights.Answer sent = Flights.send(level, a.pad, padC, false, other[0]);
            s.soft("faults / the flight to pad C is ordered", sent.ok(), sent.text().getString());
        });
        s.until("faults: given up", () -> {}, () -> done[2] && (a.aircraft.phase == Phase.STRANDED || a.clock > where[0] + 1500), 5000);
        s.step("faults: no pilot, result", () -> {
            double[] r = Flights.readings(a.aircraft);
            s.soft("faults / 1-2: without its pilot the aircraft is fixed where it is at once", where[2] > 0 && where[2] <= 3, "fixed " + where[2] + " ticks after the pilot was removed");
            s.soft("faults / 1-2: after a minute it is given up: fixed, no ticket, an end state", a.aircraft.phase == Phase.STRANDED && r != null && r[4] > 0 && !Flights.ticketed(level, ship[1])
                    && a.clock - where[0] <= 1260, "doing " + a.aircraft.phase + ", " + (a.clock - where[0]) + " ticks after the pilot was removed, ticketed=" + Flights.ticketed(level, ship[1]) + " | " + a.aircraft.note);
            s.soft("faults / it did not fall", r != null && Math.abs(r[1] - where[1]) < 1.0, r == null ? "?" : String.format("height %.2f, was %.2f", r[1], where[1]));
            s.soft("faults / an aircraft that was given up is not flying: a restart would not give it a ticket again", !a.aircraft.flying(), "flying()=" + a.aircraft.flying());
            s.clearMonitors();
        });
    }

    // ---- stopping the server in the air, and on the ground ---------------------------------------------------------

    private static void reopen1(Script s, ServerLevel level) {
        RouteNetwork net = RouteNetwork.get(level);
        Field a = new Field(HUT_A);
        addField(s, level, a, "reopen");
        BlockPos padB = new BlockPos(90, Y - 1, -10), buoy = new BlockPos(45, Y, -40);
        s.step("reopen: pad B, a buoy, the order", () -> {
            pad(level, padB, 0);
            level.setBlockAndUpdate(buoy, Airline.BUOYS.get(0).get().defaultBlockState());
            watch(s, a, "reopen1");
            Flights.Answer sent = Flights.send(level, a.pad, padB, false, a.pilot);
            s.soft("reopen / the flight is ordered", sent.ok(), sent.text().getString());
        });
        s.until("reopen: in the air, 62 blocks along", () -> {}, () -> {
            double[] r = Flights.readings(a.aircraft);
            return r != null && a.aircraft.phase == Phase.CRUISE && r[0] > 62;      // beyond the chunks that the forced floor of pad A keeps loaded
        }, 4000);
        s.step("reopen: write down the state and stop the server", () -> {
            double[] r = Flights.readings(a.aircraft);
            Files.writeString(Path.of(STATE), String.format("x=%.3f%ny=%.3f%nz=%.3f%nnodes=%d%nlines=%d%npadB=%d%npilot=%s%ncolony=%d%n", r[0], r[1], r[2], net.nodes().size(), net.edges().size(),
                    padB.asLong(), a.pilot.getUUID(), a.colony.getID()));
            s.note("OBS reopen1: stopped in the air at " + at(r) + ", pilot " + Boarding.state(a.pilot) + ", " + net.nodes().size() + " points, " + net.edges().size() + " lines");
            s.clearMonitors();
        });
    }

    private static java.util.Map<String, String> state() throws Exception {
        java.util.Map<String, String> state = new java.util.HashMap<>();
        for (String line : Files.readAllLines(Path.of(STATE))) {
            int eq = line.indexOf('=');
            if (eq > 0) state.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return state;
    }

    private static void reopen2(Script s, ServerLevel level) throws Exception {
        java.util.Map<String, String> before = state();
        BlockPos padB = BlockPos.of(Long.parseLong(before.get("padB")));
        Field a = new Field(HUT_A);
        int[] waited = new int[1];
        s.step("reopen2: the network after the restart", () -> {
            RouteNetwork net = RouteNetwork.get(level);
            s.soft("reopen2 / the network was saved and loaded: the same points and lines", net.nodes().size() == Integer.parseInt(before.get("nodes")) && net.edges().size() == Integer.parseInt(before.get("lines")),
                    net.nodes().size() + " points, " + net.edges().size() + " lines (before: " + before.get("nodes") + ", " + before.get("lines") + ")");
        });
        s.until("reopen2: the aircraft shows up again", () -> {}, () -> {
            waited[0]++;
            List<AircraftBlockEntity> all = Flights.aircraft(level);
            if (all.isEmpty()) return waited[0] > 600;
            a.aircraft = all.get(0);
            return Flights.readings(a.aircraft) != null;
        }, 700);
        s.step("reopen2: where is it", () -> {
            boolean back = a.aircraft != null;
            s.soft("reopen2 / after the restart the aircraft is loaded again by itself", back, "after " + waited[0] + " ticks: " + (back ? "loaded" : "NOT loaded (nothing loads the place where it hangs)"));
            if (!back) return;
            double[] r = Flights.readings(a.aircraft);
            IColony colony = IColonyManager.getInstance().getColonyByDimension(Integer.parseInt(before.get("colony")), level.dimension());
            if (colony != null) for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) if (citizen.getJob() instanceof com.asteain.airline.colony.JobPilot) a.pilotData = citizen;
            a.pilot = a.pilotData == null ? null : Fixture.entity(a.pilotData);
            s.monitor(() -> { if (a.pilotData != null && (a.pilot == null || !a.pilot.isAlive() || a.pilot.isRemoved())) a.pilot = Fixture.entity(a.pilotData); });
            s.note(String.format("OBS reopen2: stopped at (%s, %s, %s); %d ticks after the restart the aircraft is at %s, doing %s; pilot %s", before.get("x"), before.get("y"), before.get("z"), waited[0], at(r),
                    a.aircraft.phase, a.pilot == null ? "not found" : Boarding.state(a.pilot) + " passenger=" + a.pilot.isPassenger() + " at " + a.pilot.position()));
            s.soft("reopen2 / it did not fall: within 3 blocks of the height it had", Math.abs(r[1] - Double.parseDouble(before.get("y"))) < 3.0, String.format("height %.2f, was %s", r[1], before.get("y")));
            watch(s, a, "reopen2");
        });
        s.until("reopen2: the flight goes on to its end", () -> {}, () -> a.aircraft == null || (a.aircraft.phase == Phase.PARKED && a.clock > 100) || a.clock > 6000, 6200);
        s.step("reopen2: result", () -> {
            if (a.aircraft == null) return;
            double[] r = Flights.readings(a.aircraft);
            s.soft("reopen2 / the flight was taken up again and ended on pad B, fixed", padB.equals(a.aircraft.at) && a.aircraft.phase == Phase.PARKED && r != null && r[4] > 0,
                    "doing " + a.aircraft.phase + " at " + a.aircraft.at + " " + at(r) + " | " + a.aircraft.note);
            s.soft("reopen2 / the pilot is alive", a.pilot != null && a.pilot.isAlive(), a.pilot == null ? "not found" : "at " + a.pilot.position() + " state " + Boarding.state(a.pilot));
            if (r != null) Files.writeString(Path.of(STATE), String.format("x=%.3f%ny=%.3f%nz=%.3f%nyaw=%.2f%nnodes=%s%nlines=%s%npadB=%d%npilot=%s%ncolony=%s%n", r[0], r[1], r[2], r[3], before.get("nodes"), before.get("lines"), padB.asLong(), before.get("pilot"), before.get("colony")));
            s.clearMonitors();
        });
    }

    private static void reopen3(Script s, ServerLevel level) throws Exception {
        java.util.Map<String, String> before = state();
        BlockPos padB = BlockPos.of(Long.parseLong(before.get("padB")));
        AircraftBlockEntity[] aircraft = new AircraftBlockEntity[1];
        int[] waited = new int[1];
        s.step("reopen3: keep pad B loaded (as a port would)", () -> {
            for (int cx = (padB.getX() - 16) >> 4; cx <= (padB.getX() + 16) >> 4; cx++) for (int cz = (padB.getZ() - 16) >> 4; cz <= (padB.getZ() + 16) >> 4; cz++) level.setChunkForced(cx, cz, true);
        });
        s.until("reopen3: the landed aircraft shows up", () -> {}, () -> {
            waited[0]++;
            List<AircraftBlockEntity> all = Flights.aircraft(level);
            if (!all.isEmpty()) aircraft[0] = all.get(0);
            return (aircraft[0] != null && Flights.readings(aircraft[0]) != null) || waited[0] > 600;
        }, 700);
        s.settle("reopen3: a few seconds", 100);
        s.step("reopen3: result", () -> {
            s.soft("reopen3 / the landed aircraft is loaded again", aircraft[0] != null, "after " + waited[0] + " ticks");
            if (aircraft[0] == null) return;
            double[] r = Flights.readings(aircraft[0]);
            double moved = Math.sqrt(Math.pow(r[0] - Double.parseDouble(before.get("x")), 2) + Math.pow(r[1] - Double.parseDouble(before.get("y")), 2) + Math.pow(r[2] - Double.parseDouble(before.get("z")), 2));
            s.soft("reopen3 / after a restart on the ground the aircraft is where it was, fixed, parked", moved < 0.2 && r[4] > 0 && aircraft[0].phase == Phase.PARKED && padB.equals(aircraft[0].at),
                    String.format("moved %.3f, fixed=%s, doing %s at %s, heading %.1f (was %s)", moved, r[4] > 0, aircraft[0].phase, aircraft[0].at, r[3], before.get("yaw")));
            RouteNetwork net = RouteNetwork.get(level);
            s.soft("reopen3 / the network is still there", net.nodes().size() == Integer.parseInt(before.get("nodes")), net.nodes().size() + " points, " + net.edges().size() + " lines");
        });
    }
}
