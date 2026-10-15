package com.asteain.airline.colony;

import com.asteain.airline.ExportMath;
import com.asteain.airline.ledger.WantLedger;
import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.IBuildingEventsModule;
import com.minecolonies.api.colony.buildings.workerbuildings.IWareHouse;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.Utils;
import com.minecolonies.api.util.WorldUtil;
import com.minecolonies.core.colony.buildings.modules.MinimumStockModule;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractWarehouseRequestResolver;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.util.TriConsumer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The export table of an airfield: for every listed item, how many stacks must stay in the colony's warehouses.
 * Whatever the warehouses hold beyond that is surplus, and surplus that another colony wants (see {@link WantLedger})
 * is requested onto the airfield's racks, where couriers bring it.
 *
 * <p>It reuses MineColonies' minimum stock module (table, save format, window and network messages) and replaces
 * what the table means: the parent asks for goods until the building holds the listed amount; this one never does.</p>
 *
 * <p>The same tick also tells the ledger what this colony itself is missing: the requests that ended up with the player,
 * as far as they name one item. They are only read, never taken over.</p>
 *
 * <p>Requests are ordinary MineColonies requests ({@link Stack}); nothing of this mod is written into the request
 * system's save data. A plain {@link Stack} is used rather than the parent's MinimumStack on purpose: for a MinimumStack
 * the warehouse looks up the requesting building without a null check, which would throw if such a request were still
 * around after this mod (and with it the airfield) has been removed.</p>
 */
public class ExportStockModule extends MinimumStockModule implements IBuildingEventsModule {
    private static final TypeToken<Stack> REQUEST_TYPE = TypeToken.of(Stack.class);

    /** No limit on the number of rows (the parent allows five per building level). */
    @Override
    public void addMinimumStock(ItemStack itemStack, int quantity) {
        if (itemStack.isEmpty() || quantity < 0) return;
        minimumStock.put(new ItemStorage(itemStack), quantity);
        markDirty();
    }

    @Override
    public void removeMinimumStock(ItemStack itemStack) {
        minimumStock.remove(new ItemStorage(itemStack));
        if (building != null && building.getColony() != null) {
            IRequestManager requests = building.getColony().getRequestManager();
            for (IRequest<?> open : openRequests(requests)) if (matches(stackOf(open), itemStack)) cancel(requests, open.getId());
        }
        markDirty();
    }

    /** The table: item to the number of stacks that stay in the warehouses. */
    public Map<ItemStorage, Integer> table() {
        return Collections.unmodifiableMap(minimumStock);
    }

    @Override
    public void onColonyTick(@NotNull IColony colony) {
        if (colony.getWorld() == null || colony.getWorld().isClientSide || colony.getWorld().getServer() == null) return;
        if (building.getBuildingLevel() < 1 || !WorldUtil.isBlockLoaded(colony.getWorld(), building.getPosition())) return;
        collect(colony);
    }

    private void collect(IColony colony) {
        IRequestManager requests = colony.getRequestManager();
        WantLedger ledger = WantLedger.get(colony.getWorld().getServer());
        WantLedger.Key self = WantLedger.Key.of(colony);
        ledger.setAutomatic(self, unmetWants(requests));

        // Requests that left the warehouse (to a crafter, the retry queue or the player) or whose row is gone are withdrawn.
        for (IRequest<?> open : openRequests(requests))
            if (rowFor(stackOf(open)) == null || !heldByWarehouse(requests, open.getId())) cancel(requests, open.getId());

        // Only for colonies this airfield can fly to: what is wanted where no line leads would only lie on the racks.
        java.util.Set<WantLedger.Key> reachable = reachable(colony, ledger, self);

        for (Map.Entry<ItemStorage, Integer> row : new ArrayList<>(minimumStock.entrySet())) {
            ItemStack item = row.getKey().getItemStack();
            if (item.isEmpty()) continue;
            Predicate<ItemStack> same = stack -> matches(stack, item);

            IRequest<?> open = null;
            for (IRequest<?> candidate : openRequests(requests)) if (same.test(stackOf(candidate))) { open = candidate; break; }

            int wanted = ledger.wantedByOthers(self, item.getItem(), reachable::contains);
            // Nobody else wants it, or this colony itself is short of it (never export what the colony is still asking for).
            if (wanted <= 0 || colonyIsShortOf(requests, item)) {
                if (open != null) cancel(requests, open.getId());
                continue;
            }
            if (open != null) continue; // one request per item at a time; the next one follows when this one has arrived

            int onRacks = InventoryUtils.getItemCountInItemHandler(building.getItemHandlerCap(), same);
            int inWarehouses = 0;
            for (IWareHouse warehouse : colony.getServerBuildingManager().getWareHouses())
                inWarehouses += InventoryUtils.hasBuildingEnoughElseCount(warehouse, same, Integer.MAX_VALUE);
            int quantity = ExportMath.nextRequest(wanted, onRacks, 0, inWarehouses, row.getValue(), item.getMaxStackSize());
            if (quantity <= 0) continue;

            // The last argument (false) is "cannot be resolved by the requesting building": without it the airfield's own
            // stock could "fulfil" the request and nothing would be fetched. It also marks the request as one of this table's.
            Stack request = new Stack(item.copyWithCount(1), false, true, ItemStackUtils.EMPTY, quantity, 1, false);
            IToken<?> token = building.createRequest(request, true);
            if (!heldByWarehouse(requests, token)) cancel(requests, token);
        }
    }

    /** For the checks: every colony in the ledger counts as one this airfield can fly to (they stand in for colonies with no airfield). */
    private static boolean reachAllForChecks;

    public static void reachAllForChecks(boolean all) {
        reachAllForChecks = all;
    }

    /**
     * The colonies (as the ledger knows them) whose airfield this airfield has a way to over the air network: the same
     * test a flight makes before it goes (a route from this airfield's pad to a pad of theirs). Worked out once per look.
     */
    /** For the checks: the colonies of the ledger this place counts as ones it can send goods to, now. */
    public java.util.Set<WantLedger.Key> reachableForChecks() {
        final IColony colony = building.getColony();
        if (!(colony.getWorld() instanceof net.minecraft.server.level.ServerLevel level)) return java.util.Set.of();
        return reachable(colony, WantLedger.get(level.getServer()), WantLedger.Key.of(colony));
    }

    private java.util.Set<WantLedger.Key> reachable(IColony colony, WantLedger ledger, WantLedger.Key self) {
        java.util.Set<WantLedger.Key> out = new java.util.HashSet<>();
        if (!(colony.getWorld() instanceof net.minecraft.server.level.ServerLevel level) || !(building instanceof BuildingAirfield home)) return out;
        com.asteain.airline.route.RouteNetwork net = com.asteain.airline.route.RouteNetwork.get(level);
        // For the class of the airfield's own aircraft (the largest it builds while it has none yet). (An anchorage has no
        // export list: it keeps no goods.)
        com.asteain.airline.route.ShipClass ship = com.asteain.airline.flight.Flights.ownClass(level, home);
        for (WantLedger.Key key : ledger.keys()) {
            if (key.equals(self) || !key.dimension().equals(self.dimension())) continue;
            if (reachAllForChecks) {
                out.add(key);
                continue;
            }
            facilities:
            for (BuildingAirfield there : com.asteain.airline.flight.Cargo.facilitiesOf(level, key.colony())) {
                for (net.minecraft.core.BlockPos pad : there.pads()) {
                    com.asteain.airline.route.RouteNetwork.Node node = net.node(pad);
                    if (node != null && node.berths(ship) && net.route(home.pad(), pad, ship).size() >= 2) {
                        out.add(key);
                        break facilities;
                    }
                }
            }
        }
        return out;
    }

    /**
     * What this colony cannot get by itself: the requests that wait for the player and ask for one named item.
     * (Requests for a tool class, food, a tag or a list of alternatives are left out: they do not name an item.)
     */
    private Map<Item, Integer> unmetWants(IRequestManager requests) {
        Map<Item, Integer> wants = new LinkedHashMap<>();
        for (IToken<?> token : new ArrayList<>(requests.getPlayerResolver().getAllAssignedRequests())) {
            IRequest<?> request = requestOrNull(requests, token);
            if (request == null || !(request.getRequest() instanceof Stack stack) || stack.getStack().isEmpty()) continue;
            if (request.getRequester().getId().equals(building.getRequester().getId())) continue; // the airfield's own
            wants.merge(stack.getStack().getItem(), stack.getCount(), Integer::sum);
        }
        return wants;
    }

    /** A colony without an airfield cannot receive air freight: its automatic wants go (another airfield would write them again). */
    @Override
    public void onDestroyed() {
        IColony colony = building.getColony();
        if (colony == null || colony.getWorld() == null || colony.getWorld().isClientSide || colony.getWorld().getServer() == null) return;
        WantLedger.get(colony.getWorld().getServer()).setAutomatic(WantLedger.Key.of(colony), Map.of());
    }

    /** True when some request of this colony for the item sits with the player or the retry queue: the colony lacks it. */
    private boolean colonyIsShortOf(IRequestManager requests, ItemStack item) {
        List<IToken<?>> unresolved = new ArrayList<>(requests.getPlayerResolver().getAllAssignedRequests());
        unresolved.addAll(requests.getRetryingRequestResolver().getAllAssignedRequests());
        for (IToken<?> token : unresolved) {
            IRequest<?> request = requestOrNull(requests, token);
            if (request == null || !(request.getRequest() instanceof IDeliverable deliverable)) continue;
            if (request.getRequester().getId().equals(building.getRequester().getId())) continue; // the airfield's own
            if (deliverable.matches(item)) return true;
        }
        return false;
    }

    private List<IRequest<?>> openRequests(IRequestManager requests) {
        Collection<IToken<?>> tokens = building.getOpenRequestsByRequestableType().get(REQUEST_TYPE);
        if (tokens == null || tokens.isEmpty()) return List.of();
        List<IRequest<?>> out = new ArrayList<>();
        for (IToken<?> token : new ArrayList<>(tokens)) {
            IRequest<?> request = requestOrNull(requests, token);
            if (request != null && request.getRequest() instanceof Stack stack && !stack.canBeResolvedByBuilding()) out.add(request);
        }
        return out;
    }

    private static IRequest<?> requestOrNull(IRequestManager requests, IToken<?> token) {
        try {
            return requests.getRequestForToken(token);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static boolean heldByWarehouse(IRequestManager requests, IToken<?> token) {
        try {
            IRequestResolver<?> resolver = requests.getResolverForRequest(token);
            return resolver instanceof AbstractWarehouseRequestResolver;
        } catch (RuntimeException unassigned) {
            return false;
        }
    }

    private static void cancel(IRequestManager requests, IToken<?> token) {
        requests.updateRequestState(token, RequestState.CANCELLED);
    }

    private static ItemStack stackOf(IRequest<?> request) {
        return ((Stack) request.getRequest()).getStack();
    }

    private Integer rowFor(ItemStack stack) {
        for (Map.Entry<ItemStorage, Integer> row : minimumStock.entrySet()) if (matches(stack, row.getKey().getItemStack())) return row.getValue();
        return null;
    }

    private static boolean matches(ItemStack stack, ItemStack item) {
        return !ItemStackUtils.isEmpty(stack) && ItemStackUtils.compareItemStacksIgnoreStackSize(stack, item, false, true);
    }

    /**
     * Everything on the export racks stays: a courier picking up at the airfield is told to leave every stack.
     * (The parent would keep only the listed amounts.)
     */
    @Override
    public void alterItemsToBeKept(TriConsumer<Predicate<ItemStack>, Integer, Boolean> consumer) {
        consumer.accept(stack -> true, Integer.MAX_VALUE, false);
    }

    /** Same wire format as the parent, without its row limit. */
    @Override
    public void serializeToView(@NotNull RegistryFriendlyByteBuf buf) {
        buf.writeInt(minimumStock.size());
        for (Map.Entry<ItemStorage, Integer> row : minimumStock.entrySet()) {
            Utils.serializeCodecMess(buf, row.getKey().getItemStack());
            buf.writeInt(row.getValue());
        }
        buf.writeBoolean(false);
    }
}
