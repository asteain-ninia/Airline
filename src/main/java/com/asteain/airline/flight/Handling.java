package com.asteain.airline.flight;

import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.flight.FlightController.Runtime;
import com.asteain.airline.ledger.WantLedger;
import com.asteain.airline.route.RouteNetwork;
import com.asteain.colonypatchserver.boarding.Boarding;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IBooleanConditionSupplier;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IStateSupplier;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.ITickRateStateMachine;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Moving the goods between an airfield and an aircraft that stands fixed on its pad. By the ruling of 2026-10-11 the
 * pilot does this on foot: to the racks, take, to the aircraft, put down, and back. (Between warehouse and airfield
 * the couriers carry, as before.) With the setting switched off nobody carries: the goods move by themselves.
 *
 * <p>While the pilot carries, its own decisions (work, food, sleep) are held back the way ColonyPatchServer's boarding
 * does it, and this class tells it where to walk. That works the same at home and at an airfield a flight away, where
 * the pilot's own job would not run. A pilot asleep in its bed at home is left asleep: the loading waits.</p>
 *
 * <p>Nothing is made or lost. What the pilot holds is written down on the aircraft's own block (flow, item, count), so a
 * restart in the middle of a walk goes on where it was. The lists of a flight count goods in the hold and in the
 * pilot's hands alike; if the hands come up short (the pilot died), the lists and the ledger are told.</p>
 */
final class Handling {
    private Handling() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    /** With a carrying pilot the work is looked at this often (ticks). */
    private static final int LOOK = 5;
    /** Near enough to a rack, the import store or the hold to take and put down (blocks, from the pilot's feet). */
    private static final double REACH = 2.6;
    /** A walk that has not arrived after this many looks is given up: the goods are handed over from where the pilot stands. */
    private static final int WALK_LOOKS = 240;
    /** The hold on the pilot's own decisions ends this many ticks after the last look. */
    private static final int HOLD_TICKS = 40;
    /** An administrator is told when the goods have waited this many looks for somebody to carry them. */
    private static final int WAIT_LOOKS = 480;

    /** One stream of goods between an airfield and the hold. */
    enum Flow {
        /** At home: what the destination wants, from the export racks into the hold. */
        LOAD_OUT(true),
        /** Away: what was brought, from the hold into that airfield's import store. */
        UNLOAD_THERE(false),
        /** Away: what home wants, from that airfield's export racks into the hold. */
        LOAD_BACK(true),
        /** At home: what was brought for home, from the hold into the import store. */
        UNLOAD_HOME(false),
        /** At home: what came back undelivered (or never left), from the hold onto the export racks. */
        PUT_BACK(false),
        /** At home: whatever else lies in the hold (of a flight that ended on its way), onto the export racks. No list, no ledger. */
        CLEAR_OUT(false),
        /** Fuel, from the racks of the airfield the aircraft stands at into its tank. No list, no ledger. See {@link Fuel}. */
        FUEL(true);

        final boolean loading;

        Flow(boolean loading) {
            this.loading = loading;
        }

        @Nullable
        static Flow of(String name) {
            for (final Flow flow : values()) if (flow.name().equals(name)) return flow;
            return null;
        }
    }

    private record Take(Item item, int count) {}

    // ---- what a flow is made of ---------------------------------------------------------------------------

    private static Map<String, Integer> list(AircraftBlockEntity be, Flow flow) {
        return flow == Flow.LOAD_BACK || flow == Flow.UNLOAD_HOME ? be.returnManifest : be.manifest;
    }

    private static WantLedger.Key forColony(ServerLevel level, AircraftBlockEntity be, Flow flow) {
        return new WantLedger.Key(level.dimension().location().toString(), flow == Flow.LOAD_BACK || flow == Flow.UNLOAD_HOME ? be.homeColony : be.freightColony);
    }

    /** Whether the ledger still counts the goods of this flow's list as on their way to somebody. */
    private static boolean covered(AircraftBlockEntity be, Flow flow) {
        return list(be, flow) == be.returnManifest || !be.settled;
    }

    /** The airfield's side of a flow: its export racks, or its import store (null when there is none). */
    @Nullable
    private static IItemHandler shore(ServerLevel level, BuildingAirfield here, Flow flow) {
        return flow == Flow.UNLOAD_THERE || flow == Flow.UNLOAD_HOME ? Cargo.importStore(level, here) : Cargo.racks(here);
    }

    /** Listed goods that are still in the hold. */
    static int left(Craft c, Map<String, Integer> list) {
        final IItemHandler hold = Cargo.hold(c);
        int sum = 0;
        for (final Map.Entry<String, Integer> line : list.entrySet()) sum += Math.min(line.getValue(), Cargo.count(hold, Cargo.item(line.getKey())));
        return sum;
    }

    // ---- called by the controller -------------------------------------------------------------------------

    /** Whether this tick of an aircraft that is being loaded or unloaded is one of those in between two looks. */
    static boolean between(AircraftBlockEntity be) {
        return be.timer % (Cargo.pilotCarries() ? LOOK : Cargo.ticksPerStack()) != 0;
    }

    /**
     * One look at one flow. @return true while goods of this flow are being moved or wait to be moved; false when there
     * is nothing (more) of it to move right now
     */
    static boolean move(ServerLevel level, AircraftBlockEntity be, Runtime rt, Flow flow) {
        if (be.at == null) return false;
        final BuildingAirfield here = Cargo.airfieldAt(level, be.at);
        if (!Cargo.pilotCarries()) return here != null && direct(level, be, rt.craft, here, flow) > 0;
        return work(level, be, rt, here, flow);
    }

    /** Nobody carries: up to a stack of this flow moves by itself. @return how many items */
    private static int direct(ServerLevel level, AircraftBlockEntity be, Craft c, BuildingAirfield here, Flow flow) {
        if (flow == Flow.FUEL) {
            final Map.Entry<Item, Integer> fuel = Fuel.next(be, Cargo.racks(here), 1);
            return fuel == null ? 0 : Cargo.move(Cargo.racks(here), Fuel.tank(be), fuel.getKey(), fuel.getValue());
        }
        if (flow.loading) {
            if (flow == Flow.LOAD_BACK && here.getBuildingLevel() < 1) return 0;
            return Cargo.loadStep(level, be, c, Cargo.racks(here), forColony(level, be, flow), list(be, flow));
        }
        return Cargo.unloadStep(c, shore(level, here, flow), flow == Flow.CLEAR_OUT ? null : list(be, flow));
    }

    /** Away from home with nothing to carry: the pilot waits on its seat, where its own decisions are held too. */
    static void rest(ServerLevel level, AircraftBlockEntity be, Runtime rt) {
        final Mob pilot = FlightController.pilot(level, be);
        if (pilot == null || Boarding.state(pilot) != Boarding.State.NONE) {
            release(rt);      // nobody there, or the boarding looks after the pilot
            return;
        }
        // Until it sits (the boarding may refuse for a while) its decisions stay held: left to itself it would set out for home.
        if (pilot instanceof AbstractEntityCitizen citizen && !citizen.getCitizenSleepHandler().isAsleep()) hold(level, rt, citizen);
        if (rt.age % 40 < LOOK) Boarding.board(pilot, rt.craft.id(), rt.craft.inShip(rt.craft.type.seat));
    }

    /** Whether the pilot lies asleep (in its bed, at home): a flight does not begin by dragging it out. */
    static boolean pilotAsleep(ServerLevel level, AircraftBlockEntity be) {
        return FlightController.pilot(level, be) instanceof AbstractEntityCitizen citizen && citizen.getCitizenSleepHandler().isAsleep();
    }

    /**
     * Ends a walk at once (a flight is called off where the aircraft stands): what the pilot holds is put where it was
     * going, and what does not fit there goes back where it came from, without the walking.
     */
    static void finishNow(ServerLevel level, AircraftBlockEntity be, Runtime rt) {
        final BuildingAirfield here = be.at == null ? null : Cargo.airfieldAt(level, be.at);
        final IItemHandler hands = FlightController.pilot(level, be) instanceof AbstractEntityCitizen citizen ? citizen.getInventoryCitizen() : null;
        for (int tries = 0; tries < 2 && be.carryCount > 0; tries++) putDown(level, be, rt, here, hands);
        if (be.carryCount > 0) {
            // Neither end takes it: it no longer counts as cargo of this flight and is laid down at the pilot's feet
            // (kept in the pilot's pockets it would fill them up, and a pilot with full pockets carries nothing).
            final Flow of = Flow.of(be.carryFlow);
            final Item item = Cargo.item(be.carryItem);
            if (of != null && of != Flow.CLEAR_OUT) forget(level, be, of, item, be.carryCount);
            if (hands != null && FlightController.pilot(level, be) instanceof Mob pilot) {
                int left = Math.min(be.carryCount, Cargo.count(hands, item));
                for (int slot = 0; slot < hands.getSlots() && left > 0; slot++) {
                    if (!hands.getStackInSlot(slot).is(item)) continue;
                    final net.minecraft.world.item.ItemStack out = hands.extractItem(slot, left, false);
                    left -= out.getCount();
                    net.minecraft.world.Containers.dropItemStack(level, pilot.getX(), pilot.getY(), pilot.getZ(), out);
                }
            }
            LOGGER.warn("[Airline/flight] {} x {} were laid down at the pilot's feet: neither the airfield nor the hold had room when the flight ended.", be.carryCount, be.carryItem);
            clear(be);
        }
        release(rt);
    }

    /** What the pilot's cargo handling has done since the aircraft was loaded: walks that ended with goods put down, and walks given up. */
    static int[] record(Runtime rt) {
        return new int[] {rt.trips, rt.forced};
    }

    // ---- the pilot's work ---------------------------------------------------------------------------------

    private static boolean work(ServerLevel level, AircraftBlockEntity be, Runtime rt, @Nullable BuildingAirfield here, Flow flow) {
        final Craft c = rt.craft;
        final boolean carrying = be.carryCount > 0;
        final Take plan = carrying || here == null ? null : plan(level, be, c, here, flow);
        if (!carrying && plan == null) {
            release(rt);
            rt.waitLooks = 0;
            return false;
        }
        // There is something to carry: the pilot is needed.
        Mob mob = FlightController.pilot(level, be);
        if (mob == null && be.at.equals(be.home) && here != null) mob = adopt(be, here);
        if (!(mob instanceof AbstractEntityCitizen citizen)) return waiting(be, rt, "flight.note.nobody_carries");
        final Boarding.State state = Boarding.state(citizen);
        if (state != Boarding.State.NONE) {
            // Still on the seat (or on the way to or from it): asked to get off, now and then.
            if (state == Boarding.State.SEATED && rt.age % 20 < LOOK) Boarding.leave(citizen, FlightController.leaveSpot(RouteNetwork.get(level), be.at, false));
            return waiting(be, rt, "flight.note.nobody_carries");
        }
        if (citizen.getCitizenSleepHandler().isAsleep()) return waiting(be, rt, "flight.note.nobody_carries");
        hold(level, rt, citizen);

        if (carrying) {
            final Flow of = Flow.of(be.carryFlow);
            // A loading that is no longer wanted (the flight was called off before it left): back onto the racks.
            if (of != null && of.loading && of != flow) be.carryReturning = true;
            final boolean toHold = of == null || of.loading != be.carryReturning;
            final Vec3 target = toHold || here == null ? holdSpot(c, citizen) : shoreSpot(level, here, of, Cargo.item(be.carryItem), false);
            if (arrive(citizen, rt, target, be)) putDown(level, be, rt, here, citizen.getInventoryCitizen());
            return true;
        }

        final IItemHandler hands = citizen.getInventoryCitizen();
        if (Cargo.room(hands, plan.item(), 1) <= 0) {
            // The pilot's pockets are full (of things of its own): it cannot carry. The goods move without it, and
            // an administrator is told once; otherwise the flight would wait for ever.
            if (!rt.toldFullHands) FlightController.tell(be, "flight.note.pilot_full_hands");
            rt.toldFullHands = true;
            rt.forced++;
            release(rt);
            return direct(level, be, c, here, flow) > 0;
        }
        final Vec3 source = flow.loading ? shoreSpot(level, here, flow, plan.item(), true) : holdSpot(c, citizen);
        if (!arrive(citizen, rt, source, be)) return true;
        // There: take, as much as was planned and as the hands can hold.
        final int n = Math.min(plan.count(), Cargo.room(hands, plan.item(), plan.count()));
        final int moved = n <= 0 ? 0 : Cargo.move(flow.loading ? Cargo.racks(here) : Cargo.hold(c), hands, plan.item(), n);
        if (moved > 0) {
            if (flow.loading && flow != Flow.FUEL) {
                // Taken for the aircraft: from here on it counts as loaded, in the pilot's hands or in the hold.
                WantLedger.get(level.getServer()).cover(forColony(level, be, flow), plan.item(), moved);
                list(be, flow).merge(Cargo.name(plan.item()), moved, Integer::sum);
            }
            be.carryFlow = flow.name();
            be.carryItem = Cargo.name(plan.item());
            be.carryCount = moved;
            be.carryReturning = false;
            rt.waitLooks = 0;
            be.setChanged();
        } else {
            waiting(be, rt, "flight.note.nobody_carries");
        }
        return true;
    }

    /** What to take next for a flow, or null: an item the flow moves, as many as the pilot carries and as fit at the other end. */
    @Nullable
    private static Take plan(ServerLevel level, AircraftBlockEntity be, Craft c, BuildingAirfield here, Flow flow) {
        final int stacks = Cargo.carryStacks();
        final IItemHandler hold = Cargo.hold(c);
        if (flow == Flow.FUEL) {
            final Map.Entry<Item, Integer> fuel = Fuel.next(be, Cargo.racks(here), stacks);
            return fuel == null ? null : new Take(fuel.getKey(), fuel.getValue());
        }
        if (flow.loading) {
            if (flow == Flow.LOAD_BACK && here.getBuildingLevel() < 1) return null;
            final WantLedger ledger = WantLedger.get(level.getServer());
            final WantLedger.Key wants = forColony(level, be, flow);
            for (final Map.Entry<Item, Integer> stock : Cargo.contents(Cargo.racks(here)).entrySet()) {
                final Item item = stock.getKey();
                int n = Math.min(Math.min(stock.getValue(), ledger.net(wants, item)), stacks * item.getDefaultMaxStackSize());
                if (n > 0) n = Math.min(n, Cargo.room(hold, item, n));
                if (n > 0) return new Take(item, n);
            }
            return null;
        }
        final IItemHandler to = shore(level, here, flow);
        if (to == null) return null;
        final Map<String, Integer> list = list(be, flow);
        for (final Map.Entry<Item, Integer> stock : Cargo.contents(hold).entrySet()) {
            final Item item = stock.getKey();
            if (flow != Flow.CLEAR_OUT && !list.containsKey(Cargo.name(item))) continue;
            int n = Math.min(stock.getValue(), stacks * item.getDefaultMaxStackSize());
            n = Math.min(n, Cargo.room(to, item, n));
            if (n > 0) return new Take(item, n);
        }
        return null;
    }

    /**
     * The pilot stands where the goods in its hands go: they are put down. What does not fit is carried back where it
     * came from. Hands that hold less than was written down lost the rest: the flight's list and the ledger are told.
     */
    private static void putDown(ServerLevel level, AircraftBlockEntity be, Runtime rt, @Nullable BuildingAirfield here, @Nullable IItemHandler hands) {
        final Flow of = Flow.of(be.carryFlow);
        final Item item = Cargo.item(be.carryItem);
        if (of == null) {
            clear(be);
            return;
        }
        final int inHands = Math.min(be.carryCount, Cargo.count(hands, item)), lost = be.carryCount - inHands;
        if (lost > 0) {
            if (of != Flow.CLEAR_OUT && of != Flow.FUEL) forget(level, be, of, item, lost);
            LOGGER.warn("[Airline/flight] {} x {} were lost on the way between the airfield and the hold.", lost, be.carryItem);
        }
        final boolean toHold = of.loading != be.carryReturning;
        final IItemHandler to = toHold ? (of == Flow.FUEL ? Fuel.tank(be) : Cargo.hold(rt.craft)) : here == null ? null : shore(level, here, of);
        final int put = to == null || hands == null || inHands <= 0 ? 0 : Cargo.move(hands, to, item, inHands);
        // Loaded goods that are back on the racks are not loaded any more.
        if (put > 0 && of.loading && be.carryReturning && of != Flow.FUEL) forget(level, be, of, item, put);
        if (put > 0) rt.trips++;
        final int rest = inHands - put;
        if (rest <= 0) {
            clear(be);
        } else {
            be.carryCount = rest;
            be.carryReturning = true;      // on the way back already, when it was: tried again there with the next look
        }
        be.setChanged();
    }

    /** Goods of a flow's list are gone from it (lost, or put back): the list and, while it still counts them, the ledger. */
    private static void forget(ServerLevel level, AircraftBlockEntity be, Flow of, Item item, int count) {
        final Map<String, Integer> list = list(be, of);
        final String name = Cargo.name(item);
        final int listed = list.getOrDefault(name, 0), less = Math.min(listed, count);
        if (less <= 0) return;
        if (listed > less) list.put(name, listed - less);
        else list.remove(name);
        if (covered(be, of)) WantLedger.get(level.getServer()).cover(forColony(level, be, of), item, -less);
    }

    private static void clear(AircraftBlockEntity be) {
        be.carryFlow = "";
        be.carryItem = "";
        be.carryCount = 0;
        be.carryReturning = false;
    }

    /** Goods wait for somebody to carry them. Always true (the flow is not over); an administrator is told when it lasts. */
    private static boolean waiting(AircraftBlockEntity be, Runtime rt, String key) {
        if (++rt.waitLooks == WAIT_LOOKS) FlightController.tell(be, key);
        return true;
    }

    /** At home a pilot that is gone for good is replaced by whoever works at the airfield now. */
    @Nullable
    private static Mob adopt(AircraftBlockEntity be, BuildingAirfield here) {
        for (final ICitizenData citizen : here.getAllAssignedCitizen()) {
            if (citizen.getEntity().isEmpty() || !citizen.getEntity().get().isAlive()) continue;
            FlightController.setPilot(be, citizen.getEntity().get());
            be.setChanged();
            return citizen.getEntity().get();
        }
        return null;
    }

    // ---- walking ------------------------------------------------------------------------------------------

    /** Where to stand to work the hold: the nearer of the aircraft's access places, as it lies in the world now. */
    private static Vec3 holdSpot(Craft c, Mob pilot) {
        Vec3 best = c.pos();
        double nearest = Double.MAX_VALUE;
        for (final BlockPos place : c.type.access) {
            final Vec3 spot = c.toWorld(Vec3.atBottomCenterOf(c.inShip(place)));
            final double distance = spot.distanceToSqr(pilot.position());
            if (distance < nearest) {
                nearest = distance;
                best = spot;
            }
        }
        return best;
    }

    /** Where to stand at the airfield: the rack that holds the item (taking) or has room for it (putting down), or the import store. */
    private static Vec3 shoreSpot(ServerLevel level, BuildingAirfield here, Flow flow, Item item, boolean taking) {
        if (flow == Flow.UNLOAD_THERE || flow == Flow.UNLOAD_HOME) {
            final IBuilding store = Cargo.importBuilding(level, here);
            return Vec3.atBottomCenterOf(store == null ? here.getPosition() : store.getPosition());
        }
        for (final BlockPos rack : here.getContainers()) {
            if (!level.isLoaded(rack)) continue;
            final IItemHandler in = level.getCapability(Capabilities.ItemHandler.BLOCK, rack, null);
            if (in != null && (taking ? Cargo.count(in, item) > 0 : Cargo.room(in, item, 1) > 0)) return Vec3.atBottomCenterOf(rack);
        }
        return Vec3.atBottomCenterOf(here.getPosition());
    }

    /**
     * Sends the pilot toward a place. @return true when it stands near enough (or when the walk was given up: then the
     * goods change hands from where the pilot is, and the record says so)
     */
    private static boolean arrive(AbstractEntityCitizen citizen, Runtime rt, Vec3 target, AircraftBlockEntity be) {
        if (rt.walkTarget == null || rt.walkTarget.distanceToSqr(target) > 0.25) {
            rt.walkTarget = target;
            rt.walkLooks = 0;
        }
        if (citizen.position().distanceToSqr(target) <= REACH * REACH) {
            citizen.getNavigation().stop();
            rt.walkTarget = null;
            return true;
        }
        if (++rt.walkLooks > WALK_LOOKS) {
            rt.forced++;
            rt.walkTarget = null;
            LOGGER.warn("[Airline/flight] the pilot did not reach {} within {} ticks (it stands at {}): the goods change hands from there.", target, WALK_LOOKS * LOOK, citizen.position());
            if (rt.forced == 1) FlightController.tell(be, "flight.note.pilot_cannot_walk");
            return true;
        }
        if (citizen.getNavigation().isDone() || rt.walkLooks % 4 == 1) EntityNavigationUtils.walkToPos(citizen, BlockPos.containing(target), 1, false, 1.0);
        return false;
    }

    // ---- the hold on the pilot's own decisions ------------------------------------------------------------

    /**
     * Holds the pilot's own decisions for as long as this class keeps looking: a blocking entry in MineColonies' own
     * decision machine that answers "stay in the state you are in" (the same means as ColonyPatchServer's boarding).
     * It lapses by itself when the looks stop.
     */
    private static void hold(ServerLevel level, Runtime rt, AbstractEntityCitizen citizen) {
        rt.heldAt = level.getGameTime();
        if (rt.held == citizen) return;
        release(rt);
        if (!(citizen instanceof EntityCitizen worker)) return;
        final ITickRateStateMachine<IState> decisions = worker.getCitizenAI();
        final IBooleanConditionSupplier active = () -> rt.held == citizen && level.getGameTime() - rt.heldAt <= HOLD_TICKS;
        final IStateSupplier<IState> stay = decisions::getState;
        final AIEventTarget<IState> entry = new AIEventTarget<>(AIBlockingEventType.AI_BLOCKING, active, stay, 1);
        decisions.addTransition(entry);
        rt.held = citizen;
        rt.hold = entry;
    }

    @SuppressWarnings("unchecked")
    static void release(Runtime rt) {
        final AbstractEntityCitizen held = rt.held;
        final Object entry = rt.hold;
        rt.held = null;
        rt.hold = null;
        rt.walkTarget = null;
        if (held instanceof EntityCitizen worker && entry instanceof AIEventTarget<?> target) {
            try {
                worker.getCitizenAI().removeTransition((AIEventTarget<IState>) target);
            } catch (RuntimeException e) {
                // The entry answers "no" from now on (nobody is held), so leaving it behind is harmless.
            }
        }
    }
}
