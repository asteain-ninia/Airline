package com.asteain.airlinetest;

import com.asteain.airline.Airline;
import com.asteain.airline.route.BuoyBlock;
import com.asteain.airline.route.RouteNetwork;
import com.asteain.airline.route.ShipClass;
import com.asteain.airline.route.RouteNetwork.Edge;
import com.asteain.airline.route.RouteNetwork.State;
import com.asteain.airline.route.RouteRules;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4, first half: buoys, the network, verification of lines, the re-check after block changes, routes, commands.
 * Nothing flies. The world of this run is made fresh every time (a void), so the network starts empty.
 */
final class NetworkSuite {
    private NetworkSuite() {}

    private static final int Y = 100;

    private static Block buoy(int tier) {
        return Airline.BUOYS.get(tier - 1).get();
    }

    private static void force(ServerLevel level, int minX, int maxX, int minZ, int maxZ) {
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
        }
    }

    private static String describe(Edge edge) {
        return edge == null ? "no line" : edge.state() + (edge.blockedAt() == null ? "" : " at " + edge.blockedAt().toShortString()) + (edge.hasUnseenParts() ? " (unseen parts)" : "");
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "network (buoys, lines, verification, routes; nothing flies)";
        ServerLevel level = server.overworld();
        RouteNetwork net = RouteNetwork.get(level);
        int reach1 = RouteRules.reach(1), reach3 = RouteRules.reach(3);

        // ---- A: two buoys, one line -----------------------------------------------------------------------
        BlockPos a = new BlockPos(0, Y, 0), b = new BlockPos(80, Y, 0);
        BlockPos onLine = new BlockPos(40, Y + 8, 0);
        s.step("A: rules and registration", () -> {
            s.note(String.format("RULES reach %d / %d / %d, waypoint %d above, tube radius %.1f, half height %.1f, arrival %.1f, %d positions per tick",
                    reach1, RouteRules.reach(2), reach3, RouteRules.waypointHeight(), RouteRules.tubeRadius(), RouteRules.tubeHalfHeight(), RouteRules.arrivalRadius(), RouteRules.verifyBlocksPerTick()));
            for (int tier = 1; tier <= 3; tier++) {
                Block block = buoy(tier);
                s.soft("A / buoy grade " + tier + " is registered as a block and an item", block instanceof BuoyBlock made && made.tier() == tier
                        && BuiltInRegistries.ITEM.containsKey(BuiltInRegistries.BLOCK.getKey(block)), String.valueOf(BuiltInRegistries.BLOCK.getKey(block)));
            }
            s.soft("A / the network starts empty", net.nodes().isEmpty() && net.edges().isEmpty(), net.nodes().size() + " points, " + net.edges().size() + " lines");
            force(level, -24, 104, -24, 24);
        });
        s.step("A: place two grade 1 buoys 80 apart, in the air, on nothing", () -> {
            level.setBlockAndUpdate(a, buoy(1).defaultBlockState());
            level.setBlockAndUpdate(b, buoy(1).defaultBlockState());
            Edge edge = net.edge(a, b);
            s.soft("A / two buoys are two points", net.nodes().size() == 2 && net.node(a) != null && net.node(b) != null, net.nodes().size() + " points");
            s.soft("A / buoys whose reaches overlap get a line (80 < 48 + 48), not verified at first", edge != null && edge.state() == State.UNVERIFIED, describe(edge));
            s.soft("A / the buoys stay where they are with nothing under them", level.getBlockState(a).is(buoy(1)) && level.getBlockState(a.below()).isAir(), level.getBlockState(a) + " over " + level.getBlockState(a.below()));
        });
        s.until("A: the line is verified", () -> {}, () -> net.edge(a, b).state() != State.UNVERIFIED, 200);
        s.step("A: an empty line is clear", () -> {
            Edge edge = net.edge(a, b);
            s.soft("A / a line with nothing in its tube becomes clear", edge.state() == State.CLEAR, describe(edge));
            s.note(String.format("OBS line A-B: length %.1f, from %s to %s", edge.length(), edge.tube().from, edge.tube().to));
            level.setBlockAndUpdate(onLine, Blocks.STONE.defaultBlockState());
            s.soft("A / one stone on the line blocks it at once, and the network knows which block", edge.state() == State.BLOCKED && onLine.equals(edge.blockedAt()), describe(edge));
            level.setBlockAndUpdate(onLine, Blocks.AIR.defaultBlockState());
            s.soft("A / taking the stone away sends the line back to not verified", edge.state() == State.UNVERIFIED, describe(edge));
        });
        s.until("A: verified again", () -> {}, () -> net.edge(a, b).state() != State.UNVERIFIED, 200);
        s.step("A: clear again; the edges of the tube", () -> {
            Edge edge = net.edge(a, b);
            s.soft("A / after the stone is gone the line is clear again", edge.state() == State.CLEAR, describe(edge));
            // The line runs at y = 108.5, z = 0.5. Tube: 8 to the side (+ half a block), 6.5 up and down (+ half a block).
            BlockPos[] outside = {new BlockPos(40, Y + 8, 9), new BlockPos(40, Y + 8, -9), new BlockPos(40, Y + 16, 0), new BlockPos(40, Y, 0), new BlockPos(-10, Y + 8, 0)};
            for (BlockPos pos : outside) {
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                s.soft("A / a stone just outside the tube does not block (" + pos.toShortString() + ")", edge.state() == State.CLEAR, describe(edge));
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            BlockPos[] inside = {new BlockPos(40, Y + 8, 8), new BlockPos(40, Y + 15, 0), new BlockPos(40, Y + 2, 0), new BlockPos(-8, Y + 8, 0)};
            for (BlockPos pos : inside) {
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                s.soft("A / a stone just inside the tube blocks (" + pos.toShortString() + ")", edge.state() == State.BLOCKED && pos.equals(edge.blockedAt()), describe(edge));
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            // Things without a collision shape are not in the way.
            level.setBlockAndUpdate(onLine, Blocks.TORCH.defaultBlockState());
            s.soft("A / a block without a collision shape (a torch) does not block", edge.state() != State.BLOCKED, describe(edge));
            level.setBlockAndUpdate(onLine, Blocks.AIR.defaultBlockState());
        });
        s.until("A: verified again (2)", () -> {}, () -> net.edge(a, b).state() != State.UNVERIFIED, 200);

        // ---- the scan itself (a change that tells nobody) --------------------------------------------------
        BlockPos quiet = new BlockPos(30, Y + 9, 2);
        s.step("A: a stone placed without telling the neighbours", () -> {
            level.setBlock(quiet, Blocks.STONE.defaultBlockState(), 2);
            s.note("OBS a block set with flag 2 (no neighbour update) is not noticed by the re-check: the line is " + describe(net.edge(a, b)) + " right after it");
            s.soft("A / the command to look again finds it", net.recheckAll() == 1 && net.edge(a, b).state() == State.UNVERIFIED, describe(net.edge(a, b)));
        });
        s.until("A: scan finds the quiet stone", () -> {}, () -> net.edge(a, b).state() != State.UNVERIFIED, 200);
        s.step("A: found by the scan", () -> {
            Edge edge = net.edge(a, b);
            s.soft("A / the scan finds a stone nobody reported, and where", edge.state() == State.BLOCKED && quiet.equals(edge.blockedAt()), describe(edge));
            level.setBlock(quiet, Blocks.AIR.defaultBlockState(), 2);
            net.recheckAll();
        });
        s.until("A: scan sees it gone", () -> {}, () -> net.edge(a, b).state() != State.UNVERIFIED, 200);

        // ---- a buoy inside another line's tube --------------------------------------------------------------
        BlockPos c = new BlockPos(40, Y + 4, 0);
        s.step("A: a third buoy inside the tube of the line", () -> {
            Edge edge = net.edge(a, b);
            s.soft("A / clear before the third buoy", edge.state() == State.CLEAR, describe(edge));
            s.soft("A / the place of the third buoy lies inside the tube", edge.tube().contains(c.getX(), c.getY(), c.getZ()), "tube " + edge.tube().from + " to " + edge.tube().to);
            level.setBlockAndUpdate(c, buoy(1).defaultBlockState());
            s.soft("A / a buoy inside the tube does not block the line", edge.state() == State.CLEAR, describe(edge));
            s.soft("A / the third buoy is a point with lines to both others", net.nodes().size() == 3 && net.edge(a, c) != null && net.edge(c, b) != null, net.nodes().size() + " points, " + net.edges().size() + " lines");
            net.recheckAll();
        });
        s.until("A: all three lines verified", () -> {}, () -> net.pending() == 0, 400);
        s.step("A: still clear after a full scan with the buoy inside", () -> {
            s.soft("A / the scan does not count the buoy either", net.edge(a, b).state() == State.CLEAR && net.edge(a, c).state() == State.CLEAR && net.edge(c, b).state() == State.CLEAR,
                    describe(net.edge(a, b)) + " / " + describe(net.edge(a, c)) + " / " + describe(net.edge(c, b)));
            level.setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
            s.soft("A / breaking a buoy removes the point and its lines", net.nodes().size() == 2 && net.edge(a, c) == null && net.edge(c, b) == null && net.edge(a, b) != null,
                    net.nodes().size() + " points, " + net.edges().size() + " lines");
        });

        // ---- a line into chunks that are not loaded ----------------------------------------------------------
        BlockPos far = new BlockPos(170, Y, 0);
        s.step("A: a buoy 90 further on, in chunks nobody keeps loaded", () -> {
            level.setBlockAndUpdate(far, buoy(1).defaultBlockState());
            s.soft("A / it links to the nearer buoy only (90 < 96, 170 > 96)", net.edge(b, far) != null && net.edge(a, far) == null, describe(net.edge(b, far)));
        });
        s.settle("A: give the scan time", 200);
        s.step("A: what is not loaded stays not verified", () -> {
            Edge edge = net.edge(b, far);
            boolean middleLoaded = level.getChunkSource().getChunkNow(150 >> 4, 0) != null;      // forced chunks keep their neighbours two chunks out loaded, so look beyond those
            s.soft("A / a line through chunks that are not loaded stays not verified", edge.state() == State.UNVERIFIED && edge.hasUnseenParts(), describe(edge));
            s.soft("A / the scan did not load the chunks in between", !middleLoaded, "chunk at x=150 loaded=" + middleLoaded);
            s.soft("A / a route may use a line that is not verified", net.route(a, far, ShipClass.SMALL).equals(List.of(a, b, far)), String.valueOf(net.route(a, far, ShipClass.SMALL)));
            net.confirmClear(edge);
            s.soft("A / flying it through (reported by an aircraft) makes it clear", edge.state() == State.CLEAR && !edge.hasUnseenParts(), describe(edge));
        });

        // ---- save and load -----------------------------------------------------------------------------------
        s.step("A: save and load", () -> {
            level.setBlockAndUpdate(onLine, Blocks.STONE.defaultBlockState());
            CompoundTag saved = net.save(new CompoundTag(), level.registryAccess());
            RouteNetwork copy = RouteNetwork.load(saved, level.registryAccess());
            Edge first = copy.edge(a, b), second = copy.edge(b, far);
            s.soft("A / points survive saving and loading", copy.nodes().size() == 3 && copy.nodes().containsAll(net.nodes()), copy.nodes().size() + " points");
            s.soft("A / lines and their states survive (blocked with its block, clear)", copy.edges().size() == 2 && first != null && first.state() == State.BLOCKED && onLine.equals(first.blockedAt())
                    && second != null && second.state() == State.CLEAR, describe(first) + " / " + describe(second));
            s.note("OBS saved form: " + saved);
            level.setBlockAndUpdate(onLine, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(far, Blocks.AIR.defaultBlockState());
        });
        s.until("A: verified once more", () -> {}, () -> net.pending() == 0, 200);

        // ---- B: routes ----------------------------------------------------------------------------------------
        int z0 = 400;
        BlockPos start = new BlockPos(0, Y, z0), mid = new BlockPos(60, Y, z0), end = new BlockPos(120, Y, z0);
        BlockPos side1 = new BlockPos(30, Y, z0 + 62), side2 = new BlockPos(90, Y, z0 + 62);
        BlockPos onShort = new BlockPos(30, Y + 8, z0), onSecond = new BlockPos(90, Y + 8, z0);
        s.step("B: five buoys, a short way and a long way", () -> {
            force(level, -24, 144, z0 - 24, z0 + 86);
            for (BlockPos pos : new BlockPos[] {start, mid, end, side1, side2}) level.setBlockAndUpdate(pos, buoy(1).defaultBlockState());
            s.soft("B / lines: the five that should exist do, the two that are too long do not",
                    net.edge(start, mid) != null && net.edge(mid, end) != null && net.edge(start, side1) != null && net.edge(side1, side2) != null && net.edge(side2, end) != null
                            && net.edge(start, end) == null && net.edge(start, side2) == null && net.edge(a, start) == null,
                    net.edges().size() + " lines in the network");
        });
        s.until("B: all verified", () -> {}, () -> net.pending() == 0, 600);
        s.step("B: the short way, then the long way", () -> {
            List<BlockPos> way = net.route(start, end, ShipClass.SMALL);
            s.soft("B / all lines of the second scene are clear", net.edgesAt(mid).stream().allMatch(e -> e.state() == State.CLEAR) && net.edge(side1, side2).state() == State.CLEAR,
                    describe(net.edge(start, mid)) + " / " + describe(net.edge(side1, side2)));
            s.soft("B / the route is the short way over the middle buoy", way.equals(List.of(start, mid, end)), way + " length " + net.length(way));
            level.setBlockAndUpdate(onShort, Blocks.STONE.defaultBlockState());
            List<BlockPos> second = net.route(start, end, ShipClass.SMALL);
            s.soft("B / with the first line blocked the route goes round, without the blocked line",
                    second.size() >= 4 && second.get(0).equals(start) && second.get(second.size() - 1).equals(end) && second.get(1).equals(side1) && net.length(second) > net.length(way),
                    second + " length " + net.length(second));
            level.setBlockAndUpdate(onSecond, Blocks.STONE.defaultBlockState());
            List<BlockPos> third = net.route(start, end, ShipClass.SMALL);
            s.soft("B / with both short lines blocked the route is the long way round", third.equals(List.of(start, side1, side2, end)), third + " length " + net.length(third));
            s.note(String.format("OBS routes: short %.1f, round the first block %.1f, long %.1f", net.length(way), net.length(second), net.length(third)));
            // every way shut
            level.setBlockAndUpdate(new BlockPos(15, Y + 8, z0 + 31), Blocks.STONE.defaultBlockState());
            s.soft("B / with every line out of the start blocked there is no route", net.route(start, end, ShipClass.SMALL).isEmpty(), String.valueOf(net.route(start, end, ShipClass.SMALL)));
        });

        // ---- commands -----------------------------------------------------------------------------------------
        s.step("B: commands", () -> {
            List<String> out = new ArrayList<>();
            CommandSource sink = new CommandSource() {
                @Override public void sendSystemMessage(Component message) { out.add(message.getString()); }
                @Override public boolean acceptsSuccess() { return true; }
                @Override public boolean acceptsFailure() { return true; }
                @Override public boolean shouldInformAdmins() { return false; }
            };
            CommandSourceStack stack = new CommandSourceStack(sink, Vec3.atCenterOf(start), Vec2.ZERO, level, 4, "test", Component.literal("test"), server, null);
            String root = Airline.MOD_ID;
            server.getCommands().performPrefixedCommand(stack, root + " net list");
            s.soft("B / net list prints a summary, every point and every line", out.size() == 1 + net.nodes().size() + net.edges().size() && out.get(0).contains(String.valueOf(net.nodes().size())),
                    out.size() + " lines of text; first: " + (out.isEmpty() ? "" : out.get(0)));
            for (String line : out) s.note("CMD " + line);
            out.clear();
            server.getCommands().performPrefixedCommand(stack, root + " net blocked");
            long blocked = net.edges().stream().filter(e -> e.state() == State.BLOCKED).count();
            s.soft("B / net blocked prints the blocked lines with their blocks", out.size() == 1 + blocked && blocked == 3 && out.stream().anyMatch(t -> t.contains(onShort.getX() + " " + onShort.getY() + " " + onShort.getZ())),
                    out.size() + " lines of text for " + blocked + " blocked lines");
            for (String line : out) s.note("CMD " + line);
            out.clear();
            server.getCommands().performPrefixedCommand(stack, String.format("%s net route %d %d %d %d %d %d", root, mid.getX(), mid.getY(), mid.getZ(), end.getX(), end.getY(), end.getZ()));
            s.soft("B / net route prints a way", out.size() == 1 && out.get(0).contains(side2.getX() + " " + side2.getY() + " " + side2.getZ()), String.valueOf(out));
            for (String line : out) s.note("CMD " + line);
            out.clear();
            server.getCommands().performPrefixedCommand(stack, String.format("%s net route %d %d %d %d %d %d", root, start.getX(), start.getY(), start.getZ(), end.getX(), end.getY(), end.getZ()));
            s.soft("B / net route says so when there is no way", out.size() == 1, String.valueOf(out));
            for (String line : out) s.note("CMD " + line);
            out.clear();
            for (BlockPos pos : new BlockPos[] {onShort, onSecond, new BlockPos(15, Y + 8, z0 + 31)}) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            // This server has no Sable: the flying part is off, its commands say so, nothing else is disturbed.
            s.soft("B / without Sable the flying part is off", !com.asteain.airline.flight.Flights.available() && com.asteain.airline.flight.Flights.aircraft(level).isEmpty(),
                    "available=" + com.asteain.airline.flight.Flights.available());
            s.soft("B / the aircraft block and its block entity type are registered all the same", Airline.AIRCRAFT.get() != null && Airline.AIRCRAFT_BLOCK_ENTITY.get() != null,
                    String.valueOf(BuiltInRegistries.BLOCK.getKey(Airline.AIRCRAFT.get())));
            level.setBlockAndUpdate(start.above(20), Airline.AIRCRAFT.get().defaultBlockState());
            s.soft("B / an aircraft block set down in the world is just a block (with its data), and nothing throws",
                    level.getBlockEntity(start.above(20)) instanceof com.asteain.airline.flight.AircraftBlockEntity, String.valueOf(level.getBlockEntity(start.above(20))));
            level.setBlockAndUpdate(start.above(20), Blocks.AIR.defaultBlockState());
            server.getCommands().performPrefixedCommand(stack, root + " aircraft list");
            s.soft("B / without Sable the aircraft commands answer that the flying part is off", out.size() == 1 && out.get(0).contains("Sable"), String.valueOf(out));
            out.clear();
            server.getCommands().performPrefixedCommand(stack, root + " net recheck");
            s.soft("B / net recheck queues every line", net.pending() == net.edges().size() && out.size() == 1, net.pending() + " waiting; " + out);
        });
        s.until("B: everything verified again", () -> {}, () -> net.pending() == 0, 800);
        s.step("B: back to the short way", () -> {
            List<BlockPos> way = net.route(start, end, ShipClass.SMALL);
            s.soft("B / with the stones gone and the lines looked at again, the short way is back", way.equals(List.of(start, mid, end)), String.valueOf(way));
        });

        // ---- C: grades ------------------------------------------------------------------------------------------
        int z1 = 1200;
        s.step("C: reaches of the grades", () -> {
            BlockPos big = new BlockPos(0, Y, z1), near = new BlockPos(reach3 + reach1 - 5, Y, z1), beyond = new BlockPos(reach3 + reach1 + 5, Y, z1 + 40);
            BlockPos small1 = new BlockPos(0, Y, z1 + 600), small2 = new BlockPos(2 * reach1 + 2, Y, z1 + 600);
            level.setBlockAndUpdate(big, buoy(3).defaultBlockState());
            level.setBlockAndUpdate(near, buoy(1).defaultBlockState());
            level.setBlockAndUpdate(beyond, buoy(1).defaultBlockState());
            level.setBlockAndUpdate(small1, buoy(1).defaultBlockState());
            level.setBlockAndUpdate(small2, buoy(1).defaultBlockState());
            s.soft("C / a grade 3 buoy links to a grade 1 buoy up to the sum of their reaches (" + (reach3 + reach1) + ")", net.edge(big, near) != null && net.edge(big, beyond) == null,
                    "at " + (reach3 + reach1 - 5) + ": " + describe(net.edge(big, near)) + ", beyond: " + describe(net.edge(big, beyond)));
            s.soft("C / two grade 1 buoys just over twice the reach apart do not link", net.edge(small1, small2) == null, describe(net.edge(small1, small2)));
            level.setBlockAndUpdate(small2, buoy(3).defaultBlockState());
            s.soft("C / replacing one by a grade 3 buoy links them", net.edge(small1, small2) != null && net.node(small2).tier() == 3, describe(net.edge(small1, small2)));
            s.note("OBS points " + net.nodes().size() + ", lines " + net.edges().size() + " at the end");
        });
        s.settle("C: a few ticks with long lines waiting (nothing may be loaded for them)", 100);
        s.step("C: long lines over chunks that are not loaded", () -> {
            long unverified = net.edges().stream().filter(e -> e.state() == State.UNVERIFIED).count();
            s.soft("C / the long lines stay not verified, the server keeps running", unverified >= 2, unverified + " lines not verified, " + net.pending() + " waiting");
        });

        // ---- D: landing pads (rulings of 2026-10-11): higher lines, the shaft over the pad, what a buoy says, links ----
        int z2 = -1200, lift = RouteRules.airfieldWaypointHeight();
        BlockPos padA = new BlockPos(0, Y, z2), padB = new BlockPos(140, Y, z2), between = new BlockPos(70, Y + 8, z2);
        BlockPos lonely = new BlockPos(0, Y, z2 - 600);      // a buoy far from everything
        BlockPos inShaft = padA.offset(5, 6, -5), onFloor = padA.offset(7, 1, 0), underLine = padA.offset(20, lift - 7, 0), roof = padA.offset(9, lift - 8, 0);
        s.step("D: two landing pads and a buoy between them", () -> {
            for (int cx = -1; cx <= 9; cx++) for (int cz = (z2 >> 4) - 1; cz <= (z2 >> 4) + 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            net.setAirfield(padA, 270, 1);
            net.setAirfield(padB, 270, 1);
            level.setBlockAndUpdate(between, buoy(2).defaultBlockState());
            level.setBlockAndUpdate(lonely, buoy(1).defaultBlockState());
            s.soft("D / over a landing pad the line passes " + lift + " blocks up, over a buoy " + RouteRules.waypointHeight(),
                    net.waypoint(padA).y == padA.getY() + 0.5 + lift && net.waypoint(between).y == between.getY() + 0.5 + RouteRules.waypointHeight(),
                    "pad " + net.waypoint(padA) + ", buoy " + net.waypoint(between));
            s.soft("D / the pads join over the buoy (they are too far apart for a line of their own)", net.edge(padA, padB) == null && net.edge(padA, between) != null && net.edge(padB, between) != null,
                    describe(net.edge(padA, between)) + " / " + describe(net.edge(padB, between)));
        });
        s.until("D: verified", () -> {}, () -> net.pending() == 0, 600);
        s.settle("D: the colonies are told", 5);
        s.step("D: clear, linked, described", () -> {
            s.soft("D / both lines are clear, and the two pads count as linked", net.edge(padA, between).state() == State.CLEAR && net.edge(padB, between).state() == State.CLEAR && net.linked(padA, padB),
                    describe(net.edge(padA, between)) + " / " + describe(net.edge(padB, between)) + " linked=" + net.linked(padA, padB));
            List<String> said = net.describe(level, between, false).stream().map(line -> line.getString()).toList();
            s.soft("D / the lamp of a buoy with a line that can be flown burns; the lamp of a buoy that joined nothing does not",
                    level.getBlockState(between).getValue(com.asteain.airline.route.BuoyBlock.LIT) && net.edgesAt(lonely).isEmpty() && !level.getBlockState(lonely).getValue(com.asteain.airline.route.BuoyBlock.LIT),
                    "between " + level.getBlockState(between) + ", lonely " + level.getBlockState(lonely) + " with " + net.edgesAt(lonely).size() + " lines");
            s.soft("D / a buoy says how its lines stand and which landing pads it leads to", said.size() == 2 && said.get(1).contains(padA.toShortString()) && said.get(1).contains(padB.toShortString()), String.valueOf(said));
            // What an airfield may build: a fence on the floor inside the shaft, a roof beside the shaft up to 8 below the lines.
            level.setBlockAndUpdate(onFloor, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(roof, Blocks.STONE.defaultBlockState());
            s.soft("D / a block on the pad's floor level beside the pad, and one 9 blocks out, 8 below the lines (as high as an airfield may build), block nothing", net.edge(padA, between).state() == State.CLEAR,
                    describe(net.edge(padA, between)));
            level.setBlockAndUpdate(inShaft, Blocks.STONE.defaultBlockState());
            s.soft("D / a block in the shaft over the pad (6 up, well below the lines) blocks the pad's line at once", net.edge(padA, between).state() == State.BLOCKED && inShaft.equals(net.edge(padA, between).blockedAt())
                    && net.route(padA, padB, ShipClass.SMALL).isEmpty(), describe(net.edge(padA, between)) + ", way " + net.route(padA, padB, ShipClass.SMALL));
        });
        s.until("D: the link is taken back", () -> {}, () -> net.pending() == 0 && !net.linked(padA, padB), 200);
        s.step("D: blocked shaft, described; cleared again", () -> {
            List<String> said = net.describe(level, between, false).stream().map(line -> line.getString()).toList();
            s.soft("D / with the shaft blocked the pads are not linked any more, and the buoy names the block", !net.linked(padA, padB) && said.size() == 3 && said.get(1).contains(inShaft.getX() + " " + inShaft.getY() + " " + inShaft.getZ()),
                    "linked=" + net.linked(padA, padB) + " " + said);
            level.setBlockAndUpdate(inShaft, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(underLine, Blocks.STONE.defaultBlockState());
        });
        s.until("D: looked at again", () -> {}, () -> net.pending() == 0, 400);
        s.step("D: the scan checks the shaft and the tube", () -> {
            s.soft("D / with the shaft free again a block 20 out, 7 below the line (inside its tube), blocks it", net.edge(padA, between).state() == State.BLOCKED && underLine.equals(net.edge(padA, between).blockedAt()),
                    describe(net.edge(padA, between)));
            level.setBlockAndUpdate(underLine, Blocks.AIR.defaultBlockState());
            // The scan itself (not the block change) has to find a block in the shaft too.
            level.setBlock(inShaft, Blocks.STONE.defaultBlockState(), 2);
            net.recheck(padA);
        });
        s.until("D: scanned", () -> {}, () -> net.pending() == 0, 400);
        s.step("D: found by the scan", () -> {
            s.soft("D / a block in the shaft that nobody told the network about is found by the scan", net.edge(padA, between).state() == State.BLOCKED && inShaft.equals(net.edge(padA, between).blockedAt()),
                    describe(net.edge(padA, between)));
            level.setBlockAndUpdate(inShaft, Blocks.AIR.defaultBlockState());
        });
        s.until("D: clear and linked again", () -> {}, () -> net.pending() == 0 && net.linked(padA, padB), 400);
        s.step("D: the end", () -> s.soft("D / in the end the way is clear and the pads are linked again", net.route(padA, padB, ShipClass.SMALL).size() == 3 && net.linked(padA, padB), String.valueOf(net.route(padA, padB, ShipClass.SMALL))));

        // ---- E: a way never leads through a landing pad (the space over a pad belongs to the aircraft that go up and down there) ----
        BlockPos padC = new BlockPos(220, Y, z2);
        s.step("E: a third pad that only the second one reaches", () -> {
            net.setAirfield(padC, 270, 1);
            s.soft("E / the third pad has one line, to the second pad", net.edgesAt(padC).size() == 1 && net.edge(padB, padC) != null, net.edgesAt(padC).size() + " lines");
            s.soft("E / from the second pad there is a way to the third; from the first there is none, because it would lead through the second",
                    net.route(padB, padC, ShipClass.SMALL).equals(List.of(padB, padC)) && net.route(padA, padC, ShipClass.SMALL).isEmpty(), net.route(padB, padC, ShipClass.SMALL) + " / " + net.route(padA, padC, ShipClass.SMALL));
            net.remove(padC);
            s.soft("E / with the third pad gone the first two are still joined", net.route(padA, padB, ShipClass.SMALL).size() == 3, String.valueOf(net.route(padA, padB, ShipClass.SMALL)));
        });

        // ---- F: no line may come low over a landing pad (an aircraft may stand there) ----
        // Two buoys at the height of the pad's floor, west and east of it: their line would pass 9.5 above the floor.
        BlockPos lowWest = padA.offset(-14, 1, 0), lowEast = padA.offset(30, 1, 0), highWest = padA.offset(-14, 8, 3), highEast = padA.offset(30, 8, 3);
        s.step("F: a line low over a pad", () -> {
            level.setBlockAndUpdate(lowWest, buoy(1).defaultBlockState());
            level.setBlockAndUpdate(lowEast, buoy(1).defaultBlockState());
            Edge low = net.edge(lowWest, lowEast);
            s.soft("F / a line that passes low over a landing pad is not used: it counts as blocked by that pad from the moment it is made", low != null && low.state() == State.BLOCKED && padA.equals(low.blockedAt()) && padA.equals(low.barredBy()),
                    describe(low) + ", needs " + RouteRules.padClearance() + " above the floor within " + String.format("%.1f", RouteRules.padReach()) + " blocks");
            s.soft("F / the lines that end on the pad itself are not barred by it", net.edge(lowWest, padA) != null && net.edge(lowWest, padA).barredBy() == null && net.edge(lowEast, padA).barredBy() == null,
                    describe(net.edge(lowWest, padA)) + " / " + describe(net.edge(lowEast, padA)));
            List<String> said = net.describe(level, lowWest, false).stream().map(line -> line.getString()).toList();
            s.soft("F / the buoy says which line is not used, and names the pad", said.stream().anyMatch(line -> line.contains(lowEast.getX() + " " + lowEast.getY() + " " + lowEast.getZ()) && line.contains(padA.getX() + " " + padA.getY() + " " + padA.getZ())), String.valueOf(said));
            // Nothing a scan or a flight learns changes that.
            net.recheck(lowWest);
            net.confirmClear(low);
            s.soft("F / looking at the line again does not open it", low.state() == State.BLOCKED && padA.equals(low.blockedAt()), describe(low));
        });
        s.until("F: looked at", () -> {}, () -> net.pending() == 0, 600);
        s.step("F: still barred; buoys as high as the lines over the pad", () -> {
            Edge low = net.edge(lowWest, lowEast);
            s.soft("F / after every line was looked at the low line is still barred by the pad", low != null && low.state() == State.BLOCKED && padA.equals(low.blockedAt()), describe(low));
            level.setBlockAndUpdate(lowWest, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(lowEast, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(highWest, buoy(1).defaultBlockState());
            level.setBlockAndUpdate(highEast, buoy(1).defaultBlockState());
        });
        s.until("F: the high line is looked at", () -> {}, () -> net.pending() == 0, 600);
        s.step("F: a line high over a pad", () -> {
            Edge high = net.edge(highWest, highEast);
            s.soft("F / a line that passes as high over the pad as the pad's own lines begin is clear", high != null && high.barredBy() == null && high.state() == State.CLEAR && net.route(highWest, highEast, ShipClass.SMALL).equals(List.of(highWest, highEast)),
                    describe(high) + ", way " + net.route(highWest, highEast, ShipClass.SMALL));
            // When the pad goes, a line it barred is looked at like any other.
            level.setBlockAndUpdate(highWest, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(highEast, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(lowWest, buoy(1).defaultBlockState());
            level.setBlockAndUpdate(lowEast, buoy(1).defaultBlockState());
            boolean barred = net.edge(lowWest, lowEast).barredBy() != null;
            net.remove(padA);
            Edge low = net.edge(lowWest, lowEast);
            s.soft("F / when the pad leaves the network, the line it barred is no longer barred and is looked at again", barred && low != null && low.barredBy() == null && low.state() == State.UNVERIFIED, describe(low));
        });

        // ---- G: an airfield reaches further the higher its level, on the scale of colonies (direct flights between neighbours) ----
        // (This section runs with the reaches as the mod comes; the sections before it ran with the reach they were laid out for.
        // The rule of the large maps stays switched off here: this is about what an airfield reaches without it. Section H is about that rule.)
        int z3 = -2400;
        BlockPos g1 = new BlockPos(0, Y, z3), g2 = new BlockPos(270, Y, z3), g3 = new BlockPos(270, Y, z3 - 300), gFar = new BlockPos(-500, Y + 8, z3);
        Edge[] direct = new Edge[1];
        s.step("G: two landing pads 270 blocks apart", () -> {
            Fixture.airfieldReachChunks(0, 0, 0, 0, 0);
            for (int cx = -1; cx <= 18; cx++) for (int cz = ((z3 - 300) >> 4) - 1; cz <= (z3 >> 4) + 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            int[] reach = new int[5];
            for (int lv = 1; lv <= 5; lv++) reach[lv - 1] = RouteRules.airfieldReach(lv);
            int near = com.minecolonies.api.IMinecoloniesAPI.getInstance().getColonyManager().getMinimumDistanceBetweenTownHalls();
            s.soft("G / an airfield reaches 8, 10, 12, 16, 20 chunks at levels 1 to 5: level 1 as far as two town halls have to be apart (MineColonies' setting), level 5 as far as a colony may stretch",
                    java.util.Arrays.equals(reach, new int[] {128, 160, 192, 256, 320}) && reach[0] == near && RouteRules.reachFromColonySizes(5) == 20, java.util.Arrays.toString(reach) + " blocks; town halls at least " + near + " apart");
            net.setAirfield(g1, 270, 1);
            net.setAirfield(g2, 270, 1);
            s.soft("G / two level 1 airfields 270 blocks apart have no line (8 + 8 chunks is 256 blocks)", net.edge(g1, g2) == null && net.node(g1).reach() == 128, net.edgesAt(g1).size() + " lines at the first pad");
            net.setAirfield(g1, 270, 2);
            s.soft("G / with one of them at level 2 they have a direct line (10 + 8 chunks is 288 blocks)", net.edge(g1, g2) != null && net.node(g1).reach() == 160, describe(net.edge(g1, g2)));
            direct[0] = net.edge(g1, g2);
        });
        s.until("G: verified", () -> {}, () -> net.pending() == 0 && net.linked(g1, g2), 900);
        s.step("G: a direct flight; levels go up", () -> {
            s.soft("G / the direct line is clear, it is the way, and the two airfields count as linked with no buoy anywhere", direct[0].state() == State.CLEAR && net.route(g1, g2, ShipClass.SMALL).equals(List.of(g1, g2)) && net.linked(g1, g2),
                    describe(direct[0]) + ", way " + net.route(g1, g2, ShipClass.SMALL));
            net.setAirfield(g1, 270, 3);
            s.soft("G / when an airfield's level goes up, a line that still connects stays the same line, with what is known about it", net.edge(g1, g2) == direct[0] && direct[0].state() == State.CLEAR && net.node(g1).tier() == 3,
                    describe(net.edge(g1, g2)));
            net.setAirfield(g3, 270, 1);
            s.soft("G / a level 1 pad 300 blocks from a level 1 pad (and 404 from a level 3 pad) has no line", net.edgesAt(g3).isEmpty(), net.edgesAt(g3).size() + " lines");
            net.setAirfield(g2, 270, 4);
            s.soft("G / at level 4 the second pad reaches it (16 + 8 chunks is 384 blocks), and its line to the first pad is still the same clear line", net.edge(g2, g3) != null && net.edge(g1, g2) == direct[0] && direct[0].state() == State.CLEAR,
                    describe(net.edge(g2, g3)) + " / " + describe(net.edge(g1, g2)));
        });
        s.until("G: the new line is verified", () -> {}, () -> net.pending() == 0 && net.linked(g2, g3), 900);
        s.step("G: no way through an airfield; a level goes down; the longest line", () -> {
            s.soft("G / a way still never leads through an airfield: the second pad has lines to both others, but from the first to the third there is no way",
                    net.route(g2, g3, ShipClass.SMALL).equals(List.of(g2, g3)) && net.route(g1, g2, ShipClass.SMALL).size() == 2 && net.route(g1, g3, ShipClass.SMALL).isEmpty() && !net.linked(g1, g3), net.route(g2, g3, ShipClass.SMALL) + " / " + net.route(g1, g3, ShipClass.SMALL));
            net.setAirfield(g2, 270, 1);
            s.soft("G / when the level goes down, the line that does not reach any more is gone and the other one stays", net.edge(g2, g3) == null && net.edge(g1, g2) == direct[0] && direct[0].state() == State.CLEAR,
                    net.edgesAt(g2).size() + " lines at the second pad, " + net.edgesAt(g3).size() + " at the third");
            net.setAirfield(g1, 270, 5);
            level.setBlockAndUpdate(gFar, buoy(3).defaultBlockState());
            s.soft("G / a level 5 airfield and a grade 3 buoy 500 blocks apart have a line (20 + 12 chunks is 512 blocks)", net.edge(g1, gFar) != null, describe(net.edge(g1, gFar)));
            level.setBlockAndUpdate(gFar, Blocks.AIR.defaultBlockState());
        });
        s.until("G: the link that is gone is taken back", () -> {}, () -> !net.linked(g2, g3), 200);
        s.step("G: the end", () -> s.soft("G / the airfields whose line is gone do not count as linked any more; the others still do", !net.linked(g2, g3) && net.linked(g1, g2), "linked " + net.linked(g1, g2) + " / " + net.linked(g2, g3)));

        // ---- H: airfields reach each other by large maps (ruling of 2026-10-13) ---------------------------------
        // Large maps of 8 chunks (128 blocks) here, and a reach of 1 chunk for what lies near, so that the two rules can be told apart.
        int z4 = -3650;
        BlockPos h1 = new BlockPos(10, Y, z4), h2 = new BlockPos(110, Y, z4), h3 = new BlockPos(140, Y, z4), h4 = new BlockPos(300, Y, z4), h5 = new BlockPos(522, Y, z4), h6 = new BlockPos(650, Y, z4);
        BlockPos hBuoy = new BlockPos(10, Y + 8, z4 + 80);
        Edge[] sameMap = new Edge[1];
        s.step("H: landing pads on four large maps in a row", () -> {
            boolean asItComes = RouteRules.largeMap() == 0 && RouteRules.largeMapSetting().getDefault() == 128;
            Fixture.airfieldReachChunks(1, 1, 1, 1, 1);
            Fixture.largeMapChunks(8);
            s.soft("H / a large map is 128 chunks a side as the mod comes (sixteen region files, four by four; 2048 blocks); the scenes before this one ran with the rule switched off", asItComes && RouteRules.largeMap() == 128,
                    "default " + RouteRules.largeMapSetting().getDefault() + " chunks, now " + RouteRules.largeMap() + " blocks");
            s.soft("H / an airfield reaches 0, 1, 2, 3, 4 large maps beyond its own at levels 1 to 5 (1 x 1, 3 x 3, ... 9 x 9 maps)",
                    RouteRules.mapsAround(1) == 0 && RouteRules.mapsAround(2) == 1 && RouteRules.mapsAround(3) == 2 && RouteRules.mapsAround(4) == 3 && RouteRules.mapsAround(5) == 4 && RouteRules.mapsAround(9) == 4, "level 5: " + RouteRules.mapsAround(5));
            s.soft("H / maps are counted from the world's origin, also on the negative side, and maps that touch at a corner are one apart",
                    RouteRules.mapsApart(new BlockPos(0, 0, 0), new BlockPos(127, 0, 127)) == 0 && RouteRules.mapsApart(new BlockPos(127, 0, 0), new BlockPos(128, 0, 0)) == 1
                            && RouteRules.mapsApart(new BlockPos(-1, 0, 0), new BlockPos(0, 0, 0)) == 1 && RouteRules.mapsApart(new BlockPos(-128, 0, -1), new BlockPos(-1, 0, -128)) == 0
                            && RouteRules.mapsApart(new BlockPos(0, 0, 0), new BlockPos(128, 0, 128)) == 1 && RouteRules.mapsApart(new BlockPos(0, 0, 0), new BlockPos(600, 0, -300)) == 4,
                    "(0,0)-(600,-300): " + RouteRules.mapsApart(new BlockPos(0, 0, 0), new BlockPos(600, 0, -300)));
            for (int cx = -1; cx <= 9; cx++) for (int cz = (z4 >> 4) - 1; cz <= ((z4 + 80) >> 4) + 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            net.setAirfield(h1, 270, 1);
            net.setAirfield(h2, 270, 1);
            net.setAirfield(h3, 270, 1);
            net.setAirfield(h4, 270, 1);
            sameMap[0] = net.edge(h1, h2);
            s.soft("H / two level 1 airfields on the same large map have a direct line, however far their own reach goes (100 blocks apart, reach 16 each)", sameMap[0] != null && net.node(h1).reach() == 16, describe(sameMap[0]));
            s.soft("H / level 1 does not reach the next large map (130 blocks apart)", net.edge(h1, h3) == null, describe(net.edge(h1, h3)));
            s.soft("H / two level 1 airfields that lie close to each other across the edge of a large map do not connect: between airfields the maps alone decide (30 blocks apart, reaches 16 + 16)",
                    net.edge(h2, h3) == null, describe(net.edge(h2, h3)));
            // Where an airfield stands is where its hut block stands: a pad on one map whose hut is on the next map belongs to the next map.
            final BlockPos h7 = new BlockPos(124, Y, z4 + 30), hut7 = new BlockPos(130, Y, z4 + 30);
            net.setAirfield(h7, 270, 1, hut7);
            s.soft("H / the large map of an airfield is the one its hut block stands on: a pad at x 124 with its hut at x 130 connects to the airfield on the map of the hut, not to those on the map of the pad",
                    net.edge(h7, h3) != null && net.edge(h7, h2) == null && net.edge(h7, h1) == null && hut7.equals(net.node(h7).home()) && h3.equals(net.node(h3).home()),
                    "to h3 " + describe(net.edge(h7, h3)) + " / to h2 " + describe(net.edge(h7, h2)));
            net.remove(h7);
            s.soft("H / a level 1 airfield alone on its large map has no line", net.edgesAt(h4).isEmpty(), net.edgesAt(h4).size() + " lines");
            level.setBlockAndUpdate(hBuoy, buoy(1).defaultBlockState());
            s.soft("H / a buoy has nothing to do with large maps: on the same map as an airfield, 80 blocks from it, it has no line (its reach and the airfield's are 48 + 16 blocks)", net.node(hBuoy) != null && net.edgesAt(hBuoy).isEmpty(), net.edgesAt(hBuoy).size() + " lines");
            level.setBlockAndUpdate(hBuoy, Blocks.AIR.defaultBlockState());
        });
        s.until("H: verified", () -> {}, () -> net.pending() == 0 && net.linked(h1, h2), 900);
        s.step("H: levels go up and down", () -> {
            s.soft("H / the line on the same large map is clear, it is the way, and the two airfields count as linked", sameMap[0].state() == State.CLEAR && net.route(h1, h2, ShipClass.SMALL).equals(List.of(h1, h2)) && net.linked(h1, h2), describe(sameMap[0]));
            net.setAirfield(h1, 270, 2);
            s.soft("H / at level 2 an airfield reaches the airfields on the large maps around its own, whatever their level, but not the map after that",
                    net.edge(h1, h3) != null && net.edge(h1, h4) == null && net.edge(h1, h2) == sameMap[0], describe(net.edge(h1, h3)) + " / " + describe(net.edge(h1, h4)));
            net.setAirfield(h1, 270, 3);
            s.soft("H / at level 3 it reaches two large maps away: the higher of the two levels counts (the other airfield is level 1)", net.edge(h1, h4) != null && net.node(h4).tier() == 1, describe(net.edge(h1, h4)));
            net.setAirfield(h5, 270, 1);
            net.setAirfield(h6, 270, 1);
            s.soft("H / level 3 does not reach four large maps away", net.edge(h1, h5) == null, describe(net.edge(h1, h5)));
            net.setAirfield(h1, 270, 5);
            s.soft("H / level 5 reaches four large maps away (9 x 9 maps) and no further", net.edge(h1, h5) != null && net.edge(h1, h6) == null, describe(net.edge(h1, h5)) + " / " + describe(net.edge(h1, h6)));
            net.setAirfield(h1, 270, 1);
            s.soft("H / when the level goes down, the lines to other large maps go and the line on its own map stays the same line, with what is known about it",
                    net.edgesAt(h1).size() == 1 && net.edge(h1, h2) == sameMap[0] && sameMap[0].state() == State.CLEAR, net.edgesAt(h1).size() + " lines, " + describe(net.edge(h1, h2)));
            net.remove(h5);
            net.remove(h6);
        });

        // A line across large maps of the real size, through country that is not loaded: looking at it must not hold anything up.
        // (Far from everything that came before: an airfield of level 5 reaches four large maps, and the airfields of the scenes above are still on the network.)
        BlockPos far1 = new BlockPos(1900, Y, -28800), far2 = new BlockPos(5700, Y, -25900), near1 = new BlockPos(1900, Y, -28800 + 40);
        int[] began = new int[1];
        s.step("H: a line of 4780 blocks through country that is not loaded", () -> {
            Fixture.largeMapChunks(128);
            for (int cx = (1900 >> 4) - 1; cx <= (1900 >> 4) + 1; cx++) for (int cz = (-28800 >> 4) - 1; cz <= ((-28800 + 40) >> 4) + 1; cz++) { level.setChunkForced(cx, cz, true); level.getChunk(cx, cz); }
            net.setAirfield(far1, 270, 5);
            net.setAirfield(far2, 270, 1);
            net.setAirfield(near1, 270, 1);
            Edge line = net.edge(far1, far2);
            s.soft("H / a level 5 airfield and a level 1 airfield two large maps away (4780 blocks) have a direct line, and it reaches none of the airfields of the scenes before (more than four large maps away)",
                    line != null && RouteRules.mapsApart(far1, far2) == 2 && line.length() > 4700 && net.edgesAt(far1).size() == 2,
                    line == null ? "no line" : String.format("%.0f blocks, %d chunks along it, %d lines at the level 5 airfield", line.length(), line.tube().chunks().length, net.edgesAt(far1).size()));
            began[0] = level.getServer().getTickCount();
        });
        s.until("H: both lines are looked at", () -> {}, () -> net.pending() == 0, 600);
        s.step("H: the long line", () -> {
            int took = level.getServer().getTickCount() - began[0];
            Edge line = net.edge(far1, far2), shortLine = net.edge(far1, near1);
            // (Looked at square by square, as lines were before 0.11.0, this line would take 2700 ticks a pass: 11 million columns at 4000 a tick.
            // What is waited for here is the chunks of the short line, which were asked for a moment ago.)
            s.soft("H / the long line is looked at within 10 seconds, stays 'not verified' with unseen parts, can be flown, and the short line beside it is verified meanwhile",
                    took <= 200 && line.state() == State.UNVERIFIED && line.hasUnseenParts() && net.route(far1, far2, ShipClass.SMALL).equals(List.of(far1, far2)) && shortLine != null && shortLine.state() == State.CLEAR,
                    took + " ticks, " + describe(line) + " / short line " + describe(shortLine));
            net.remove(far1);
            net.remove(far2);
            net.remove(near1);
        });
        return s;
    }
}
