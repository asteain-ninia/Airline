package com.asteain.airline.colony;

import com.asteain.airline.Airline;
import com.asteain.airline.route.Facility;
import com.asteain.airline.route.RouteNetwork;
import com.asteain.airline.route.RouteRules;
import com.ldtteam.structurize.storage.StructurePacks;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * An airfield (small, medium or large) or an anchorage: what kind is its {@link Facility}. They are one building
 * class; the kind decides the size and place of the pads, which classes of aircraft
 * may berth and are built there, and whether it is an airfield at all (an anchorage has no pilot, no aircraft of its
 * own and builds none, and is only on the air network while its colony has an airfield). Its racks are the export store: goods that other colonies want are collected here from the warehouse by couriers.
 * The import store is the stash that comes with its blueprint.
 *
 * <p>Level 1 has one landing pad: the home of the airfield's own aircraft. From level 2 on there is a second pad beside
 * it for aircraft of other airfields (see {@link #pads()}). Levels 3 to 5 rebuild the buildings in better materials and
 * add racks; the plot and the two pads stay as they are (tools/make_blueprint.py).</p>
 */
public class BuildingAirfield extends AbstractBuilding {
    private final Facility facility;

    public BuildingAirfield(IColony colony, BlockPos pos) {
        this(colony, pos, Facility.SMALL_AIRFIELD);
    }

    public BuildingAirfield(IColony colony, BlockPos pos, Facility facility) {
        super(colony, pos);
        this.facility = facility;
    }

    /** What kind of place this is: small, medium or large airfield, or anchorage. */
    public Facility facility() {
        return facility;
    }

    @NotNull
    @Override
    public String getSchematicName() {
        return facility.schematic;
    }

    @Override
    public int getMaxBuildingLevel() {
        return facility.maxLevel();
    }

    /** The middle block of the first landing pad's floor: this airfield's own point in the air network, the home of its aircraft. */
    public BlockPos pad() {
        return getPosition().offset(getRotationMirror().applyToPos(facility.padFromHut()));
    }

    /** The second landing pad (for visitors), whether it is built yet or not. */
    public BlockPos guestPad() {
        return getPosition().offset(getRotationMirror().applyToPos(facility.guestPadFromHut()));
    }

    /** Every landing pad this airfield has at its level: the first is its own, the others are for visitors. */
    public List<BlockPos> pads() {
        final List<BlockPos> all = new ArrayList<>();
        all.add(pad());
        if (facility.airfield() && getBuildingLevel() >= RouteRules.GUEST_PAD_LEVEL) all.add(guestPad());
        return all;
    }

    /** Whether a point of the air network is one of this airfield's landing pads (also one it does not have at its level). */
    public boolean hasPad(BlockPos point) {
        return point.equals(pad()) || point.equals(guestPad());
    }

    /** The heading (degrees, a multiple of 90) an aircraft rests in on the pad. */
    public int padHeading() {
        return RouteRules.padHeading(getRotationMirror().applyToPos(new BlockPos(0, 0, -1)));
    }

    /**
     * Puts the landing pads on the air network of this dimension (again; nothing changes when they are there already).
     * A pad the airfield does not have at its level (any more) leaves the network.
     */
    public void registerPad() {
        registerPad(null);
        // An airfield's anchorages follow it at once, not with their own next colony tick.
        if (facility.airfield()) anchoragesFollow(null);
    }

    /** ... {@code gone}: an airfield of the colony that is being taken down (still among its buildings for this moment). */
    private void registerPad(@org.jetbrains.annotations.Nullable BuildingAirfield gone) {
        if (!(getColony().getWorld() instanceof ServerLevel level)) return;
        final RouteNetwork network = RouteNetwork.get(level);
        // An anchorage serves the colony's airfield: without one (built) it is not on the network.
        final List<BlockPos> pads = facility.airfield() || hasAirfield(getColony(), gone) ? pads() : List.of();
        for (final BlockPos pad : pads) network.setAirfield(pad, padHeading(), getBuildingLevel(), getPosition(), facility);
        if (!pads.contains(pad())) {
            final RouteNetwork.Node own = network.node(pad());
            if (own != null && own.kind() == RouteNetwork.Kind.AIRFIELD) network.remove(pad());
        }
        final RouteNetwork.Node guest = network.node(guestPad());
        if (!pads.contains(guestPad()) && guest != null && guest.kind() == RouteNetwork.Kind.AIRFIELD) network.remove(guestPad());
    }

    /** Whether the colony has an airfield (of any size, built): what an anchorage needs. */
    public static boolean hasAirfield(IColony colony) {
        return hasAirfield(colony, null);
    }

    /** The same, not counting {@code gone} (an airfield that is being taken down). */
    private static boolean hasAirfield(IColony colony, @org.jetbrains.annotations.Nullable BuildingAirfield gone) {
        for (final var building : colony.getServerBuildingManager().getBuildings().values())
            if (building != gone && building instanceof BuildingAirfield other && other.facility.airfield() && other.getBuildingLevel() >= 1) return true;
        return false;
    }

    /** The colony's anchorages come onto the network, or leave it, as the colony has an airfield or not (without {@code gone}). */
    private void anchoragesFollow(@org.jetbrains.annotations.Nullable BuildingAirfield gone) {
        for (final var building : getColony().getServerBuildingManager().getBuildings().values())
            if (building != gone && building instanceof BuildingAirfield anchorage && !anchorage.facility.airfield()) anchorage.registerPad(gone);
    }

    @Override
    public void onColonyTick(@NotNull IColony colony) {
        super.onColonyTick(colony);
        registerPad();
        askForFuel();
        askForParts();
    }

    /**
     * The parts its aircraft lack, the materials for repairing it, are asked for with ordinary item requests, like
     * anything else a building asks for: couriers bring them to the racks, and the
     * aircraft is put right from there at its next look (every ten seconds). One request per kind of part for what is
     * lacking and not on the racks yet; a request for a part that is no longer lacking is withdrawn. These requests are
     * the building's own {@code Stack} requests that the building may fill itself; those of the export table
     * ({@link ExportStockModule}) are the ones it may not, and the two never touch each other's.
     */
    public void askForParts() {
        if (!facility.airfield() || !(getColony().getWorld() instanceof ServerLevel level)) return;
        final com.asteain.airline.flight.Flights.PartsWanted wanted = com.asteain.airline.flight.Flights.partsWanted(level, this);
        if (wanted == null) return;
        final java.util.Map<String, Integer> asked = new java.util.HashMap<>();
        final com.google.common.reflect.TypeToken<com.minecolonies.api.colony.requestsystem.requestable.Stack> kind =
                com.google.common.reflect.TypeToken.of(com.minecolonies.api.colony.requestsystem.requestable.Stack.class);
        for (final com.minecolonies.api.colony.requestsystem.request.IRequest<? extends com.minecolonies.api.colony.requestsystem.requestable.Stack> open : getOpenRequestsOfType(-1, kind)) {
            final com.minecolonies.api.colony.requestsystem.requestable.Stack stack = open.getRequest();
            if (!stack.canBeResolvedByBuilding()) continue;
            String key = null;
            for (final String part : wanted.items().keySet()) if (wanted.fits(part, stack.getStack())) key = part;
            if (key == null) {
                if (wanted.complete()) getColony().getRequestManager().updateRequestState(open.getId(), com.minecolonies.api.colony.requestsystem.request.RequestState.CANCELLED);
                continue;
            }
            asked.merge(key, stack.getCount(), Integer::sum);
        }
        for (final java.util.Map.Entry<String, Integer> part : wanted.items().entrySet()) {
            final String key = part.getKey();
            final net.minecraft.world.item.ItemStack item = wanted.item(key);
            final int onRacks = com.minecolonies.api.util.InventoryUtils.getItemCountInItemHandler(getItemHandlerCap(), stack -> wanted.fits(key, stack));
            final int more = part.getValue() - asked.getOrDefault(key, 0) - onRacks;
            if (more <= 0) continue;
            // A part made of others asks for exactly that (its materials as well); any other, for the item alone.
            final boolean madeOf = key.indexOf('|') >= 0;
            createRequest(new com.minecolonies.api.colony.requestsystem.requestable.Stack(item.copyWithCount(1), madeOf, true,
                    net.minecraft.world.item.ItemStack.EMPTY, more, 1, true), true);
        }
    }

    /**
     * An airfield whose own aircraft stands at home with a tank that is not well filled asks the colony for fuel when
     * little of it lies on its racks (one request at a time; couriers bring it like anything else a building asks for).
     */
    private void askForFuel() {
        if (!facility.airfield() || com.asteain.airline.flight.Cargo.fuelStock() <= 0 || !(getColony().getWorld() instanceof ServerLevel level)) return;
        if (!com.asteain.airline.flight.Flights.wantsFuel(level, this)) return;
        final com.google.common.reflect.TypeToken<com.minecolonies.api.colony.requestsystem.requestable.StackList> kind = com.google.common.reflect.TypeToken.of(com.minecolonies.api.colony.requestsystem.requestable.StackList.class);
        if (!getOpenRequestsOfType(-1, kind).isEmpty()) return;
        createRequest(new com.minecolonies.api.colony.requestsystem.requestable.StackList(com.asteain.airline.flight.Flights.fuelAsked(), "message." + Airline.MOD_ID + ".fuel.request", 64, 1), true);
    }

    /** A new level may bring a new pad: it comes onto the network at once, not with the next colony tick. */
    @Override
    public void onUpgradeComplete(@org.jetbrains.annotations.Nullable com.ldtteam.structurize.blueprints.v1.Blueprint blueprint, int newLevel) {
        super.onUpgradeComplete(blueprint, newLevel);
        registerPad();
    }

    @Override
    public void onDestroyed() {
        if (getColony().getWorld() instanceof ServerLevel level) {
            final RouteNetwork network = RouteNetwork.find(level);
            if (network != null) {
                network.remove(pad());
                final RouteNetwork.Node guest = network.node(guestPad());
                if (guest != null && guest.kind() == RouteNetwork.Kind.AIRFIELD) network.remove(guestPad());
            }
            // The colony's anchorages leave with its last airfield (this one is still among the colony's buildings now).
            if (facility.airfield()) anchoragesFollow(this);
        }
        super.onDestroyed();
    }

    /**
     * A hut placed without the build tool gets the colony's style pack and no usable blueprint path ("null1.blueprint" from
     * MineColonies' own name table). The airfield only exists in the pack this mod ships, so such a hut is pointed there,
     * pack and path together, and that is what gets saved.
     */
    @Override
    public void onPlacement() {
        super.onPlacement();
        registerPad();
        if (!hasOwnBlueprint() && getTileEntity() != null && StructurePacks.hasPack(Airline.PACK_NAME)) {
            setStructurePack(Airline.PACK_NAME);
            setBlueprintPath(Airline.blueprintPath(facility));
        }
    }

    /** Until {@link #onPlacement()} has run (or when the pack was not loaded yet) the answers still name the shipped blueprint. */
    @Override
    public String getBlueprintPath() {
        return hasOwnBlueprint() ? super.getBlueprintPath() : Airline.blueprintPath(facility);
    }

    @Override
    public String getStructurePack() {
        return hasOwnBlueprint() ? super.getStructurePack() : Airline.PACK_NAME;
    }

    private boolean hasOwnBlueprint() {
        String path = super.getBlueprintPath();
        return path != null && !path.isEmpty() && !path.contains("null");
    }

    /**
     * Export racks are never emptied by couriers: no pick-up request is created, not even when a player orders one.
     * (The module's keep-everything rule covers a pick-up that is already under way.)
     */
    @Override
    public boolean createPickupRequest(int quantity, boolean force) {
        return false;
    }

    @Override
    public boolean canBeGathered() {
        return false;
    }

    /**
     * Pilots an airfield hires: one for its own pad and one for each anchorage of its colony, whose aircraft
     * are the airfield's too; a small airfield one (small aircraft do not berth at anchorages).
     */
    public static int pilotsFor(com.minecolonies.api.colony.buildings.IBuilding building) {
        if (!(building instanceof BuildingAirfield airfield) || !airfield.facility().airfield() || airfield.facility() == com.asteain.airline.route.Facility.SMALL_AIRFIELD) return 1;
        int n = 1;
        for (final com.minecolonies.api.colony.buildings.IBuilding other : building.getColony().getServerBuildingManager().getBuildings().values())
            if (other instanceof BuildingAirfield anchorage && anchorage.facility() == com.asteain.airline.route.Facility.ANCHORAGE && anchorage.getBuildingLevel() >= 1) n++;
        return n;
    }
}
