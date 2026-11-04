package com.asteain.shiptest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage 2 lab scenarios with a small test aircraft that is flown through its own devices only: one lever at the burner,
 * and per side one lever for "forward" and one for "reverse" at a directional gearshift between engine and propeller.
 */
final class LabFlight {
    private LabFlight() {}

    static void add(Script s, MinecraftServer server, ServerLevel level, BlockPos origin, String which, String arg) {
        Map<String, Double> p = new HashMap<>();
        for (String pair : arg.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && !pair.trim().startsWith("ids=")) p.put(pair.substring(0, eq).trim(), Double.parseDouble(pair.substring(eq + 1).trim()));
        }
        switch (which) {
            case "masses" -> masses(s, level, origin, arg);
            case "rackdupe" -> rackdupe(s, level, origin);
            case "open" -> open(s, level, origin, p);
            case "fly" -> LabAutopilot.fly(s, server, level, origin, p);
            case "wall" -> LabExtras.wall(s, server, level, origin, p);
            case "load" -> LabExtras.load(s, server, level, origin, p);
            case "lock" -> LabExtras.lock(s, level, origin, p);
            case "trip" -> LabTrip.trip(s, server, level, origin, p);
            case "stand" -> LabTrip.stand(s, server, level, origin, p);
            case "deckwalk" -> LabTrip.deckwalk(s, server, level, origin, p);
            default -> throw new AssertionError("unknown lab scenario '" + which + "'");
        }
    }

    static double mass(Object ship) {
        return LabKit.number(LabKit.call(LabKit.call(ship, "getMassTracker"), "getMass"));
    }

    /**
     * Sable issue 683: "when assembling a physics object, all MineColonies racks drop their items on the ground, however
     * they still exist inside the rack". A single rack, a double rack and (for comparison) a barrel, each slot 0 holding 64
     * cobblestone, are assembled into one ship; afterwards the items lying around and the items inside the ship are counted.
     */
    private static void rackdupe(Script s, ServerLevel level, BlockPos origin) {
        Object[] ship = new Object[1];
        BlockPos base = origin.offset(0, 4, 0);
        BlockPos single = base.offset(0, 1, 0), left = base.offset(2, 1, 0), right = base.offset(3, 1, 0), barrel = base.offset(5, 1, 0);
        BlockPos[] inShip = new BlockPos[4];   // where the four containers are inside the ship (taken right after assembly; the ship falls afterwards)
        s.step("rackdupe: build, fill, assemble", () -> {
            Rig.force(level, origin, 16, 16);
            java.util.List<BlockPos> world = new java.util.ArrayList<>();
            for (int x = 0; x <= 5; x++) { level.setBlock(base.offset(x, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(), 3); world.add(base.offset(x, 0, 0)); }
            BlockState rack = LabKit.state("minecolonies:blockminecoloniesrack");
            for (BlockPos p : new BlockPos[]{single, left, right}) { level.setBlock(p, rack, 3); world.add(p); }
            level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
            world.add(barrel);
            for (BlockPos p : new BlockPos[]{single, left, right})
                ((com.minecolonies.api.tileentities.AbstractTileEntityRack) level.getBlockEntity(p)).getInventory()
                        .setStackInSlot(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
            ((net.minecraft.world.Container) level.getBlockEntity(barrel)).setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
            s.note("RACKDUPE before: rack states single=" + level.getBlockState(single) + " left=" + level.getBlockState(left) + " right=" + level.getBlockState(right));
            ship[0] = Ships.assemble(level, world, base, base.offset(5, 1, 0), base);
            BlockPos[] at = {single, left, right, barrel};
            for (int i = 0; i < 4; i++) inShip[i] = BlockPos.containing(Ships.toPlot(ship[0], net.minecraft.world.phys.Vec3.atCenterOf(at[i])));
            s.note("RACKDUPE right after assembly: " + rackdupeCount(level, inShip));
        });
        s.settle("rackdupe: wait", 10);
        s.step("rackdupe: count", () -> {
            s.note("RACKDUPE ten ticks later: " + rackdupeCount(level, inShip));
            try { Ships.remove(level, ship[0]); } catch (Throwable t) { throw new AssertionError(t); }
        });
    }

    private static String rackdupeCount(ServerLevel level, BlockPos[] inShip) {
        int lying = 0, stacks = 0;
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof net.minecraft.world.entity.item.ItemEntity item && item.getItem().is(net.minecraft.world.item.Items.COBBLESTONE)) {
                lying += item.getItem().getCount();
                stacks++;
            }
        }
        StringBuilder inside = new StringBuilder();
        int total = 0;
        String[] names = {"single rack", "double rack (first half)", "double rack (second half)", "barrel"};
        for (int i = 0; i < inShip.length; i++) {
            BlockEntity be = level.getBlockEntity(inShip[i]);
            int n = -1;
            if (be instanceof com.minecolonies.api.tileentities.AbstractTileEntityRack r) n = r.getInventory().getStackInSlot(0).getCount();
            else if (be instanceof net.minecraft.world.Container c) n = c.getItem(0).getCount();
            if (n > 0) total += n;
            inside.append(names[i]).append('=').append(n).append(' ');
        }
        return "cobblestone lying around as items = " + lying + " (" + stacks + " item entities); inside the ship: " + inside + "(total " + total + "; 256 were put in)";
    }

    /** One block of each kind as a ship of its own: what does it weigh? */
    private static void masses(Script s, ServerLevel level, BlockPos origin, String arg) {
        String[] standard = {"minecraft:oak_planks", "minecraft:stone", "minecraft:redstone_block", "minecraft:oak_slab", "minecraft:white_wool", "minecraft:white_carpet",
                "aeronautics:white_envelope", "aeronautics:adjustable_burner", "aeronautics:wooden_propeller", "aeronautics:andesite_propeller", "aeronautics:smart_propeller",
                "aeronautics:levitite", "simulated:white_portable_engine", "simulated:throttle_lever", "simulated:directional_gearshift", "simulated:analog_transmission",
                "simulated:steering_wheel", "create:shaft", "create:analog_lever", "create:clutch", "create:gearshift", "create:white_seat", "minecraft:lever",
                "simulated:docking_connector", "simulated:white_symmetric_sail", "create:andesite_casing", "minecraft:oak_trapdoor", "minecraft:oak_fence"};
        // Another list can be given from outside: -PlabArg=ids=minecraft:oak_stairs+minecraft:lantern (ids joined by "+").
        String[] ids = arg.trim().startsWith("ids=") ? arg.trim().substring(4).split("\\+") : standard;
        s.step("masses", () -> {
            Rig.force(level, origin, 16, 16);
            for (int i = 0; i < ids.length; i++) {
                BlockPos at = origin.offset(3 * (i % 8) - 12, 4, 3 * (i / 8) - 12);
                try {
                    BlockState state = LabKit.state(ids[i]);
                    LabKit.Plan plan = new LabKit.Plan(level, at);
                    plan.put(0, 0, 0, state);
                    if (state.getCollisionShape(level, at).isEmpty() || ids[i].contains("lever") || ids[i].contains("carpet")) plan.put(0, -1, 0, Blocks.OAK_PLANKS.defaultBlockState());
                    Object ship = plan.assemble();
                    s.note(String.format("MASS %s = %.3f%s", ids[i], mass(ship), plan.parts.size() > 1 ? " (together with one oak plank block)" : ""));
                    try { Ships.remove(level, ship); } catch (Throwable t) { throw new AssertionError(t); }
                } catch (AssertionError | Exception e) {
                    s.note("MASS " + ids[i] + " failed: " + e);
                }
            }
        });
    }

    // ---- the test aircraft ----------------------------------------------------------------------------------

    /** The devices of one test aircraft. Side 0 is the row at z = -2 (left when looking along +x), side 1 the row at z = +2. */
    static final class Craft {
        final ServerLevel level;
        Object ship;
        BlockEntity burner, burnerLever;
        final BlockEntity[] engine = new BlockEntity[2], forward = new BlockEntity[2], reverse = new BlockEntity[2], propeller = new BlockEntity[2];
        BlockPos seat, gearshift0;
        int burnerSet = -1, writes;
        final int[] driveSet = {9, 9};
        /** Which lever makes the ship go toward its own +x; found by measurement (1 or -1). */
        int forwardSign = 1;
        /** Per side, on top of forwardSign: set by calibrate(). */
        final int[] sign = {1, 1};
        final java.util.List<String> parts = new java.util.ArrayList<>();

        /**
         * Finds out which lever of each side pushes the ship toward its own +x. Call while the drives are running with
         * drive(side, 1) and forwardSign = 1, sign = 1: a propeller spinning with positive speed pushes toward -x.
         */
        void calibrate() {
            for (int side = 0; side < 2; side++) sign[side] = propSpeed(side) > 0 ? -1 : 1;
        }

        Craft(ServerLevel level) { this.level = level; }

        private void lever(BlockEntity lever, int value) {
            if ((int) LabKit.get(lever, "state") == value) return;
            LabKit.call(lever, "setSignal", value);
            writes++;
        }

        /** The lever beside the burner: 0..15. */
        void burner(int value) {
            value = Math.max(0, Math.min(15, value));
            lever(burnerLever, value);
            burnerSet = value;
        }

        /** One side's drive: 1 = pushes the ship toward its +x, -1 = toward -x, 0 = disconnected. Sets that side's two levers. */
        void drive(int side, int direction) {
            int d = direction * forwardSign * sign[side];
            lever(forward[side], d > 0 ? 15 : 0);
            lever(reverse[side], d < 0 ? 15 : 0);
            driveSet[side] = direction;
        }

        Object lastBalloon;
        Object balloon() { Object b = LabKit.call(burner, "getBalloon"); if (b != null) lastBalloon = b; return lastBalloon; }
        double gas() { Object b = balloon(); return b == null ? -1 : LabKit.number(LabKit.call(b, "getTotalFilledVolume")); }
        double gasTarget() { Object b = balloon(); return b == null ? -1 : LabKit.number(LabKit.call(b, "getTotalTargetVolume")); }
        double lift() { Object b = balloon(); return b == null ? -1 : LabKit.number(LabKit.call(b, "getTotalLift")); }
        double capacity() { Object b = balloon(); return b == null ? -1 : LabKit.number(LabKit.call(b, "getCapacity")); }
        double propSpeed(int side) { return LabKit.number(LabKit.call(propeller[side], "getSpeed")); }
        double thrust(int side) { return LabKit.number(LabKit.call(propeller[side], "getThrust")); }

        Vec3 pos() { try { return Ships.position(ship); } catch (Exception e) { throw new AssertionError(e); } }
        Vec3 vel() { try { return Ships.velocity(ship); } catch (Exception e) { throw new AssertionError(e); } }
        Quaterniond q() { try { return Ships.orientation(ship); } catch (Exception e) { throw new AssertionError(e); } }
        /** Heading of the ship's own +x axis in the world, degrees: 0 = +x, 90 = +z. */
        double yaw() { Vector3d f = q().transform(new Vector3d(1, 0, 0)); return Math.toDegrees(Math.atan2(f.z, f.x)); }
        double yawRate() { try { return Math.toDegrees(-Ships.angularVelocity(ship).y); } catch (Exception e) { throw new AssertionError(e); } }
        double tilt() { return PoseRecorder.tilt(q()); }
    }

    /**
     * Builds the aircraft with its deck at the given position: 3x3 plank deck, on each side engine - directional gearshift -
     * wooden propeller in a row along x, burner in the middle of the deck with a throttle lever beside it, a seat behind
     * the burner, and a 5x7 envelope (inside 3x5x4 = 60 cells) four blocks above the deck on corner posts.
     */
    static Craft build(ServerLevel level, BlockPos deck, int burnerValue) throws Exception {
        Craft c = new Craft(level);
        LabKit.Plan plan = new LabKit.Plan(level, deck);
        plan.box(-1, 0, -1, 1, 0, 1, Blocks.OAK_PLANKS.defaultBlockState());
        for (int side = 0; side < 2; side++) {
            int z = side == 0 ? -2 : 2;
            plan.put(-1, 0, z, LabKit.state("simulated:white_portable_engine", "facing=east"));
            plan.put(0, 0, z, LabKit.state("simulated:directional_gearshift", "facing=up", "axis_along_first=true"));
            plan.put(1, 0, z, LabKit.state("aeronautics:wooden_propeller", "facing=east"));
            plan.put(0, 1, z, LabKit.state("simulated:throttle_lever", "face=floor", "facing=east"));
            plan.put(0, -1, z, LabKit.state("simulated:throttle_lever", "face=ceiling", "facing=east"));
            for (int x = -1; x <= 1; x += 2) {
                plan.put(x, 1, z, Blocks.OAK_FENCE.defaultBlockState());
                plan.put(x, 2, z, Blocks.OAK_FENCE.defaultBlockState());
                plan.put(2 * x, 2, z, Blocks.OAK_FENCE.defaultBlockState());
            }
        }
        plan.put(0, 1, 0, LabKit.state("aeronautics:adjustable_burner", "powered=false"));
        plan.put(0, 1, 1, LabKit.state("simulated:throttle_lever", "face=floor", "facing=east"));
        plan.put(-1, 1, 0, LabKit.state("create:white_seat"));
        BlockState envelope = LabKit.state("aeronautics:white_envelope");
        for (int y = 3; y <= 6; y++) for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++)
            if (Math.abs(x) == 2 || Math.abs(z) == 3) plan.put(x, y, z, envelope);
        plan.box(-2, 7, -3, 2, 7, 3, envelope);
        c.ship = plan.assemble();
        c.burner = plan.entity(0, 1, 0);
        c.burnerLever = plan.entity(0, 1, 1);
        c.seat = plan.inShip(-1, 1, 0);
        c.gearshift0 = plan.inShip(0, 0, -2);
        for (int side = 0; side < 2; side++) {
            int z = side == 0 ? -2 : 2;
            c.engine[side] = plan.entity(-1, 0, z);
            c.forward[side] = plan.entity(0, 1, z);
            c.reverse[side] = plan.entity(0, -1, z);
            c.propeller[side] = plan.entity(1, 0, z);
            Object slot = LabKit.get(LabKit.get(c.engine[side], "inventory"), "slot");
            LabKit.call(slot, "setStack", new ItemStack(Items.COAL_BLOCK, 64));
        }
        LabKit.call(LabKit.get(c.burner, "hotAirAmountBehaviour"), "setValue", burnerValue);
        return c;
    }

    /**
     * The second test aircraft. Per side: engine, two cog stages (large to small = twice the speed each; a stage can be
     * replaced by plain cogs), directional gearshift with its "forward" lever on top and its "reverse" lever on the side of
     * a plank below it (toward the seat), then shafts and a cog tower up to a propeller in front of (left side) or behind
     * (right side) the envelope at the height where the envelope's drag acts. The left drive runs forward, the right one
     * backward, so the ship is balanced fore and aft.
     *
     * @param gear 1, 2 or 4: how many times faster than the engine the propellers turn
     */
    static Craft build2(ServerLevel level, BlockPos deck, int gear, int burnerValue, boolean longDeck) throws Exception {
        Craft c = new Craft(level);
        LabKit.Plan plan = new LabKit.Plan(level, deck);
        BlockState small = LabKit.state("create:cogwheel", "axis=x"), large = LabKit.state("create:large_cogwheel", "axis=x"), shaft = LabKit.state("create:shaft", "axis=x");
        plan.box(-1, 0, -1, 1, 0, 1, Blocks.OAK_PLANKS.defaultBlockState());
        // Fourth round: one more row of deck behind the seat and one in front of the burner (the same weight fore and aft),
        // so that the seat no longer sits on the edge and a citizen can step onto the deck behind it.
        if (longDeck) {
            plan.box(-2, 0, -1, -2, 0, 1, Blocks.OAK_PLANKS.defaultBlockState());
            plan.box(2, 0, -1, 2, 0, 1, Blocks.OAK_PLANKS.defaultBlockState());
        }
        // The burner must not touch the reverse levers at (0, 1, +-1): a lever powers every block around it.
        plan.put(1, 1, 0, LabKit.state("aeronautics:adjustable_burner", "powered=false"));
        plan.put(1, 1, 1, LabKit.state("simulated:throttle_lever", "face=floor", "facing=east"));
        plan.put(-1, 1, 0, LabKit.state("create:white_seat"));
        for (int side = 0; side < 2; side++) {
            int s = side == 0 ? -1 : 1;      // z side
            int d = side == 0 ? 1 : -1;      // the direction this drive runs along x
            String toward = d > 0 ? "east" : "west";
            plan.put(-3 * d, 0, 2 * s, LabKit.state("simulated:white_portable_engine", "facing=" + toward));
            // stage 1: (-2d, 0, 2s) -> (-2d, 1, 3s)
            if (gear >= 2) {
                plan.put(-2 * d, 0, 2 * s, large);
            } else {
                plan.put(-2 * d, 0, 2 * s, small);
                plan.put(-2 * d, 1, 2 * s, small);
            }
            plan.put(-2 * d, 1, 3 * s, small);
            // stage 2: (-d, 1, 3s) -> (-d, 2, 2s)
            if (gear >= 4) {
                plan.put(-d, 1, 3 * s, large);
            } else {
                plan.put(-d, 1, 3 * s, small);
                plan.put(-d, 2, 3 * s, small);
            }
            plan.put(-d, 2, 2 * s, small);
            plan.put(0, 2, 2 * s, LabKit.state("simulated:directional_gearshift", "facing=up", "axis_along_first=true"));
            plan.put(0, 3, 2 * s, LabKit.state("simulated:throttle_lever", "face=floor", "facing=east"));
            plan.put(0, 1, 2 * s, Blocks.OAK_PLANKS.defaultBlockState());
            plan.put(0, 1, s, LabKit.state("simulated:throttle_lever", "face=wall", "facing=" + (s > 0 ? "north" : "south")));
            plan.put(d, 2, 2 * s, shaft);
            plan.put(2 * d, 2, 2 * s, shaft);
            plan.put(3 * d, 2, 2 * s, small);
            plan.put(3 * d, 2, s, small);
            for (int y = 3; y <= 6; y++) plan.put(3 * d, y, s, small);
            plan.put(4 * d, 6, s, LabKit.state("aeronautics:wooden_propeller", "facing=" + toward));
        }
        BlockState envelope = LabKit.state("aeronautics:white_envelope");
        for (int y = 3; y <= 7; y++) for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++)
            if (Math.abs(x) == 2 || Math.abs(z) == 3) plan.put(x, y, z, envelope);
        plan.box(-2, 8, -3, 2, 8, 3, envelope);
        for (Map.Entry<BlockPos, BlockState> e : plan.parts.entrySet())
            c.parts.add(e.getKey().getX() + " " + e.getKey().getY() + " " + e.getKey().getZ() + " " + e.getValue());
        c.ship = plan.assemble();
        c.burner = plan.entity(1, 1, 0);
        c.burnerLever = plan.entity(1, 1, 1);
        c.seat = plan.inShip(-1, 1, 0);
        c.gearshift0 = plan.inShip(0, 2, -2);
        for (int side = 0; side < 2; side++) {
            int s = side == 0 ? -1 : 1, d = side == 0 ? 1 : -1;
            c.engine[side] = plan.entity(-3 * d, 0, 2 * s);
            c.forward[side] = plan.entity(0, 3, 2 * s);
            c.reverse[side] = plan.entity(0, 1, s);
            c.propeller[side] = plan.entity(4 * d, 6, s);
            Object slot = LabKit.get(LabKit.get(c.engine[side], "inventory"), "slot");
            LabKit.call(slot, "setStack", new ItemStack(Items.COAL_BLOCK, 64));
        }
        LabKit.call(LabKit.get(c.burner, "hotAirAmountBehaviour"), "setValue", burnerValue);
        return c;
    }

    /** Builds the aircraft chosen with craft=1|2 (and gear=1|2|4 for the second one). */
    static Craft buildChosen(ServerLevel level, BlockPos deck, Map<String, Double> p) throws Exception {
        if (p.getOrDefault("craft", 1.0) < 2) {
            Craft c = build(level, deck, (int) (double) p.getOrDefault("value", 60.0));
            c.forwardSign = -1;      // measured in the first round
            return c;
        }
        return build2(level, deck, (int) (double) p.getOrDefault("gear", 4.0), (int) (double) p.getOrDefault("value", 80.0), p.getOrDefault("deck", 3.0) >= 5);
    }

    static String line(Craft c, int t, Vec3 p0) {
        Vec3 p = c.pos(), v = c.vel();
        return String.format("TEL t=%d x=%.3f y=%.3f z=%.3f vx=%.4f vy=%.4f vz=%.4f yaw=%.2f yawRate=%.3f tilt=%.2f gas=%.2f target=%.2f lift=%.2f burner=%d L=%d R=%d rpmL=%.1f rpmR=%.1f thrL=%.2f thrR=%.2f",
                t, p.x - p0.x, p.y - p0.y, p.z - p0.z, v.x, v.y, v.z, c.yaw(), c.yawRate(), c.tilt(), c.gas(), c.gasTarget(), c.lift(),
                c.burnerSet, c.driveSet[0], c.driveSet[1], c.propSpeed(0), c.propSpeed(1), c.thrust(0), c.thrust(1));
    }

    /**
     * Open loop: fill the balloon while the ship is held, let go, then a crude height hold, and with that running:
     * both drives forward, off, reverse; then one forward and one reverse. Everything is logged; nothing is judged.
     */
    private static void open(Script s, ServerLevel level, BlockPos origin, Map<String, Double> p) {
        int value = (int) (double) p.getOrDefault("value", 60.0);
        int fill = (int) (double) p.getOrDefault("fill", 15.0);
        BlockPos deck = origin.offset(0, 30, 0);
        Craft[] craft = new Craft[1];
        Vec3[] p0 = new Vec3[1];
        Vector3d[] holdPos = new Vector3d[1];
        Quaterniond[] holdQ = new Quaterniond[1];
        boolean[] hold = {true};
        int[] t = new int[1];
        double[] hover = {p.getOrDefault("hover", 7.0)}, integral = new double[1], carry = new double[1];
        s.step("open: build", () -> {
            Rig.force(level, origin, 96, 96);
            craft[0] = p.getOrDefault("craft", 1.0) < 2 ? build(level, deck, value) : buildChosen(level, deck, p);
            Craft c = craft[0];
            for (String part : c.parts) s.note("PART " + part);
            Object centre = LabKit.call(LabKit.call(c.ship, "getMassTracker"), "getCenterOfMass");
            s.note("OPEN centre of mass in the ship = " + centre + ", deck centre block in the ship = " + BlockPos.containing(Ships.toPlot(c.ship, Vec3.atCenterOf(deck))));
            p0[0] = c.pos();
            holdPos[0] = new Vector3d(p0[0].x, p0[0].y, p0[0].z);
            holdQ[0] = c.q();
            s.note(String.format("OPEN built: mass=%.2f burnerValue=%d entities burner=%s lever=%s engine=%s gearLeverF=%s gearLeverR=%s prop=%s",
                    mass(c.ship), value, name(c.burner), name(c.burnerLever), name(c.engine[0]), name(c.forward[0]), name(c.reverse[0]), name(c.propeller[0])));
            c.burner(fill);
            c.drive(0, 0);
            c.drive(1, 0);
            s.monitor(() -> {
                try {
                    Craft k = craft[0];
                    if (hold[0]) { Ships.teleport(k.ship, holdPos[0], holdQ[0]); Ships.zeroVelocity(k.ship); }
                    t[0]++;
                    if (t[0] % 4 == 0) s.note(line(k, t[0], p0[0]));
                } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        if (p.getOrDefault("craft", 1.0) >= 2) {
            s.settle("open: fill a little", 20);
            s.step("open: which lever is forward", () -> { craft[0].drive(0, 1); craft[0].drive(1, 1); });
            s.settle("open: drives running while held", 30);
            s.step("open: calibrate", () -> {
                Craft c = craft[0];
                s.note(String.format("OPEN calibration: with the top levers on, propeller speeds are %.1f / %.1f rpm, thrust readings %.1f / %.1f", c.propSpeed(0), c.propSpeed(1), c.thrust(0), c.thrust(1)));
                c.calibrate();
                c.drive(0, 0);
                c.drive(1, 0);
                s.note("OPEN calibration: signs " + c.sign[0] + " / " + c.sign[1]);
            });
        }
        s.settle("open: fill while held", 500);
        s.step("open: state after filling", () -> {
            Craft c = craft[0];
            s.note(String.format("OPEN filled: capacity=%.1f gas=%.2f target=%.2f lift=%.2f mass=%.2f signal=%s gearshift=%s", c.capacity(), c.gas(), c.gasTarget(), c.lift(), mass(c.ship),
                    LabKit.call(c.burner, "getSignalStrength"), level.getBlockState(c.gearshift0)));
            hold[0] = false;
        });
        s.settle("open: free with the burner as it was", 60);
        // crude height hold from here on: proportional + rate + slow integral, written to the burner lever every tick
        s.step("open: height hold on", () -> s.monitor(() -> {
            Craft c = craft[0];
            double y = c.pos().y - p0[0].y, vy = c.vel().y;
            double target = p.getOrDefault("alt", 8.0);
            integral[0] += (target - y) * 0.002;
            integral[0] = Math.max(-4, Math.min(4, integral[0]));
            double want = hover[0] + integral[0] + p.getOrDefault("kp", 0.8) * (target - y) - p.getOrDefault("kd", 4.0) * vy;
            carry[0] += want - Math.floor(want);
            int out = (int) Math.floor(want);
            if (carry[0] >= 1.0) { carry[0] -= 1.0; out++; }
            c.burner(out);
        }));
        s.settle("open: settle at height", 500);
        s.step("open: both forward", () -> { craft[0].drive(0, 1); craft[0].drive(1, 1); });
        s.settle("open: forward", 240);
        s.step("open: both off", () -> { craft[0].drive(0, 0); craft[0].drive(1, 0); });
        s.settle("open: coast", 240);
        s.step("open: both reverse", () -> { craft[0].drive(0, -1); craft[0].drive(1, -1); });
        s.settle("open: reverse", 240);
        s.step("open: off", () -> { craft[0].drive(0, 0); craft[0].drive(1, 0); });
        s.settle("open: coast 2", 100);
        s.step("open: turn", () -> { craft[0].drive(0, 1); craft[0].drive(1, -1); });
        s.settle("open: turning", 100);
        s.step("open: off", () -> { craft[0].drive(0, 0); craft[0].drive(1, 0); });
        s.settle("open: turning freely", 200);
        s.step("open: end", () -> {
            s.clearMonitors();
            s.note("OPEN lever writes in total: " + craft[0].writes);
            try { Ships.removeNear(level, Vec3.atCenterOf(origin), 400); } catch (Throwable e) { throw new AssertionError(e); }
        });
    }

    private static String name(BlockEntity be) { return be == null ? "null" : be.getClass().getSimpleName(); }
}
