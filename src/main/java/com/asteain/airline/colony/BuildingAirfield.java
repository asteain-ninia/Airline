package com.asteain.airline.colony;

import com.asteain.airline.Airline;
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
 * The airfield. Its racks are the export store: goods that other colonies want are collected here from the warehouse by couriers.
 * The import store is the stash that comes with its blueprint.
 *
 * <p>Level 1 has one landing pad: the home of the airfield's own aircraft. From level 2 on there is a second pad beside
 * it for aircraft of other airfields (see {@link #pads()}). Levels 3 to 5 rebuild the buildings in better materials and
 * add racks; the plot and the two pads stay as they are (tools/make_blueprint.py).</p>
 */
public class BuildingAirfield extends AbstractBuilding {
    public BuildingAirfield(IColony colony, BlockPos pos) {
        super(colony, pos);
    }

    @NotNull
    @Override
    public String getSchematicName() {
        return Airline.BUILDING_NAME;
    }

    @Override
    public int getMaxBuildingLevel() {
        return 5;
    }

    /** The middle block of the first landing pad's floor: this airfield's own point in the air network, the home of its aircraft. */
    public BlockPos pad() {
        return getPosition().offset(getRotationMirror().applyToPos(RouteRules.PAD_FROM_HUT));
    }

    /** The second landing pad (for visitors), whether it is built yet or not. */
    public BlockPos guestPad() {
        return getPosition().offset(getRotationMirror().applyToPos(RouteRules.GUEST_PAD_FROM_HUT));
    }

    /** Every landing pad this airfield has at its level: the first is its own, the others are for visitors. */
    public List<BlockPos> pads() {
        final List<BlockPos> all = new ArrayList<>();
        all.add(pad());
        if (getBuildingLevel() >= RouteRules.GUEST_PAD_LEVEL) all.add(guestPad());
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
        if (!(getColony().getWorld() instanceof ServerLevel level)) return;
        final RouteNetwork network = RouteNetwork.get(level);
        final List<BlockPos> pads = pads();
        for (final BlockPos pad : pads) network.setAirfield(pad, padHeading(), getBuildingLevel(), getPosition());
        final RouteNetwork.Node guest = network.node(guestPad());
        if (!pads.contains(guestPad()) && guest != null && guest.kind() == RouteNetwork.Kind.AIRFIELD) network.remove(guestPad());
    }

    @Override
    public void onColonyTick(@NotNull IColony colony) {
        super.onColonyTick(colony);
        registerPad();
        askForFuel();
    }

    /**
     * An airfield whose own aircraft stands at home with a tank that is not well filled asks the colony for fuel when
     * little of it lies on its racks (one request at a time; couriers bring it like anything else a building asks for).
     */
    private void askForFuel() {
        if (com.asteain.airline.flight.Cargo.fuelStock() <= 0 || !(getColony().getWorld() instanceof ServerLevel level)) return;
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
            setBlueprintPath(Airline.BLUEPRINT_PATH);
        }
    }

    /** Until {@link #onPlacement()} has run (or when the pack was not loaded yet) the answers still name the shipped blueprint. */
    @Override
    public String getBlueprintPath() {
        return hasOwnBlueprint() ? super.getBlueprintPath() : Airline.BLUEPRINT_PATH;
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
}
