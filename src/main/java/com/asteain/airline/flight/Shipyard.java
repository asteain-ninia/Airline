package com.asteain.airline.flight;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.BuildingAirfield;
import com.ldtteam.structurize.api.RotationMirror;
import com.minecolonies.api.colony.workorders.IServerWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.util.MessageUtils;
import com.minecolonies.core.colony.workorders.WorkOrderDecoration;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Building an aircraft where it will stand. It is the airfield's business alone (the user's ruling of 2026-10-14): asked
 * for in the airfield's window, the aircraft's own block is set down on the middle of the airfield's own landing pad and
 * makes that pad a building site: a work order goes to the colony's builders (every design is a blueprint of the
 * mod's pack, so the builders ask for its materials and put it up the way they do any decoration), and when every block
 * of the design stands there, put up by a builder or by hand, the whole becomes a ship, parked on its pad.
 *
 * <p>The aircraft is built with its nose to the east whatever way the pad faces, and turned to the pad's heading when it
 * becomes a ship (the designs, and what the autopilot knows of them, are all written nose to +x).</p>
 */
final class Shipyard {
    private Shipyard() {}

    /** A site is looked at this often (ticks). */
    private static final int LOOK_EVERY = 40;
    private static final String KEY = "message." + Airline.MOD_ID + ".yard.";

    /** Where a design's blueprint is in the mod's pack. */
    static String blueprint(AircraftType type) {
        return "aircraft/" + type.name + ".blueprint";
    }

    // ---- the design as block states ---------------------------------------------------------------------------

    /** One block of a design: the state as the design names it, and which of its properties the design names. */
    record Part(BlockState state, Map<String, String> named) {
        /** Whether what stands there is this part: the same block, and for a device the same setting of what the design names. */
        boolean is(BlockState there) {
            if (there.getBlock() != state.getBlock()) return false;
            if (named.isEmpty() || !device()) return true;
            for (final Property<?> property : there.getProperties()) {
                final String wanted = named.get(property.getName());
                // (What a device changes by itself while it works is not asked about.)
                if (wanted != null && !CHANGING.contains(property.getName()) && !wanted.equals(name(there, property))) return false;
            }
            return true;
        }

        /** A part of the drive or of what the autopilot works: it only does its work turned the way the design has it. */
        boolean device() {
            final String namespace = state.getBlock().builtInRegistryHolder().key().location().getNamespace();
            return !"minecraft".equals(namespace) && !Airline.MOD_ID.equals(namespace);
        }

        private static <T extends Comparable<T>> String name(BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }
    }

    /** Properties a device sets by itself while it works: they say nothing about how it was built. */
    private static final java.util.Set<String> CHANGING = java.util.Set.of("powered", "lit", "waterlogged", "open", "extended", "triggered", "enabled", "left_powered", "right_powered");

    private static final Map<String, Map<BlockPos, Part>> PARTS = new HashMap<>();

    /** The blocks of a design, seen from the aircraft's own block; null when the design names a block that does not exist. */
    @Nullable
    static Map<BlockPos, Part> parts(ServerLevel level, AircraftType type) {
        if (PARTS.containsKey(type.name)) return PARTS.get(type.name);
        Map<BlockPos, Part> parts = new HashMap<>();
        try {
            for (final Map.Entry<BlockPos, String> part : type.parts.entrySet()) {
                final String text = part.getValue();
                final Map<String, String> named = new HashMap<>();
                final int open = text.indexOf('[');
                if (open > 0) for (final String pair : text.substring(open + 1, text.length() - 1).split(",")) named.put(pair.substring(0, pair.indexOf('=')).trim(), pair.substring(pair.indexOf('=') + 1).trim());
                parts.put(part.getKey(), new Part(BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), text, false).blockState(), named));
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | RuntimeException e) {
            FlightController.LOGGER.error("[Airline/flight] the aircraft design '{}' names a block that does not exist.", type.name, e);
            parts = null;
        }
        PARTS.put(type.name, parts);
        return parts;
    }

    /** How many blocks of the design do not stand yet around an aircraft block at {@code base}; -1 for a design that cannot be read. */
    static int missing(ServerLevel level, BlockPos base, AircraftType type) {
        final Map<BlockPos, Part> parts = parts(level, type);
        if (parts == null) return -1;
        int missing = 0;
        for (final Map.Entry<BlockPos, Part> part : parts.entrySet()) if (!part.getValue().is(level.getBlockState(base.offset(part.getKey())))) missing++;
        return missing;
    }

    // ---- keeping an aircraft as it was built --------------------------------------------------------------------

    /**
     * An aircraft that stands on its home pad is put back the way its design has it (the user's ruling of 2026-10-07: the
     * air network takes aircraft as the airfield built them; what was changed is put right when it lands). Inside the
     * outline of the design: a block the design does not have there is taken out (what it gives goes to the airfield's
     * import store), a device that was turned is turned back, and a block that is missing is put in again with one from
     * the airfield's racks. What the racks do not have is remembered in {@link AircraftBlockEntity#lacks}: an aircraft
     * that lacks a part does not fly.
     *
     * @return how many blocks were put right, and how many were taken out
     */
    static int[] maintain(ServerLevel level, AircraftBlockEntity be, Craft c) {
        final Map<BlockPos, Part> parts = parts(level, c.type);
        final BuildingAirfield airfield = be.home == null || !be.home.equals(be.at) ? null : Cargo.airfieldAt(level, be.home);
        if (parts == null || airfield == null) return new int[2];
        final net.neoforged.neoforge.items.IItemHandler racks = Cargo.racks(airfield), store = Cargo.importStore(level, airfield);
        final Map<String, Integer> lacks = new java.util.LinkedHashMap<>();
        int restored = 0, removed = 0;
        for (int y = 0; y < AircraftType.HEIGHT; y++) for (int x = -AircraftType.HALF_LENGTH; x <= AircraftType.HALF_LENGTH; x++) for (int z = -AircraftType.HALF_WIDTH; z <= AircraftType.HALF_WIDTH; z++) {
            final BlockPos part = new BlockPos(x, y, z), at = c.inShip(part);
            if (part.equals(BlockPos.ZERO)) continue;
            final Part wanted = parts.get(part);
            BlockState there = level.getBlockState(at);
            if (wanted != null && wanted.is(there)) continue;
            if (wanted != null && there.getBlock() == wanted.state().getBlock()) {
                // The right block, turned another way.
                level.setBlock(at, wanted.state(), 3);
                restored++;
                continue;
            }
            if (!there.isAir()) {
                final net.minecraft.world.level.block.entity.BlockEntity entity = level.getBlockEntity(at);
                if (entity instanceof net.minecraft.world.Container container) {
                    for (int slot = 0; slot < container.getContainerSize(); slot++) keep(level, be, store, container.removeItemNoUpdate(slot));
                }
                // It comes back as the block it is (whatever tool it would take to break it).
                keep(level, be, store, new net.minecraft.world.item.ItemStack(there.getBlock().asItem()));
                level.removeBlock(at, false);
                removed++;
            }
            if (wanted == null) continue;
            final net.minecraft.world.item.Item item = wanted.state().getBlock().asItem();
            if (item != net.minecraft.world.item.Items.AIR && take(racks, item)) {
                level.setBlock(at, wanted.state(), 3);
                restored++;
            } else {
                lacks.merge(wanted.state().getBlock().builtInRegistryHolder().key().location().toString(), 1, Integer::sum);
            }
        }
        if (restored > 0) c.dial();
        if (!lacks.equals(be.lacks)) {
            be.lacks.clear();
            be.lacks.putAll(lacks);
            if (!lacks.isEmpty()) {
                final String list = list(lacks);
                MessageUtils.format(KEY + "lacks", airfield.getColony().getName(), list).sendTo(airfield.getColony()).forManagers();
                FlightController.note(be, "waiting for parts: " + list);
            } else {
                FlightController.note(be, "all parts are in place again");
            }
        }
        return new int[] {restored, removed};
    }

    /** "3 x minecraft:oak_planks, 1 x create:cogwheel". */
    static String list(Map<String, Integer> items) {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, Integer> item : items.entrySet()) text.append(text.isEmpty() ? "" : ", ").append(item.getValue()).append(" x ").append(item.getKey());
        return text.toString();
    }

    private static boolean take(@Nullable net.neoforged.neoforge.items.IItemHandler from, net.minecraft.world.item.Item item) {
        if (from == null) return false;
        for (int slot = 0; slot < from.getSlots(); slot++) {
            if (from.getStackInSlot(slot).is(item) && !from.extractItem(slot, 1, false).isEmpty()) return true;
        }
        return false;
    }

    /** What was taken off an aircraft goes into the airfield's import store; what does not fit is put down on the pad. */
    private static void keep(ServerLevel level, AircraftBlockEntity be, @Nullable net.neoforged.neoforge.items.IItemHandler store, net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) return;
        final net.minecraft.world.item.ItemStack rest = store == null ? stack : net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(store, stack, false);
        if (!rest.isEmpty() && be.home != null) net.minecraft.world.level.block.Block.popResource(level, be.home.above(), rest);
    }

    // ---- the site ---------------------------------------------------------------------------------------------

    private static void say(@Nullable ServerPlayer player, String key, Object... args) {
        if (player != null) player.sendSystemMessage(Component.translatable(KEY + key, args));
    }

    /**
     * Asked for from the airfield's window: an aircraft of this design is built on the airfield's own pad. Where nothing
     * stands yet the aircraft's own block is set down on the middle of the pad and the site begins (as when a player sets
     * the block down there); where a site is already, it builds this design instead. Returns the key of what was said.
     */
    static String request(ServerLevel level, BuildingAirfield airfield, AircraftType type, @Nullable ServerPlayer player) {
        final BlockPos pad = airfield.pad(), at = pad.above();
        if (level.getBlockEntity(at) instanceof AircraftBlockEntity site && pad.equals(site.yard)) {
            if (type.name.equals(site.type)) {
                say(player, "design", Component.translatable(type.title()), missing(level, at, type));
                return "same";
            }
            return switchTo(level, airfield, site, type, player);
        }
        FlightController.loaded(level);
        // An aircraft's own block that stands on the middle of the pad without being a site (set down by hand): it becomes one.
        if (level.getBlockEntity(at) instanceof AircraftBlockEntity loose && loose.yard == null && Craft.shipAt(level, at) == null && FlightController.unclaimed(level, pad, null))
            return begin(level, airfield, loose, type, player);
        if (!FlightController.unclaimed(level, pad, null)) {
            say(player, "pad_taken");
            return "pad_taken";
        }
        if (!level.getBlockState(at).canBeReplaced()) {
            say(player, "pad_blocked", at.getX(), at.getY(), at.getZ());
            return "pad_blocked";
        }
        // (Set down by the server, not by a player: the block's own "placed" does not run, so the site is begun here.)
        level.setBlock(at, Airline.AIRCRAFT.get().defaultBlockState(), 3);
        if (!(level.getBlockEntity(at) instanceof AircraftBlockEntity be)) return "pad_blocked";
        return begin(level, airfield, be, type, player);
    }

    /**
     * Asked for from the airfield's window: the building of the aircraft on the airfield's own pad is called off. The
     * builders are not asked any more and the aircraft's own block is taken away; what was put up already stays where it
     * stands. Returns the key of what was said.
     */
    static String stop(ServerLevel level, BuildingAirfield airfield, @Nullable ServerPlayer player) {
        final BlockPos at = airfield.pad().above();
        if (!(level.getBlockEntity(at) instanceof AircraftBlockEntity site) || !airfield.pad().equals(site.yard)) {
            say(player, "no_site");
            return "no_site";
        }
        cancel(airfield, site);
        site.yard = null;
        level.removeBlock(at, false);
        say(player, "stopped");
        return "stopped";
    }

    /** A site begins: the builders are asked for this design. */
    private static String begin(ServerLevel level, BuildingAirfield airfield, AircraftBlockEntity be, AircraftType type, @Nullable ServerPlayer player) {
        be.yard = airfield.pad();
        be.type = type.name;
        order(airfield, be);
        be.setChanged();
        say(player, "started", Component.translatable(type.title()), missing(level, be.getBlockPos(), type));
        return "started";
    }

    /** A site builds another design: the builders' order is taken back and given again for it. */
    private static String switchTo(ServerLevel level, BuildingAirfield airfield, AircraftBlockEntity be, AircraftType type, @Nullable ServerPlayer player) {
        cancel(airfield, be);
        be.type = type.name;
        order(airfield, be);
        be.setChanged();
        say(player, "design", Component.translatable(type.title()), missing(level, be.getBlockPos(), type));
        return "design";
    }

    /** The block of a site goes away before the aircraft was finished: the builders are not asked any more. */
    static void removed(ServerLevel level, AircraftBlockEntity be) {
        final BuildingAirfield airfield = be.yard == null ? null : Cargo.airfieldAt(level, be.yard);
        if (airfield != null) cancel(airfield, be);
        be.yard = null;
    }

    private static void order(BuildingAirfield airfield, AircraftBlockEntity be) {
        final AircraftType type = AircraftType.of(be.type);
        final WorkOrderDecoration order = WorkOrderDecoration.create(WorkOrderType.BUILD, Airline.PACK_NAME, blueprint(type), type.title(), be.getBlockPos(), RotationMirror.NONE, 0);
        airfield.getColony().getWorkManager().addWorkOrder(order, false);
        // (Outside the colony's land MineColonies does not take the order and gives it no number: the site is then built by hand.)
        be.workOrder = order.getID();
    }

    private static void cancel(BuildingAirfield airfield, AircraftBlockEntity be) {
        if (be.workOrder != 0 && airfield.getColony().getWorkManager().getWorkOrder(be.workOrder) != null) airfield.getColony().getWorkManager().removeWorkOrder(be.workOrder);
        be.workOrder = 0;
    }

    /** Every tick, for an aircraft block that stands in the world (not in a ship). */
    static void tick(ServerLevel level, AircraftBlockEntity be) {
        if (be.yard == null || (level.getGameTime() + be.getBlockPos().asLong()) % LOOK_EVERY != 0) return;
        final BlockPos pos = be.getBlockPos(), pad = be.yard;
        final BuildingAirfield airfield = Cargo.airfieldAt(level, pad);
        if (airfield == null || !pos.below().equals(pad)) {
            // The airfield is gone (or the block was pushed away): no site any more, just a block.
            be.yard = null;
            be.workOrder = 0;
            be.setChanged();
            return;
        }
        final AircraftType type = AircraftType.of(be.type);
        final IServerWorkOrder order = be.workOrder == 0 ? null : airfield.getColony().getWorkManager().getWorkOrder(be.workOrder);
        if (missing(level, pos, type) != 0) return;
        // A builder who is at it finishes its own work first (it would put the blocks up again if they went away under its hands).
        if (order != null && order.isClaimed()) return;
        cancel(airfield, be);
        be.yard = null;
        be.setChanged();
        final Component title = Component.translatable(type.title());
        final Flights.Answer made = FlightController.commission(level, pad, type);
        // (From here on this block entity is no more: the block is part of the ship now.)
        if (made.ok()) MessageUtils.format(KEY + "done", title, pad.getX(), pad.getY(), pad.getZ()).sendTo(airfield.getColony()).forManagers();
        else FlightController.LOGGER.error("[Airline/flight] the aircraft built at {} could not be made a ship: {}", pos, made.text().getString());
    }
}
