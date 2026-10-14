package com.asteain.airline.command;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.colony.ExportStockModule;
import com.asteain.airline.flight.AircraftBlockEntity;
import com.asteain.airline.flight.Flights;
import com.asteain.airline.flight.Timetable;
import com.asteain.airline.ledger.WantLedger;
import com.asteain.airline.route.RouteNetwork;
import com.asteain.airline.route.RouteRules;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.crafting.ItemStorage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Administrator commands (permission level 2).
 * <pre>
 * /&lt;id&gt; want add &lt;colony&gt; &lt;item&gt; &lt;count&gt;   a colony asks the air network for an item
 * /&lt;id&gt; want remove &lt;colony&gt; &lt;item&gt;
 * /&lt;id&gt; want list
 * /&lt;id&gt; want clear [colony]
 * /&lt;id&gt; export set &lt;colony&gt; &lt;item&gt; &lt;stacks&gt; a row of the airfield's export table (same as the airfield's window)
 * /&lt;id&gt; export remove &lt;colony&gt; &lt;item&gt;
 * /&lt;id&gt; export list &lt;colony&gt;
 * /&lt;id&gt; net list                              the air network of this dimension: points, lines, their states
 * /&lt;id&gt; net blocked                           only the blocked lines, with the block that blocks each
 * /&lt;id&gt; net route &lt;from&gt; &lt;to&gt;                 the way between two points (positions of the buoys or pads)
 * /&lt;id&gt; net recheck                           verify every line again
 * /&lt;id&gt; aircraft spawn &lt;colony&gt; [design]      a new aircraft on the landing pad of the colony's airfield
 * /&lt;id&gt; aircraft designs                      the aircraft designs there are
 * /&lt;id&gt; aircraft list                         the aircraft that are loaded, and what each is doing
 * /&lt;id&gt; aircraft clear                        takes away the aircraft that were given up in the air (their cargo is handed over)
 * /&lt;id&gt; aircraft forget &lt;colony&gt;              the timetable forgets that colony's aircraft that are not loaded (for one that is gone for good)
 * /&lt;id&gt; flight cancel [colony]                calls off the flight under way (of that colony's aircraft): back to where it started
 * /&lt;id&gt; flight send &lt;colony&gt; &lt;colony&gt;         the pilot of the first colony flies its aircraft to a free pad of the second colony's airfield
 * /&lt;id&gt; flight round &lt;colony&gt; &lt;colony&gt;        the same, and back again
 * /&lt;id&gt; flight status                         the flights under way and the last cargo flights
 * </pre>
 * Colony numbers are those of the dimension the command is run in.
 */
public final class AirlineCommand {
    private static final String KEY = "commands." + Airline.MOD_ID + ".";
    private static final String COLONY = "colony";
    private static final String DESIGN = "design";
    private static final String ITEM = "item";
    private static final String COUNT = "count";
    private static final String STACKS = "stacks";
    private static final String FROM = "from";
    private static final String DESTINATION = "destination";
    private static final String TO = "to";

    private AirlineCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal(Airline.MOD_ID).requires(source -> source.hasPermission(2))
                .then(Commands.literal("want")
                        .then(Commands.literal("add").then(Commands.argument(COLONY, IntegerArgumentType.integer(1))
                                .then(Commands.argument(ITEM, ItemArgument.item(context))
                                        .then(Commands.argument(COUNT, IntegerArgumentType.integer(1)).executes(AirlineCommand::wantAdd)))))
                        .then(Commands.literal("remove").then(Commands.argument(COLONY, IntegerArgumentType.integer(1))
                                .then(Commands.argument(ITEM, ItemArgument.item(context)).executes(AirlineCommand::wantRemove))))
                        .then(Commands.literal("list").executes(AirlineCommand::wantList))
                        .then(Commands.literal("clear").executes(ctx -> wantClear(ctx, false))
                                .then(Commands.argument(COLONY, IntegerArgumentType.integer(1)).executes(ctx -> wantClear(ctx, true)))))
                .then(Commands.literal("export")
                        .then(Commands.literal("set").then(Commands.argument(COLONY, IntegerArgumentType.integer(1))
                                .then(Commands.argument(ITEM, ItemArgument.item(context))
                                        .then(Commands.argument(STACKS, IntegerArgumentType.integer(0)).executes(AirlineCommand::exportSet)))))
                        .then(Commands.literal("remove").then(Commands.argument(COLONY, IntegerArgumentType.integer(1))
                                .then(Commands.argument(ITEM, ItemArgument.item(context)).executes(AirlineCommand::exportRemove))))
                        .then(Commands.literal("list").then(Commands.argument(COLONY, IntegerArgumentType.integer(1)).executes(AirlineCommand::exportList))))
                .then(Commands.literal("net")
                        .then(Commands.literal("list").executes(ctx -> netList(ctx, false)))
                        .then(Commands.literal("blocked").executes(ctx -> netList(ctx, true)))
                        .then(Commands.literal("recheck").executes(AirlineCommand::netRecheck))
                        .then(Commands.literal("route").then(Commands.argument(FROM, BlockPosArgument.blockPos())
                                .then(Commands.argument(TO, BlockPosArgument.blockPos()).executes(AirlineCommand::netRoute)))))
                .then(Commands.literal("aircraft")
                        .then(Commands.literal("spawn").then(Commands.argument(COLONY, IntegerArgumentType.integer(1)).executes(ctx -> aircraftSpawn(ctx, null))
                                .then(Commands.argument(DESIGN, com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(Flights.designs(), builder))
                                        .executes(ctx -> aircraftSpawn(ctx, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, DESIGN))))))
                        .then(Commands.literal("designs").executes(AirlineCommand::aircraftDesigns))
                        .then(Commands.literal("list").executes(AirlineCommand::aircraftList))
                        .then(Commands.literal("clear").executes(AirlineCommand::aircraftClear))
                        .then(Commands.literal("forget").then(Commands.argument(COLONY, IntegerArgumentType.integer(1)).executes(AirlineCommand::aircraftForget))))
                .then(Commands.literal("flight")
                        .then(Commands.literal("status").executes(AirlineCommand::flightStatus))
                        .then(Commands.literal("cancel").executes(ctx -> answer(ctx, Flights.cancel(ctx.getSource().getLevel())))
                                .then(Commands.argument(COLONY, IntegerArgumentType.integer(1)).executes(AirlineCommand::flightCancel)))
                        .then(Commands.literal("send").then(Commands.argument(COLONY, IntegerArgumentType.integer(1))
                                .then(Commands.argument(DESTINATION, IntegerArgumentType.integer(1)).executes(ctx -> flightSend(ctx, false)))))
                        .then(Commands.literal("round").then(Commands.argument(COLONY, IntegerArgumentType.integer(1))
                                .then(Commands.argument(DESTINATION, IntegerArgumentType.integer(1)).executes(ctx -> flightSend(ctx, true)))))));
    }

    // ---- aircraft and flights -----------------------------------------------------------------------------

    /** The colony's first airfield, or null (with a message). */
    private static BuildingAirfield airfield(CommandContext<CommandSourceStack> ctx, String argument) {
        final int id = IntegerArgumentType.getInteger(ctx, argument);
        final IColony colony = IColonyManager.getInstance().getColonyByDimension(id, ctx.getSource().getLevel().dimension());
        if (colony == null) {
            ctx.getSource().sendFailure(Component.translatable(KEY + "no_colony", id));
            return null;
        }
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            if (building instanceof BuildingAirfield airfield) return airfield;
        ctx.getSource().sendFailure(Component.translatable(KEY + "no_airfield", colony.getName()));
        return null;
    }

    private static int answer(CommandContext<CommandSourceStack> ctx, Flights.Answer answer) {
        if (answer.ok()) ctx.getSource().sendSuccess(answer::text, true);
        else ctx.getSource().sendFailure(answer.text());
        return answer.ok() ? 1 : 0;
    }

    private static int aircraftSpawn(CommandContext<CommandSourceStack> ctx, @org.jetbrains.annotations.Nullable String design) {
        final BuildingAirfield airfield = airfield(ctx, COLONY);
        if (airfield == null) return 0;
        airfield.registerPad();
        return answer(ctx, design == null ? Flights.spawn(ctx.getSource().getLevel(), airfield.pad()) : Flights.spawn(ctx.getSource().getLevel(), airfield.pad(), design));
    }

    private static int aircraftDesigns(CommandContext<CommandSourceStack> ctx) {
        final java.util.List<String> designs = Flights.designs();
        for (int i = 0; i < designs.size(); i++) {
            final String design = designs.get(i);
            final boolean first = i == 0;
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + (first ? "aircraft.design.first" : "aircraft.design"), design, Component.translatable(Flights.designTitle(design))), false);
        }
        return designs.size();
    }

    private static int aircraftList(CommandContext<CommandSourceStack> ctx) {
        if (!Flights.available()) return answer(ctx, new Flights.Answer(false, "flight.off"));
        final List<AircraftBlockEntity> all = Flights.aircraft(ctx.getSource().getLevel());
        if (all.isEmpty()) ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.none"), false);
        for (final AircraftBlockEntity aircraft : all) {
            final String where = aircraft.at == null ? "-" : at(aircraft.at), home = aircraft.home == null ? "-" : at(aircraft.home);
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.line", aircraft.type, home, where, aircraft.phase.name(), aircraft.note), false);
        }
        return all.size();
    }

    /**
     * Takes away the aircraft that were given up in the air (and are loaded: go there first). What is in their holds goes
     * into the inventory of the player who typed this; typed from the console it falls where the aircraft was.
     */
    private static int aircraftClear(CommandContext<CommandSourceStack> ctx) {
        return answer(ctx, Flights.clear(ctx.getSource().getLevel(), ctx.getSource().getPlayer()));
    }

    private static int aircraftForget(CommandContext<CommandSourceStack> ctx) {
        final BuildingAirfield airfield = airfield(ctx, COLONY);
        return airfield == null ? 0 : answer(ctx, Flights.forget(ctx.getSource().getLevel(), airfield.pad()));
    }

    private static int flightCancel(CommandContext<CommandSourceStack> ctx) {
        final BuildingAirfield airfield = airfield(ctx, COLONY);
        return airfield == null ? 0 : answer(ctx, Flights.cancel(ctx.getSource().getLevel(), airfield.pad()));
    }

    /** The flights under way (where from, where to, what is in the manifest, what the aircraft is doing) and the last flights. */
    private static int flightStatus(CommandContext<CommandSourceStack> ctx) {
        for (final AircraftBlockEntity aircraft : Flights.aircraft(ctx.getSource().getLevel())) {
            final String from = aircraft.origin == null ? "-" : at(aircraft.origin), to = aircraft.destination == null ? "-" : at(aircraft.destination);
            final String goods = aircraft.manifest.isEmpty() && aircraft.returnManifest.isEmpty() ? "-" : aircraft.manifest + " / " + aircraft.returnManifest;
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.status", aircraft.phase.name(), from, to, goods, aircraft.note), false);
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.status.fuel", Flights.fuelMinutes(aircraft)), false);
            // Where the goods are: what really lies in the hold (the lists above are what the flight was meant to carry).
            final Map<Item, Integer> hold = Flights.holdContents(aircraft);
            if (!hold.isEmpty()) {
                final StringBuilder text = new StringBuilder();
                hold.forEach((item, count) -> text.append(text.isEmpty() ? "" : ", ").append(count).append(" x ").append(new ItemStack(item).getHoverName().getString()));
                ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.status.hold", text.toString()), false);
            }
        }
        // The timetable also knows the aircraft that are not loaded.
        final long now = ctx.getSource().getLevel().getGameTime();
        final String dimension = ctx.getSource().getLevel().dimension().location().toString();
        for (final Timetable.Entry line : Timetable.get(ctx.getSource().getServer()).entries()) {
            if (!line.dimension().equals(dimension)) continue;
            final String to = line.to() == null ? "-" : at(line.to()), arrives = line.to() == null ? "-" : Timetable.clock(line.arrives() - now);
            if (line.flying()) ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.table.flying", at(line.home()), to, arrives), false);
            else ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.table.standing", at(line.home()), at(line.at()), line.state(), Timetable.clock(line.leaves() - now), to, arrives), false);
        }
        final List<String> history = WantLedger.get(ctx.getSource().getServer()).history();
        if (history.isEmpty()) ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.history.none"), false);
        for (final String line : history) ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "flight.history.line", line), false);
        return history.size();
    }

    private static int flightSend(CommandContext<CommandSourceStack> ctx, boolean round) {
        final BuildingAirfield from = airfield(ctx, COLONY), to = from == null ? null : airfield(ctx, DESTINATION);
        if (from == null || to == null) return 0;
        if (to.getBuildingLevel() < 1) return answer(ctx, new Flights.Answer(false, "flight.not_built"));
        from.registerPad();
        to.registerPad();
        Mob pilot = null;
        for (final ICitizenData citizen : from.getAllAssignedCitizen()) {
            if (citizen.getEntity().isPresent()) pilot = citizen.getEntity().get();
        }
        return answer(ctx, Flights.send(ctx.getSource().getLevel(), from.pad(), to.pad(), round, pilot));
    }

    // ---- the air network ----------------------------------------------------------------------------------

    private static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static int netList(CommandContext<CommandSourceStack> ctx, boolean blockedOnly) {
        final RouteNetwork network = RouteNetwork.find(ctx.getSource().getLevel());
        if (network == null || network.nodes().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "net.empty"), false);
            return 0;
        }
        int clear = 0, blocked = 0, unverified = 0;
        for (final RouteNetwork.Edge edge : network.edges()) {
            switch (edge.state()) {
                case CLEAR -> clear++;
                case BLOCKED -> blocked++;
                default -> unverified++;
            }
        }
        final int c = clear, b = blocked, u = unverified;
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "net.summary", network.nodes().size(), network.edges().size(), c, b, u, network.pending()), false);
        if (!blockedOnly) {
            for (final RouteNetwork.Node node : network.nodes()) {
                final int lines = network.edgesAt(node.pos()).size();
                ctx.getSource().sendSuccess(() -> node.kind() == RouteNetwork.Kind.BUOY
                        ? Component.translatable(KEY + "net.point.buoy", node.tier(), at(node.pos()), node.reach(), lines)
                        : RouteRules.largeMap() > 0
                        ? Component.translatable(KEY + "net.point.airfield.maps", at(node.pos()), node.heading(), node.tier(), 2 * RouteRules.mapsAround(node.tier()) + 1, node.reach(), lines)
                        : Component.translatable(KEY + "net.point.airfield", at(node.pos()), node.heading(), node.reach(), lines), false);
            }
        }
        for (final RouteNetwork.Edge edge : network.edges()) {
            if (blockedOnly && edge.state() != RouteNetwork.State.BLOCKED) continue;
            final String length = String.format("%.0f", edge.length());
            ctx.getSource().sendSuccess(() -> switch (edge.state()) {
                case BLOCKED -> Component.translatable(KEY + (edge.barredBy() != null ? "net.line.barred" : "net.line.blocked"), at(edge.a), at(edge.b), length, edge.blockedAt() == null ? "?" : at(edge.blockedAt()));
                case CLEAR -> Component.translatable(KEY + "net.line.clear", at(edge.a), at(edge.b), length);
                default -> Component.translatable(KEY + (edge.hasUnseenParts() ? "net.line.unseen" : "net.line.unverified"), at(edge.a), at(edge.b), length);
            }, false);
        }
        if (blockedOnly && blocked == 0) ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "net.none_blocked"), false);
        return network.edges().size();
    }

    private static int netRecheck(CommandContext<CommandSourceStack> ctx) {
        final int lines = RouteNetwork.get(ctx.getSource().getLevel()).recheckAll();
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "net.recheck", lines), true);
        return lines;
    }

    private static int netRoute(CommandContext<CommandSourceStack> ctx) {
        final BlockPos from = BlockPosArgument.getBlockPos(ctx, FROM), to = BlockPosArgument.getBlockPos(ctx, TO);
        final RouteNetwork network = RouteNetwork.get(ctx.getSource().getLevel());
        final List<BlockPos> way = network.route(from, to);
        if (way.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(KEY + "net.no_route", at(from), at(to)));
            return 0;
        }
        final StringBuilder text = new StringBuilder();
        for (final BlockPos point : way) text.append(text.isEmpty() ? "" : " -> ").append(at(point));
        final String length = String.format("%.0f", network.length(way));
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "net.route", way.size(), length, text.toString()), false);
        return way.size();
    }

    private static IColony colony(CommandContext<CommandSourceStack> ctx) {
        int id = IntegerArgumentType.getInteger(ctx, COLONY);
        IColony colony = IColonyManager.getInstance().getColonyByDimension(id, ctx.getSource().getLevel().dimension());
        if (colony == null) ctx.getSource().sendFailure(Component.translatable(KEY + "no_colony", id));
        return colony;
    }

    private static Component name(Item item) {
        return new ItemStack(item).getHoverName();
    }

    private static int wantAdd(CommandContext<CommandSourceStack> ctx) {
        IColony colony = colony(ctx);
        if (colony == null) return 0;
        Item item = ItemArgument.getItem(ctx, ITEM).getItem();
        int count = IntegerArgumentType.getInteger(ctx, COUNT);
        WantLedger.get(ctx.getSource().getServer()).set(WantLedger.Key.of(colony), item, count);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "want.added", colony.getName(), count, name(item)), true);
        return 1;
    }

    private static int wantRemove(CommandContext<CommandSourceStack> ctx) {
        IColony colony = colony(ctx);
        if (colony == null) return 0;
        Item item = ItemArgument.getItem(ctx, ITEM).getItem();
        WantLedger.get(ctx.getSource().getServer()).set(WantLedger.Key.of(colony), item, 0);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "want.removed", colony.getName(), name(item)), true);
        return 1;
    }

    private static int wantList(CommandContext<CommandSourceStack> ctx) {
        List<WantLedger.Entry> entries = WantLedger.get(ctx.getSource().getServer()).entries();
        if (entries.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "want.empty"), false);
            return 0;
        }
        for (WantLedger.Entry entry : entries)
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + (entry.automatic() ? "want.line.automatic" : "want.line"), entry.key().colony(), entry.key().dimension(), entry.count(), name(entry.item())), false);
        return entries.size();
    }

    private static int wantClear(CommandContext<CommandSourceStack> ctx, boolean one) {
        WantLedger.Key key = null;
        if (one) {
            IColony colony = colony(ctx);
            if (colony == null) return 0;
            key = WantLedger.Key.of(colony);
        }
        int removed = WantLedger.get(ctx.getSource().getServer()).clear(key);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "want.cleared", removed), true);
        return removed;
    }

    /** The export table of the colony's first airfield, or null (with a message) when the colony has none. */
    private static ExportStockModule exportTable(CommandContext<CommandSourceStack> ctx) {
        IColony colony = colony(ctx);
        if (colony == null) return null;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            if (building instanceof BuildingAirfield && building.hasModule(ExportStockModule.class)) return building.getFirstModuleOccurance(ExportStockModule.class);
        ctx.getSource().sendFailure(Component.translatable(KEY + "no_airfield", colony.getName()));
        return null;
    }

    private static int exportSet(CommandContext<CommandSourceStack> ctx) {
        ExportStockModule table = exportTable(ctx);
        if (table == null) return 0;
        Item item = ItemArgument.getItem(ctx, ITEM).getItem();
        int stacks = IntegerArgumentType.getInteger(ctx, STACKS);
        table.addMinimumStock(new ItemStack(item), stacks);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "export.set", name(item), stacks), true);
        return 1;
    }

    private static int exportRemove(CommandContext<CommandSourceStack> ctx) {
        ExportStockModule table = exportTable(ctx);
        if (table == null) return 0;
        Item item = ItemArgument.getItem(ctx, ITEM).getItem();
        table.removeMinimumStock(new ItemStack(item));
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "export.removed", name(item)), true);
        return 1;
    }

    private static int exportList(CommandContext<CommandSourceStack> ctx) {
        ExportStockModule table = exportTable(ctx);
        if (table == null) return 0;
        if (table.table().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "export.empty"), false);
            return 0;
        }
        for (Map.Entry<ItemStorage, Integer> row : table.table().entrySet())
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "export.line", row.getKey().getItemStack().getHoverName(), row.getValue()), false);
        return table.table().size();
    }
}
