package com.asteain.airline.flight;

import com.asteain.airline.Airline;
import com.asteain.airline.colony.JobPilot;
import com.asteain.airline.flight.AircraftBlockEntity.Phase;
import com.asteain.airline.colony.BuildingAirfield;
import com.asteain.airline.flight.Flights.Answer;
import com.asteain.airline.ledger.WantLedger;
import com.asteain.airline.route.RouteNetwork;
import com.asteain.airline.route.RouteNetwork.Edge;
import com.asteain.airline.route.RouteRules;
import com.asteain.airline.route.Tube;
import com.asteain.colonypatchserver.boarding.Boarding;
import com.minecolonies.api.colony.ICivilianData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs every aircraft: called once a tick by the aircraft's own block. Only loaded when Sable, Create Aeronautics and
 * ColonyPatchServer are installed. Server thread only.
 *
 * <p>On the ground the ship is kept fixed to the world and carries no loading ticket. For a flight the pilot is sent to
 * the seat (ColonyPatchServer's boarding); once seated the ship is released, gets Sable's loading ticket, climbs to the
 * line, flies it point by point while looking ahead, turns to the heading of the destination pad, goes down, is fixed
 * again, and the pilot walks off. The autopilot only works while the pilot sits on the seat.</p>
 *
 * <p>Several aircraft fly at once. Two things keep them apart: a pad takes one aircraft (the pad a flight will land on
 * is chosen before it leaves and counts as taken from then on; see {@link #free}), and an aircraft only leaves the
 * ground when its way keeps clear of every flight that is under way (see {@link Airspace}).</p>
 */
final class FlightController {
    private FlightController() {}

    static final Logger LOGGER = LogUtils.getLogger();
    /** An aircraft that stands at home is looked over this often (ticks). */
    private static final int MAINTAIN_EVERY = 200;
    /** A flight is called off when the pilot is not seated this many ticks after being sent to the aircraft. */
    private static final int BOARDING_LIMIT = 1500;
    /** An administrator is told when the pilot has not got off this many ticks after landing. */
    private static final int LEAVING_LIMIT = 1300;
    private static final int TURNAROUND_TICKS = 200, HOLD_TICKS = 200, RETRY_LEAVING = 300;
    /** In the air without a seated pilot the aircraft waits fixed this long for the pilot to sit again, then gives up. */
    private static final int PAUSE_LIMIT = 1200;
    /** After a failure of the controller on a pad the aircraft rests this long, then takes up its work again. */
    private static final int FAULT_REST = 600;
    /** An aircraft that wants to fly home looks for a pilot and a way this often (ticks), and keeps itself loaded for this many looks. */
    private static final int HOME_LOOK = 100, HOME_TRIES = 240;
    /** At home, what lies in the hold of an aircraft without a flight is carried back onto the racks; tried again this often (ticks). */
    private static final int CLEAR_OUT_EVERY = 1200;
    /**
     * How often an aircraft whose line is barred by another ship waits there before it goes round: back to the point
     * behind, and on over other lines (ruling of 2026-10-11: go round rather than turn back). Of two aircraft that bar each
     * other the one with the lower id goes round first; the other waits twice as long. When there is no way round, it
     * waits twice as long again, and then a flight on its way out is given up and goes back to where it started.
     */
    private static final int SHIP_HOLDS = 3;
    /**
     * For the plans of flights (see {@link Airspace}), as measured in the checks: blocks per second straight up (11.6 blocks
     * in 47 seconds), ticks to be placed over a pad (470 to 520), ticks lost at every point of the way to starting and
     * stopping (a line of 64 blocks takes 49 seconds, 15 more than at top speed).
     */
    /** Seated and waiting for the air to be free, an aircraft looks again this often (ticks). */
    private static final int AIR_LOOK = 40;

    private static final Map<UUID, AircraftBlockEntity> LOADED = new HashMap<>();

    /** The controller's working state for one aircraft; not saved. */
    static final class Runtime {
        /** The colony was told that the flight waits for fuel. */
        boolean toldNoFuel;
        final Craft craft;
        final Autopilot pilot;
        boolean fresh = true, faulted, toldWaiting, paused;
        int pausedFor, rest;
        int age, still, tries, placeStep;
        /** Loading for a round: the colonies looked at in a row with nothing (more) to load for them. */
        int idleStops;
        /** Placing over a pad: the heading at a right angle to the pad's that is gone across in, how far to the right of the middle the centre of mass is put then, and how often that was done again. */
        double acrossHeading, acrossAim;
        int alignRounds;
        Vec3 from = Vec3.ZERO, to = Vec3.ZERO;
        double heading;
        Phase resume = Phase.RETREAT;
        /** For the record: nearest any block of the ship's box came to a buoy block, largest distance from a line, how far from the last point it stopped. */
        double closestBuoy = Double.MAX_VALUE, lastMiss, lastHeadingError;
        /** Cargo handling by the pilot (see Handling): whose decisions are held and by what, where the pilot walks, and the record. */
        @Nullable AbstractEntityCitizen held;
        @Nullable Object hold;
        long heldAt;
        @Nullable Vec3 walkTarget;
        int walkLooks, waitLooks, trips, forced;
        boolean toldFullHands;
        /** Looks of an aircraft that wants to fly home without finding a pilot and a way; when the hold was last cleared out at home (game time). */
        int homeTries;
        long clearedAt;
        /** Seated and kept on the ground because another flight is in the way; told that no pad is free; waits on one line because of a ship. */
        boolean heldForAir, toldNoPad;
        /**
         * Joining the pier's connector at an anchorage: ticks since the ship's connector was let out (0: not begun; -1:
         * over, joined or not), and ticks spent letting go of it before leaving.
         */
        int dockTicks, undockTicks;
        /** The lever moves the pilot was last shown making (see the arm in the tick), and when. */
        int writesSeen, swungAt;
        int shipHolds;
        /** Lines it found another ship on and goes round, until it has flown a line to its end or landed. */
        final java.util.Set<Edge> avoid = new java.util.HashSet<>();

        Runtime(Craft craft) {
            this.craft = craft;
            this.pilot = new Autopilot(craft);
        }
    }

    static List<AircraftBlockEntity> loaded(ServerLevel level) {
        LOADED.values().removeIf(aircraft -> aircraft.isRemoved());
        final List<AircraftBlockEntity> here = new ArrayList<>();
        for (final AircraftBlockEntity aircraft : LOADED.values()) if (aircraft.getLevel() == level) here.add(aircraft);
        return here;
    }

    @Nullable
    static Runtime runtime(AircraftBlockEntity aircraft) {
        return aircraft.runtime instanceof Runtime rt ? rt : null;
    }

    @Nullable
    static double[] readings(AircraftBlockEntity aircraft) {
        final Runtime rt = runtime(aircraft);
        if (rt == null || rt.craft.ship.isRemoved()) return null;
        final Craft c = rt.craft;
        final Vec3 p = c.pos(), v = c.vel();
        return new double[] {p.x, p.y, p.z, c.yaw(), c.locked() ? 1 : 0, rt.closestBuoy, rt.lastMiss, rt.lastHeadingError, c.writes, Math.hypot(v.x, v.z), rt.age};
    }

    // ---- every tick ---------------------------------------------------------------------------------------

    static void tick(ServerLevel level, AircraftBlockEntity be) {
        final ServerSubLevel ship = Craft.shipAt(level, be.getBlockPos());
        if (ship == null) {
            // Set down in the world: just a block, or the middle of an aircraft that is being built on its pad.
            Shipyard.tick(level, be);
            return;
        }
        // An aircraft block set down by hand in a ship (no airfield made that ship, so it has no home) is just a block: the
        // mod flies only the aircraft it made (the user's ruling of 2026-10-16; such a ship was flown as the first design
        // before, and its controller failed again and again on levers that were not there).
        if (be.home == null) return;
        Runtime rt = runtime(be);
        if (rt == null || rt.craft.ship != ship) {
            rt = new Runtime(new Craft(level, ship, AircraftType.ALL.getOrDefault(be.type, AircraftType.DEFAULT), be.getBlockPos()));
            be.runtime = rt;
        }
        LOADED.put(ship.getUniqueId(), be);
        if (be.modifying) {
            // Being changed by hand (the design tools): it stands fixed, and nothing else happens to it.
            final Craft c = rt.craft;
            if (!c.locked()) safely(() -> c.lock(true));
            return;
        }
        // A flight under way goes on with nobody in the dimension. Minecraft stops ticking block entities and entities
        // 300 ticks after the last player left a dimension that has no force-loaded chunk, while Sable keeps moving the
        // ship: it would fly on with its levers as they were, with nobody at the controls. (Seen in the reopen checks.)
        if ((be.phase != Phase.PARKED || be.homeward) && be.phase != Phase.STRANDED) level.resetEmptyTime();
        try {
            step(level, be, rt);
        } catch (RuntimeException | LinkageError e) {
            if (!rt.faulted) {
                rt.faulted = true;
                rt.rest = 0;
                // Never left flying on its own. One step at a time: any of them may fail as well.
                final Craft c = rt.craft;
                safely(c::drivesOff);
                safely(() -> c.lock(true));
                if (be.flying()) {
                    LOGGER.error("[Airline/flight] the aircraft's controller failed in the air; the aircraft is stopped, fixed where it is, and gives its ticket back.", e);
                    safely(() -> c.ticket(false));
                    safely(() -> uncover(level, be));
                    safely(() -> Airspace.get(level.getServer()).remove(c.id()));
                    safely(() -> Timetable.get(level.getServer()).remove(c.id()));
                    be.phase = Phase.STRANDED;
                    be.at = null;
                    be.note = "the controller failed in the air (see the server log)";
                } else if (be.phase != Phase.STRANDED) {
                    LOGGER.error("[Airline/flight] the aircraft's controller failed on a pad; the aircraft stays on its pad and takes up its work again after {} ticks.", FAULT_REST, e);
                    faultOnPad(level, be, rt);
                }
                be.setChanged();
            }
        }
    }

    /**
     * A failure while the aircraft stands on a pad. It stays what it is: an aircraft on that pad. With cargo it goes to the
     * unloading step, which settles the goods and the ledger (at home before the flight left: back onto the racks, flight
     * cancelled); without cargo it is parked. Nothing here touches the ledger: the unloading step does.
     */
    private static void faultOnPad(ServerLevel level, AircraftBlockEntity be, Runtime rt) {
        // The record of where it stands is what lets it be used again.
        if (be.at == null) be.at = be.origin != null ? be.origin : be.home;
        final boolean beforeLeaving = be.at != null && be.at.equals(be.home) && !be.returning;
        if (be.cargo) {
            if (beforeLeaving) {
                be.cancelled = true;
                be.cancelNote = "flight.note.failed_on_pad";
            }
            be.phase = Phase.UNLOAD;
        } else {
            be.phase = Phase.PARKED;
            safely(() -> rt.craft.ticket(false));
        }
        be.timer = 0;
        if (!be.cargo || beforeLeaving) {
            safely(() -> {
                final Mob pilot = pilot(level, be);
                if (pilot != null && Boarding.state(pilot) != Boarding.State.NONE) Boarding.leave(pilot, null);
            });
        }
        be.note = "the controller failed on the pad (see the server log); it goes on after a rest";
    }

    /**
     * A cargo flight ends before the end of its way (given up in the air, or its block is gone): what it carried no longer
     * counts as on its way to anybody, so the same goods are collected and sent again. The goods themselves stay in the
     * hold, and the lists of them stay for the status command.
     */
    private static void uncover(ServerLevel level, AircraftBlockEntity be) {
        if (!be.cargo) return;
        final WantLedger ledger = WantLedger.get(level.getServer());
        final WantLedger.Key there = key(level, be.freightColony), home = key(level, be.homeColony);
        int left = 0;
        for (final Map.Entry<String, Integer> line : be.manifest.entrySet()) {
            // After the unloading away from home the ledger knows already what did not arrive there.
            if (!be.settled) ledger.cover(there, Cargo.item(line.getKey()), -line.getValue());
            left += line.getValue();
        }
        for (final Map.Entry<String, Integer> line : be.returnManifest.entrySet()) {
            ledger.cover(home, Cargo.item(line.getKey()), -line.getValue());
            left += line.getValue();
        }
        for (int i = be.stopAt + 1; i < be.stops.size(); i++) {
            for (final Map.Entry<String, Integer> line : be.stops.get(i).goods.entrySet()) {
                ledger.cover(key(level, be.stops.get(i).colony), Cargo.item(line.getKey()), -line.getValue());
                left += line.getValue();
            }
        }
        be.forgetStops();
        be.cargo = false;
        be.settled = false;
        if (left > 0) ledger.log(String.format("colony %d -> colony %d: the flight ended on the way; %d items stay in the aircraft's hold", be.homeColony, be.freightColony, left));
        be.setChanged();
    }

    private static void safely(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Airline/flight] a step of making an aircraft safe failed.", e);
        }
    }

    static void bootstrap() {
        LOGGER.debug("[Airline/flight] loading ticket type {}", Craft.TICKET.name());
    }

    static boolean ticketed(ServerLevel level, UUID ship) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        return container != null && container.getAllTickets().containsKey(ship);
    }

    /** 1-3: the aircraft's own block is gone while the ship still exists. */
    static void abandon(ServerLevel level, BlockPos pos, @Nullable AircraftBlockEntity be) {
        final ServerSubLevel ship = Craft.shipAt(level, pos);
        if (ship == null) return;
        final Craft c = new Craft(level, ship, AircraftType.ALL.getOrDefault(be == null ? "" : be.type, AircraftType.DEFAULT), pos);
        safely(c::drivesOff);
        safely(() -> c.ticket(false));
        if (be != null) safely(() -> uncover(level, be));
        LOADED.remove(ship.getUniqueId());
        safely(() -> Timetable.get(level.getServer()).remove(ship.getUniqueId()));
        safely(() -> Airspace.get(level.getServer()).remove(ship.getUniqueId()));
        LOGGER.warn("[Airline/flight] the aircraft block at {} was removed: the drives are switched off and the ticket is given back.", c.pos());
    }

    /** Given up in the air: drives off, fixed where it is, no ticket; nothing more happens until somebody comes. */
    private static void strand(AircraftBlockEntity be, Runtime rt, String key, Object... args) {
        final Craft c = rt.craft;
        safely(c::drivesOff);
        safely(() -> c.lock(true));
        safely(() -> c.ticket(false));
        safely(() -> uncover(c.level, be));
        safely(() -> Airspace.get(c.level.getServer()).remove(c.id()));
        // A given-up aircraft has no place in the timetable: its home is free for another.
        safely(() -> Timetable.get(c.level.getServer()).remove(c.id()));
        be.phase = Phase.STRANDED;
        be.at = null;
        tell(be, key, args);
    }

    /**
     * Nothing burns any more and nothing is left to burn: the aircraft stays where it is, fixed, its pilot on board and its
     * ticket kept (it stays loaded), and its colony is told where it is.
     */
    private static void distress(AircraftBlockEntity be, Runtime rt) {
        final Craft c = rt.craft;
        safely(c::drivesOff);
        safely(() -> c.lock(true));
        safely(() -> Airspace.get(c.level.getServer()).remove(c.id()));
        be.phase = Phase.DISTRESS;
        be.setChanged();
        final Vec3 at = c.pos();
        tellColony(c.level, be, "flight.note.distress", (int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z), fuelToGo(be, rt, RouteNetwork.get(c.level)) / 1600 + 1);
    }

    /** What the tank has to hold for the rest of the flight (to the point behind it and on to where it goes), in ticks of one engine. */
    private static long fuelToGo(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        if (be.route.isEmpty() || be.destination == null) return Fuel.need(rt.craft.type, 0);
        final BlockPos behind = be.route.get(Math.max(0, Math.min(be.leg, be.route.size() - 1)));
        final Vec3 at = rt.craft.pos(), point = net.waypoint(behind);
        final long back = Math.round(Math.hypot(point.x - at.x, point.z - at.z) / rt.craft.type.topSpeed * 20.0);
        return Fuel.need(rt.craft.type, back + flightTicks(net, net.route(behind, be.destination, shipClass(be)), rt.craft.type));
    }

    /** In distress, once a second: with enough in the tank (or in the engines) for the rest of the way the flight goes on. */
    private static void rescue(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        final Craft c = rt.craft;
        // Fuel somebody put straight into an engine counts as well: it is taken into the tank.
        Fuel.drain(c, be);
        if (Fuel.total(be) < fuelToGo(be, rt, net)) return;
        c.lock(false);
        // Back to the point behind it, and on from there (the way is looked for anew).
        be.phase = Phase.RETREAT;
        resume(be, rt, net);
        tellColony(c.level, be, "flight.note.rescued");
    }

    /** Whether fuel is to be carried now: the pilot has some in its hands, or (with empty hands) the tank is not well filled. */
    private static boolean fuelling(AircraftBlockEntity be) {
        return be.carryCount > 0 ? Handling.Flow.FUEL.name().equals(be.carryFlow) : Fuel.low(be);
    }

    /** What the engines still burn of what they have lit (ticks); -1 for each when the aircraft is not loaded. */
    static int[] engineBurn(AircraftBlockEntity be) {
        final Runtime rt = runtime(be);
        final int[] burning = new int[AircraftType.of(be.type).engines.length];
        for (int i = 0; i < burning.length; i++) burning[i] = rt == null ? -1 : rt.craft.engineBurn(i);
        return burning;
    }

    /** Tells the managers of the aircraft's home colony (and the log, and the status command). */
    static void tellColony(ServerLevel level, AircraftBlockEntity be, String key, Object... args) {
        final Component text = Component.translatable("commands." + Airline.MOD_ID + "." + key, args);
        be.note = text.getString();
        be.setChanged();
        LOGGER.info("[Airline/flight] {}", be.note);
        final BuildingAirfield home = Cargo.airfieldAt(level, be.home);
        if (home != null) com.minecolonies.api.util.MessageUtils.format(text).sendTo(home.getColony()).forManagers();
        else tell(be, key, args);
    }

    /** Whether aircraft are held in the air at this pad beside a pier (an anchorage) instead of set down on a floor. */
    static boolean airborne(RouteNetwork net, @Nullable BlockPos pad) {
        final RouteNetwork.Node node = pad == null ? null : net.node(pad);
        return node != null && node.facility() != null && node.facility().airborne();
    }

    /**
     * At an anchorage: the side the pier is on, as {x, z} (one block), seen from the pad: where its deck is, the large
     * aircraft's half width and one block to either side across the pad's heading, a block higher (a building that was
     * mirrored has it on the other side). Null when there is no pier there, or that is not loaded, or it is no anchorage.
     */
    @Nullable
    static int[] pierSide(ServerLevel level, RouteNetwork net, @Nullable BlockPos pad) {
        if (!airborne(net, pad)) return null;
        final double heading = Math.toRadians(net.node(pad).heading());
        final int ax = (int) Math.round(Math.cos(heading)), az = (int) Math.round(Math.sin(heading));
        final int reach = com.asteain.airline.route.Facility.PIER_FROM_PAD;
        for (final int[] side : new int[][] {{-az, ax}, {az, -ax}}) {
            final BlockPos deck = pad.offset(side[0] * reach, 1, side[1] * reach);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(deck.getX() >> 4, deck.getZ() >> 4);
            if (chunk != null && !chunk.getBlockState(deck).getCollisionShape(level, deck).isEmpty()) return side;
        }
        return null;
    }

    /**
     * Where the aircraft's own block is when it is berthed at this pad: over the middle of the pad's floor; at an
     * anchorage, beside the pier, so far toward it that the edge of its deck lies against the pier ({@link AircraftType#deckReach}).
     */
    static BlockPos berthBlock(ServerLevel level, RouteNetwork net, BlockPos pad, AircraftType type) {
        final int[] side = pierSide(level, net, pad);
        if (side == null) return pad.above();
        final int shift = com.asteain.airline.route.ShipClass.LARGE.halfWidth - type.deckReach();
        return pad.offset(side[0] * shift, 1, side[1] * shift);
    }

    /** Whether a connector of the aircraft's hull is joined to another one now. */
    static boolean docked(AircraftBlockEntity be) {
        final Runtime rt = runtime(be);
        if (rt == null) return false;
        for (final AircraftType.Dock dock : rt.craft.type.docks.values()) if (rt.craft.connectorLocked(dock.connector())) return true;
        return false;
    }

    /** How long the ship's connector is given to join the pier's (ticks); then the aircraft is fixed where it is, as without one. */
    private static final int DOCK_TICKS = 200;
    /** How long it waits for its connectors to come in before it leaves all the same (ticks). */
    private static final int UNDOCK_TICKS = 100;

    /**
     * The connector of the aircraft on the side of the pier, at an anchorage whose pier has a connector that is out (it
     * is always out where the anchorage was built with its signal, 2026-10-18); null when there is nothing to join.
     */
    @Nullable
    static AircraftType.Dock pierDock(ServerLevel level, RouteNetwork net, BlockPos pad, AircraftType type) {
        final int[] side = pierSide(level, net, pad);
        if (side == null || type.docks.isEmpty()) return null;
        final double heading = Math.toRadians(net.node(pad).heading());
        final int ax = (int) Math.round(Math.cos(heading)), az = (int) Math.round(Math.sin(heading));
        // Looking ahead, the right is (-az, ax).
        final AircraftType.Dock dock = type.docks.get(side[0] == -az && side[1] == ax ? "starboard" : "port");
        if (dock == null) return null;
        final int reach = com.asteain.airline.route.Facility.PIER_FROM_PAD;
        final BlockPos pier = pad.offset(side[0] * reach, 1, side[1] * reach);
        if (Craft.connector(level, pier, "isExtended")) return dock;
        // Signalled but not out: a connector notices its signal when something beside it changes, and in a building put up
        // from the blueprint its lever came after it (2026-10-18: the anchorage's lever powers the floor block beside the
        // connector). It is told to look again, and comes out within half a second; the aircraft waits for it.
        if (level.hasNeighborSignal(pier) && level.getBlockEntity(pier) != null) {
            level.neighborChanged(pier, level.getBlockState(pier).getBlock(), pier.relative(Direction.UP));
            return dock;
        }
        return null;
    }

    /**
     * At an anchorage, at rest at the pier's height: the connector on the pier's side is let out and the aircraft left to
     * it (no pushes; its height still held), until the two have joined: then the deck lies exactly against the pier, square
     * with it. True while that goes on. Not joined in time, the connector is taken in again and the aircraft is fixed
     * where it is, as at a pier without a connector.
     */
    private static boolean docking(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        if (rt.dockTicks < 0) return false;
        final Craft c = rt.craft;
        final AircraftType.Dock dock = rt.dockTicks == 0 ? pierDock(c.level, net, be.destination, c.type) : dockOn(c, net, be);
        if (dock == null) {
            rt.dockTicks = -1;
            return false;
        }
        if (rt.dockTicks == 0) {
            c.dockLever(dock.lever(), 15);
            rt.pilot.hover();
            note(be, "joining the pier's connector");
        }
        if (c.connectorLocked(dock.connector())) {
            LOGGER.info("[Airline/flight] joined the pier's connector at {} after {} ticks.", be.destination, rt.dockTicks);
            rt.dockTicks = -1;
            return false;
        }
        if (++rt.dockTicks > DOCK_TICKS) {
            c.dockLever(dock.lever(), 0);
            LOGGER.info("[Airline/flight] the connectors at {} did not join; the aircraft is fixed where it is.", be.destination);
            note(be, "the connectors did not join; fixed where it is");
            rt.dockTicks = -1;
            return false;
        }
        return true;
    }

    /** The connector being let out (on the pier's side); null when that cannot be told any more. */
    @Nullable
    private static AircraftType.Dock dockOn(Craft c, RouteNetwork net, AircraftBlockEntity be) {
        for (final AircraftType.Dock dock : c.type.docks.values()) if (c.leverValue(dock.lever()) > 0) return dock;
        return null;
    }

    /**
     * Before it leaves: every connector of the hull is taken in (its lever to 0), and the aircraft waits until none is out
     * any more (a joined connector holds the ship to the pier). True while it waits; not for ever.
     */
    private static boolean undocking(AircraftBlockEntity be, Runtime rt) {
        final Craft c = rt.craft;
        if (c.type.docks.isEmpty()) return false;
        boolean out = false;
        for (final AircraftType.Dock dock : c.type.docks.values()) {
            if (c.leverValue(dock.lever()) > 0) c.dockLever(dock.lever(), 0);
            out |= c.connectorOut(dock.connector()) || c.connectorLocked(dock.connector());
        }
        if (!out || rt.undockTicks++ > UNDOCK_TICKS) {
            if (out) LOGGER.info("[Airline/flight] a connector of the aircraft is still out; it leaves all the same.");
            rt.undockTicks = 0;
            return false;
        }
        if (rt.undockTicks == 1) note(be, "letting go of the pier's connector");
        return true;
    }

    /** Whether there is nothing to berth at: no floor at the pad, or at an anchorage no pier beside it; false when that is not loaded. */
    static boolean berthMissing(ServerLevel level, RouteNetwork net, BlockPos pad) {
        if (!airborne(net, pad)) return floorMissing(level, pad);
        if (level.getChunkSource().getChunkNow(pad.getX() >> 4, pad.getZ() >> 4) == null) return false;
        return pierSide(level, net, pad) == null;
    }

    /** 1-4: whether the pad has no floor to land on (its middle and four places around it); false when that is not loaded. */
    static boolean floorMissing(ServerLevel level, BlockPos pad) {
        for (final int[] d : new int[][] {{0, 0}, {4, 4}, {4, -4}, {-4, 4}, {-4, -4}}) {
            final BlockPos pos = pad.offset(d[0], 0, d[1]);
            final LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) return false;
            if (chunk.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Set by the development checks only: the next step of an aircraft in the air (or of one that is busy on a pad)
     * throws, to see what the catch above does.
     */
    static boolean failNext, failNextOnPad;
    /** For the checks: the next placing over a pad (not at an anchorage) goes across again once; how often it went across again. */
    static boolean redoAcrossNext;
    static int acrossRedone;

    private static void step(ServerLevel level, AircraftBlockEntity be, Runtime rt) {
        if (be.phase == Phase.TRIAL) {
            // Worked by the test bench (Trial): the tank feeds the engines, the autopilot does what the bench asked for.
            rt.fresh = false;
            if (++rt.age % 20 == 3) Fuel.feed(rt.craft, be);
            rt.pilot.tick();
            return;
        }
        if (rt.faulted) {
            // In the air a failure is the end (the aircraft is given up). On a pad the work is taken up again after a rest.
            if (be.phase == Phase.STRANDED || be.flying() || ++rt.rest < FAULT_REST) return;
            rt.faulted = false;
            LOGGER.info("[Airline/flight] the aircraft on the pad at {} takes up its work again after a failure ({}).", be.at, be.phase);
        }
        if (failNext && be.flying()) {
            failNext = false;
            throw new IllegalStateException("a failure made on purpose by a check");
        }
        if (failNextOnPad && !be.flying() && be.phase != Phase.PARKED && be.phase != Phase.STRANDED) {
            failNextOnPad = false;
            throw new IllegalStateException("a failure on a pad made on purpose by a check");
        }
        final Craft c = rt.craft;
        final Autopilot ap = rt.pilot;
        final RouteNetwork net = RouteNetwork.get(level);
        rt.age++;
        // Every engine is set going again as a source of rotation once the ship has been loaded (and once more a moment
        // later, when its land is surely there). An engine that was saved while it burnt is read back burning, but not as
        // a source in the turning network: it burnt and nothing turned (2026-10-15, koeda after a restart in the air).
        if (rt.fresh || rt.age == 40) c.restartEngines();
        if (rt.fresh) {
            rt.fresh = false;
            if (be.flying()) resume(be, rt, net);
        }
        if (!be.flying()) {
            // On the ground: fixed to the world, balloon kept warm, no ticket.
            if (rt.age % 20 == 1) {
                if (!c.locked()) c.lock(true);
                c.burner(c.type.parkedLever);
            }
        } else if (rt.age % 20 == 0) {
            be.setChanged();
        }
        if (be.flying() && rt.age % 20 == 3) {
            Fuel.feed(c, be);
            if (Fuel.dry(c, be)) distress(be, rt);
        }
        if (rt.age % 20 == 5) publish(level, be, rt, net);
        final Mob pilot = pilot(level, be);
        if (pilot != null && rt.age % 20 == 7 && pilot.isPassenger() && Boarding.state(pilot) == Boarding.State.NONE && isSeat(pilot.getVehicle())) {
            // Create's seats sit down any creature that steps onto them: a pilot that walked over a seat (one for passengers,
            // 2026-10-17) is taken off it again; the boarding puts it on its own seat when it is wanted there.
            pilot.stopRiding();
            LOGGER.info("[Airline/flight] the pilot of the aircraft at {} sat down on a seat it was not sent to; it is taken off it.", c.pos());
        }
        switch (be.phase) {
            case PARKED -> {
                if (be.homeward && be.at != null && !be.at.equals(be.home)) goHome(level, be, rt, net);
                else if (be.newlyBuilt && rt.age % 100 == 25) relocate(level, be, rt, net);
                else if (rt.age % MAINTAIN_EVERY == 150) Shipyard.maintain(level, be, c);
                else if (rt.age % 100 == 50 && be.lacks.isEmpty() && be.carryCount == 0) dispatch(level, be, rt, net);
                // The tank is filled from the racks of the airfield it stands at, whenever it is not well filled.
                if (be.phase == Phase.PARKED && rt.age % 5 == 2 && be.at != null && fuelling(be)) Handling.move(level, be, rt, Handling.Flow.FUEL);
            }
            case STRANDED -> { }
            case DISTRESS -> {
                if (rt.age % 20 == 7) rescue(be, rt, net);
            }
            case LOAD -> load(level, be, rt);
            case UNLOAD -> unload(level, be, rt, net);
            case BOARDING -> boarding(be, rt, net, pilot);
            case WAIT -> {
                if (++be.timer >= TURNAROUND_TICKS) turnAround(level, be, rt, net, pilot);
            }
            case DISEMBARK -> disembark(level, be, rt, net, pilot);
            default -> {
                // In the air. The autopilot works only while the pilot sits on the seat.
                if (pilot == null || Boarding.state(pilot) != Boarding.State.SEATED) {
                    // Nobody flies it (after a restart the pilot stands on the deck): the ship is fixed where it is, the
                    // pilot is sent to the seat again, and the flight goes on when the pilot sits.
                    if (!rt.paused) {
                        rt.paused = true;
                        rt.pausedFor = 0;
                        c.drivesOff();
                        c.lock(true);
                        LOGGER.warn("[Airline/flight] the aircraft at {} is in the air without its pilot on the seat: fixed where it is until the pilot sits.", c.pos());
                    }
                    if (++rt.pausedFor >= PAUSE_LIMIT) {
                        // The pilot is gone for good (dead, colony gone, boarding switched off): given up, without a ticket.
                        strand(be, rt, "flight.note.no_pilot_in_air");
                        return;
                    }
                    if (pilot != null && Boarding.state(pilot) == Boarding.State.NONE && rt.pausedFor % 20 == 0) Boarding.board(pilot, c.id(), c.inShip(c.type.seat));
                    return;
                }
                if (rt.paused || rt.age % 40 == 0) {
                    if (c.locked()) c.lock(false);
                    rt.paused = false;
                }
                fly(level, be, rt, net);
                // The pilot works the levers (2026-10-18, the user's wish): an arm moved when the autopilot set one, not
                // more than twice a second.
                if (c.writes != rt.writesSeen) {
                    rt.writesSeen = c.writes;
                    if (rt.age - rt.swungAt >= 10) {
                        pilot.swing(InteractionHand.MAIN_HAND);
                        rt.swungAt = rt.age;
                    }
                }
            }
        }
    }

    @Nullable
    static Mob pilot(ServerLevel level, AircraftBlockEntity be) {
        final Entity entity = be.pilot == null ? null : level.getEntity(be.pilot);
        if (entity instanceof Mob mob && mob.isAlive()) return mob;
        if (be.pilotColony <= 0) return null;
        // The entity may have been made anew by its colony (after a restart): ask the colony for the citizen.
        final IColony colony = IColonyManager.getInstance().getColonyByDimension(be.pilotColony, level.dimension());
        final ICivilianData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(be.pilotCitizen);
        if (citizen == null || citizen.getEntity().isEmpty() || !citizen.getEntity().get().isAlive()) return null;
        be.pilot = citizen.getEntity().get().getUUID();
        return citizen.getEntity().get();
    }

    // ---- pads ---------------------------------------------------------------------------------------------

    /** The pads of the airfield a pad belongs to, its own first (for a pad without an airfield: that pad alone). */
    static List<BlockPos> padsWith(ServerLevel level, BlockPos pad) {
        final BuildingAirfield airfield = Cargo.airfieldAt(level, pad);
        final List<BlockPos> pads = airfield == null ? List.of() : airfield.pads();
        return pads.contains(pad) ? pads : List.of(pad);
    }

    /** Whether another aircraft stands on a pad (by the timetable, which also knows aircraft that are not loaded, and by the loaded aircraft). */
    static boolean stoodOn(ServerLevel level, BlockPos pad, @Nullable UUID self) {
        final Timetable.Entry standing = Timetable.get(level.getServer()).standing(level.dimension().location().toString(), pad);
        if (standing != null && !standing.ship().equals(self)) return true;
        for (final Map.Entry<UUID, AircraftBlockEntity> other : LOADED.entrySet()) {
            final AircraftBlockEntity aircraft = other.getValue();
            if (!other.getKey().equals(self) && aircraft.getLevel() == level && !aircraft.isRemoved() && pad.equals(aircraft.at)) return true;
        }
        return false;
    }

    /**
     * Whether an aircraft may come to stand on a pad: nobody else stands there, nobody else is on the way there or will
     * leave for it, and it is not the home of another aircraft (a home is kept for its aircraft while that is away).
     * So an airfield with one pad and an aircraft of its own takes no visitors.
     */
    static boolean free(ServerLevel level, BlockPos pad, @Nullable UUID self) {
        // An aircraft is being built there (its own block stands on the middle of the pad).
        final LevelChunk chunk = level.getChunkSource().getChunkNow(pad.getX() >> 4, pad.getZ() >> 4);
        if (chunk != null && chunk.getBlockState(pad.above()).getBlock() instanceof AircraftBlock) return false;
        return unclaimed(level, pad, self);
    }

    /** {@link #free} without the look at the pad itself: nobody else stands there, is on the way there, or has its home there. */
    static boolean unclaimed(ServerLevel level, BlockPos pad, @Nullable UUID self) {
        final String dimension = level.dimension().location().toString();
        for (final Timetable.Entry entry : Timetable.get(level.getServer()).entries()) {
            if (entry.ship().equals(self) || !entry.dimension().equals(dimension)) continue;
            if (pad.equals(entry.at()) || pad.equals(entry.to()) || pad.equals(entry.home())) return false;
        }
        // The timetable is written once a second; the loaded aircraft are asked themselves.
        for (final Map.Entry<UUID, AircraftBlockEntity> other : LOADED.entrySet()) {
            final AircraftBlockEntity aircraft = other.getValue();
            if (other.getKey().equals(self) || aircraft.getLevel() != level || aircraft.isRemoved() || aircraft.phase == Phase.STRANDED) continue;
            if (pad.equals(aircraft.at) || pad.equals(aircraft.home) || (aircraft.phase != Phase.PARKED && pad.equals(aircraft.destination))) return false;
        }
        return true;
    }

    /** Where a flight may land: the pad, or why there is none (the key of a command's answer). */
    record Landing(@Nullable BlockPos pad, String why) {}

    /** The size class of the aircraft (from its design). */
    static com.asteain.airline.route.ShipClass shipClass(AircraftBlockEntity be) {
        return AircraftType.of(be.type).shipClass;
    }

    /** Whether an aircraft of this class may berth at the pad {@code pad} (the kind of place it belongs to; see {@link com.asteain.airline.route.Facility}). */
    static boolean berthsAt(RouteNetwork net, @Nullable BlockPos pad, com.asteain.airline.route.ShipClass ship) {
        final RouteNetwork.Node node = pad == null ? null : net.node(pad);
        return node != null && node.berths(ship);
    }

    /**
     * Chooses the pad for a flight of an aircraft of class {@code ship} from the pad {@code from} to the airfield of the pad
     * {@code wanted}: that pad when it is free, else another pad of the same airfield (not when {@code exact}). Only a pad
     * where that class may berth, with a floor and a way to it for that class. Why there is none: the key of an answer
     * ("flight.no_berth": no pad there takes this class; then no way, no floor, no free pad).
     */
    static Landing landing(ServerLevel level, RouteNetwork net, BlockPos from, BlockPos wanted, @Nullable UUID self, boolean exact, com.asteain.airline.route.ShipClass ship) {
        final List<BlockPos> pads = new ArrayList<>(List.of(wanted));
        if (!exact) for (final BlockPos pad : padsWith(level, wanted)) if (!pads.contains(pad)) pads.add(pad);
        boolean berth = false, way = false, floor = false;
        for (final BlockPos pad : pads) {
            if (!berthsAt(net, pad, ship)) continue;
            berth = true;
            if (net.route(from, pad, ship).size() < 2) continue;
            way = true;
            if (berthMissing(level, net, pad)) continue;
            floor = true;
            if (free(level, pad, self)) return new Landing(pad, "");
        }
        return new Landing(null, !berth ? "flight.no_berth" : !way ? "flight.no_route" : !floor ? "flight.no_floor_there" : "flight.no_free_pad");
    }

    // ---- plans (see Airspace) -----------------------------------------------------------------------------

    /**
     * Where this aircraft means to be from now on: the rest of its flight, with the time each part should take (after
     * waiting {@code wait} ticks where it is). The times are rough; the plans allow for that.
     */
    private static Airspace.Plan plan(AircraftBlockEntity be, Runtime rt, RouteNetwork net, int wait) {
        final Craft c = rt.craft;
        final long now = c.level.getGameTime();
        final Vec3 here = c.pos();
        final Airspace.Builder plan = new Airspace.Builder(here, now);
        if (wait > 0) plan.then(here, wait);
        // The first point of the route it still has to come to.
        final int first = switch (be.phase) {
            case CRUISE -> be.leg + 1;
            case HOLD -> rt.resume == Phase.CRUISE ? be.leg + 1 : be.leg;
            case ALIGN, DESCEND -> be.route.size();
            default -> be.leg;      // on the pad and climbing: up to the first point; going back: to the point behind, then on
        };
        Vec3 at = here;
        for (int i = Math.max(0, first); i < be.route.size(); i++) {
            // Toward a point at the height of the line that ends there; on the pad, on the way back to the point behind and
            // over a point, at the height of the line from there on (lines are level; heights change over the points only).
            final double y = i == 0 || (i == first && first == be.leg) ? legHeight(net, be.route, i) : legHeight(net, be.route, i - 1);
            final Vec3 point = overPoint(net, be.route.get(i), y);
            final double flat = Math.hypot(point.x - at.x, point.z - at.z);
            final long way = Math.round(Math.max(flat / c.type.topSpeed, Math.abs(point.y - at.y) / c.type.climbSpeed) * 20.0);
            if (flat < 1.0) {
                // Straight up (from the pad to the height of the line): nothing but the climb.
                plan.then(point, way);
            } else {
                // What a point costs is spent in two places: half of it where the aircraft is, turning toward the next
                // point, and half of it under way (getting up to speed, and coming to rest). A plan that spread it
                // evenly along the leg had the aircraft gone from a passing point while it was still turning there.
                final long half = Math.round(c.type.pointSeconds * 10.0);
                plan.then(at, half);
                plan.then(point, way + half);
            }
            at = point;
            if (i + 1 < be.route.size()) {
                // Over a point where the next line lies higher or lower: up or down there first.
                final double next = legHeight(net, be.route, i);
                if (Math.abs(next - at.y) > 1.5) {
                    final Vec3 shifted = new Vec3(at.x, next, at.z);
                    plan.then(shifted, Math.round(Math.abs(next - at.y) / Math.max(0.1, next > at.y ? c.type.climbSpeed : c.type.descentRate) * 20.0));
                    at = shifted;
                }
            }
        }
        if (be.destination != null) {
            // Placed over the pad (beside the pier at an anchorage), then down onto it.
            final BlockPos spot = berthBlock(c.level, net, be.destination, c.type);
            final Vec3 over = new Vec3(spot.getX() + 0.5, at.y, spot.getZ() + 0.5);
            if (be.phase != Phase.DESCEND) plan.then(over, Math.round(c.type.alignSeconds * 20.0));
            final double rest = be.destination.getY() + 1 + be.restOffset;
            plan.then(new Vec3(over.x, rest, over.z), Math.round(Math.max(0, at.y - rest) / c.type.descentRate * 20.0) + 60);
        }
        return plan.build(c.id(), c.level.dimension().location().toString(), now);
    }

    /** In the air: the plan is made again from where the aircraft is now (at every point it passes, and whenever it is held up). */
    private static void replan(AircraftBlockEntity be, Runtime rt, int wait) {
        Airspace.get(rt.craft.level.getServer()).put(plan(be, rt, RouteNetwork.get(rt.craft.level), wait));
    }

    /** Something to know that is not worth a message to every administrator: kept for the status command, and logged. */
    static void note(AircraftBlockEntity be, String text) {
        be.note = text;
        be.setChanged();
        LOGGER.info("[Airline/flight] {}", text);
    }

    // ---- the timetable ------------------------------------------------------------------------------------

    /** Ticks a flight along that way takes: the way itself at top speed, and the design's time for climbing, being placed over the pad and going down. */
    private static long flightTicks(RouteNetwork net, List<BlockPos> way, AircraftType type) {
        return way.size() < 2 ? 0 : Math.round((net.length(way) / type.topSpeed + type.groundSeconds) * 20.0);
    }

    /**
     * Writes this aircraft's line of the timetable (once a second): the pad it stands on and when its berth there is
     * over, where it goes next and when it is expected there.
     */
    private static void publish(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        if (be.home == null) return;
        final Timetable table = Timetable.get(level.getServer());
        final Craft c = rt.craft;
        if (be.phase == Phase.STRANDED) {
            table.remove(c.id());
            return;
        }
        final long now = level.getGameTime(), leaves = be.landed + Cargo.berthTicks();
        BlockPos to = null;
        long arrives = 0;
        if (be.flying()) {
            to = be.destination;
            // What is left of the way: to the point ahead, then the lines after it.
            double left = 0;
            if (!be.route.isEmpty() && be.leg + 1 < be.route.size()) {
                final Vec3 next = net.waypoint(be.route.get(be.leg + 1)), here = c.pos();
                left = Math.hypot(next.x - here.x, next.z - here.z) + net.length(be.route.subList(be.leg + 1, be.route.size()));
            }
            arrives = now + Math.round((left / c.type.topSpeed + c.type.groundSeconds / 2.0) * 20.0);
        } else if (be.at != null) {
            // On a pad: away from home the next flight is the way home; at home it is known once the loading has begun.
            if (!be.at.equals(be.home)) to = be.home;
            else if (be.phase != Phase.PARKED && be.destination != null && !be.destination.equals(be.at)) to = be.destination;
            if (to != null) arrives = Math.max(now, leaves) + flightTicks(net, net.route(be.at, to, shipClass(be)), c.type);
        }
        // Whole seconds: the line is only written again when something changed that somebody could read.
        table.put(new Timetable.Entry(c.id(), level.dimension().location().toString(), be.home, be.flying() ? null : be.at, to, leaves, arrives / 20 * 20, be.phase.name()));
    }

    // ---- on the ground ------------------------------------------------------------------------------------

    private static void boarding(AircraftBlockEntity be, Runtime rt, RouteNetwork net, @Nullable Mob pilot) {
        be.timer++;
        final Boarding.State state = pilot == null ? Boarding.State.NONE : Boarding.state(pilot);
        if (state == Boarding.State.SEATED) {
            // Joined to a pier's connector: let go of it first, and wait until the connector is in.
            if (undocking(be, rt)) {
                be.timer = 0;
                return;
            }
            // It leaves when the air is free: its way must keep clear of every flight that is under way (see Airspace).
            if (rt.heldForAir && rt.age % AIR_LOOK != 0) return;
            final Airspace air = Airspace.get(rt.craft.level.getServer());
            final Airspace.Plan plan = plan(be, rt, net, 0);
            if (air.clash(plan, rt.craft.level.getGameTime()) != null) {
                // Waiting for the air is no failure to board: the time allowed for boarding does not run.
                be.timer = 0;
                if (!rt.heldForAir) note(be, "ready to leave; waiting on the pad until another flight is out of the way");
                rt.heldForAir = true;
                return;
            }
            if (rt.heldForAir) note(be, "the other flight is out of the way; leaving");
            rt.heldForAir = false;
            air.put(plan);
            takeOff(be, rt);
        } else if (be.timer > BOARDING_LIMIT) {
            // (A pilot that is not there right now is waited for like one that does not arrive: its entity may only be unloaded for a moment.)
            if (pilot != null) Boarding.leave(pilot, null);
            final boolean atHome = be.at != null && be.at.equals(be.home);
            if (!atHome && (be.cargo || (be.returning && !be.roundTrip))) {
                // Away from home a cargo flight (or an aircraft on its way home) must not end as a parked aircraft nobody moves.
                stayAway(rt.craft.level, be, rt);
            } else {
                // A cargo flight that never left puts its goods back first.
                be.phase = be.cargo && atHome ? Phase.UNLOAD : Phase.PARKED;
                be.cancelled = be.cargo;
                rt.craft.ticket(false);
            }
            tell(be, "flight.note.not_boarded");
        } else if (pilot != null && state == Boarding.State.NONE && be.timer % 20 == 0) {
            // Asked again now and then: the ship counts as stopped only a moment after it was fixed.
            Boarding.board(pilot, rt.craft.id(), rt.craft.inShip(rt.craft.type.seat));
        }
    }

    /**
     * Away from home a flight cannot go on (nobody got to the seat in time, or it was called off there). Its cargo is
     * settled: what the pilot holds is put down, what arrived is told to the ledger, what is still in the hold no longer
     * counts as on its way. The aircraft stays what it is, an aircraft of its home: it keeps its loading ticket for a
     * while and flies home as soon as it has a pilot and a way ({@link #goHome}).
     */
    private static void stayAway(ServerLevel level, AircraftBlockEntity be, Runtime rt) {
        Handling.finishNow(level, be, rt);
        if (be.cargo && !be.settled) settleAway(level, be, rt.craft);
        uncover(level, be);
        be.homeward = true;
        be.phase = Phase.PARKED;
        be.timer = 0;
        rt.homeTries = 0;
        be.setChanged();
    }

    /** Parked away from home and wanting to fly back: the pilot waits on its seat; now and then a pilot and a way are looked for. */
    private static void goHome(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        if (rt.age % 5 == 0 && rt.homeTries < HOME_TRIES) Handling.rest(level, be, rt);
        if (rt.age % HOME_LOOK != 0) return;
        final Mob pilot = pilot(level, be);
        // (Its home is kept for it; somebody can only stand there who was made there by command.)
        final List<BlockPos> back = be.home == null || !free(level, be.home, rt.craft.id()) ? List.of() : net.route(be.at, be.home, shipClass(be));
        if (pilot == null || back.size() < 2 || berthMissing(level, net, be.home)) {
            if (++rt.homeTries == HOME_TRIES) {
                // Not for ever: the surroundings are let go. It goes on trying whenever somebody is near.
                Handling.release(rt);
                rt.craft.ticket(false);
                tell(be, "flight.note.cannot_go_home", be.at.getX(), be.at.getY(), be.at.getZ());
            }
            return;
        }
        rt.homeTries = 0;
        rt.craft.ticket(true);
        be.homeward = false;
        be.origin = be.at;
        be.destination = be.home;
        be.route = new ArrayList<>(back);
        be.leg = 0;
        be.roundTrip = false;
        be.returning = true;
        be.cancelled = false;
        be.cargo = false;
        be.forgetStops();
        be.timer = 0;
        be.phase = Phase.BOARDING;
        be.note = "flying home";
        LOGGER.info("[Airline/flight] the aircraft parked at {} flies home.", be.at);
        be.setChanged();
    }

    /**
     * A new aircraft of a class that anchorages take moves to a free anchorage of its colony, which becomes its home
     * (2026-10-17, the user's ruling "判断は一旦全部推奨で": an airfield may have more aircraft than pads; the ones over go
     * to the colony's anchorages, one at each, and fly from there; the airfield's pad is free for the next one then).
     * The nearest anchorage it has a way to is taken. One that finds none free keeps the airfield's pad as its home.
     * An anchorage that is an aircraft's home is kept for it, as the home pad of an airfield is.
     */
    private static void relocate(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        if (be.at == null || !be.at.equals(be.home)) return;
        final BuildingAirfield airfield = Cargo.airfieldAt(level, be.home);
        final BlockPos best = freeAnchorage(level, net, be, rt.craft.id());
        if (best == null) {
            // No anchorage free for it: its home stays the airfield's pad.
            be.newlyBuilt = false;
            be.setChanged();
            return;
        }
        final Mob pilot = Cargo.freePilot(level, airfield, be);
        if (pilot == null) return;      // looked at again: it moves once a pilot is free
        be.newlyBuilt = false;
        setPilot(be, pilot);
        be.home = best;
        be.homeward = true;
        note(be, "a new aircraft: its home is the anchorage at " + best.toShortString() + " (the airfield's pad is free for the next one)");
        be.setChanged();
    }

    /** The nearest free anchorage of the colony of the aircraft's home airfield that takes its class and that it has a way to; null when there is none. */
    @Nullable
    private static BlockPos freeAnchorage(ServerLevel level, RouteNetwork net, AircraftBlockEntity be, @Nullable UUID ship) {
        final BuildingAirfield airfield = be.home == null ? null : Cargo.airfieldAt(level, be.home);
        final com.asteain.airline.route.ShipClass cls = shipClass(be);
        if (airfield == null || !airfield.facility().airfield() || !com.asteain.airline.route.Facility.ANCHORAGE.berths(cls)) return null;
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (final com.minecolonies.api.colony.buildings.IBuilding building : airfield.getColony().getServerBuildingManager().getBuildings().values()) {
            if (!(building instanceof BuildingAirfield anchorage) || anchorage.facility() != com.asteain.airline.route.Facility.ANCHORAGE || anchorage.getBuildingLevel() < 1) continue;
            final BlockPos pad = anchorage.pad();
            if (pad == null || !berthsAt(net, pad, cls) || !free(level, pad, ship) || net.route(be.home, pad, cls).size() < 2) continue;
            if (pad.distSqr(be.home) < nearest) {
                nearest = pad.distSqr(be.home);
                best = pad;
            }
        }
        return best;
    }

    /**
     * Asked for in the airfield's aircraft tab (2026-10-18, the user's ruling "作る"): the airfield's own aircraft moves
     * to a free anchorage of the colony, which becomes its home, as a newly built one does (the nearest that takes its
     * class and that it has a way to); the airfield's pad is free then. Only an aircraft that stands on its home pad with
     * nothing to do.
     */
    static String toAnchorage(ServerLevel level, BuildingAirfield airfield, @Nullable net.minecraft.server.level.ServerPlayer player) {
        final String say = "message." + Airline.MOD_ID + ".fleet.";
        final BlockPos pad = airfield.pad();
        for (final AircraftBlockEntity be : loaded(level)) {
            if (pad == null || !pad.equals(be.home)) continue;
            if (!com.asteain.airline.route.Facility.ANCHORAGE.berths(shipClass(be))) return said(player, say + "no_class");
            if (be.phase != Phase.PARKED || !pad.equals(be.at) || be.cargo || be.homeward || be.modifying) return said(player, say + "busy");
            final Runtime rt = runtime(be);
            if (freeAnchorage(level, RouteNetwork.get(level), be, rt == null ? null : rt.craft.id()) == null) return said(player, say + "no_anchorage");
            be.newlyBuilt = true;
            be.setChanged();
            return said(player, say + "moving");
        }
        return said(player, say + "none");
    }

    /**
     * Asked for in the airfield's aircraft tab: an aircraft that stands at one of the colony's anchorages (its home there)
     * flies to this airfield, whose pad becomes its home. Only when the pad is nobody's home, and only an aircraft of a
     * class the airfield takes, with nothing to do.
     */
    static String toAirfield(ServerLevel level, BuildingAirfield airfield, @Nullable net.minecraft.server.level.ServerPlayer player) {
        final String say = "message." + Airline.MOD_ID + ".fleet.";
        final BlockPos pad = airfield.pad();
        if (pad == null || !airfield.facility().airfield()) return said(player, say + "none_away");
        for (final AircraftBlockEntity be : loaded(level)) if (pad.equals(be.home)) return said(player, say + "pad_taken");
        for (final Timetable.Entry entry : Timetable.get(level.getServer()).entries()) if (pad.equals(entry.home())) return said(player, say + "pad_taken");
        final RouteNetwork net = RouteNetwork.get(level);
        for (final AircraftBlockEntity be : loaded(level)) {
            if (be.home == null || be.phase != Phase.PARKED || !be.home.equals(be.at) || be.cargo || be.homeward || be.modifying) continue;
            final BuildingAirfield anchorage = Cargo.airfieldAt(level, be.home);
            if (anchorage == null || anchorage.facility() != com.asteain.airline.route.Facility.ANCHORAGE || anchorage.getColony().getID() != airfield.getColony().getID()) continue;
            if (!berthsAt(net, pad, shipClass(be)) || net.route(be.home, pad, shipClass(be)).size() < 2) continue;
            be.home = pad;
            be.homeward = true;
            be.setChanged();
            LOGGER.info("[Airline/flight] the aircraft at the anchorage {} is sent back to the airfield's pad {}, its home from now.", be.at, pad);
            return said(player, say + "returning");
        }
        return said(player, say + "none_away");
    }

    /** For the aircraft tab: {whether the airfield's own aircraft can be moved to an anchorage now, whether one can be brought back from an anchorage}. */
    static boolean[] fleetMoves(ServerLevel level, BuildingAirfield airfield) {
        final BlockPos pad = airfield.pad();
        final boolean[] can = new boolean[2];
        if (pad == null || !airfield.facility().airfield()) return can;
        boolean padTaken = false;
        final RouteNetwork net = RouteNetwork.get(level);
        for (final AircraftBlockEntity be : loaded(level)) {
            if (!pad.equals(be.home)) continue;
            padTaken = true;
            final Runtime rt = runtime(be);
            can[0] = be.phase == Phase.PARKED && pad.equals(be.at) && !be.cargo && !be.homeward && !be.modifying
                    && freeAnchorage(level, net, be, rt == null ? null : rt.craft.id()) != null;
        }
        for (final Timetable.Entry entry : Timetable.get(level.getServer()).entries()) padTaken |= pad.equals(entry.home());
        if (!padTaken) {
            for (final AircraftBlockEntity be : loaded(level)) {
                if (be.home == null || be.phase != Phase.PARKED || !be.home.equals(be.at) || be.cargo || be.homeward) continue;
                final BuildingAirfield anchorage = Cargo.airfieldAt(level, be.home);
                if (anchorage != null && anchorage.facility() == com.asteain.airline.route.Facility.ANCHORAGE && anchorage.getColony().getID() == airfield.getColony().getID()
                        && berthsAt(net, pad, shipClass(be))) can[1] = true;
            }
        }
        return can;
    }

    /** Whether a creature rides a seat (Create's, or another mod's seat entity). */
    private static boolean isSeat(@Nullable Entity vehicle) {
        return vehicle != null && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getPath().contains("seat");
    }

    private static void takeOff(AircraftBlockEntity be, Runtime rt) {
        final Craft c = rt.craft;
        rt.dockTicks = 0;
        c.ticket(true);
        c.lock(false);
        be.at = null;
        be.leg = 0;
        rt.pilot.resetHeight();
        rt.pilot.hover();
        // (A design with a climbing rate has its target raised step by step from where it stands; see the climb.)
        rt.pilot.altTarget = c.type.climbRate > 0 ? c.pos().y : legHeight(RouteNetwork.get(c.level), be.route, 0);
        be.phase = Phase.CLIMB;
        be.setChanged();
    }

    private static void disembark(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net, @Nullable Mob pilot) {
        be.timer++;
        // Behind the tail first, then ahead of the nose, in turn: from a ship that does not lie on the world's block grid
        // the walk off one end of the deck can fail (seen in the tests) while the other end works.
        final BlockPos spot = leaveSpot(level, net, be, be.at, (be.timer / RETRY_LEAVING) % 2 == 1);
        if (pilot != null && be.at != null) {
            final Boarding.State state = Boarding.state(pilot);
            final int[] pier = pierSide(level, net, be.at);
            // Off the deck: on the pad (the deck is a block higher than the pad); at an anchorage, on the pier beside the hull.
            final boolean onPad;
            if (pier == null) onPad = pilot.getY() < be.at.getY() + 1.6;
            else {
                final BlockPos own = berthBlock(level, net, be.at, AircraftType.of(be.type));
                final double across = (pilot.getX() - (own.getX() + 0.5)) * pier[0] + (pilot.getZ() - (own.getZ() + 0.5)) * pier[1];
                onPad = across > AircraftType.of(be.type).deckReach() + 0.3;
            }
            if (state == Boarding.State.SEATED) {
                // Refused while the ship does not count as stopped yet; asked again until it is accepted.
                if (be.timer % 10 == 0) Boarding.leave(pilot, spot);
            } else if (state == Boarding.State.LEAVING) {
                if (be.timer % RETRY_LEAVING == 0) Boarding.leave(pilot, spot);
            } else if (!onPad) {
                // The walk was given up with the pilot still on the deck: the pilot's own job keeps trying.
                post(pilot, spot);
            }
            if (state != Boarding.State.NONE || !onPad) {
                if (be.timer == LEAVING_LIMIT) tell(be, "flight.note.not_left");
                // The aircraft stays fixed and waits. After twice the limit it is at least free for the next order.
                if (be.timer < 2 * LEAVING_LIMIT) return;
            }
            post(pilot, be.at.equals(be.home) ? null : leaveSpot(level, net, be, be.at, false));
        }
        // The ticket is kept until here: a landed aircraft at a pad nobody else keeps loaded would stop in the middle of
        // letting its pilot off. It goes when the order is over; for a round trip it stays for the wait and the way back.
        if (be.cancelled) {
            be.cancelled = false;
            be.phase = Phase.PARKED;
            rt.craft.ticket(false);
            // Why it was cancelled, when that was not a blocked line (called off by command, a failure on the pad).
            tell(be, be.cancelNote.isEmpty() ? "flight.note.cancelled" : be.cancelNote);
            be.cancelNote = "";
        } else if (be.roundTrip && !be.returning) {
            be.returning = true;
            be.phase = Phase.WAIT;
            be.timer = 0;
        } else {
            be.phase = Phase.PARKED;
            rt.craft.ticket(false);
            be.note = "arrived";
        }
        be.setChanged();
    }

    private static void turnAround(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net, @Nullable Mob pilot) {
        final BlockPos back = be.origin;
        be.origin = be.at;
        be.destination = back;
        be.route = be.origin == null || back == null || !free(level, back, rt.craft.id()) ? List.of() : new ArrayList<>(net.route(be.origin, back, shipClass(be)));
        if (be.route.size() < 2 || pilot == null) {
            be.phase = Phase.PARKED;
            rt.craft.ticket(false);
            tell(be, "flight.note.no_way_back");
            return;
        }
        be.phase = Phase.BOARDING;
        be.timer = 0;
    }

    /**
     * Where the pilot walks to after landing: on the pad, at its edge behind the aircraft (the side of the airfield's hut);
     * at an anchorage, onto the middle of the pier beside the hull (a little astern, or ahead).
     */
    static BlockPos leaveSpot(ServerLevel level, RouteNetwork net, AircraftBlockEntity be, @Nullable BlockPos pad, boolean ahead) {
        if (pad == null) return BlockPos.ZERO;
        final RouteNetwork.Node node = net.node(pad);
        final int[] pier = pierSide(level, net, pad);
        if (pier != null) {
            final BlockPos own = berthBlock(level, net, pad, AircraftType.of(be.type));
            // To the middle of the pier (3 wide): walking is over within a block of the spot, which at its edge left the pilot on the deck's end.
            final int across = AircraftType.of(be.type).deckReach() + 2;
            final double heading = Math.toRadians(node.heading());
            final int along = ahead ? 2 : -2;
            return own.offset(pier[0] * across + (int) Math.round(Math.cos(heading) * along), 1, pier[1] * across + (int) Math.round(Math.sin(heading) * along));
        }
        final double heading = Math.toRadians(node == null ? 0 : node.heading());
        final int far = ahead ? 5 : -5;
        return pad.offset((int) Math.round(Math.cos(heading) * far), 1, (int) Math.round(Math.sin(heading) * far));
    }

    /** Tells the pilot's own job where to stay while it is away from home (null: at home again). */
    private static void post(Mob pilot, @Nullable BlockPos where) {
        if (pilot instanceof AbstractEntityCitizen citizen && citizen.getCitizenJobHandler().getColonyJob() instanceof JobPilot job) job.setDutyPost(where);
    }

    // ---- in the air ---------------------------------------------------------------------------------------

    /** After a restart in the air: the ticket again, and the autopilot is told again what it was doing. */
    private static void resume(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        final Craft c = rt.craft;
        c.ticket(true);
        final Autopilot ap = rt.pilot;
        ap.altTarget = c.pos().y;
        if (be.route.isEmpty() || be.destination == null) {
            be.route = new ArrayList<>(List.of(be.destination == null ? BlockPos.containing(c.pos()) : be.destination));
            be.leg = 0;
        }
        be.leg = Math.max(0, Math.min(be.leg, be.route.size() - 1));
        if (be.destination == null) be.destination = be.route.get(be.route.size() - 1);
        switch (be.phase) {
            case CLIMB -> {
                ap.hover();
                ap.altTarget = c.type.climbRate > 0 ? c.pos().y : legHeight(net, be.route, be.leg);
            }
            case CRUISE -> startLeg(be, rt, net);
            case ALTITUDE -> {
                ap.hover();
                startLeg(be, rt, net);
            }
            case RETREAT, HOLD -> {
                // Back to the point behind at the height it is at (the line may have been raised meanwhile): heights change over points only.
                rt.from = overPoint(net, be.route.get(be.leg), c.pos().y);
                retreat(be, rt);
            }
            default -> beginAlign(be, rt, net);
        }
        // Plans are not saved: this one is made again.
        if (be.flying()) replan(be, rt, 0);
        LOGGER.info("[Airline/flight] the aircraft at {} takes up its flight again ({}).", c.pos(), be.phase);
    }

    private static void fly(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        final Craft c = rt.craft;
        final Autopilot ap = rt.pilot;
        final boolean decide = rt.age % c.type.period == 0;
        switch (be.phase) {
            case CLIMB -> {
                final double line = legHeight(net, be.route, be.leg);
                ap.climbToward(line);
                ap.tick();
                if (ap.atHeight(line)) startLeg(be, rt, net);
            }
            case ALTITUDE -> {
                // Over a point, up or down to the height of the line ahead (set by startLeg); down as slowly as onto a pad.
                final Edge ahead = be.leg + 1 < be.route.size() ? net.edge(be.route.get(be.leg), be.route.get(be.leg + 1)) : null;
                if (decide && ahead != null && !ahead.opens(shipClass(be))) {
                    // The line ahead was closed meanwhile (something in its shaft): stay at this height and look for another way
                    // (and, with none, wait here at this height).
                    ap.altTarget = c.pos().y;
                    rt.from = overPoint(net, be.route.get(be.leg), c.pos().y);
                    reroute(be, rt, net);
                    return;
                }
                final double line = rt.from.y;
                if (decide) {
                    // Something solid in the way up or down (set there while the aircraft was on its way): it stays at this
                    // height, the lines over this point are told, and it waits and looks for another way.
                    final BlockPos seen = firstSolid(level, upOrDown(c, line));
                    if (seen != null) {
                        if (ahead != null) net.reportBlocked(level, ahead, seen);
                        final Edge behind = be.leg > 0 ? net.edge(be.route.get(be.leg - 1), be.route.get(be.leg)) : null;
                        if (behind != null) net.reportBlocked(level, behind, seen);
                        tell(be, "flight.note.blocked", seen.getX(), seen.getY(), seen.getZ());
                        ap.altTarget = c.pos().y;
                        rt.from = overPoint(net, be.route.get(be.leg), c.pos().y);
                        hold(be, rt, Phase.RETREAT);
                        return;
                    }
                }
                if (line >= ap.altTarget) ap.climbToward(line);
                else ap.altTarget = Math.max(line, ap.altTarget - c.type.descentRate / 20.0);
                ap.tick();
                if (ap.atHeight(line)) cruise(be, rt);
            }
            case CRUISE -> {
                // The height follows the line from one passing point to the next.
                final double total = Math.hypot(rt.to.x - rt.from.x, rt.to.z - rt.from.z);
                final double done = total < 1.0e-6 ? 1.0 : Math.max(0.0, Math.min(1.0, 1.0 - ap.remaining() / total));
                ap.altTarget = rt.from.y + (rt.to.y - rt.from.y) * done;
                ap.tick();
                if (decide) {
                    final Edge edge = be.leg + 1 < be.route.size() ? net.edge(be.route.get(be.leg), be.route.get(be.leg + 1)) : null;
                    BlockPos seen = lookAhead(level, c, rt, rt.to);
                    if (seen == null && edge != null && edge.state() == RouteNetwork.State.BLOCKED && edge.blockedAt() != null) {
                        // The network learnt of a block on this line (somebody placed one): it matters when it is still ahead.
                        final BlockPos known = edge.blockedAt();
                        final Tube whole = new Tube(rt.from, rt.to, 1, 1);
                        if (whole.progress(known.getX() + 0.5, known.getZ() + 0.5) > whole.progress(c.pos().x, c.pos().z)) seen = known;
                    }
                    if (seen != null) {
                        if (edge != null) net.reportBlocked(level, edge, seen);
                        tell(be, "flight.note.blocked", seen.getX(), seen.getY(), seen.getZ());
                        retreat(be, rt);
                        return;
                    }
                    final UUID inTheWay = shipAhead(level, c, rt, rt.to);
                    if (inTheWay != null) {
                        // Another ship is in the way: wait. When waiting does not help, go round it: back to the point
                        // behind and on over other lines. Only when there is no way round, and the flight is on its way
                        // out, it is given up and goes back to where it started (on its way back it can only wait).
                        final int patience = c.id().compareTo(inTheWay) < 0 ? SHIP_HOLDS : 2 * SHIP_HOLDS;
                        rt.shipHolds++;
                        final java.util.Set<Edge> without = new java.util.HashSet<>(rt.avoid);
                        if (edge != null) without.add(edge);
                        final boolean wayRound = edge != null && be.destination != null && net.route(be.route.get(be.leg), be.destination, shipClass(be), without).size() >= 2;
                        if (rt.shipHolds >= patience && wayRound) {
                            rt.shipHolds = 0;
                            rt.avoid.add(edge);
                            tell(be, "flight.note.going_round");
                            retreat(be, rt);
                        } else if (rt.shipHolds >= 2 * patience && !be.cancelled && !be.returning && be.origin != null) {
                            rt.shipHolds = 0;
                            be.cancelled = true;
                            be.cancelNote = "flight.note.gave_way";
                            be.destination = be.origin;
                            // Not sent again at once.
                            if (be.cargo) be.quietUntil = level.getGameTime() + Cargo.quietTicks();
                            tell(be, "flight.note.giving_way");
                            retreat(be, rt);
                        } else {
                            hold(be, rt, Phase.CRUISE);
                        }
                        return;
                    }
                    measure(level, rt, net);
                }
                if (ap.stopped && Math.hypot(c.vel().x, c.vel().z) < 0.05) arrived(be, rt, net);
            }
            case RETREAT -> {
                ap.altTarget = rt.from.y;
                ap.tick();
                if (decide && (lookAhead(level, c, rt, rt.from) != null || shipAhead(level, c, rt, rt.from) != null)) {
                    // Blocked ahead and behind: stay here, keep the height, look again later.
                    hold(be, rt, Phase.RETREAT);
                    return;
                }
                if (ap.stopped && Math.hypot(c.vel().x, c.vel().z) < 0.05) reroute(be, rt, net);
            }
            case HOLD -> {
                ap.tick();
                if (++be.timer >= HOLD_TICKS) {
                    if (rt.resume == Phase.CRUISE) startLeg(be, rt, net);
                    else retreat(be, rt);
                }
            }
            case ALIGN -> {
                // Over the pad: first across the pad's heading, then along it, the ship is placed over the middle of the pad;
                // a pilot cannot walk off a ship that rests half a block off the world's block grid.
                ap.tick();
                if (ap.placed || ++be.timer > 900) {
                    final Vec3 middle = Vec3.atBottomCenterOf(berthBlock(c.level, net, be.destination, c.type).below());
                    final boolean forced = redoAcrossNext && rt.placeStep == 1 && ap.placed && !airborne(net, be.destination);
                    if (forced) redoAcrossNext = false;
                    final double off = forced ? 0.5 : across(c, rt.heading, middle);
                    if (rt.placeStep == 1 && ap.placed && Math.abs(off) > c.type.placeWithin && rt.alignRounds < 2 && !airborne(net, be.destination)) {
                        acrossRedone++;
                        // Turning from across onto the pad's heading carries a ship sideways (koeda, 2026-10-16: 0.3 to 0.4 of a
                        // block at full turning speed, up to 0.2 with the slow turn), and going along the heading cannot take it
                        // back; the centre of mass stays where it is across while the ship goes down. Across again, aimed to the
                        // other side by what was left over, then the same turn onto the heading. Not beside a pier: aimed toward
                        // it, the large aircraft touched the pier on the way down and was pushed two blocks off (2026-10-16).
                        rt.alignRounds++;
                        rt.acrossAim -= off;
                        LOGGER.info("[Airline/flight] {} across the middle of the pad after turning onto its heading: placed across again (round {})", String.format("%.2f", Math.abs(off)), rt.alignRounds);
                        placeAcross(rt, middle);
                        rt.placeStep = 0;
                        be.timer = 0;
                        // It stays over the pad longer than planned: the others are told (one seated on its pad waits for it,
                        // instead of leaving by the old plan and stopping in the air: c139 traffic).
                        replan(be, rt, 0);
                    } else if (rt.placeStep++ == 0) {
                        // Along the pad's heading it is the aircraft's own block (the middle of the hull's floor) that is put
                        // over the middle of the pad, not the centre of mass: a design whose centre of mass lies ahead of
                        // its lift hangs with its nose down, its floor behind its centre of mass, and came down a third of
                        // a block astern of the middle; a pilot cannot step off a ship that lies that far into the next block.
                        ap.placeAt(rt.heading, middle.x, middle.z, true);
                        be.timer = 0;
                    } else {
                        // Down onto the pad with the aircraft's own block kept over the middle of the pad: left to itself
                        // for the half minute of the descent a ship drifts (a third of a block was measured).
                        ap.placeAt(rt.heading, middle.x, middle.z, true);
                        be.phase = Phase.DESCEND;
                        rt.still = 0;
                    }
                }
            }
            case DESCEND -> {
                final double rest = be.destination.getY() + 1 + be.restOffset;
                // At an anchorage there is no floor to come down onto: the aircraft is brought to rest at the pier's height
                // and held there (and fixed, as on a pad), the controller kept on.
                final boolean held = airborne(net, be.destination);
                ap.altTarget = Math.max(held ? rest : rest - 0.4, ap.altTarget - c.type.descentRate / 20.0);
                if (held ? Math.abs(c.pos().y - rest) < 0.15 : c.pos().y < rest + 0.12) {
                    if (rt.still++ == 0 && !held) ap.hover();      // touching the pad: no more pushes
                } else {
                    if (rt.still > 0) {
                        final Vec3 middle = Vec3.atBottomCenterOf(berthBlock(c.level, net, be.destination, c.type).below());
                        ap.placeAt(rt.heading, middle.x, middle.z, true);
                    }
                    rt.still = 0;
                }
                ap.tick();
                if (rt.still > 40 && held && docking(be, rt, net)) return;
                if (rt.still > 40) landed(be, rt, net);
            }
            default -> { }
        }
    }

    private static void startLeg(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        if (be.leg + 1 >= be.route.size()) {
            beginAlign(be, rt, net);
            return;
        }
        // Lines are level (2026-10-18): the whole leg at the height of its line.
        final double y = legHeight(net, be.route, be.leg);
        rt.from = overPoint(net, be.route.get(be.leg), y);
        rt.to = overPoint(net, be.route.get(be.leg + 1), y);
        final Vec3 here = rt.craft.pos();
        if (Math.abs(here.y - y) > 2.0) {
            if (Math.hypot(here.x - rt.from.x, here.z - rt.from.z) >= 4.0) {
                // Away from the point at another height (the line was raised or lowered while the aircraft was on it): back
                // to the point at the height it is at, and up or down there.
                rt.from = overPoint(net, be.route.get(be.leg), here.y);
                retreat(be, rt);
                return;
            }
            // The line lies higher or lower than the aircraft is: up or down over the point first, in its shaft.
            rt.pilot.hover();
            be.phase = Phase.ALTITUDE;
            be.setChanged();
            replan(be, rt, 0);
            return;
        }
        cruise(be, rt);
    }

    /** Along the leg from {@code rt.from} to {@code rt.to}. */
    private static void cruise(AircraftBlockEntity be, Runtime rt) {
        rt.pilot.leg(rt.from.x, rt.from.z, rt.to.x, rt.to.z);
        rt.tries = 0;
        rt.toldWaiting = false;
        be.phase = Phase.CRUISE;
        be.setChanged();
        replan(be, rt, 0);
    }

    /** The height of the line from the i-th point of a way to the next (lines are level); at the last point, where lines pass over it. */
    private static double legHeight(RouteNetwork net, List<BlockPos> way, int i) {
        if (way.isEmpty()) return 0.0;
        return i + 1 < way.size() ? net.height(way.get(i), way.get(i + 1)) : net.waypoint(way.get(Math.min(i, way.size() - 1))).y;
    }

    /** The place over a point of the network at the height {@code y}. */
    private static Vec3 overPoint(RouteNetwork net, BlockPos point, double y) {
        final Vec3 over = net.waypoint(point);
        return new Vec3(over.x, y, over.z);
    }

    /** The end of a leg: the line just flown is clear; on to the next point, or down when this was the last. */
    private static void arrived(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        final double miss = rt.pilot.remaining();
        final boolean last = be.leg + 2 >= be.route.size();
        if (miss > (last ? RouteRules.arrivalRadius() : 3.0) && rt.tries++ < 3) {
            rt.pilot.again();
            return;
        }
        rt.lastMiss = miss;
        rt.shipHolds = 0;
        rt.avoid.clear();      // a line was flown to its end: whatever was in the way elsewhere may be gone by now
        final Edge edge = net.edge(be.route.get(be.leg), be.route.get(be.leg + 1));
        // (Only a line flown at the height it has now: one raised meanwhile is still to be looked at up there.)
        if (edge != null && Math.abs(edge.height() - rt.from.y) < 0.5) net.confirmClear(edge);
        be.leg++;
        startLeg(be, rt, net);
    }

    private static void retreat(AircraftBlockEntity be, Runtime rt) {
        final Vec3 here = rt.craft.pos();
        rt.pilot.leg(here.x, here.z, rt.from.x, rt.from.z);
        be.phase = Phase.RETREAT;
        be.setChanged();
        replan(be, rt, 0);
    }

    private static void hold(AircraftBlockEntity be, Runtime rt, Phase resume) {
        rt.pilot.halt();
        rt.resume = resume;
        be.phase = Phase.HOLD;
        be.timer = 0;
        if (!rt.toldWaiting) tell(be, "flight.note.waiting");
        rt.toldWaiting = true;
        replan(be, rt, HOLD_TICKS);
    }

    /** Back at the point behind the blocked line: another way to the destination, else home, else wait here. */
    private static void reroute(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        final BlockPos here = be.route.get(be.leg);
        // (Without the lines it goes round because of another ship; when the way round is gone meanwhile, the same way again.)
        List<BlockPos> way = be.destination == null ? List.of() : net.route(here, be.destination, shipClass(be), rt.avoid);
        if (way.size() < 2 && !rt.avoid.isEmpty() && be.destination != null) {
            rt.avoid.clear();
            way = net.route(here, be.destination, shipClass(be));
        }
        if (way.size() < 2 && be.origin != null && !be.cancelled) {
            way = net.route(here, be.origin, shipClass(be));
            if (!way.isEmpty()) {
                be.cancelled = true;
                be.destination = be.origin;
                tell(be, "flight.note.turning_back");
            }
        }
        if (way.isEmpty()) {
            hold(be, rt, Phase.RETREAT);
            return;
        }
        be.route = new ArrayList<>(way);
        be.leg = 0;
        startLeg(be, rt, net);
    }

    private static void beginAlign(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        final ServerLevel level = rt.craft.level;
        // A pad that does not take this class (any more: the airfield was built anew as another kind while the aircraft
        // was on its way) is never gone down onto, as one without a floor.
        final boolean noBerth = be.destination != null && !berthsAt(net, be.destination, shipClass(be));
        final boolean noFloor = noBerth || (be.destination != null && berthMissing(level, net, be.destination));
        if (noFloor || (be.destination != null && stoodOn(level, be.destination, rt.craft.id()))) {
            // An airfield that is not built (or lost its pad), or a pad somebody stands on after all (an aircraft was made
            // there by command): never go down there.
            final BlockPos here = be.destination;
            if (!noFloor && here.equals(be.home)) {
                // At home it lands on its own pad and nowhere else: it waits above until whoever stands there is gone.
                hold(be, rt, Phase.CRUISE);
                return;
            }
            // Another pad of the same airfield, when it has one that is free.
            for (final BlockPos other : padsWith(level, here)) {
                if (other.equals(here) || !berthsAt(net, other, shipClass(be)) || berthMissing(level, net, other) || !free(level, other, rt.craft.id()) || net.route(here, other, shipClass(be)).size() < 2) continue;
                be.destination = other;
                be.route = new ArrayList<>(net.route(here, other, shipClass(be)));
                be.leg = 0;
                tell(be, "flight.note.other_pad", here.getX(), here.getY(), here.getZ());
                startLeg(be, rt, net);
                return;
            }
            // Else back to where it started, cancelled.
            final boolean gone = net.node(here) == null;
            List<BlockPos> back = be.cancelled || be.origin == null || be.origin.equals(here) ? List.of() : net.route(here, be.origin, shipClass(be));
            if (back.size() < 2 && gone && !be.cancelled && be.origin != null && !be.origin.equals(here) && be.route.size() >= 2 && here.equals(be.route.get(be.route.size() - 1))) {
                // The pad left the network while the aircraft was on its way (its airfield was taken away, or an anchorage's
                // colony lost its last airfield): no line leads from it any more. Back over the point it came from.
                final BlockPos before = be.route.get(be.route.size() - 2);
                final List<BlockPos> rest = before.equals(be.origin) ? List.of(before) : net.route(before, be.origin, shipClass(be));
                if (!rest.isEmpty()) {
                    back = new ArrayList<>();
                    back.add(here);
                    back.addAll(rest);
                }
            }
            if (back.size() >= 2) {
                be.cancelled = true;
                be.cancelNote = "flight.note.cancelled_pad";
                be.destination = be.origin;
                be.route = new ArrayList<>(back);
                be.leg = 0;
                tell(be, gone ? "flight.note.pad_gone" : noBerth ? "flight.note.no_berth" : noFloor ? "flight.note.no_floor" : "flight.note.pad_taken", here.getX(), here.getY(), here.getZ());
                startLeg(be, rt, net);
            } else if (noFloor) {
                strand(be, rt, gone ? "flight.note.pad_gone_stranded" : noBerth ? "flight.note.no_berth_stranded" : "flight.note.no_floor_stranded", here.getX(), here.getY(), here.getZ());
            } else {
                // Somebody stands where it must land and there is nowhere else to go: it waits above until the pad is free.
                hold(be, rt, Phase.CRUISE);
            }
            return;
        }
        final RouteNetwork.Node node = be.destination == null ? null : net.node(be.destination);
        rt.heading = node == null ? rt.craft.yaw() : node.heading();
        // Across first: the heading at a right angle to the pad's that is nearer to where the nose points now.
        rt.acrossHeading = Math.abs(Autopilot.wrap(rt.heading + 90 - rt.craft.yaw())) <= 90 ? rt.heading + 90 : rt.heading - 90;
        rt.acrossAim = 0;
        rt.alignRounds = 0;
        placeAcross(rt, Vec3.atBottomCenterOf(be.destination == null ? BlockPos.containing(rt.craft.pos()) : berthBlock(level, net, be.destination, AircraftType.of(be.type)).below()));
        rt.placeStep = 0;
        be.phase = Phase.ALIGN;
        be.timer = 0;
        be.setChanged();
        replan(be, rt, 0);
    }

    /** Across the pad: the centre of mass is put {@code acrossAim} to the right of the middle (looking along the pad's heading). */
    private static void placeAcross(Runtime rt, Vec3 middle) {
        final double h = Math.toRadians(rt.heading);
        rt.pilot.placeAt(rt.acrossHeading, middle.x - Math.sin(h) * rt.acrossAim, middle.z + Math.cos(h) * rt.acrossAim);
    }

    /** How far the ship's centre of mass lies to the right of the middle, across the pad's heading (degrees in the sense of {@link Craft#yaw()}). */
    private static double across(Craft c, double heading, Vec3 middle) {
        final double h = Math.toRadians(heading);
        final Vec3 p = c.pos();
        return (p.x - middle.x) * -Math.sin(h) + (p.z - middle.z) * Math.cos(h);
    }

    private static void landed(AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        final Craft c = rt.craft;
        c.drivesOff();
        c.lock(true);
        c.burner(c.type.parkedLever);
        Fuel.drain(c, be);
        Airspace.get(c.level.getServer()).remove(c.id());
        rt.avoid.clear();
        rt.shipHolds = 0;
        be.at = be.destination;
        be.landed = c.level.getGameTime();
        rt.lastHeadingError = Autopilot.wrap(rt.heading - c.yaw());
        // (How far the aircraft's own block lies from the middle of the pad: that is what says whether its blocks sit on the world's grid.)
        final Vec3 centre = Vec3.atBottomCenterOf(berthBlock(c.level, net, be.at, c.type)), at = c.ownBlock();
        rt.lastMiss = Math.hypot(at.x - centre.x, at.z - centre.z);
        final double h = Math.toRadians(rt.heading);
        be.note = String.format("landed %.2f from the middle of the pad (ahead %+.2f, to the right %+.2f), heading off by %.2f degrees", rt.lastMiss,
                (at.x - centre.x) * Math.cos(h) + (at.z - centre.z) * Math.sin(h), (at.x - centre.x) * -Math.sin(h) + (at.z - centre.z) * Math.cos(h), rt.lastHeadingError);
        LOGGER.info("[Airline/flight] aircraft {}", be.note);
        if (be.at.equals(be.home)) be.homeward = false;
        // Away from home with cargo the pilot's own job is told to stay by the aircraft: whenever nobody holds the pilot
        // or has it on the seat, it must not set out for its own airfield, a flight away.
        final Mob pilot = pilot(c.level, be);
        if (pilot != null && be.cargo && !be.at.equals(be.home)) post(pilot, leaveSpot(c.level, net, be, be.at, false));
        // A round called off on its way to one of its later colonies came back to the one before: the rest of the round is
        // given up (its goods no longer count as on their way and go home in the hold); from here it flies home.
        if (be.cargo && be.cancelled && be.stopAt > 0 && !be.stops.isEmpty() && !be.at.equals(be.home)) giveUpRound(c.level, be);
        // A cargo flight unloads first; otherwise the pilot gets off.
        be.phase = be.cargo ? Phase.UNLOAD : Phase.DISEMBARK;
        be.timer = 0;
        be.setChanged();
    }

    // ---- cargo --------------------------------------------------------------------------------------------

    private static WantLedger.Key key(ServerLevel level, int colony) {
        return new WantLedger.Key(level.dimension().location().toString(), colony);
    }

    static void setPilot(AircraftBlockEntity be, Mob pilot) {
        be.pilot = pilot.getUUID();
        be.pilotColony = 0;
        if (pilot instanceof AbstractEntityCitizen citizen && citizen.getCitizenData() != null) {
            be.pilotColony = citizen.getCitizenData().getColony().getID();
            be.pilotCitizen = citizen.getCitizenData().getId();
        }
    }

    /**
     * At home with nothing to do: is there cargo for somebody? (Looked at every five seconds.) Flights are scheduled: the
     * loading begins as soon as there is something to load, the aircraft leaves when its berth is over.
     */
    /**
     * Asked for in the airfield's timetable tab: the aircraft that stands on this airfield's pads leaves now, without
     * waiting out its berth. At home it looks at once for goods to carry (and says so when there are none); away, its
     * berth is over and it sets out as soon as loading and unloading allow. Returns the key of what was said.
     */
    static String departNow(ServerLevel level, com.asteain.airline.colony.BuildingAirfield airfield, @Nullable net.minecraft.server.level.ServerPlayer player) {
        final RouteNetwork net = RouteNetwork.get(level);
        final long now = level.getGameTime();
        for (final AircraftBlockEntity be : loaded(level)) {
            if (be.at == null || !airfield.hasPad(be.at) || be.flying()) continue;
            final Runtime rt = runtime(be);
            if (rt == null) continue;
            be.landed = Math.min(be.landed, now - Cargo.berthTicks());
            be.quietUntil = 0;
            be.setChanged();
            if (be.phase == Phase.PARKED && be.at.equals(be.home)) {
                if (be.modifying) return said(player, "message." + Airline.MOD_ID + ".yard.modifying");
                if (!be.lacks.isEmpty()) return said(player, "message." + Airline.MOD_ID + ".yard.lacks", Shipyard.partNames(be.lacks));
                if (be.carryCount == 0) dispatch(level, be, rt, net);
                return said(player, be.phase == Phase.PARKED ? "message." + Airline.MOD_ID + ".depart.nothing" : "message." + Airline.MOD_ID + ".depart.going");
            }
            return said(player, "message." + Airline.MOD_ID + ".depart.soon");
        }
        return said(player, "message." + Airline.MOD_ID + ".depart.none");
    }

    private static String said(@Nullable net.minecraft.server.level.ServerPlayer player, String key, Object... args) {
        if (player != null) player.sendSystemMessage(Component.translatable(key, args));
        return key.substring(key.lastIndexOf('.') + 1);
    }

    /** How many colonies a round calls at, at most (2026-10-17, the recommended answer: three). */
    static final int ROUND = 3;

    /** The colonies of a round in the order of the shortest way round, from home and back; when no order has ways all round, the last-ranked colony is left out. */
    private static List<Cargo.Order> shortestRound(ServerLevel level, RouteNetwork net, BlockPos home, List<Cargo.Order> all, com.asteain.airline.route.ShipClass cls) {
        for (int n = all.size(); n > 1; n--) {
            List<Cargo.Order> best = null;
            double shortest = Double.POSITIVE_INFINITY;
            for (final List<Cargo.Order> order : orders(all.subList(0, n))) {
                double length = leg(net, home, order.get(0).pad(), cls) + leg(net, order.get(order.size() - 1).pad(), home, cls);
                for (int i = 0; i + 1 < order.size(); i++) {
                    // From one colony of the round to the next only where a route is drawn between the two (2026-10-18).
                    final boolean drawn = com.asteain.airline.route.ColonyRoutes.drawn(level, order.get(i).to().getColony().getID(), order.get(i + 1).to().getColony().getID());
                    length += drawn ? leg(net, order.get(i).pad(), order.get(i + 1).pad(), cls) : Double.POSITIVE_INFINITY;
                }
                if (length < shortest) {
                    shortest = length;
                    best = order;
                }
            }
            if (best != null) return best;
        }
        return List.of(all.get(0));
    }

    /** All the orders of a few things. */
    private static List<List<Cargo.Order>> orders(List<Cargo.Order> things) {
        if (things.size() <= 1) return List.of(new ArrayList<>(things));
        final List<List<Cargo.Order>> all = new ArrayList<>();
        for (int i = 0; i < things.size(); i++) {
            final List<Cargo.Order> rest = new ArrayList<>(things);
            final Cargo.Order head = rest.remove(i);
            for (final List<Cargo.Order> tail : orders(rest)) {
                final List<Cargo.Order> one = new ArrayList<>();
                one.add(head);
                one.addAll(tail);
                all.add(one);
            }
        }
        return all;
    }

    private static double leg(RouteNetwork net, BlockPos from, BlockPos to, com.asteain.airline.route.ShipClass cls) {
        final List<BlockPos> way = net.route(from, to, cls);
        return way.size() < 2 ? Double.POSITIVE_INFINITY : net.length(way);
    }

    private static String roundText(AircraftBlockEntity be) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < be.stops.size(); i++) text.append(i == 0 ? "" : ", ").append("colony ").append(be.stops.get(i).colony);
        return text.toString();
    }

    /**
     * The loading for a round is over: back to the first colony; colonies nothing could be loaded for are not called at;
     * a round of one colony is a plain flight. The pad of the (new) first colony is looked for again.
     */
    private static void startRound(ServerLevel level, AircraftBlockEntity be, Runtime rt) {
        be.select(0);
        final List<AircraftBlockEntity.Stop> kept = new ArrayList<>();
        for (int i = 0; i < be.stops.size(); i++) {
            final AircraftBlockEntity.Stop stop = be.stops.get(i);
            if (!(i == 0 ? be.manifest : stop.goods).isEmpty()) kept.add(stop);
        }
        if (kept.size() == be.stops.size() && kept.size() > 1) return;
        final AircraftBlockEntity.Stop firstWas = be.stops.get(0);
        firstWas.goods.clear();
        firstWas.goods.putAll(be.manifest);
        be.forgetStops();
        if (kept.isEmpty()) {
            be.manifest.clear();
            return;
        }
        if (kept.size() > 1) be.stops.addAll(kept);
        final AircraftBlockEntity.Stop first = kept.get(0);
        be.manifest.clear();
        be.manifest.putAll(first.goods);
        be.freightColony = first.colony;
        if (first != firstWas) {
            final RouteNetwork net = RouteNetwork.get(level);
            final Cargo.Landing place = Cargo.landingIn(level, net, be.at, first.colony, rt.craft.id(), shipClass(be));
            if (place.there() != null) be.destination = place.landing().pad();
        }
    }

    /**
     * Ticks a round takes from {@code from}: to {@code to} (the place of the colony at {@link AircraftBlockEntity#stopAt}),
     * on to each later colony of the round (its first place that takes the aircraft) and home. Legs with no way are left out.
     */
    private static long roundTicks(ServerLevel level, RouteNetwork net, AircraftBlockEntity be, BlockPos from, BlockPos to, java.util.UUID ship) {
        final AircraftType type = AircraftType.of(be.type);
        long ticks = flightTicks(net, net.route(from, to, shipClass(be)), type);
        BlockPos at = to;
        for (int i = be.stopAt + 1; i < be.stops.size(); i++) {
            final Cargo.Landing place = Cargo.landingIn(level, net, at, be.stops.get(i).colony, ship, shipClass(be));
            if (place.there() == null) continue;
            ticks += flightTicks(net, net.route(at, place.landing().pad(), shipClass(be)), type);
            at = place.landing().pad();
        }
        return ticks + (be.home == null ? 0 : flightTicks(net, net.route(at, be.home, shipClass(be)), type));
    }

    /**
     * Away, the berth over: on to the next colony of a round, if there is one with a way, a place to land and fuel enough
     * for the rest of the round. Colonies that cannot be got to are given up (their goods come home in the hold).
     * @return true when it flies on (false: it flies home)
     */
    private static boolean nextStop(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        while (be.stopAt + 1 < be.stops.size()) {
            be.select(be.stopAt + 1);
            final Cargo.Landing place = Cargo.landingIn(level, net, be.at, be.freightColony, rt.craft.id(), shipClass(be));
            final List<BlockPos> way = place.there() == null ? List.of() : net.route(be.at, place.landing().pad(), shipClass(be));
            if (way.size() >= 2 && Fuel.total(be) >= Fuel.need(rt.craft.type, roundTicks(level, net, be, be.at, place.landing().pad(), rt.craft.id()))) {
                be.settled = false;
                be.origin = be.at;
                be.destination = place.landing().pad();
                be.route = new ArrayList<>(way);
                be.leg = 0;
                be.timer = 0;
                be.phase = Phase.BOARDING;      // the pilot sits already: this takes off at once
                note(be, "on to colony " + be.freightColony + " (a round: " + roundText(be) + ")");
                be.setChanged();
                return true;
            }
            note(be, "colony " + be.freightColony + " of the round is left out: " + (place.there() == null ? place.landing().why() : way.size() < 2 ? "no way there" : "not fuel enough for the rest of the round"));
            leaveOut(level, be);
        }
        return false;
    }

    /** The colony at {@link AircraftBlockEntity#stopAt} is not called at: its goods no longer count as on their way (they stay in the hold). */
    private static void leaveOut(ServerLevel level, AircraftBlockEntity be) {
        final WantLedger ledger = WantLedger.get(level.getServer());
        for (final Map.Entry<String, Integer> line : be.manifest.entrySet()) ledger.cover(key(level, be.freightColony), Cargo.item(line.getKey()), -line.getValue());
        if (!be.manifest.isEmpty()) ledger.log(String.format("colony %d -> colony %d: left out of the round; %d items come back in the hold", be.homeColony, be.freightColony, be.manifest.values().stream().mapToInt(Integer::intValue).sum()));
        be.manifest.clear();
        be.settled = true;
        be.setChanged();
    }

    /** The rest of a round is given up where the aircraft stands (it came back here, its way on called off). */
    private static void giveUpRound(ServerLevel level, AircraftBlockEntity be) {
        leaveOut(level, be);
        while (be.stopAt + 1 < be.stops.size()) {
            be.select(be.stopAt + 1);
            leaveOut(level, be);
        }
        be.cancelled = false;
        be.forgetStops();
        be.note = "the rest of the round was given up";
    }

    private static void dispatch(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        final long now = level.getGameTime();
        if (be.home == null || !be.home.equals(be.at)) return;
        if (!be.cargo && (rt.clearedAt == 0 || now - rt.clearedAt >= CLEAR_OUT_EVERY) && !Cargo.contents(Cargo.hold(rt.craft)).isEmpty()) {
            // Goods of a flight that ended on its way are still in the hold: they go back onto the export racks first
            // (they belong to no flight any more, and nothing else would ever take them out).
            rt.clearedAt = now;
            be.roundTrip = false;
            be.returning = false;
            be.cancelled = false;
            be.manifest.clear();
            be.returnManifest.clear();
            be.forgetStops();
            be.timer = 0;
            be.phase = Phase.UNLOAD;
            be.note = "clearing out the hold";
            be.setChanged();
            return;
        }
        if (now < be.quietUntil) return;
        final List<String> barred = new ArrayList<>();
        final Cargo.Order order = Cargo.consider(level, be, net, rt.craft.id(), barred);
        // (Looked at again every five seconds; said once.)
        if (!barred.isEmpty() && !rt.toldNoPad) note(be, "goods lie ready, but the aircraft cannot land there now: " + String.join("; ", barred));
        rt.toldNoPad = !barred.isEmpty();
        if (order == null) return;
        be.forgetStops();
        Cargo.Order first = order;
        if (!order.fetch()) {
            // A round (2026-10-17): up to three colonies, called at the shortest way round from home and back.
            final List<Cargo.Order> all = new ArrayList<>(List.of(order));
            all.addAll(Cargo.moreStops(level, be, net, rt.craft.id(), order, ROUND - 1));
            if (all.size() > 1) {
                final List<Cargo.Order> round = shortestRound(level, net, be.home, all, shipClass(be));
                if (round.size() > 1) {
                    for (final Cargo.Order stop : round) be.stops.add(new AircraftBlockEntity.Stop(stop.to().getColony().getID()));
                    first = round.get(0);
                }
            }
        }
        be.cargo = true;
        be.fetch = order.fetch();
        be.settled = false;
        be.roundTrip = true;
        be.returning = false;
        be.cancelled = false;
        be.origin = be.home;
        be.destination = first.pad();
        be.route = new ArrayList<>(net.route(be.origin, be.destination, shipClass(be)));
        be.leg = 0;
        be.freightColony = first.to().getColony().getID();
        be.homeColony = order.from().getColony().getID();
        be.manifest.clear();
        be.returnManifest.clear();
        setPilot(be, order.pilot());
        be.timer = 0;
        be.phase = Phase.LOAD;
        be.note = order.fetch() ? "going to fetch goods from colony " + be.freightColony : "loading for colony " + be.freightColony + (be.stops.isEmpty() ? "" : " (a round: " + roundText(be) + ")");
        LOGGER.info("[Airline/flight] cargo flight to colony {}: {} wanted items lie ready {}.", be.freightColony, order.ready(), order.fetch() ? "there; the flight goes out empty to fetch them"
                : be.stops.isEmpty() ? "here; loading" : "here; loading for a round: " + roundText(be));
        be.setChanged();
        // At once, not with the next second: the pad it will land on counts as taken from now on.
        publish(level, be, rt, net);
    }

    /**
     * At home, fixed: goods the destination wants go from the export racks into the hold, a stack at a time, for as long
     * as the berth lasts. It leaves when the berth is over and nothing more is to be loaded.
     */
    private static void load(ServerLevel level, AircraftBlockEntity be, Runtime rt) {
        be.timer++;
        if (Handling.between(be)) return;
        if (fuelling(be) && Handling.move(level, be, rt, Handling.Flow.FUEL)) return;
        if (Handling.move(level, be, rt, Handling.Flow.LOAD_OUT)) {
            rt.idleStops = 0;
            return;
        }
        if (be.stops.size() > 1 && be.carryCount == 0 && ++rt.idleStops < be.stops.size()) {
            // A round: the next colony's goods are looked for (one colony at a time: the ledger is told for whom).
            be.select((be.stopAt + 1) % be.stops.size());
            return;
        }
        rt.idleStops = 0;
        if (level.getGameTime() < be.landed + Cargo.berthTicks()) return;      // more may be brought while it waits
        if (!be.stops.isEmpty()) startRound(level, be, rt);
        if (be.manifest.isEmpty() && !be.fetch) {
            // Nothing could be loaded after all (taken away meanwhile, or the hold is full of something else).
            be.cargo = false;
            be.phase = Phase.PARKED;
            be.quietUntil = level.getGameTime() + 600;
            be.note = "nothing to load";
            return;
        }
        // The way and the pad were found when the loading began; that can be a day ago.
        final RouteNetwork net = RouteNetwork.get(level);
        final Landing landing = be.destination == null ? new Landing(null, "flight.no_route") : landing(level, net, be.at, be.destination, rt.craft.id(), false, shipClass(be));
        if (landing.pad() == null) {
            if (!rt.toldWaiting) tell(be, "flight.note.no_way_out");
            rt.toldWaiting = true;
            return;      // looked at again with the next step
        }
        be.destination = landing.pad();
        final List<BlockPos> way = net.route(be.at, be.destination, shipClass(be));
        // Out and back (or round), with what the tank holds now (the racks had their chance above).
        final long fuelNeeded = Fuel.need(rt.craft.type, be.stops.isEmpty() ? flightTicks(net, way, rt.craft.type) * 2 : roundTicks(level, net, be, be.at, be.destination, rt.craft.id()));
        if (Fuel.total(be) < fuelNeeded) {
            // (More than the tank holds: no fuel on the racks will help. It is said as what it is.)
            if (!rt.toldNoFuel) tellColony(level, be, fuelNeeded > Fuel.capacity() ? "flight.note.too_far" : "flight.note.waiting_for_fuel",
                    Fuel.minutes(rt.craft.type, fuelNeeded > Fuel.capacity() ? Fuel.capacity() : Fuel.total(be)), Fuel.minutes(rt.craft.type, fuelNeeded));
            rt.toldNoFuel = true;
            return;
        }
        rt.toldNoFuel = false;
        // A pilot asleep in its bed is not dragged out: the flight leaves when the pilot is up.
        if (Handling.pilotAsleep(level, be)) return;
        rt.toldWaiting = false;
        be.route = new ArrayList<>(way);
        be.leg = 0;
        be.timer = 0;
        be.phase = Phase.BOARDING;
        be.setChanged();
    }

    /** Away from home, the unloading is over: the ledger is told what arrived, by what is really still in the hold. */
    private static void settleAway(ServerLevel level, AircraftBlockEntity be, Craft c) {
        final WantLedger ledger = WantLedger.get(level.getServer());
        final WantLedger.Key there = key(level, be.freightColony);
        final IItemHandler hold = Cargo.hold(c);
        int delivered = 0, kept = 0;
        for (final Map.Entry<String, Integer> line : be.manifest.entrySet()) {
            final Item item = Cargo.item(line.getKey());
            final int left = Math.min(line.getValue(), Cargo.count(hold, item)), arrived = line.getValue() - left;
            ledger.cover(there, item, -left);
            ledger.delivered(there, item, arrived);
            delivered += arrived;
            kept += left;
            line.setValue(left);
        }
        be.manifest.values().removeIf(count -> count <= 0);
        be.settled = true;
        if (delivered == 0) be.quietUntil = level.getGameTime() + Cargo.quietTicks();
        ledger.log(String.format("colony %d -> colony %d: delivered %d, carried back %d", be.homeColony, be.freightColony, delivered, kept));
        be.note = "delivered " + delivered + ", carrying back " + kept;
        be.setChanged();
    }

    /**
     * Fixed on a pad after a cargo flight. Away from home: the goods go into the import store (what does not fit stays in
     * the hold), then goods that home wants are loaded from this airfield's export racks for as long as the berth lasts,
     * then it flies home (with or without goods). At home:
     * what was brought goes into the import store, what came back undelivered goes back onto the export racks.
     */
    private static void unload(ServerLevel level, AircraftBlockEntity be, Runtime rt, RouteNetwork net) {
        be.timer++;
        if (be.at == null || Handling.between(be)) return;
        final Craft c = rt.craft;
        final boolean carried = Cargo.pilotCarries(), berthOver = level.getGameTime() >= be.landed + Cargo.berthTicks();
        final WantLedger ledger = WantLedger.get(level.getServer());
        final WantLedger.Key there = key(level, be.freightColony), home = key(level, be.homeColony);
        final BuildingAirfield here = Cargo.airfieldAt(level, be.at);
        if (!be.at.equals(be.home)) {
            if (!be.settled) {
                if (Handling.move(level, be, rt, Handling.Flow.UNLOAD_THERE)) return;
                // The store is full: with somebody carrying, the rest waits for room (couriers empty the store) until the berth is over.
                if (carried && !berthOver && here != null && Cargo.importStore(level, here) != null && Handling.left(c, be.manifest) > 0) {
                    Handling.rest(level, be, rt);
                    return;
                }
                // Nothing more fits, or nothing is left.
                settleAway(level, be, c);
                return;
            }
            if (Handling.move(level, be, rt, Handling.Flow.LOAD_BACK)) return;
            if (!berthOver) {
                // The berth here is not over: more may be brought for the way back.
                if (carried) Handling.rest(level, be, rt);
                return;
            }
            if (nextStop(level, be, rt, net)) return;
            final List<BlockPos> back = be.home == null || !free(level, be.home, c.id()) ? List.of() : net.route(be.at, be.home, shipClass(be));
            if (back.size() < 2) {
                if (!rt.toldWaiting) tell(be, "flight.note.no_way_back");
                rt.toldWaiting = true;
                if (carried) Handling.rest(level, be, rt);
                return;      // looked at again with the next step
            }
            be.origin = be.at;
            be.destination = be.home;
            be.route = new ArrayList<>(back);
            be.leg = 0;
            be.returning = true;
            be.timer = 0;
            be.phase = Phase.BOARDING;      // the pilot sits already: this takes off at once
            be.setChanged();
            return;
        }
        if (here != null) {
            if (be.cargo) {
                if (Handling.move(level, be, rt, Handling.Flow.UNLOAD_HOME)) return;
                if (carried && !berthOver && Cargo.importStore(level, here) != null && Handling.left(c, be.returnManifest) > 0) return;      // waits for room, as above
                if (Handling.move(level, be, rt, Handling.Flow.PUT_BACK)) return;
            }
            // Whatever else lies in the hold (left there by a flight that ended on its way) goes onto the export racks too.
            if (Handling.move(level, be, rt, Handling.Flow.CLEAR_OUT)) return;
        }
        // Never settled with goods in the pilot's hands (the airfield is gone): they go back into the hold and count as not arrived.
        if (be.carryCount > 0) Handling.finishNow(level, be, rt);
        final IItemHandler hold = Cargo.hold(c);
        int brought = 0, returned = 0;
        // (Without a cargo flight the lists are empty: only the hold was cleared out.)
        for (final Map.Entry<String, Integer> line : be.returnManifest.entrySet()) {
            final Item item = Cargo.item(line.getKey());
            final int left = Math.min(line.getValue(), Cargo.count(hold, item)), arrived = line.getValue() - left;
            ledger.cover(home, item, -left);
            ledger.delivered(home, item, arrived);
            brought += arrived;
        }
        for (final Map.Entry<String, Integer> line : be.manifest.entrySet()) {
            // Still listed: it never reached the other colony (cancelled on the way), or did not fit there (then the ledger knows already).
            if (!be.settled) ledger.cover(there, Cargo.item(line.getKey()), -line.getValue());
            returned += line.getValue();
        }
        // The colonies of a round it never got to: their goods no longer count as on their way (they go onto the racks with the rest).
        for (int i = be.stopAt + 1; i < be.stops.size(); i++) {
            for (final Map.Entry<String, Integer> line : be.stops.get(i).goods.entrySet()) {
                ledger.cover(key(level, be.stops.get(i).colony), Cargo.item(line.getKey()), -line.getValue());
                returned += line.getValue();
            }
        }
        be.forgetStops();
        if (brought > 0 || returned > 0) ledger.log(String.format("colony %d <- colony %d: brought %d, put back on the export racks %d", be.homeColony, be.freightColony, brought, returned));
        be.manifest.clear();
        be.returnManifest.clear();
        be.cargo = false;
        be.settled = false;
        be.phase = Phase.DISEMBARK;
        be.timer = 0;
        be.setChanged();
    }

    // ---- looking ahead ------------------------------------------------------------------------------------

    /**
     * The part of the way that is looked at: a piece of the line being flown (the very tube the network verified), from
     * where the ship is along that line to as far toward {@code target} (one of the line's two ends) as the rules say.
     */
    private static Tube ahead(Craft c, Runtime rt, Vec3 target) {
        final Vec3 p = c.pos();
        final double t = new Tube(rt.from, rt.to, 1, 1).progress(p.x, p.z);
        final Vec3 on = new Vec3(rt.from.x + (rt.to.x - rt.from.x) * t, rt.from.y + (rt.to.y - rt.from.y) * t, rt.from.z + (rt.to.z - rt.from.z) * t);
        final double dx = target.x - on.x, dz = target.z - on.z, dist = Math.hypot(dx, dz);
        // As far as the rules say, and for a fast design twice as far as it needs to come to rest, and a little more.
        final AircraftType type = c.type;
        final double rest = type.stopDistance + type.topSpeed * (type.brakeSeconds > 0 ? type.brakeSeconds : type.coastSeconds);
        final double look = Math.min(dist, Math.max(RouteRules.lookAhead(), 2.0 * rest + 8.0));
        final Vec3 end = dist < 1.0e-6 ? on : new Vec3(on.x + dx / dist * look, on.y + (target.y - on.y) * look / dist, on.z + dz / dist * look);
        return new Tube(on, end, RouteRules.tubeRadius(type.shipClass), RouteRules.tubeHalfHeight(type.shipClass));
    }

    /** The first solid block in the tube ahead, in chunks that are loaded (the ship's ticket keeps its surroundings loaded). */
    @Nullable
    private static BlockPos lookAhead(ServerLevel level, Craft c, Runtime rt, Vec3 target) {
        return firstSolid(level, ahead(c, rt, target));
    }

    /** The upright piece of the shaft over a point the aircraft goes up or down in next, toward {@code goal}, as far as it looks ahead. */
    private static Tube upOrDown(Craft c, double goal) {
        final Vec3 p = c.pos();
        final double step = Math.max(-RouteRules.lookAhead(), Math.min(RouteRules.lookAhead(), goal - p.y));
        return new Tube(p, new Vec3(p.x, p.y + step, p.z), RouteRules.tubeRadius(c.type.shipClass), RouteRules.tubeHalfHeight(c.type.shipClass));
    }

    /** The first solid block in a tube, in chunks that are loaded. */
    @Nullable
    private static BlockPos firstSolid(ServerLevel level, Tube tube) {
        final int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = tube.minX(); x <= tube.maxX(); x++) {
            for (int z = tube.minZ(); z <= tube.maxZ(); z++) {
                if (!tube.touchesColumn(x, z)) continue;
                final LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                final int from = Math.max(minY, (int) Math.ceil(tube.low(x, z) - 0.5)), to = Math.min(maxY, (int) Math.floor(tube.high(x, z) - 0.5));
                for (int y = from; y <= to; y++) {
                    final BlockState state = chunk.getBlockState(pos.set(x, y, z));
                    if (RouteNetwork.solid(level, pos, state)) return pos.immutable();
                }
            }
        }
        return null;
    }

    /**
     * The ship whose box reaches into the tube ahead, if there is one. An aircraft that stands on a landing pad is passed
     * closely (the lines of an airfield's other pad run right above it): the network lets no line come too low over a pad
     * ({@link RouteRules#grazes}), and such an aircraft is only in the way where the line ahead does that all the same.
     */
    @Nullable
    private static UUID shipAhead(ServerLevel level, Craft c, Runtime rt, Vec3 target) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        final Tube tube = ahead(c, rt, target);
        final double low = Math.min(tube.from.y, tube.to.y) - tube.halfHeight, high = Math.max(tube.from.y, tube.to.y) + tube.halfHeight;
        for (final SubLevel other : container.getAllSubLevels()) {
            if (other == c.ship || other.isRemoved()) continue;
            final BoundingBox3dc box = other.boundingBox();
            final AircraftBlockEntity standing = LOADED.get(other.getUniqueId());
            if (standing != null && standing.at != null) {
                if (RouteRules.grazes(tube, standing.at, AircraftType.of(standing.type).shipClass, c.type.shipClass)) return other.getUniqueId();
                continue;
            }
            if (box.maxY() < low || box.minY() > high) continue;
            // Nearest place of the other ship's box to the line, seen from above.
            final double x = Math.max(box.minX(), Math.min(c.pos().x, box.maxX())), z = Math.max(box.minZ(), Math.min(c.pos().z, box.maxZ()));
            final double ex = Math.max(box.minX(), Math.min(tube.to.x, box.maxX())), ez = Math.max(box.minZ(), Math.min(tube.to.z, box.maxZ()));
            if (tube.flatDistance(x, z) <= tube.radius || tube.flatDistance(ex, ez) <= tube.radius) return other.getUniqueId();
        }
        return null;
    }

    /**
     * For the record: how near the ship comes to any buoy block while it flies. Far from a buoy the ship's box in the
     * world is near enough to the truth (it is never nearer than that); within three blocks of it the blocks of the ship
     * are looked at one by one, where they are now. (The box of a ship that pitches when it comes to rest over a buoy
     * reaches far below its bow and its stern, where the buoy is not: a fast design seemed to graze the buoys it passed.)
     */
    private static void measure(ServerLevel level, Runtime rt, RouteNetwork net) {
        final BoundingBox3dc box = rt.craft.ship.boundingBox();
        for (final RouteNetwork.Node node : net.nodes()) {
            if (node.kind() != RouteNetwork.Kind.BUOY) continue;
            final BlockPos b = node.pos();
            final double dx = Math.max(0, Math.max(box.minX() - (b.getX() + 1), b.getX() - box.maxX()));
            final double dy = Math.max(0, Math.max(box.minY() - (b.getY() + 1), b.getY() - box.maxY()));
            final double dz = Math.max(0, Math.max(box.minZ() - (b.getZ() + 1), b.getZ() - box.maxZ()));
            final double rough = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (rough >= rt.closestBuoy) continue;
            rt.closestBuoy = Math.min(rt.closestBuoy, rough > 3.0 ? rough : Math.max(rough, rt.craft.nearest(b)));
        }
    }

    // ---- telling people -----------------------------------------------------------------------------------

    static void tell(AircraftBlockEntity be, String key, Object... args) {
        final Component text = Component.translatable("commands." + Airline.MOD_ID + "." + key, args);
        be.note = text.getString();
        be.setChanged();
        LOGGER.info("[Airline/flight] {}", be.note);
        if (be.getLevel() instanceof ServerLevel level) {
            for (final ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (player.hasPermissions(2)) player.sendSystemMessage(text);
            }
        }
    }

    // ---- making an aircraft, sending it -------------------------------------------------------------------

    /**
     * The mass of an aircraft's ship, and where its centre of mass lies seen from the middle of the aircraft's own block
     * (toward the nose, up, to the right): what a design has to keep small. Null when the ship is not loaded.
     */
    @Nullable
    static double[] weight(ServerLevel level, AircraftBlockEntity be) {
        final ServerSubLevel ship = Craft.shipAt(level, be.getBlockPos());
        if (ship == null) return null;
        final Object tracker = Craft.call(ship, "getMassTracker");
        final double mass = ((Number) Craft.call(tracker, "getMass")).doubleValue();
        if (!(Craft.call(tracker, "getCenterOfMass") instanceof org.joml.Vector3dc centre)) return new double[] {mass, Double.NaN, Double.NaN, Double.NaN};
        final BlockPos base = be.getBlockPos();
        return new double[] {mass, centre.x() - (base.getX() + 0.5), centre.y() - (base.getY() + 0.5), centre.z() - (base.getZ() + 0.5)};
    }

    /** Why the last aircraft could not be made, in words for whoever works on the design (the answer itself only names the kind of trouble). */
    static String lastFault = "";

    static Answer spawn(ServerLevel level, BlockPos pad, AircraftType type) {
        lastFault = "";
        final RouteNetwork net = RouteNetwork.get(level);
        final RouteNetwork.Node node = net.node(pad);
        if (node == null || node.kind() != RouteNetwork.Kind.AIRFIELD) return new Answer(false, "flight.no_pad");
        // Only where this class may berth: an aircraft's home is a pad that takes it. Never at an anchorage (no home).
        if (node.facility() != null && !node.facility().airfield()) return new Answer(false, "flight.anchorage_no_home");
        if (!node.berths(type.shipClass)) return new Answer(false, "flight.no_berth");
        // Not on a pad where an aircraft stands, is expected, or has its home (also one that is not loaded now).
        loaded(level);
        if (!free(level, pad, null)) return new Answer(false, "flight.pad_taken");
        final BlockPos deck = pad.above();
        final Map<BlockPos, BlockState> states = new HashMap<>();
        try {
            for (final Map.Entry<BlockPos, String> part : type.parts.entrySet())
                states.put(part.getKey(), BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), part.getValue(), false).blockState());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            LOGGER.error("[Airline/flight] the aircraft design '{}' names a block that does not exist.", type.name, e);
            lastFault = "a block or block state that does not exist: " + e.getMessage();
            return new Answer(false, "flight.bad_design");
        }
        states.put(BlockPos.ZERO, Airline.AIRCRAFT.get().defaultBlockState());
        for (final BlockPos part : states.keySet()) {
            final BlockPos world = deck.offset(part);
            if (!level.getBlockState(world).getCollisionShape(level, world).isEmpty()) return new Answer(false, "flight.no_room", world.getX(), world.getY(), world.getZ());
        }
        final List<BlockPos> blocks = new ArrayList<>();
        // From the bottom up, and whole blocks before the rest of their layer: what hangs or stands on something
        // (levers, lanterns, carpets) finds it there.
        final List<Map.Entry<BlockPos, BlockState>> order = new ArrayList<>(states.entrySet());
        order.sort(java.util.Comparator.<Map.Entry<BlockPos, BlockState>>comparingInt(part -> part.getKey().getY())
                .thenComparingInt(part -> part.getValue().isCollisionShapeFullBlock(level, deck.offset(part.getKey())) ? 0 : 1)
                .thenComparingInt(part -> part.getKey().getX()).thenComparingInt(part -> part.getKey().getZ()));
        for (final Map.Entry<BlockPos, BlockState> part : order) {
            final BlockPos world = deck.offset(part.getKey());
            level.setBlock(world, part.getValue(), 3);
            blocks.add(world);
        }
        // Fences, panes and stairs of the hull take the shape their neighbours give them (a design names them without it).
        for (final Map.Entry<BlockPos, BlockState> part : order) {
            if (!"minecraft".equals(part.getValue().getBlock().builtInRegistryHolder().key().location().getNamespace())) continue;
            final BlockPos world = deck.offset(part.getKey());
            final BlockState is = level.getBlockState(world), shaped = Block.updateFromNeighbourShapes(is, level, world);
            if (shaped != is && shaped.getBlock() == is.getBlock()) level.setBlock(world, shaped, 2 | 16);
        }
        // A part that did not stay (it needed a support the design does not give it) would be missing from the ship.
        for (final Map.Entry<BlockPos, BlockState> part : order) {
            final BlockPos world = deck.offset(part.getKey());
            if (level.getBlockState(world).getBlock() != part.getValue().getBlock()) {
                LOGGER.error("[Airline/flight] the aircraft design '{}': {} did not stay at {} (found {}).", type.name, part.getValue(), part.getKey(), level.getBlockState(world));
                lastFault = part.getValue() + " did not stay at " + part.getKey().toShortString() + " (it needs something to hang or stand on that the design does not give it); found there: " + level.getBlockState(world);
                for (final BlockPos placed : blocks) level.removeBlock(placed, false);
                return new Answer(false, "flight.bad_design");
            }
        }
        return launch(level, net, node, pad, type, blocks, true);
    }

    /**
     * An aircraft that was built block by block on its pad, in the pad's heading (by a builder from the design's blueprint,
     * or by hand), becomes a ship: every block of the design stands around the aircraft's own block on the middle of the pad.
     */
    static Answer commission(ServerLevel level, BlockPos pad, AircraftType type) {
        final RouteNetwork net = RouteNetwork.get(level);
        final RouteNetwork.Node node = net.node(pad);
        if (node == null || node.kind() != RouteNetwork.Kind.AIRFIELD) return new Answer(false, "flight.no_pad");
        // (The window only begins sites for classes the place builds; a site begun before a design changed its class is not finished.)
        if (!node.berths(type.shipClass)) return new Answer(false, "flight.no_berth");
        final Map<BlockPos, Shipyard.Part> parts = Shipyard.parts(level, type);
        final net.minecraft.world.level.block.Rotation turn = Shipyard.turn(node.heading());
        if (parts == null || Shipyard.missing(level, pad.above(), type, turn) != 0) return new Answer(false, "flight.bad_design");
        final BlockPos deck = pad.above();
        // Built in the pad's heading: laid down nose to +x for this moment, made a ship, and turned back (below).
        Shipyard.layNoseEast(level, deck, type, turn);
        final List<BlockPos> blocks = new ArrayList<>();
        blocks.add(deck);
        for (final BlockPos part : parts.keySet()) blocks.add(deck.offset(part));
        // Fences, panes and stairs of the hull take the shape their neighbours give them (a blueprint names them without it).
        for (final Map.Entry<BlockPos, Shipyard.Part> part : parts.entrySet()) {
            if (part.getValue().device()) continue;
            final BlockPos world = deck.offset(part.getKey());
            final BlockState is = level.getBlockState(world), shaped = Block.updateFromNeighbourShapes(is, level, world);
            if (shaped != is && shaped.getBlock() == is.getBlock()) level.setBlock(world, shaped, 2 | 16);
        }
        return launch(level, net, node, pad, type, blocks, false);
    }

    /** The blocks of an aircraft that stand on a pad, nose to +x, are made a ship, turned to the pad's heading and parked there. */
    private static Answer launch(ServerLevel level, RouteNetwork net, RouteNetwork.Node node, BlockPos pad, AircraftType type, List<BlockPos> blocks, boolean fuelled) {
        final BlockPos deck = pad.above();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (final BlockPos world : blocks) {
            x0 = Math.min(x0, world.getX()); y0 = Math.min(y0, world.getY()); z0 = Math.min(z0, world.getZ());
            x1 = Math.max(x1, world.getX()); y1 = Math.max(y1, world.getY()); z1 = Math.max(z1, world.getZ());
        }
        final BlockPos min = new BlockPos(x0, y0, z0);
        final ServerSubLevel ship = (ServerSubLevel) (Object) SubLevelAssemblyHelper.assembleBlocks(level, min, blocks, new BoundingBox3i(min, new BlockPos(x1, y1, z1)));
        final Pose3dc pose = ship.logicalPose();
        final Vector3d local = pose.transformPositionInverse(new Vector3d(deck.getX() + 0.5, deck.getY() + 0.5, deck.getZ() + 0.5));
        final BlockPos base = BlockPos.containing(local.x, local.y, local.z);
        if (!(level.getBlockEntity(base) instanceof AircraftBlockEntity be)) {
            LOGGER.error("[Airline/flight] the new ship has no aircraft block at {}.", base);
            lastFault = "the blocks did not become one ship around the aircraft's own block (are all of them joined to it?)";
            return new Answer(false, "flight.bad_design");
        }
        final Craft craft = new Craft(level, ship, type, base);
        craft.dial();
        // An aircraft made by command comes with a full tank; one that was built is fuelled from its airfield's racks.
        // Made by command: a full store. Built: the store as it was (a player may have put fuel in while it was a site);
        // otherwise it is fuelled from its airfield's racks.
        if (fuelled) Fuel.fill(be, Fuel.capacity());
        be.type = type.name;
        be.yard = null;
        be.workOrder = 0;
        be.home = pad;
        be.at = pad;
        be.restOffset = pose.position().y() - (pad.getY() + 1);
        be.landed = level.getGameTime();
        be.phase = Phase.PARKED;
        // Built by the colony's builders (not made by command): looked at for a home at one of the colony's anchorages.
        be.newlyBuilt = !fuelled;
        // Built with the nose toward +x; turned to the heading of the pad, then fixed.
        RigidBodyHandle.of(ship).teleport(new Vector3d(pose.position()), new Quaterniond(pose.orientation()).rotateY(-Math.toRadians(node.heading())));
        craft.lock(true);
        craft.burner(type.parkedLever);
        be.setChanged();
        LOADED.put(ship.getUniqueId(), be);
        // The blocks stood in the tubes of the pad's lines for a moment.
        net.recheck(pad);
        return new Answer(true, "flight.spawned", pad.getX(), pad.getY(), pad.getZ());
    }

    static Answer send(ServerLevel level, BlockPos from, BlockPos to, boolean round, @Nullable Mob pilot) {
        final RouteNetwork net = RouteNetwork.get(level);
        AircraftBlockEntity be = null;
        for (final AircraftBlockEntity aircraft : loaded(level)) if (from.equals(aircraft.at)) be = aircraft;
        final Runtime rt = be == null ? null : runtime(be);
        if (be == null || rt == null) return new Answer(false, "flight.no_aircraft");
        if (be.phase != Phase.PARKED) return new Answer(false, "flight.busy");
        if (be.modifying) return new Answer(false, "flight.modifying");
        if (!be.lacks.isEmpty()) return new Answer(false, "flight.unfit", Shipyard.partNames(be.lacks));
        if (pilot == null) return new Answer(false, "flight.no_pilot");
        // The pad it lands on: the one asked for, or another pad of that airfield (its own home is never exchanged).
        final Landing landing = landing(level, net, from, to, rt.craft.id(), to.equals(be.home), shipClass(be));
        if (landing.pad() == null) return new Answer(false, landing.why());
        final List<BlockPos> way = net.route(from, landing.pad(), shipClass(be));
        final long fuelNeeded = Fuel.need(rt.craft.type, flightTicks(net, way, rt.craft.type) * (round ? 2 : 1));
        if (fuelNeeded > Fuel.capacity()) return new Answer(false, "flight.too_far", Fuel.minutes(rt.craft.type, Fuel.capacity()), Fuel.minutes(rt.craft.type, fuelNeeded));
        if (Fuel.total(be) < fuelNeeded) return new Answer(false, "flight.no_fuel", Fuel.minutes(rt.craft.type, Fuel.total(be)), Fuel.minutes(rt.craft.type, fuelNeeded));
        if (be.cargo) uncover(level, be);
        be.homeward = false;
        be.route = new ArrayList<>(way);
        be.leg = 0;
        be.origin = from;
        be.destination = landing.pad();
        be.roundTrip = round;
        be.returning = false;
        be.cancelled = false;
        setPilot(be, pilot);
        be.cargo = false;
        be.timer = 0;
        be.phase = Phase.BOARDING;
        be.note = "boarding";
        Boarding.board(pilot, rt.craft.id(), rt.craft.inShip(rt.craft.type.seat));
        be.setChanged();
        // At once, not with the next second: the pad it will land on counts as taken from now on.
        publish(level, be, rt, net);
        return new Answer(true, "flight.sent", way.size(), String.format("%.0f", net.length(way)));
    }

    // ---- calling a flight off, taking a given-up aircraft away ---------------------------------------------

    /**
     * Calls off the flight that is under way: the one of the aircraft whose home is the pad {@code home}, or, without a
     * home, the only one there is (with several under way it has to be said which). In the air on the way out: back to
     * where it started and down there, by the same steps as when a line is blocked. Before it left: it does not leave,
     * and cargo goes back onto the racks. Landed away from home: a cargo flight stops its handling and flies home at
     * once; a flight sent by command ends there.
     */
    static Answer cancel(ServerLevel level, @Nullable BlockPos home) {
        AircraftBlockEntity found = null;
        int underWay = 0;
        for (final AircraftBlockEntity aircraft : loaded(level)) {
            if (aircraft.phase == Phase.PARKED || aircraft.phase == Phase.STRANDED || (home != null && !home.equals(aircraft.home))) continue;
            found = aircraft;
            underWay++;
        }
        if (underWay > 1) return new Answer(false, "flight.cancel.which");
        final AircraftBlockEntity be = found;
        final Runtime rt = be == null ? null : runtime(be);
        if (be == null || rt == null) return new Answer(false, "flight.cancel.none");
        final Craft c = rt.craft;
        final boolean atHome = be.at != null && be.at.equals(be.home);
        switch (be.phase) {
            case CLIMB, CRUISE, ALTITUDE, RETREAT, HOLD -> {
                if (be.cancelled || be.returning || be.origin == null) return new Answer(false, "flight.cancel.too_late");
                be.cancelled = true;
                be.cancelNote = "flight.note.called_off";
                be.destination = be.origin;
                if (be.phase == Phase.CLIMB) {
                    // Still over the pad it left: no higher, then down again.
                    be.route = new ArrayList<>(List.of(be.origin));
                    be.leg = 0;
                    rt.pilot.altTarget = Math.max(c.pos().y, be.origin.getY() + 2 + be.restOffset);
                } else if (be.phase == Phase.CRUISE) {
                    retreat(be, rt);
                } else if (be.phase == Phase.ALTITUDE) {
                    // Over a point of the way: from there back.
                    reroute(be, rt, RouteNetwork.get(level));
                } else if (be.phase == Phase.HOLD) {
                    rt.resume = Phase.RETREAT;
                }
                // Not sent again at once: whoever called it off gets time to change what the ledger says.
                if (be.cargo) be.quietUntil = level.getGameTime() + Cargo.quietTicks();
                tell(be, "flight.note.calling_off");
                return new Answer(true, "flight.cancel.turning");
            }
            case LOAD, BOARDING -> {
                if (be.cancelled || be.returning) return new Answer(false, "flight.cancel.too_late");
                final Mob pilot = pilot(level, be);
                if (pilot != null && Boarding.state(pilot) != Boarding.State.NONE) Boarding.leave(pilot, null);
                be.timer = 0;
                if (be.cargo && atHome) {
                    // The unloading step at home puts the goods back onto the racks and tells the ledger.
                    be.cancelled = true;
                    be.cancelNote = "flight.note.called_off";
                    be.phase = Phase.UNLOAD;
                    be.quietUntil = level.getGameTime() + Cargo.quietTicks();
                } else {
                    be.phase = Phase.PARKED;
                    c.ticket(false);
                }
                tell(be, "flight.note.calling_off");
                return new Answer(true, "flight.cancel.before");
            }
            case UNLOAD, WAIT -> {
                if (atHome || be.at == null) return new Answer(false, "flight.cancel.too_late");
                // What the pilot holds is put down first. Then what arrived is told to the ledger; what is still in the hold
                // stays there and no longer counts as on its way.
                if (be.phase == Phase.WAIT) {
                    // A flight sent by command that waits to turn round: it ends on that pad.
                    uncover(level, be);
                    be.phase = Phase.PARKED;
                    c.ticket(false);
                    tell(be, "flight.note.ended_there");
                    return new Answer(true, "flight.cancel.ended_there", be.at.getX(), be.at.getY(), be.at.getZ());
                }
                // A cargo flight at its destination: the handling stops and it flies home at once, with what is in its hold.
                // (Left there it would stand on a pad that is not its own, with its pilot a flight away from home.)
                stayAway(level, be, rt);
                // Not sent again at once: whoever called it off gets time to change what the ledger says.
                be.quietUntil = level.getGameTime() + Cargo.quietTicks();
                tell(be, "flight.note.coming_home");
                return new Answer(true, "flight.cancel.coming_home");
            }
            default -> {
                return new Answer(false, "flight.cancel.too_late");
            }
        }
    }

    /**
     * Takes away every aircraft that was given up (STRANDED) and is loaded here. What is in its hold is not lost: it goes
     * to the player who gave the order (what does not fit falls at that player's feet), or, without a player, falls where
     * the aircraft was. A pilot still on the seat is brought to the ground by the boarding code (the ship is gone).
     */
    static Answer clear(ServerLevel level, @Nullable ServerPlayer receiver) {
        int ships = 0, goods = 0;
        Vec3 where = Vec3.ZERO;
        for (final AircraftBlockEntity be : loaded(level)) {
            if (be.phase != Phase.STRANDED) continue;
            final Runtime rt = runtime(be);
            if (rt == null || rt.craft.ship.isRemoved()) continue;
            final Craft c = rt.craft;
            where = c.pos();
            final IItemHandler hold = Cargo.hold(c);
            for (int slot = 0; slot < hold.getSlots(); slot++) {
                final ItemStack stack = hold.extractItem(slot, Integer.MAX_VALUE, false);
                if (stack.isEmpty()) continue;
                goods += stack.getCount();
                if (receiver != null) ItemHandlerHelper.giveItemToPlayer(receiver, stack);
                else Containers.dropItemStack(level, where.x, where.y, where.z, stack);
            }
            uncover(level, be);
            final Mob pilot = pilot(level, be);
            safely(() -> c.ticket(false));
            safely(() -> c.lock(false));
            LOADED.remove(c.id());
            Timetable.get(level.getServer()).remove(c.id());
            Airspace.get(level.getServer()).remove(c.id());
            // Sable takes a ship that is marked like this away with its next tick, blocks and all (nothing is dropped).
            c.ship.markRemoved();
            if (pilot != null && Boarding.state(pilot) != Boarding.State.NONE) Boarding.leave(pilot, null);
            LOGGER.info("[Airline/flight] the given-up aircraft at {} was taken away by command; {} items were in its hold.", where, goods);
            ships++;
        }
        if (ships == 0) return new Answer(false, "flight.clear.none");
        return new Answer(true, receiver != null ? "flight.clear.given" : "flight.clear.dropped", ships, goods,
                String.format("%.0f %.0f %.0f", where.x, where.y, where.z));
    }

    /**
     * Forgets the aircraft of a home that are not loaded here now: their lines leave the timetable, so that the pad is no
     * longer kept for them. For an aircraft that is gone for good without having been taken away by the clear command
     * (its ship was removed by other means); an aircraft that still exists writes its line again when it is loaded.
     */
    static Answer forget(ServerLevel level, BlockPos home) {
        loaded(level);
        final Timetable table = Timetable.get(level.getServer());
        final String dimension = level.dimension().location().toString();
        int dropped = 0;
        for (final Timetable.Entry entry : table.entries()) {
            if (!entry.dimension().equals(dimension) || !home.equals(entry.home()) || LOADED.containsKey(entry.ship())) continue;
            table.remove(entry.ship());
            Airspace.get(level.getServer()).remove(entry.ship());
            dropped++;
        }
        return dropped == 0 ? new Answer(false, "flight.forget.none") : new Answer(true, "flight.forget.done", dropped);
    }

    /** For the checks: the plans of the flights that are under way. */
    static Airspace airspace(ServerLevel level) {
        return Airspace.get(level.getServer());
    }

    /** The record of the pilot's cargo handling: walks that ended with goods put down, walks given up. Null when not loaded. */
    @Nullable
    static int[] handling(AircraftBlockEntity be) {
        final Runtime rt = runtime(be);
        return rt == null ? null : Handling.record(rt);
    }

    /** What is in the hold right now, by item (for the checks, and for whoever wants to know where the goods are). */
    static Map<Item, Integer> holdContents(AircraftBlockEntity be) {
        final Runtime rt = runtime(be);
        return rt == null || rt.craft.ship.isRemoved() ? Map.of() : Cargo.contents(Cargo.hold(rt.craft));
    }

    /** The blocks of the hold (in the ship's own coordinates). */
    static List<BlockPos> holdBlocks(AircraftBlockEntity be) {
        final List<BlockPos> blocks = new ArrayList<>();
        for (final BlockPos barrel : AircraftType.ALL.getOrDefault(be.type, AircraftType.DEFAULT).cargo) blocks.add(be.getBlockPos().offset(barrel));
        return blocks;
    }
}
