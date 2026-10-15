package com.asteain.airlinetest;

import com.asteain.airline.flight.AircraftBlockEntity;
import com.asteain.airline.flight.AircraftType;
import com.asteain.airline.flight.AircraftType.Drive;
import com.asteain.airline.flight.Flights;
import com.asteain.airline.flight.Trial;
import com.asteain.airline.route.RouteNetwork;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The test bench for aircraft designs. It makes one aircraft of the design that was given to the server
 * ({@code -Dairline.design=<file>}), works it through its own levers only, the way the autopilot does, and writes down
 * what it measured as a JSON file ({@code -Dairlinetest.benchOut=<file>}): mass and balance, what turns how fast for
 * every manoeuvre, lift and climb, speed, coasting, braking, turning, the slow manoeuvres, and how the mod's autopilot
 * gets on with it (a leg of 80 blocks with a stop, a quarter turn, being placed over a point), with the handling
 * numbers it worked out.
 *
 * <p>Nothing here is an expectation: a design that cannot be read, cannot be built or does not lift still gives a
 * report, which says so.</p>
 */
final class BenchSuite {
    private BenchSuite() {}

    private static final BlockPos PAD = new BlockPos(0, 99, 0);
    private static final double CLIMB = 12.0;
    /** The length of the leg the autopilot flies (-Dairlinetest.benchLeg; 80 as a rule, longer to look at how a line is kept over a distance). */
    private static final double LEG = Math.max(20.0, Double.parseDouble(System.getProperty("airlinetest.benchLeg", "80")));
    /** The leg begins this far to the side of where the aircraft rests (-Dairlinetest.benchOffset): how it finds its way onto a line. */
    private static final double OFFSET = Double.parseDouble(System.getProperty("airlinetest.benchOffset", "0"));

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "bench";
        ServerLevel level = server.overworld();
        Bench bench = new Bench(level, s);
        s.step("bench: a pad, and the aircraft on it", bench::start);
        s.until("bench: trials", () -> {}, bench::tick, Script.WATCHDOG_TICKS);
        s.step("bench: report", () -> {
            bench.write();
            s.check(true, "the bench wrote its report");
        });
        return s;
    }

    private enum Stage {
        WEIGH, ON_THE_PAD, CLIMB, HOVER, AHEAD, COAST, ASTERN, SPEED_UP, BRAKE,
        REST_BEFORE_RIGHT, RIGHT, RIGHT_COAST, REST_BEFORE_LEFT, LEFT, LEFT_COAST,
        REST_BEFORE_SLOW, SLOW_AHEAD, REST_BEFORE_SLOW_TURN, SLOW_RIGHT, SLOW_RIGHT_COAST,
        RUN_UP_RIGHT, GENTLE_RIGHT, RUN_UP_LEFT, GENTLE_LEFT, RUN_UP_PUSH, GENTLE_PUSH,
        REST_BEFORE_LEG, LEG, CORNER, QUARTER_TURN, PLACE,
        DOCK_SETUP, DOCK_PULL, DOCK_HANG, DOCK_RELEASE, DOCK_AFTER
    }

    private static final class Bench {
        final ServerLevel level;
        final Script s;
        final String mode = System.getProperty("airlinetest.benchMode", "all");
        final JsonObject report = new JsonObject();
        final JsonArray notes = new JsonArray();
        Trial trial;
        AircraftType type;
        Stage stage = Stage.WEIGH;
        int age;
        final long made = System.nanoTime();
        boolean over;

        // what the stages keep between ticks
        double y0, target, ramp, mass, peak, sum, sumB, low, high, lastYaw, turned, noseSum, noseLow, noseHigh;
        Vec3 from = Vec3.ZERO, legA = Vec3.ZERO, legB = Vec3.ZERO;
        int count, held, tries;
        boolean flag;
        double topSpeed, sternSpeed, rateRight, rateLeft, coastTurnRight, coastTurnLeft, coastDistance, coastFrom, climbSeconds, brakeDistance, brakeFrom;
        double slowSpeed, slowTurnRate, cornerSeconds, turnSeconds, placeSeconds, gentleRate, gentleLead, heightScale = 1.0;
        final List<double[]> series = new ArrayList<>();
        JsonObject current = new JsonObject(), ground = new JsonObject();
        // the docking trial (-PbenchMode=dock): the aircraft's own block entity, its connector, and the connector of the world
        AircraftBlockEntity craftBlock;
        BlockPos dockPart, towerAt, towerPower, shipPower;
        double dockY, dockYaw;

        Bench(ServerLevel level, Script s) {
            this.level = level;
            this.s = s;
        }

        void note(String text) {
            notes.add(text);
            s.note("BENCH " + text);
        }

        void start() {
            String file = System.getProperty(AircraftType.TRIAL_PROPERTY, "");
            String name = file.isEmpty() ? System.getProperty("airlinetest.aircraft", "small") : Path.of(file).getFileName().toString().replaceFirst("\\.json$", "");
            report.addProperty("design", name);
            report.addProperty("ok", false);
            if (AircraftType.trialFault != null) {
                fail("the design file could not be read: " + AircraftType.trialFault);
                return;
            }
            if (!Flights.designs().contains(name)) {
                fail("no design of the name '" + name + "' (there are: " + String.join(", ", Flights.designs()) + ")");
                return;
            }
            Fixture.platform(level, PAD.above(), 10, 10);
            RouteNetwork.get(level).setAirfield(PAD, 0, 1);
            Flights.Answer made = Flights.spawn(level, PAD, name);
            if (!made.ok()) fail("the aircraft could not be made (" + made.key() + "): " + Flights.lastSpawnFault());
        }

        void fail(String why) {
            report.addProperty("error", why);
            note("NOT BUILT: " + why);
            over = true;
        }

        // ---- small helpers ----------------------------------------------------------------------------------

        double forward() {
            double yaw = Math.toRadians(trial.yaw());
            Vec3 v = trial.vel();
            return v.x * Math.cos(yaw) + v.z * Math.sin(yaw);
        }

        double flat(Vec3 a, Vec3 b) {
            return Math.hypot(a.x - b.x, a.z - b.z);
        }

        static double wrap(double degrees) {
            double d = degrees % 360.0;
            if (d > 180.0) d -= 360.0;
            if (d < -180.0) d += 360.0;
            return d;
        }

        /** A yes-or-no reading of a block entity of another mod (the docking connector has them as public methods). */
        static boolean is(BlockEntity entity, String method) {
            try {
                return (boolean) entity.getClass().getMethod(method).invoke(entity);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("the block entity " + entity.getClass().getName() + " has no reading '" + method + "'", e);
            }
        }

        static double round(double v) {
            return Double.isNaN(v) || Double.isInfinite(v) ? -1 : Math.round(v * 1000.0) / 1000.0;
        }

        void put(String key, double value) {
            current.addProperty(key, round(value));
        }

        /** On to a stage; what was gathered in {@code current} is filed under that name first (null: it is thrown away or was filed already). */
        void go(Stage next, String finished) {
            if (finished != null) report.add(finished, current);
            current = new JsonObject();
            stage = next;
            age = 0;
            count = 0;
            held = 0;
            sum = 0;
            sumB = 0;
            peak = 0;
            flag = false;
            series.clear();
            from = trial == null ? Vec3.ZERO : trial.pos();
            lastYaw = trial == null ? 0 : trial.yaw();
            turned = 0;
            low = Double.MAX_VALUE;
            high = -Double.MAX_VALUE;
            noseSum = 0;
            noseLow = Double.MAX_VALUE;
            noseHigh = -Double.MAX_VALUE;
        }

        /** At rest for a second: hardly moving, hardly turning, the deck level again (or 40 seconds have passed). */
        boolean atRest() {
            Vec3 v = trial.vel();
            boolean rest = Math.hypot(v.x, v.z) < 0.08 && Math.abs(trial.yawRate()) < 0.5 && trial.attitude()[2] < 3.0;
            held = rest ? held + 1 : 0;
            return held >= 20 || age > 800;
        }

        /** The value sampled once a second has changed by less than 2 percent over the last five seconds (after at least eight). */
        boolean settled(double floor) {
            int n = series.size();
            return n >= 8 && Math.abs(series.get(n - 1)[1] - series.get(n - 6)[1]) < 0.02 * Math.max(floor, Math.abs(series.get(n - 1)[1]));
        }

        boolean ownSlow(Drive slow) {
            return type.drive[slow.ordinal()] != type.drive[slow.ordinary().ordinal()];
        }

        JsonArray turning() {
            JsonArray all = new JsonArray();
            for (Trial.Turning each : trial.turning()) {
                JsonObject one = new JsonObject();
                one.addProperty("at", each.at().getX() + " " + each.at().getY() + " " + each.at().getZ());
                one.addProperty("block", each.block());
                one.addProperty("rpm", round(each.speed()));
                if (each.overstressed()) one.addProperty("overstressed", true);
                if (!Double.isNaN(each.thrust())) one.addProperty("thrust", round(each.thrust()));
                all.add(one);
            }
            return all;
        }

        /**
         * What the run on the pad says about the drives, in words: for every manoeuvre the push of all propellers together
         * (what each one reports as its thrust, sizes added up), and whether anything had too much to turn.
         */
        void drives() {
            JsonObject sums = new JsonObject();
            for (String manoeuvre : ground.keySet()) {
                double thrust = 0;
                int turns = 0, propellers = 0;
                boolean overstressed = false;
                for (JsonElement each : ground.getAsJsonArray(manoeuvre)) {
                    JsonObject one = each.getAsJsonObject();
                    if (Math.abs(one.get("rpm").getAsDouble()) > 0.01) turns++;
                    if (one.has("overstressed")) overstressed = true;
                    if (one.has("thrust")) {
                        propellers++;
                        thrust += Math.abs(one.get("thrust").getAsDouble());
                    }
                }
                JsonObject sum = new JsonObject();
                sum.addProperty("partsTurning", turns);
                sum.addProperty("propellers", propellers);
                sum.addProperty("thrustOfAllPropellersAddedUp", round(thrust));
                sums.add(manoeuvre, sum);
                if (overstressed) note("DRIVE: with the manoeuvre '" + manoeuvre + "' a part has too much to turn (overstressed): the engines do not carry what is asked of them");
                if (!manoeuvre.equals("off") && thrust == 0) note("DRIVE: with the manoeuvre '" + manoeuvre + "' no propeller pushes (see 'turning': where does the turning stop?)");
                if (manoeuvre.equals("off") && thrust > 0) note("DRIVE: with the drives off a propeller still pushes");
            }
            report.add("turningSummary", sums);
        }

        /** Seconds until the series (time in ticks, value) first reaches that share of its final value. */
        double secondsTo(double share, double last) {
            for (double[] point : series) if (Math.abs(point[1]) >= Math.abs(last) * share) return point[0] / 20.0;
            return -1;
        }

        void end(boolean ok) {
            if (ok) report.addProperty("ok", true);
            report.addProperty("leverWrites", trial.leverWrites());
            trial.end();
            over = true;
        }

        // ---- the trials, one stage after the other ----------------------------------------------------------

        /** One tick of the trials; true when they are over. A failure of the bench itself is written into the report, not lost. */
        boolean tick() {
            try {
                return trials();
            } catch (RuntimeException | LinkageError e) {
                com.mojang.logging.LogUtils.getLogger().error("[AIRTEST] the bench failed in stage {}", stage, e);
                report.addProperty("error", "the bench itself failed in stage " + stage + " (the measurements up to there are in the report): " + e);
                notes.add("BENCH FAILURE in stage " + stage + ": " + e);
                over = true;
                if (trial != null) {
                    try {
                        trial.end();
                    } catch (RuntimeException | LinkageError ignored) {
                        // the report matters more
                    }
                }
                return true;
            }
        }

        boolean trials() {
            if (over) return true;
            age++;
            if (trial == null) {
                // Parked on the pad: the controller has to take it up first (it makes the aircraft's runtime on its first tick).
                AircraftBlockEntity aircraft = null;
                for (AircraftBlockEntity each : Flights.aircraft(level)) if (PAD.equals(each.home)) aircraft = each;
                if (aircraft != null) trial = Trial.of(aircraft);
                craftBlock = aircraft;
                if (trial == null) {
                    if (age > 100 && Script.pastInRealTime(made, 100)) fail("the aircraft was made but did not come to stand parked on its pad");
                    return over;
                }
                type = trial.type();
                age = 0;
            }
            Vec3 p = trial.pos();
            double speed = forward(), rate = trial.yawRate();
            double[] attitude = trial.attitude();
            turned += wrap(trial.yaw() - lastYaw);
            lastYaw = trial.yaw();
            switch (stage) {
                case WEIGH -> {        // on the pad: what it weighs, what its burners see
                    if (age < 60) return false;
                    double[] weight = trial.weight();
                    mass = weight[0];
                    put("blocks", type.parts.size() + 1);
                    put("mass", mass);
                    put("centreTowardNose", weight[1]);
                    put("centreUp", weight[2]);
                    put("centreToRight", weight[3]);
                    report.add("weight", current);
                    if (Math.abs(weight[1]) > 0.3 || Math.abs(weight[3]) > 0.3) note("BALANCE: the centre of mass lies " + round(weight[1]) + " toward the nose and " + round(weight[3]) + " to the right of the middle (0.3 is allowed)");
                    JsonArray balloons = new JsonArray();
                    double room = 0, dial = 0;
                    for (int i = 0; i < type.burners.length; i++) {
                        JsonObject one = new JsonObject();
                        one.addProperty("burner", type.burners[i].toShortString());
                        one.addProperty("dial", type.burnerValues[i]);
                        double[] balloon = trial.balloon(i);
                        one.addProperty("seesBalloon", balloon != null);
                        if (balloon != null) {
                            one.addProperty("room", round(balloon[2]));
                            room = Math.max(room, balloon[2]);
                        }
                        dial += type.burnerValues[i];
                        balloons.add(one);
                    }
                    report.add("balloons", balloons);
                    if (room <= 0) {
                        fail("no burner sees a balloon: the envelope is not a closed space above a burner (the report has the weight and the burners)");
                        return true;
                    }
                    // Roughly where the height will be held (the hot air that carries a mass of 1 is about 0.85 at this height); the trial finds it exactly.
                    double holds = Math.min(room, dial), needs = mass * 0.85;
                    current = new JsonObject();
                    put("hotAirNeededRoughly", needs);
                    put("hotAirAtFullLever", holds);
                    report.add("liftEstimate", current);
                    if (needs > holds * 0.97) note("LIFT: the balloon holds about " + Math.round(holds) + " at full lever and about " + Math.round(needs) + " is needed to carry the mass of " + round(mass) + ": it will hardly leave the pad");
                    type.hoverLever = Math.max(1.0, Math.min(14.0, 15.0 * needs / Math.max(1.0, dial)));
                    type.parkedLever = (int) Math.floor(type.hoverLever);
                    // One notch of the burners' lever is dial / 15 of hot air; what a notch does to the aircraft goes with
                    // dial / mass. The numbers for keeping the height were found for the first design (dial 80, mass 64.25).
                    // A design with more hot air to a notch for its mass gets them smaller by as much: with the first
                    // design's numbers it keeps swinging about its height when the balance lies between two notches
                    // (measured with the fast design: a third of a block either way, the lever going from 6 to 9).
                    if (!mode.equals("legs")) {
                        heightScale = Math.max(0.4, Math.min(1.0, (80.0 / 64.25) / (dial / Math.max(1.0, mass))));
                        type.heightGain = 1.0 * heightScale;
                        type.heightDamping = 4.0 * heightScale;
                        type.heightIntegral = 0.003 * heightScale;
                    }
                    y0 = p.y;
                    go(Stage.ON_THE_PAD, null);
                }
                case ON_THE_PAD -> {   // still fixed to the pad: the engines turn, every manoeuvre is set for 4 seconds, and what turns is written down
                    if (age == 1) {
                        trial.begin();
                        ground = new JsonObject();
                    }
                    Drive[] all = Drive.values();
                    int step = (age - 1) / 80, within = (age - 1) % 80;
                    if (step < all.length) {
                        boolean named = all[step].ordinary() == all[step] || ownSlow(all[step]);
                        if (within == 0 && named) trial.driveNow(all[step]);
                        if (within == 79 && named) ground.add(all[step].name().toLowerCase(Locale.ROOT), turning());
                        if (!named) age += 79;      // a slow manoeuvre the design does not name: nothing to look at
                        return false;
                    }
                    trial.driveNow(Drive.OFF);
                    report.add("turning", ground);
                    drives();
                    if (mode.equals("static")) {
                        end(true);
                        return true;
                    }
                    trial.release();
                    target = y0 + CLIMB;
                    ramp = y0;
                    go(Stage.CLIMB, null);
                }
                case CLIMB -> {        // climb 12 blocks and hold (the target raised step by step when the design has a climbing rate)
                    ramp = trial.climbToward(target);
                    peak = Math.max(peak, p.y - target);
                    // What the flight controller waits for before it flies off along the line; and, for the trials that follow, steady at the height.
                    if (sum == 0 && trial.atHeight(target)) sum = age;
                    // (A balance between two notches of the lever leaves it swinging about the height by a third of a block, at up to 0.3 a second:
                    // that is at the height as well. Measured with a design whose dial put the balance at 7.4 notches.)
                    boolean there = Math.abs(p.y - target) < 0.5 && Math.abs(trial.vel().y) < 0.35;
                    if (there && count == 0) count = age;
                    if (age % 20 == 0 && series.size() < 120) series.add(new double[] {age, p.y - y0, trial.burnerLever(0)});
                    held = there ? held + 1 : 0;
                    if (held >= 100) {
                        climbSeconds = sum / 20.0;
                        put("climb", CLIMB);
                        put("targetRaisedPerSecond", type.climbRate);
                        put("secondsUntilItFliesOff", climbSeconds);
                        put("secondsUntilThere", count / 20.0);
                        put("overshoot", Math.max(0, peak));
                        JsonArray curve = new JsonArray();
                        for (double[] point : series) {
                            JsonArray one = new JsonArray();
                            one.add(round(point[0] / 20.0));
                            one.add(round(point[1]));
                            one.add(round(point[2]));
                            curve.add(one);
                        }
                        current.add("secondsHeightLever", curve);
                        go(Stage.HOVER, "climb");
                    } else if (age > 2400) {
                        put("climb", CLIMB);
                        put("reachedAfter120Seconds", p.y - y0);
                        put("leverNow", trial.burnerLever(0));
                        double[] balloon = trial.balloon(0);
                        if (balloon != null) {
                            put("hotAirNow", balloon[0]);
                            put("room", balloon[2]);
                        }
                        // How it went, second by second: whether it swings about the height or never came to it.
                        final JsonArray went = new JsonArray();
                        for (double[] point : series) {
                            final JsonArray one = new JsonArray();
                            one.add(round(point[0] / 20.0));
                            one.add(round(point[1]));
                            one.add(round(point[2]));
                            went.add(one);
                        }
                        current.add("secondsHeightLever", went);
                        report.add("climb", current);
                        note("LIFT: the aircraft did not climb " + CLIMB + " blocks and hold there within 120 seconds (it is " + round(p.y - y0) + " above where it stood); the trials of the drives were left out");
                        end(false);
                        return true;
                    }
                }
                case HOVER -> {        // hold for 10 seconds: where the lever rests
                    sum += trial.burnerLever(0);
                    sumB += Math.abs(p.y - target);
                    low = Math.min(low, p.y);
                    high = Math.max(high, p.y);
                    // How the ship hangs while nothing drives it: nose up (degrees; below zero, the nose is down). A ship
                    // whose centre of mass lies ahead of its lift hangs nose down, its floor behind its centre of mass.
                    final double noseUp = attitude[0];
                    noseSum += noseUp;
                    noseLow = Math.min(noseLow, noseUp);
                    noseHigh = Math.max(noseHigh, noseUp);
                    if (age < 200) return false;
                    put("hoverLever", sum / age);
                    put("meanHeightError", sumB / age);
                    put("heightSwing", high - low);
                    put("meanNoseUp", noseSum / age);
                    put("noseUpFrom", noseLow);
                    put("noseUpTo", noseHigh);
                    put("height", target);
                    double[] balloon = trial.balloon(0);
                    if (balloon != null) {
                        put("hotAir", balloon[0]);
                        put("room", balloon[2]);
                    }
                    type.hoverLever = sum / age;
                    type.parkedLever = (int) Math.floor(type.hoverLever);
                    if (type.hoverLever > 9.5) note("LIFT: the height is held with the lever at " + round(type.hoverLever) + " (9.5 or less is asked for: higher up the air carries less)");
                    if (mode.equals("legs")) {
                        // Only the autopilot's own trials, with the handling numbers the design's file has (not measured anew).
                        go(Stage.REST_BEFORE_LEG, "hover");
                        return false;
                    }
                    if (mode.equals("dock")) {
                        go(Stage.DOCK_SETUP, "hover");
                        return false;
                    }
                    trial.drive(Drive.AHEAD);
                    go(Stage.AHEAD, "hover");
                }
                case DOCK_SETUP -> {   // a connector of the world right under the ship's own, four blocks below it; both switched on
                    dockPart = null;
                    for (java.util.Map.Entry<BlockPos, String> part : type.parts.entrySet()) if (part.getValue().startsWith("simulated:docking_connector")) dockPart = part.getKey();
                    if (dockPart == null) {
                        fail("the design has no docking connector (simulated:docking_connector[facing=down])");
                        return true;
                    }
                    // Where the ship's connector is in the world: from the ship's own position (its centre of mass) and heading.
                    double[] w = trial.weight();
                    double yaw = Math.toRadians(trial.yaw());
                    double ahead = dockPart.getX() - w[1], up = dockPart.getY() - w[2], right = dockPart.getZ() - w[3];
                    double wx = p.x + ahead * Math.cos(yaw) - right * Math.sin(yaw), wz = p.z + ahead * Math.sin(yaw) + right * Math.cos(yaw), wy = p.y + up;
                    towerAt = BlockPos.containing(wx, wy - 4.0, wz);
                    towerPower = towerAt.north();
                    BlockState connector = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:docking_connector")).defaultBlockState();
                    for (Property<?> property : connector.getProperties()) {
                        if (property.getName().equals("facing") && property instanceof DirectionProperty facing) connector = connector.setValue(facing, Direction.UP);
                    }
                    level.setBlockAndUpdate(towerAt, connector);
                    level.setBlockAndUpdate(towerPower, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    // The ship's connector gets its signal from a block of redstone put beside it now (on the pad it would have pushed its head into the floor).
                    shipPower = Flights.partOf(craftBlock, dockPart.east());
                    level.setBlockAndUpdate(shipPower, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    dockY = p.y;
                    dockYaw = trial.yaw();
                    put("shipConnectorX", wx);
                    put("shipConnectorY", wy);
                    put("shipConnectorZ", wz);
                    put("worldConnectorX", towerAt.getX() + 0.5);
                    put("worldConnectorY", towerAt.getY() + 0.5);
                    put("worldConnectorZ", towerAt.getZ() + 0.5);
                    put("headingThen", dockYaw);
                    go(Stage.DOCK_PULL, "dockSetup");
                }
                case DOCK_PULL -> {    // down slowly, at most 1.5 blocks, until the two are locked
                    trial.altitude(dockY - Math.min(1.5, age * 0.005));
                    BlockEntity mine = level.getBlockEntity(Flights.partOf(craftBlock, dockPart));
                    boolean extended = mine != null && is(mine, "isExtended"), found = mine != null && is(mine, "hasOtherConnector"), locked = mine != null && is(mine, "isLocked");
                    if (extended && count == 0) count = age;
                    if (found && held == 0) held = age;
                    if (age % 20 == 0 && series.size() < 60) series.add(new double[] {age, p.y - dockY, extended ? 1 : 0, found ? 1 : 0});
                    if (!locked && age < 1200) return false;
                    put("locked", locked ? 1 : 0);
                    put("secondsUntilExtended", count / 20.0);
                    put("secondsUntilTheOtherWasFound", held / 20.0);
                    put("secondsUntilLocked", locked ? age / 20.0 : -1);
                    put("wentDown", dockY - p.y);
                    put("headingNow", trial.yaw());
                    put("turnedToLock", wrap(trial.yaw() - dockYaw));
                    put("tilt", attitude[2]);
                    JsonArray curve = new JsonArray();
                    for (double[] point : series) {
                        JsonArray one = new JsonArray();
                        one.add(round(point[0] / 20.0));
                        for (int i = 1; i < point.length; i++) one.add(round(point[i]));
                        curve.add(one);
                    }
                    current.add("secondsHeightExtendedFound", curve);
                    if (!locked) {
                        note("DOCK: the two connectors did not lock within 60 seconds (extended " + extended + ", the other one found " + found + ")");
                        go(Stage.DOCK_AFTER, "dockPull");
                        return false;
                    }
                    from = p;
                    trial.drive(Drive.OFF);
                    go(Stage.DOCK_HANG, "dockPull");
                }
                case DOCK_HANG -> {    // locked: the burners go off as well (the height to keep is set far below); ten seconds
                    trial.altitude(dockY - 40.0);
                    low = Math.min(low, p.y);
                    if (age < 200) return false;
                    BlockEntity mine = level.getBlockEntity(Flights.partOf(craftBlock, dockPart));
                    put("stillLocked", mine != null && is(mine, "isLocked") ? 1 : 0);
                    put("burnerLever", trial.burnerLever(0));
                    put("sankMeanwhile", from.y - p.y);
                    put("lowestMeanwhile", from.y - low);
                    put("movedSideways", flat(from, p));
                    put("tilt", attitude[2]);
                    double[] balloon = trial.balloon(0);
                    if (balloon != null) put("hotAirLeft", balloon[0]);
                    // The signal of the world's connector goes off.
                    level.setBlockAndUpdate(towerPower, Blocks.AIR.defaultBlockState());
                    trial.altitude(dockY);
                    from = p;
                    go(Stage.DOCK_RELEASE, "dockHang");
                }
                case DOCK_RELEASE -> { // let go: how long until it is free, and it keeps its height again
                    BlockEntity mine = level.getBlockEntity(Flights.partOf(craftBlock, dockPart));
                    boolean locked = mine != null && is(mine, "isLocked");
                    if (!locked && count == 0) count = age;
                    low = Math.min(low, p.y);
                    if (age < 400) return false;
                    put("letGo", count > 0 ? 1 : 0);
                    put("secondsUntilFree", count > 0 ? count / 20.0 : -1);
                    put("lowestAfterwards", from.y - low);
                    put("heightErrorAtTheEnd", p.y - dockY);
                    go(Stage.DOCK_AFTER, "dockRelease");
                }
                case DOCK_AFTER -> {   // tidy up: the world's connector and the block of redstone on the ship go away
                    level.setBlockAndUpdate(towerPower, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(towerAt, Blocks.AIR.defaultBlockState());
                    if (shipPower != null) level.setBlockAndUpdate(shipPower, Blocks.AIR.defaultBlockState());
                    end(true);
                    return true;
                }
                case AHEAD -> {        // ahead from rest until the speed has settled
                    if (age % 20 == 0) series.add(new double[] {age, speed, attitude[0]});
                    peak = Math.max(peak, Math.abs(attitude[0]));
                    high = Math.max(high, Math.abs(p.y - target));
                    if (age < 1200 && !settled(0.5)) return false;
                    topSpeed = series.get(series.size() - 1)[1];
                    put("topSpeed", topSpeed);
                    put("secondsTo63Percent", secondsTo(0.63, topSpeed));
                    put("secondsTo90Percent", secondsTo(0.9, topSpeed));
                    put("noseUpAtTheEnd", attitude[0]);
                    put("largestNoseUpOrDown", peak);
                    put("largestHeightError", high);
                    put("turnedMeanwhile", turned);
                    put("seconds", age / 20.0);
                    put("settled", settled(0.5) ? 1 : 0);
                    JsonArray curve = new JsonArray();
                    for (double[] point : series) {
                        JsonArray one = new JsonArray();
                        one.add(round(point[0] / 20.0));
                        one.add(round(point[1]));
                        one.add(round(point[2]));
                        curve.add(one);
                    }
                    current.add("secondsSpeedNoseUp", curve);
                    if (Math.abs(topSpeed) < 0.2) note("DRIVE: with the manoeuvre 'ahead' the aircraft reaches " + round(topSpeed) + " blocks per second (see 'turning': does anything turn, does a propeller push?)");
                    else if (topSpeed < 0) note("DRIVE: the manoeuvre 'ahead' moves the aircraft toward its tail");
                    coastFrom = topSpeed;
                    trial.drive(Drive.OFF);
                    go(Stage.COAST, "ahead");
                }
                case COAST -> {        // drives off: how far it coasts
                    if (Math.abs(speed) > 0.05 && age < 1200) return false;
                    coastDistance = flat(p, from);
                    put("fromSpeed", coastFrom);
                    put("distance", coastDistance);
                    put("seconds", age / 20.0);
                    trial.drive(Drive.ASTERN);
                    go(Stage.ASTERN, "coast");
                }
                case ASTERN -> {       // astern from rest
                    if (age % 20 == 0) series.add(new double[] {age, speed});
                    if (age < 600 && !settled(0.5)) return false;
                    sternSpeed = series.get(series.size() - 1)[1];
                    put("topSpeed", -sternSpeed);
                    put("secondsTo63Percent", secondsTo(0.63, sternSpeed));
                    if (sternSpeed > -0.1) note("DRIVE: the manoeuvre 'astern' does not move the aircraft toward its tail (" + round(sternSpeed) + ")");
                    trial.drive(Drive.OFF);
                    go(Stage.SPEED_UP, "astern");
                }
                case SPEED_UP -> {     // to rest, then ahead to top speed again
                    if (!flag) {
                        if (!atRest()) return false;
                        flag = true;
                        count = age;
                        trial.drive(Drive.AHEAD);
                        return false;
                    }
                    if (speed < 0.95 * topSpeed && age - count < 1200) return false;
                    brakeFrom = speed;
                    trial.drive(Drive.ASTERN);
                    go(Stage.BRAKE, null);
                }
                case BRAKE -> {        // astern against the way it has: how far until its way is off
                    peak = Math.max(peak, Math.abs(attitude[0]));
                    if (speed > 0.1 && age < 600) return false;
                    brakeDistance = flat(p, from);
                    put("fromSpeed", brakeFrom);
                    put("distance", brakeDistance);
                    put("seconds", age / 20.0);
                    put("largestNoseUpOrDown", peak);
                    trial.drive(Drive.OFF);
                    go(Stage.REST_BEFORE_RIGHT, "brake");
                }
                case REST_BEFORE_RIGHT, REST_BEFORE_LEFT -> {
                    if (!atRest()) return false;
                    boolean right = stage == Stage.REST_BEFORE_RIGHT;
                    trial.drive(right ? Drive.RIGHT : Drive.LEFT);
                    go(right ? Stage.RIGHT : Stage.LEFT, null);
                }
                case RIGHT, LEFT -> {  // turning on the spot until the turning speed has settled
                    if (age % 20 == 0) series.add(new double[] {age, rate});
                    peak = Math.max(peak, attitude[2]);
                    if (age < 600 && !settled(2.0)) return false;
                    boolean right = stage == Stage.RIGHT;
                    double steady = series.get(series.size() - 1)[1];
                    put("degreesPerSecond", steady);
                    put("secondsTo63Percent", secondsTo(0.63, steady));
                    put("movedMeanwhile", flat(p, from));
                    put("largestTilt", peak);
                    if (right) rateRight = steady;
                    else rateLeft = steady;
                    if (Math.abs(steady) < 1.0) note("DRIVE: the manoeuvre '" + (right ? "right" : "left") + "' does not turn the aircraft (" + round(steady) + " degrees per second)");
                    else if (right != steady > 0) note("DRIVE: the manoeuvre '" + (right ? "right" : "left") + "' turns the aircraft the other way");
                    trial.drive(Drive.OFF);
                    JsonObject keep = current;
                    go(right ? Stage.RIGHT_COAST : Stage.LEFT_COAST, right ? "right" : "left");
                    current = keep;
                }
                case RIGHT_COAST, LEFT_COAST -> {   // drives off: how far the turning carries on
                    if (Math.abs(rate) > 0.5 && age < 800) return false;
                    boolean right = stage == Stage.RIGHT_COAST;
                    put("turnsOnAfterDrivesOff", Math.abs(turned));
                    put("secondsUntilItStops", age / 20.0);
                    if (right) coastTurnRight = Math.abs(turned);
                    else coastTurnLeft = Math.abs(turned);
                    go(right ? Stage.REST_BEFORE_LEFT : Stage.REST_BEFORE_SLOW, null);
                }
                case REST_BEFORE_SLOW -> {
                    if (!atRest()) return false;
                    if (ownSlow(Drive.AHEAD_SLOW)) {
                        trial.drive(Drive.AHEAD_SLOW);
                        go(Stage.SLOW_AHEAD, null);
                    } else {
                        go(Stage.REST_BEFORE_SLOW_TURN, null);
                    }
                }
                case SLOW_AHEAD -> {   // the slow manoeuvre ahead, from rest until the speed has settled
                    if (age % 20 == 0) series.add(new double[] {age, speed});
                    if (age < 600 && !settled(0.2)) return false;
                    slowSpeed = series.get(series.size() - 1)[1];
                    put("topSpeed", slowSpeed);
                    put("secondsTo63Percent", secondsTo(0.63, slowSpeed));
                    if (slowSpeed < 0.05) note("DRIVE: the manoeuvre 'ahead_slow' does not move the aircraft ahead (" + round(slowSpeed) + ")");
                    trial.drive(Drive.OFF);
                    go(Stage.REST_BEFORE_SLOW_TURN, "aheadSlow");
                }
                case REST_BEFORE_SLOW_TURN -> {
                    if (!atRest()) return false;
                    if (ownSlow(Drive.RIGHT_SLOW)) {
                        trial.drive(Drive.RIGHT_SLOW);
                        go(Stage.SLOW_RIGHT, null);
                    } else {
                        afterRaw();
                    }
                }
                case SLOW_RIGHT -> {   // the slow turn to the right, until the turning speed has settled
                    if (age % 20 == 0) series.add(new double[] {age, rate});
                    if (age < 600 && !settled(1.0)) return false;
                    slowTurnRate = series.get(series.size() - 1)[1];
                    put("degreesPerSecond", slowTurnRate);
                    put("secondsTo63Percent", secondsTo(0.63, slowTurnRate));
                    if (slowTurnRate < 0.5) note("DRIVE: the manoeuvre 'right_slow' does not turn the aircraft to the right (" + round(slowTurnRate) + " degrees per second)");
                    trial.drive(Drive.OFF);
                    go(Stage.SLOW_RIGHT_COAST, "rightSlow");
                }
                case SLOW_RIGHT_COAST -> {
                    if (!atRest()) return false;
                    afterRaw();
                }
                case RUN_UP_RIGHT, RUN_UP_LEFT -> {   // ahead for 10 seconds, then the gentle turn for 10 seconds
                    if (age < 200) return false;
                    boolean right = stage == Stage.RUN_UP_RIGHT;
                    trial.drive(right ? Drive.AHEAD_RIGHT : Drive.AHEAD_LEFT);
                    go(right ? Stage.GENTLE_RIGHT : Stage.GENTLE_LEFT, null);
                }
                case GENTLE_RIGHT, GENTLE_LEFT -> {
                    if (age > 100) {
                        sum += rate;
                        sumB += speed;
                        count++;
                    }
                    if (age < 200) return false;
                    boolean right = stage == Stage.GENTLE_RIGHT;
                    put("degreesPerSecond", sum / count);
                    put("speedAhead", sumB / count);
                    if (right != sum > 0) note("DRIVE: the manoeuvre '" + (right ? "ahead_right" : "ahead_left") + "' does not turn the aircraft to the " + (right ? "right" : "left") + " (" + round(sum / count) + " degrees per second)");
                    trial.drive(Drive.AHEAD);
                    go(right ? Stage.RUN_UP_LEFT : Stage.RUN_UP_PUSH, right ? "aheadRight" : "aheadLeft");
                }
                case RUN_UP_PUSH -> {  // ahead for 12 seconds: at cruising speed again, headed straight
                    if (age < 240) return false;
                    trial.drive(Drive.AHEAD_RIGHT);
                    go(Stage.GENTLE_PUSH, null);
                }
                case GENTLE_PUSH -> {  // a push of 6 ticks of the gentle turn to the right at cruising speed, then ahead again: what it turns in the end
                    if (age == 6) {
                        trial.drive(Drive.AHEAD);
                        sum = turned;
                        sumB = rate;
                    }
                    if (age < 160) return false;
                    // Turned so much for a push of 6 ticks: as if it turned this fast for those 6 ticks. What came after the push, by the turning speed at its end.
                    gentleRate = turned / 6.0 * 20.0;
                    gentleLead = Math.abs(sumB) > 0.05 ? Math.max(0.2, Math.min(4.0, (turned - sum) / sumB)) : 1.0;
                    put("ticks", 6);
                    put("turnedInTheEnd", turned);
                    put("turnedDuringThePush", sum);
                    put("asDegreesPerSecond", gentleRate);
                    put("secondsTheTurningCarriesOn", gentleLead);
                    put("speedAhead", speed);
                    if (gentleRate < 0.5) note("DRIVE: a short push of 'ahead_right' at cruising speed does not turn the aircraft to the right (" + round(turned) + " degrees for 6 ticks)");
                    trial.drive(Drive.OFF);
                    go(Stage.REST_BEFORE_LEG, "gentlePush");
                }
                case REST_BEFORE_LEG -> {   // to rest; then a leg of 80 blocks with the numbers that were worked out
                    if (!atRest()) return false;
                    if (gentleRate > 0.5 && type.topSpeed > 3.0 && !mode.equals("legs")) {
                        type.gentleTurnRate = gentleRate;
                        type.gentleLead = gentleLead;
                        report.getAsJsonObject("handling").addProperty("gentleTurnRate", round(type.gentleTurnRate));
                        report.getAsJsonObject("handling").addProperty("gentleLead", round(type.gentleLead));
                        report.getAsJsonObject("handling").addProperty("cruiseDeadband", round(type.cruiseDeadband));
                    }
                    double yaw = Math.toRadians(trial.yaw());
                    // (With an offset the line lies that far to the right of the aircraft, parallel to the way it is headed.)
                    legA = new Vec3(p.x - OFFSET * Math.sin(yaw), p.y, p.z + OFFSET * Math.cos(yaw));
                    legB = new Vec3(legA.x + LEG * Math.cos(yaw), p.y, legA.z + LEG * Math.sin(yaw));
                    trial.leg(legA.x, legA.z, legB.x, legB.z);
                    tries = 0;
                    go(Stage.LEG, null);
                }
                case LEG -> {          // the autopilot flies the leg and stops at its end
                    double lx = legB.x - legA.x, lz = legB.z - legA.z;
                    double along = ((p.x - legA.x) * lx + (p.z - legA.z) * lz) / LEG, side = ((p.x - legA.x) * lz - (p.z - legA.z) * lx) / LEG, across = Math.abs(side);
                    if (age > 100 || OFFSET == 0) peak = Math.max(peak, across);
                    high = Math.max(high, along - LEG);
                    sum = Math.max(sum, speed);
                    sumB = Math.max(sumB, attitude[2]);
                    if (age % 40 == 0 && series.size() < 400)
                        series.add(new double[] {age, along, side, speed, wrap(trial.yaw() - Math.toDegrees(Math.atan2(lz, lx)))});
                    boolean rest = trial.stopped() && Math.hypot(trial.vel().x, trial.vel().z) < 0.05;
                    held = rest ? held + 1 : 0;
                    if (held >= 20 && trial.remaining() > 1.5 && tries < 3) {
                        // As the flight controller does: at rest too far from the point, once more from here.
                        tries++;
                        held = 0;
                        trial.again();
                        return false;
                    }
                    if (held < 20 && age < 3600 + LEG * 20) return false;
                    put("length", LEG);
                    if (OFFSET != 0) put("lineBeganThisFarToTheRight", OFFSET);
                    put("seconds", age / 20.0);
                    put("blocksPerSecondOverTheWholeLeg", LEG / (age / 20.0));
                    put("stoppedThisFarFromThePoint", trial.remaining());
                    put("wentPastThePointBy", Math.max(0, high));
                    put("largestDistanceFromTheLine", peak);
                    put("topSpeed", sum);
                    put("largestTilt", sumB);
                    put("furtherTries", tries);
                    put("cameToRest", held >= 20 ? 1 : 0);
                    JsonArray track = new JsonArray();
                    for (double[] point : series) {
                        JsonArray one = new JsonArray();
                        for (int i = 0; i < point.length; i++) one.add(round(i == 0 ? point[0] / 20.0 : point[i]));
                        track.add(one);
                    }
                    current.add("secondsAlongSideSpeedHeadingOff", track);
                    if (held < 20) note("AUTOPILOT: on the leg of " + Math.round(LEG) + " blocks the aircraft did not come to rest at the end in time");
                    else if (trial.remaining() > 1.5) note("AUTOPILOT: the aircraft came to rest " + round(trial.remaining()) + " blocks from the end of the leg (1.5 is allowed)");
                    JsonObject autopilot = new JsonObject();
                    autopilot.add("leg", current);
                    report.add("autopilot", autopilot);
                    // On from where it rests: a leg of 40 blocks at a right angle to the right, as at a point of a route.
                    series.clear();
                    double yaw = Math.toRadians(Math.toDegrees(Math.atan2(legB.z - legA.z, legB.x - legA.x)) + 90.0);
                    legA = p;
                    legB = new Vec3(p.x + 40.0 * Math.cos(yaw), p.y, p.z + 40.0 * Math.sin(yaw));
                    trial.leg(legA.x, legA.z, legB.x, legB.z);
                    tries = 0;
                    go(Stage.CORNER, null);
                }
                case CORNER -> {       // the autopilot turns into the new leg, flies it and stops at its end
                    double lx = legB.x - legA.x, lz = legB.z - legA.z;
                    double along = ((p.x - legA.x) * lx + (p.z - legA.z) * lz) / 40.0, across = Math.abs((p.x - legA.x) * lz - (p.z - legA.z) * lx) / 40.0;
                    peak = Math.max(peak, across);
                    high = Math.max(high, along - 40.0);
                    sumB = Math.max(sumB, attitude[2]);
                    if (count == 0 && Math.abs(speed) > 1.0) count = age;      // under way along the new leg
                    boolean rest = trial.stopped() && Math.hypot(trial.vel().x, trial.vel().z) < 0.05;
                    held = rest ? held + 1 : 0;
                    if (held >= 20 && trial.remaining() > 1.5 && tries < 3) {
                        tries++;
                        held = 0;
                        trial.again();
                        return false;
                    }
                    if (held < 20 && age < 3600) return false;
                    cornerSeconds = age / 20.0;
                    put("length", 40);
                    put("seconds", age / 20.0);
                    put("secondsUntilUnderWay", count / 20.0);
                    put("stoppedThisFarFromThePoint", trial.remaining());
                    put("wentPastThePointBy", Math.max(0, high));
                    put("largestDistanceFromTheLine", peak);
                    put("largestTilt", sumB);
                    put("furtherTries", tries);
                    put("cameToRest", held >= 20 ? 1 : 0);
                    if (held < 20) note("AUTOPILOT: after the corner the aircraft did not come to rest at the end of the leg of 40 blocks within 180 seconds");
                    else if (trial.remaining() > 1.5) note("AUTOPILOT: after the corner the aircraft came to rest " + round(trial.remaining()) + " blocks from the end of the leg (1.5 is allowed)");
                    if (peak > 2.0) note("AUTOPILOT: turning into the new leg the aircraft strayed " + round(peak) + " blocks from the line (the tube of a line has 2 blocks of room to each side)");
                    report.getAsJsonObject("autopilot").add("corner", current);
                    trial.turnTo(trial.yaw() + 90.0);
                    go(Stage.QUARTER_TURN, null);
                }
                case QUARTER_TURN -> { // a quarter turn to the right, and hold
                    boolean rest = Math.abs(trial.headingError()) < 2.0 && Math.abs(rate) < 1.0;
                    held = rest ? held + 1 : 0;
                    peak = Math.max(peak, flat(p, from));
                    if (held < 40 && age < 1800) return false;
                    turnSeconds = (age - 40) / 20.0;
                    put("degrees", 90);
                    put("seconds", (age - 40) / 20.0);
                    put("headingOffAtTheEnd", trial.headingError());
                    put("movedMeanwhile", peak);
                    put("cameToRest", held >= 40 ? 1 : 0);
                    if (held < 40) note("AUTOPILOT: the quarter turn did not come to rest on the heading within 90 seconds");
                    report.getAsJsonObject("autopilot").add("turn", current);
                    double heading = trial.yaw() + trial.headingError(), yaw = Math.toRadians(heading);
                    legB = new Vec3(p.x + 3.0 * Math.cos(yaw), p.y, p.z + 3.0 * Math.sin(yaw));
                    trial.placeAt(heading, legB.x, legB.z);
                    go(Stage.PLACE, null);
                }
                case PLACE -> {        // placed over a point 3 blocks ahead, keeping the heading
                    if (!trial.placed() && age < 1800) return false;
                    put("blocks", 3);
                    put("seconds", age / 20.0);
                    put("restsThisFarFromThePoint", flat(p, legB));
                    put("headingOff", trial.headingError());
                    put("placed", trial.placed() ? 1 : 0);
                    if (!trial.placed()) note("AUTOPILOT: the aircraft was not placed over a point 3 blocks ahead within 90 seconds");
                    report.getAsJsonObject("autopilot").add("place", current);
                    // What the plans of several aircraft reckon with (the handling numbers were written down before the autopilot's trials).
                    type.pointSeconds = Math.max(5.0, cornerSeconds - 40.0 / type.topSpeed);
                    type.alignSeconds = Math.max(10.0, turnSeconds + 2.0 * age / 20.0);
                    if (report.has("handling")) {
                        report.getAsJsonObject("handling").addProperty("pointSeconds", round(type.pointSeconds));
                        report.getAsJsonObject("handling").addProperty("alignSeconds", round(type.alignSeconds));
                    }
                    end(true);
                    return true;
                }
            }
            return false;
        }

        /** The raw trials are over: the numbers the autopilot needs are worked out; then the gentle turns and the autopilot's own trials, or the end. */
        void afterRaw() {
            handling();
            if (mode.equals("raw")) {
                end(true);
                return;
            }
            trial.drive(Drive.AHEAD);
            go(Stage.RUN_UP_RIGHT, null);
        }

        /** The numbers the autopilot flies by, worked out from what was measured, set on the design and written down. */
        void handling() {
            double turnRate = (Math.abs(rateRight) + Math.abs(rateLeft)) / 2.0, turnCoast = (coastTurnRight + coastTurnLeft) / 2.0;
            type.topSpeed = Math.max(0.3, topSpeed);
            type.coastSeconds = Math.max(0.5, coastDistance / Math.max(0.3, Math.abs(coastFrom)));
            if (turnRate > 1.0) {
                type.turnCoastSeconds = Math.max(0.3, turnCoast / turnRate);
                type.turnTicksPerDegree = 0.8 * 20.0 / turnRate;
                type.leadSeconds = type.turnCoastSeconds;
            }
            // A design that is fast is stopped with its drives astern, and brought to the point with short pushes.
            // (-Dairlinetest.benchFast=true: a slow design is handled as if it were fast, to try that part of the autopilot out.)
            boolean fast = type.topSpeed > 3.0 || Boolean.getBoolean("airlinetest.benchFast");
            // (Stopped with the drives astern only when that is clearly shorter than coasting: with throttles that are closed
            // and opened step by step it need not be.)
            boolean brakes = fast && brakeFrom > 0.5 && brakeDistance / brakeFrom < 0.7 * coastDistance / Math.max(0.3, Math.abs(coastFrom));
            type.brakeSeconds = brakes ? Math.max(0.2, brakeDistance / brakeFrom) : 0.0;
            type.slowSpeed = slowSpeed > 0.05 ? slowSpeed : 0.0;
            type.slowTurnRate = slowTurnRate > 0.5 ? slowTurnRate : 0.0;
            type.creepWithin = fast || type.slowSpeed > 0 ? Math.max(4.0, type.topSpeed) : 0.0;
            // A fast design leans toward its line as if it aimed at a place on it 4 seconds ahead (the first designs: 12 degrees a block).
            if (fast) {
                type.leanPerBlock = Math.max(1.0, Math.min(12.0, 57.3 / (type.topSpeed * 4.0)));
                type.maxLean = 20.0;
            }
            type.fineTurnBelow = type.slowTurnRate > 0 ? Math.max(10.0, type.slowTurnRate * 1.5) : 0.0;
            type.groundSeconds = Math.round(climbSeconds * 16.0 / CLIMB + 16.0 / type.descentRate + 20.0);
            type.climbSpeed = Math.max(0.1, CLIMB / Math.max(1.0, climbSeconds));
            JsonObject h = new JsonObject();
            h.addProperty("topSpeed", round(type.topSpeed));
            h.addProperty("coastSeconds", round(type.coastSeconds));
            h.addProperty("brakeSeconds", round(type.brakeSeconds));
            h.addProperty("turnCoastSeconds", round(type.turnCoastSeconds));
            h.addProperty("turnTicksPerDegree", round(type.turnTicksPerDegree));
            h.addProperty("leadSeconds", round(type.leadSeconds));
            h.addProperty("slowSpeed", round(type.slowSpeed));
            h.addProperty("slowTurnRate", round(type.slowTurnRate));
            h.addProperty("creepWithin", round(type.creepWithin));
            h.addProperty("fineTurnBelow", round(type.fineTurnBelow));
            h.addProperty("leanPerBlock", round(type.leanPerBlock));
            h.addProperty("maxLean", round(type.maxLean));
            h.addProperty("hoverLever", round(type.hoverLever));
            if (heightScale < 0.999) {
                h.addProperty("heightGain", round(type.heightGain));
                h.addProperty("heightDamping", round(type.heightDamping));
                h.addProperty("heightIntegral", Math.round(type.heightIntegral * 1.0e6) / 1.0e6);
            }
            h.addProperty("parkedLever", type.parkedLever);
            h.addProperty("climbRate", round(type.climbRate));
            if (type.throttle != null) h.addProperty("throttleStep", type.throttleStep);
            h.addProperty("groundSeconds", round(type.groundSeconds));
            h.addProperty("climbSpeed", round(type.climbSpeed));
            report.add("handling", h);
        }

        void write() throws Exception {
            report.add("notes", notes);
            String out = System.getProperty("airlinetest.benchOut", "");
            Path path = out.isEmpty() ? Path.of("bench-report.json") : Path.of(out);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            s.note("BENCH report: " + path.toAbsolutePath());
        }
    }
}
