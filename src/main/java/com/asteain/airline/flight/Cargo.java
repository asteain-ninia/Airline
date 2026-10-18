package com.asteain.airline.flight;

import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.flight.AircraftBlockEntity.Phase;
import com.asteain.airline.ledger.WantLedger;
import com.asteain.airline.route.RouteNetwork;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.tileentities.TileEntityStash;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cargo: the rules and numbers of loading, unloading and of sending flights by the ledger, in one place, and the moving of goods between an airfield's racks, the aircraft's hold
 * and the import store. Nothing here needs Sable; it works on block entities and MineColonies buildings.
 *
 * <p>Goods are never made or lost: every move takes out exactly what it could put in.</p>
 */
public final class Cargo {
    private Cargo() {}

    // ---- rules --------------------------------------------------------------------------------------------

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Cargo flights.").push("cargo");
    }

    private static final ModConfigSpec.BooleanValue PILOT_CARRIES = BUILDER
            .comment("The pilot carries the goods between the airfield's racks and the aircraft's hold, on foot.",
                    "When false nobody carries them: they move by themselves while the aircraft stands fixed (ticksPerStack).")
            .define("pilotCarries", true);
    private static final ModConfigSpec.IntValue CARRY_STACKS = BUILDER
            .comment("How many stacks (of one item) the pilot carries on one walk.")
            .defineInRange("carryStacks", 4, 1, 16);
    private static final ModConfigSpec.IntValue TICKS_PER_STACK = BUILDER
            .comment("Without a carrying pilot: loading and unloading take this many ticks for every stack.")
            .defineInRange("ticksPerStack", 60, 1, 1200);
    private static final ModConfigSpec.IntValue BERTH_TICKS = BUILDER
            .comment("Scheduled flights: an aircraft stays on a pad this many ticks after every landing (24000 is one Minecraft day).",
                    "It is loaded and unloaded meanwhile, and leaves later than this only when the loading is not finished.")
            .defineInRange("berthTicks", 24000, 20, 240000);
    private static final ModConfigSpec.IntValue QUIET = BUILDER
            .comment("After a flight that was called off or delivered nothing, no new flight is prepared for this many seconds.")
            .defineInRange("quietSeconds", 600, 10, 86400);
    private static final ModConfigSpec.IntValue IMPORT_RANGE = BUILDER
            .comment("A MineColonies stash within this many blocks of an airfield's landing pad or its hut is that airfield's import store.")
            .defineInRange("importStoreRange", 24, 4, 64);

    static {
        BUILDER.pop();
        BUILDER.comment("Fuel. The two engines of an aircraft burn it while the aircraft is in the air; on a pad they stand still.").push("fuel");
    }

    private static final ModConfigSpec.ConfigValue<List<? extends String>> FUEL_ITEMS = BUILDER
            .comment("What airfields take as fuel for their aircraft (item names). An item lets an engine burn as long as it burns in a furnace.")
            .defineListAllowEmpty("fuelItems", List.of("minecraft:charcoal", "minecraft:coal", "minecraft:coal_block"), () -> "minecraft:charcoal", name -> name instanceof String);
    private static final ModConfigSpec.IntValue FUEL_CAPACITY = BUILDER
            .comment("How much an aircraft's tank holds, in ticks of one engine burning (a piece of coal is 1600; an aircraft has two engines).",
                    "192000 is 80 minutes in the air, or 120 pieces of coal.")
            .defineInRange("fuelCapacity", 192000, 4800, 4000000);
    private static final ModConfigSpec.IntValue FUEL_STOCK = BUILDER
            .comment("An airfield with an aircraft of its own asks its colony for fuel when fewer than this many pieces lie on its racks (0: it never asks).")
            .defineInRange("fuelStock", 32, 0, 640);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static List<? extends String> fuelNames;
    private static java.util.Set<Item> fuelItems = java.util.Set.of();

    /** The items airfields take as fuel. */
    public static java.util.Set<Item> fuelItems() {
        final List<? extends String> names = FUEL_ITEMS.get();
        if (names != fuelNames) {
            final java.util.Set<Item> items = new java.util.LinkedHashSet<>();
            for (final String name : names) {
                final net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(name);
                final Item item = id == null ? net.minecraft.world.item.Items.AIR : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
                if (item != net.minecraft.world.item.Items.AIR) items.add(item);
            }
            fuelItems = items;
            fuelNames = names;
        }
        return fuelItems;
    }

    private static final boolean[] FETCH_FOR_CHECKS = {true};

    /** Whether flights that only fetch are made (the development checks of the older scenes switch them off). */
    public static boolean fetching() { return FETCH_FOR_CHECKS[0]; }

    /** For the development checks only. */
    public static void fetchingForChecks(boolean on) { FETCH_FOR_CHECKS[0] = on; }

    public static long fuelCapacity() { return FUEL_CAPACITY.get(); }
    public static int fuelStock() { return FUEL_STOCK.get(); }

    public static int ticksPerStack() { return TICKS_PER_STACK.get(); }
    public static int berthTicks() { return berthForChecks > 0 ? berthForChecks : BERTH_TICKS.get(); }
    public static int quietTicks() { return QUIET.get() * 20; }

    public static boolean pilotCarries() { return carriesForChecks != null ? carriesForChecks : PILOT_CARRIES.get(); }
    public static int carryStacks() { return CARRY_STACKS.get(); }

    private static int berthForChecks;
    private static Boolean carriesForChecks;

    /** For the development checks only: whether the pilot carries the goods (null: what the settings say). */
    public static void pilotCarriesForChecks(Boolean carries) {
        carriesForChecks = carries;
    }

    /** For the development checks only: a shorter berth than a whole day (0: what the settings say). */
    public static void berthForChecks(int ticks) {
        berthForChecks = ticks;
    }
    public static int importRange() { return IMPORT_RANGE.get(); }

    // ---- places -------------------------------------------------------------------------------------------

    /** The airfield that has this point of the network as one of its landing pads. */
    @Nullable
    public static BuildingAirfield airfieldAt(ServerLevel level, @Nullable BlockPos pad) {
        if (pad == null) return null;
        for (final IColony colony : IColonyManager.getInstance().getColonies(level)) {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
                if (building instanceof BuildingAirfield airfield && airfield.hasPad(pad)) return airfield;
        }
        return null;
    }

    @Nullable
    public static List<BuildingAirfield> facilitiesOf(ServerLevel level, int colonyId) {
        final IColony colony = IColonyManager.getInstance().getColonyByDimension(colonyId, level.dimension());
        if (colony == null) return List.of();
        final List<BuildingAirfield> all = new java.util.ArrayList<>();
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            if (building instanceof BuildingAirfield facility && facility.getBuildingLevel() >= 1) all.add(facility);
        // Airfields before anchorages, larger before smaller: the first that takes the class is tried first.
        all.sort(java.util.Comparator.comparingInt((BuildingAirfield f) -> f.facility().airfield() ? 0 : 1).thenComparingInt(f -> -f.facility().ordinal()));
        return all;
    }

    /**
     * Where an aircraft of class {@code ship} at {@code from} may land in the colony {@code colonyId}: the first of the
     * colony's places (airfields and anchorages) that takes the class and has a free pad with a way to it. When there is
     * none, the answer says why, the most telling first: there is a place for it but no free pad, or no floor; there is a
     * place but no way to it; no place in that colony takes the class at all ("flight.no_berth").
     */
    static Landing landingIn(ServerLevel level, RouteNetwork net, BlockPos from, int colonyId, @Nullable UUID self, com.asteain.airline.route.ShipClass ship) {
        String why = "flight.no_berth";
        for (final BuildingAirfield there : facilitiesOf(level, colonyId)) {
            final FlightController.Landing landing = FlightController.landing(level, net, from, there.pad(), self, false, ship);
            if (landing.pad() != null) return new Landing(there, landing);
            if (rank(landing.why()) > rank(why)) why = landing.why();
        }
        return new Landing(null, new FlightController.Landing(null, why));
    }

    private static int rank(String why) {
        return switch (why) {
            case "flight.no_free_pad" -> 3;
            case "flight.no_floor_there" -> 2;
            case "flight.no_route" -> 1;
            default -> 0;
        };
    }

    /** A place to land in another colony: the facility and the pad there (null when there is none, with why). */
    record Landing(@Nullable BuildingAirfield there, FlightController.Landing landing) {}

    /**
     * Whose racks and import store a place uses: an airfield its own; an anchorage, which has none (the user's ruling of
     * 2026-10-15), those of the colony's airfield nearest to its pad. Null for an anchorage whose colony has no built airfield.
     */
    @Nullable
    public static BuildingAirfield storeOf(BuildingAirfield place) {
        if (place.facility().airfield()) return place;
        BuildingAirfield best = null;
        for (final IBuilding building : place.getColony().getServerBuildingManager().getBuildings().values()) {
            if (!(building instanceof BuildingAirfield airfield) || !airfield.facility().airfield() || airfield.getBuildingLevel() < 1) continue;
            if (best == null || airfield.pad().distSqr(place.pad()) < best.pad().distSqr(place.pad())) best = airfield;
        }
        return best;
    }

    /** Nothing to take from and no room: the racks of an anchorage whose colony has no airfield. */
    private static final IItemHandler NO_RACKS = new net.neoforged.neoforge.items.ItemStackHandler(0);

    /** The export racks of an airfield (its hut chest and racks); for an anchorage, those of its colony's airfield ({@link #storeOf}). */
    public static IItemHandler racks(BuildingAirfield place) {
        final BuildingAirfield airfield = storeOf(place);
        return airfield == null ? NO_RACKS : airfield.getItemHandlerCap();
    }

    /**
     * The import store of an airfield: the nearest MineColonies stash of its colony within range of the pad or of the hut
     * (whichever is nearer; 2026-10-15: the large airfield's own stash lies 27 blocks from its pad, 12 from its hut). What is put
     * into a stash is fetched by the colony's couriers and brought to the warehouse. Null when there is none or it is not loaded.
     */
    @Nullable
    public static IItemHandler importStore(ServerLevel level, BuildingAirfield airfield) {
        final IBuilding stash = importBuilding(level, airfield);
        return stash == null ? null : stash.getItemHandlerCap();
    }

    /** The building that is the import store (see {@link #importStore}). */
    @Nullable
    public static IBuilding importBuilding(ServerLevel level, BuildingAirfield place) {
        // An anchorage has no import store of its own: its colony's airfield's.
        final BuildingAirfield airfield = storeOf(place);
        if (airfield == null) return null;
        final BlockPos pad = airfield.pad(), hut = airfield.getPosition();
        final double range = (double) importRange() * importRange();
        IBuilding best = null;
        double bestNear = Double.MAX_VALUE;
        for (final IBuilding building : airfield.getColony().getServerBuildingManager().getBuildings().values()) {
            final double near = Math.min(building.getPosition().distSqr(pad), building.getPosition().distSqr(hut));
            if (building == airfield || near > range) continue;
            if (!level.isLoaded(building.getPosition()) || !(level.getBlockEntity(building.getPosition()) instanceof TileEntityStash)) continue;
            if (near < bestNear) {
                best = building;
                bestNear = near;
            }
        }
        return best;
    }

    /** How many of an item an inventory would take right now, up to {@code max}. */
    public static int room(@Nullable IItemHandler in, Item item, int max) {
        if (in == null) return 0;
        int fits = 0;
        // Asked a stack at a time: a simulated insert does not remember the one before, so only the first stack is exact.
        final int stack = Math.min(max, item.getDefaultMaxStackSize());
        fits += stack - ItemHandlerHelper.insertItemStacked(in, new ItemStack(item, stack), true).getCount();
        if (fits < stack || max <= stack) return Math.min(fits, max);
        // More than a stack: count the free and the matching slots.
        int space = 0;
        for (int slot = 0; slot < in.getSlots() && space < max; slot++) {
            final ItemStack there = in.getStackInSlot(slot);
            if (there.isEmpty()) space += Math.min(in.getSlotLimit(slot), item.getDefaultMaxStackSize());
            else if (there.is(item) && there.getComponentsPatch().isEmpty()) space += Math.max(0, Math.min(in.getSlotLimit(slot), there.getMaxStackSize()) - there.getCount());
        }
        return Math.min(space, max);
    }

    /** The hold of an aircraft: its barrels, as one inventory. */
    static IItemHandler hold(Craft c) {
        final List<IItemHandlerModifiable> parts = new ArrayList<>();
        for (final BlockPos place : c.type.cargo) {
            final net.minecraft.world.level.block.entity.BlockEntity entity = c.level.getBlockEntity(c.inShip(place));
            if (entity instanceof Container container) parts.add(new InvWrapper(container));
            // A rack: its own slots (not those of the pair it may make with the rack beside it, which is in the hold as well).
            else if (entity instanceof com.minecolonies.api.tileentities.AbstractTileEntityRack rack) parts.add(rack.getInventory());
        }
        return new CombinedInvWrapper(parts.toArray(new IItemHandlerModifiable[0]));
    }

    // ---- moving -------------------------------------------------------------------------------------------

    public static int count(@Nullable IItemHandler in, Item item) {
        if (in == null) return 0;
        int sum = 0;
        for (int slot = 0; slot < in.getSlots(); slot++) if (in.getStackInSlot(slot).is(item)) sum += in.getStackInSlot(slot).getCount();
        return sum;
    }

    /** Everything in an inventory, by item. */
    public static Map<Item, Integer> contents(@Nullable IItemHandler in) {
        final Map<Item, Integer> all = new LinkedHashMap<>();
        if (in != null) for (int slot = 0; slot < in.getSlots(); slot++) {
            final ItemStack stack = in.getStackInSlot(slot);
            if (!stack.isEmpty()) all.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return all;
    }

    /**
     * Moves up to {@code max} of an item from one inventory to another. Only what fits is taken out; if something that
     * was taken out cannot be put in after all, it goes back where it came from.
     * @return how many were moved
     */
    public static int move(IItemHandler from, IItemHandler to, Item item, int max) {
        int moved = 0;
        for (int slot = 0; slot < from.getSlots() && moved < max; slot++) {
            if (!from.getStackInSlot(slot).is(item)) continue;
            final ItemStack offer = from.extractItem(slot, max - moved, true);
            if (offer.isEmpty()) continue;
            final int fits = offer.getCount() - ItemHandlerHelper.insertItemStacked(to, offer, true).getCount();
            if (fits <= 0) return moved;
            final ItemStack taken = from.extractItem(slot, fits, false);
            final ItemStack left = ItemHandlerHelper.insertItemStacked(to, taken, false);
            if (!left.isEmpty()) {
                final ItemStack stillLeft = ItemHandlerHelper.insertItemStacked(from, left, false);
                if (!stillLeft.isEmpty()) throw new IllegalStateException("could not put back " + stillLeft + " while moving cargo");
            }
            moved += taken.getCount() - left.getCount();
        }
        return moved;
    }

    static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static Item item(String name) {
        final ResourceLocation id = ResourceLocation.tryParse(name);
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
    }

    // ---- sending flights by the ledger --------------------------------------------------------------------

    /** What the dispatcher found worth flying: where to (and the pad to land on there), and how many wanted items lie ready. */
    /**
     * A flight worth making: {@code ready} items the other colony wants lie on the home racks, or, for a flight that only
     * fetches ({@code fetch}), so many items home wants lie on the other airfield's racks.
     */
    record Order(BuildingAirfield from, BuildingAirfield to, int ready, Mob pilot, BlockPos pad, boolean fetch) {}

    /**
     * A pilot of the airfield, present, that no other aircraft has on its way (flying, loading, unloading, going home):
     * the one this aircraft had last, if it is free, else the first free one. Null when there is none.
     */
    @Nullable
    static Mob freePilot(ServerLevel level, BuildingAirfield airfield, AircraftBlockEntity self) {
        // Its own pilot first; then one that flies no other aircraft; only then one whose aircraft stands idle (it may stay
        // at an anchorage far from here, 2026-10-18: a pilot was called from the anchorage to fly the airfield's aircraft).
        Mob unbound = null, idle = null;
        for (final ICitizenData citizen : airfield.getAllAssignedCitizen()) {
            if (citizen.getEntity().isEmpty()) continue;
            final Mob mob = citizen.getEntity().get();
            boolean busy = false, bound = false;
            for (final AircraftBlockEntity other : FlightController.loaded(level)) {
                if (other == self) continue;
                final boolean same = mob.getUUID().equals(other.pilot) || (other.pilotColony == citizen.getColony().getID() && other.pilotCitizen == citizen.getId());
                bound |= same;
                if (same && (other.cargo || other.flying() || other.homeward || other.phase != AircraftBlockEntity.Phase.PARKED)) busy = true;
            }
            if (busy) continue;
            if (mob.getUUID().equals(self.pilot)) return mob;
            if (!bound && unbound == null) unbound = mob;
            if (bound && idle == null) idle = mob;
        }
        return unbound != null ? unbound : idle;
    }

    /**
     * A round (2026-10-17): other colonies the same flight may carry goods to, besides the one chosen first: those with
     * wanted goods ready on home's export racks and a place to land with a way from home or from the first colony, the
     * ones with the most ready first.
     */
    static List<Order> moreStops(ServerLevel level, AircraftBlockEntity be, RouteNetwork net, UUID ship, Order first, int more) {
        final List<Order> found = new java.util.ArrayList<>();
        if (more <= 0) return found;
        final WantLedger ledger = WantLedger.get(level.getServer());
        final WantLedger.Key self = WantLedger.Key.of(first.from().getColony());
        final Map<Item, Integer> onRacks = contents(racks(first.from()));
        final com.asteain.airline.route.ShipClass cls = FlightController.shipClass(be);
        for (final WantLedger.Key other : ledger.keys()) {
            if (other.equals(self) || other.colony() == first.to().getColony().getID() || !other.dimension().equals(self.dimension())) continue;
            if (!com.asteain.airline.route.ColonyRoutes.drawn(level, self.colony(), other.colony())) continue;
            int ready = 0;
            for (final Map.Entry<Item, Integer> stock : onRacks.entrySet()) ready += Math.min(stock.getValue(), ledger.net(other, stock.getKey()));
            if (ready <= 0 || facilitiesOf(level, other.colony()).isEmpty()) continue;
            // A colony of a round need not be reached from home directly: by way of the first colony is as good (the
            // order of the round is checked for ways all round afterwards).
            Landing place = landingIn(level, net, be.home, other.colony(), ship, cls);
            if (place.there() == null) place = landingIn(level, net, first.pad(), other.colony(), ship, cls);
            if (place.there() != null) found.add(new Order(first.from(), place.there(), ready, first.pilot(), place.landing().pad(), false));
        }
        found.sort((a, b) -> Integer.compare(b.ready(), a.ready()));
        return found.size() > more ? new java.util.ArrayList<>(found.subList(0, more)) : found;
    }

    /**
     * Looks whether the aircraft that stands at home should fly: the colony with the most wanted items ready on the
     * export racks that it can fly to and land at, with a pilot present at home. Null when there is nothing to do.
     * Colonies it has goods for and a way to, but no pad to land on right now, are named in {@code barred}.
     */
    @Nullable
    static Order consider(ServerLevel level, AircraftBlockEntity be, RouteNetwork net, UUID ship, List<String> barred) {
        final BuildingAirfield home = airfieldAt(level, be.home);
        if (home == null || home.getBuildingLevel() < 1) return null;
        // The pilots are the airfield's (an aircraft whose home is an anchorage flies with a pilot of the colony's airfield).
        final BuildingAirfield crew = storeOf(home);
        final Mob pilot = crew == null ? null : freePilot(level, crew, be);
        if (pilot == null) return null;
        final WantLedger ledger = WantLedger.get(level.getServer());
        final WantLedger.Key self = WantLedger.Key.of(home.getColony());
        final Map<Item, Integer> onRacks = contents(racks(home));
        Order best = null;
        final com.asteain.airline.route.ShipClass cls = FlightController.shipClass(be);
        for (final WantLedger.Key other : ledger.keys()) {
            if (other.equals(self) || !other.dimension().equals(self.dimension())) continue;
            // Only to colonies a route is drawn to (2026-10-18: routes are drawn by players).
            if (!com.asteain.airline.route.ColonyRoutes.drawn(level, self.colony(), other.colony())) continue;
            int ready = 0;
            for (final Map.Entry<Item, Integer> stock : onRacks.entrySet()) ready += Math.min(stock.getValue(), ledger.net(other, stock.getKey()));
            if (ready <= 0 || (best != null && ready <= best.ready())) continue;
            if (facilitiesOf(level, other.colony()).isEmpty()) continue;
            final Landing place = landingIn(level, net, be.home, other.colony(), ship, cls);
            if (place.there() == null) {
                // No way there is the usual case and nothing to tell; a way without a pad to land on is, and so is a colony
                // with no place that takes aircraft of this class at all.
                final String why = place.landing().why();
                if (!why.equals("flight.no_route"))
                    barred.add("colony " + other.colony() + (why.equals("flight.no_free_pad") ? ": no landing pad is free there"
                            : why.equals("flight.no_berth") ? ": no place there takes " + cls.key() + " aircraft" : ": the landing pad there has no floor"));
                continue;
            }
            best = new Order(home, place.there(), ready, pilot, place.landing().pad(), false);
        }
        if (best != null || !fetching()) return best;
        // Nothing to bring anybody. But what home wants may lie on another airfield's racks (collected there because home
        // asked the network for it): then the flight goes out empty and fetches it. The other colony need not want
        // anything itself, so it is looked for among the colonies, not in the ledger; and its racks can only be looked
        // at while its airfield is loaded.
        for (final IColony colony : IColonyManager.getInstance().getColonies(level)) {
            if (colony == home.getColony() || !com.asteain.airline.route.ColonyRoutes.drawn(level, home.getColony().getID(), colony.getID())) continue;
            for (final BuildingAirfield there : facilitiesOf(level, colony.getID())) {
                if (!level.isLoaded(there.getPosition())) continue;
                int ready = 0;
                for (final Map.Entry<Item, Integer> stock : contents(racks(there)).entrySet()) ready += Math.min(stock.getValue(), ledger.net(self, stock.getKey()));
                if (ready <= 0 || (best != null && ready <= best.ready())) continue;
                final FlightController.Landing landing = FlightController.landing(level, net, be.home, there.pad(), ship, false, cls);
                if (landing.pad() == null) continue;
                best = new Order(home, there, ready, pilot, landing.pad(), true);
            }
        }
        return best;
    }

    /** One step of loading: up to a stack of one item that {@code forColony} still wants, from racks into the hold. */
    static int loadStep(ServerLevel level, AircraftBlockEntity be, Craft c, IItemHandler racks, WantLedger.Key forColony, Map<String, Integer> manifest) {
        final WantLedger ledger = WantLedger.get(level.getServer());
        final IItemHandler hold = hold(c);
        for (final Map.Entry<Item, Integer> stock : contents(racks).entrySet()) {
            final int want = Math.min(Math.min(stock.getValue(), ledger.net(forColony, stock.getKey())), stock.getKey().getDefaultMaxStackSize());
            if (want <= 0 || be.listedElsewhere(name(stock.getKey()))) continue;
            final int moved = move(racks, hold, stock.getKey(), want);
            if (moved <= 0) continue;
            ledger.cover(forColony, stock.getKey(), moved);
            manifest.merge(name(stock.getKey()), moved, Integer::sum);
            be.setChanged();
            return moved;
        }
        return 0;
    }

    /** One step of unloading: up to a stack of one of the listed items (or of anything when {@code only} is null) from the hold. */
    static int unloadStep(Craft c, @Nullable IItemHandler to, @Nullable Map<String, Integer> only) {
        if (to == null) return 0;
        final IItemHandler hold = hold(c);
        for (final Map.Entry<Item, Integer> stock : contents(hold).entrySet()) {
            if (only != null && !only.containsKey(name(stock.getKey()))) continue;
            final int moved = move(hold, to, stock.getKey(), Math.min(stock.getValue(), stock.getKey().getDefaultMaxStackSize()));
            if (moved > 0) return moved;
        }
        return 0;
    }

    static boolean grounded(Phase phase) {
        return phase == Phase.LOAD || phase == Phase.UNLOAD;
    }
}
