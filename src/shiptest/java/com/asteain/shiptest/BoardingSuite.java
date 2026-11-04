package com.asteain.shiptest;

import com.asteain.airline.boarding.Boarding;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Boarding (stage 1b): a citizen is sent to a seat on a stopped ship, sits, is carried while the ship moves, and is
 * sent back to the ground. Zero gravity; the 5x3 deck touches the ground edge and its pose is held every tick, except
 * while the test lets it drift. Headings 0 (driven through the operator command), 20 and 45 degrees (through the code
 * entry point), plus a ship that disappears under a seated citizen.
 */
final class BoardingSuite {
    private BoardingSuite() {}

    /** Collects what a command answers. */
    static final class Answers implements CommandSource {
        final List<String> lines = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { lines.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
        String last() { return lines.isEmpty() ? "(no answer)" : lines.get(lines.size() - 1); }
    }

    static String command(MinecraftServer server, String line) {
        Answers answers = new Answers();
        CommandSourceStack source = server.createCommandSourceStack().withSource(answers).withPermission(4);
        server.getCommands().performPrefixedCommand(source, line);
        return answers.last();
    }

    /** Watches the citizen every tick. */
    private static final class Track {
        final Mob mob;
        Vec3 last;
        double maxJump, minY = 1e9, walked;
        int ticks;
        boolean alive = true;
        Track(Mob mob) { this.mob = mob; this.last = mob.position(); }
        void tick() {
            ticks++;
            Vec3 p = mob.position();
            double d = p.distanceTo(last);
            maxJump = Math.max(maxJump, d);
            walked += Math.hypot(p.x - last.x, p.z - last.z);
            minY = Math.min(minY, p.y);
            alive &= mob.isAlive();
            last = p;
        }
        String describe() { return String.format("ticks=%d walked=%.2f maxJump/tick=%.3f minY=%.3f alive=%s", ticks, walked, maxJump, minY, alive); }
    }

    static Script build(MinecraftServer server) {
        Script s = new Script();
        s.label = "boarding (walk to a seat on a stopped ship, ride, leave; zero gravity)";
        ServerLevel level = server.overworld();
        BlockPos origin = Rig.site(level, 0);
        Rig rig = new Rig(server, origin);
        rig.addColony(s, "CPS boarding", 12, 8);
        addCase(s, rig, origin.offset(0, 0, 70), 0, true);
        addCase(s, rig, origin.offset(0, 0, 140), 20, false);
        addCase(s, rig, origin.offset(0, 0, 210), 45, false);
        addVanishingShip(s, rig, origin.offset(0, 0, 280));
        s.step("no internal failures", () -> s.soft("boarding had no internal failure in the whole run", Boarding.failures() == 0, "failures=" + Boarding.failures()));
        return s;
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static Vec3 world(Object ship, Vec3 plot) {
        try { return Ships.toWorld(ship, plot); } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Builds the deck, turns it in the air, puts its nearest corner 0.02 blocks from the ground edge and holds that pose while hold[0] is true. */
    private static void addDeck(Script s, Rig rig, BlockPos site, double yaw, String name, Object[] ship, Map<Long, Vec3> plotOf, boolean[] hold, Vector3d[] holdPos, Quaterniond[] holdQ) {
        ServerLevel level = rig.level;
        s.step(name + ": build", () -> {
            Colony.platform(level, site, 12, 8);
            Rig.force(level, site.offset(25, 0, 0), 40, 12);
            List<BlockPos> blocks = new ArrayList<>();
            for (int x = 13; x <= 17; x++) for (int z = -1; z <= 1; z++) {
                BlockPos p = site.offset(x, -1, z);
                level.setBlockAndUpdate(p, Blocks.OAK_PLANKS.defaultBlockState());
                blocks.add(p);
            }
            ship[0] = rig.assemble(blocks);
            for (int x = 13; x <= 17; x++) for (int z = -1; z <= 1; z++)
                plotOf.put(key(x, z), Vec3.atCenterOf(BlockPos.containing(Ships.toPlot(ship[0], Vec3.atCenterOf(site.offset(x, -1, z))))));
            Vec3 pos = Ships.position(ship[0]);
            holdQ[0] = Ships.orientation(ship[0]).rotateY(Math.toRadians(yaw));
            Ships.teleport(ship[0], new Vector3d(pos.x, pos.y + 8, pos.z), holdQ[0]);
        });
        s.settle(name + ": turn in the air", 3);
        s.step(name + ": put beside the ground edge and hold", () -> {
            double minX = 1e18;
            for (Vec3 pc : plotOf.values())
                for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) minX = Math.min(minX, world(ship[0], pc.add(0.5 * sx, 0, 0.5 * sz)).x);
            Vec3 pos = Ships.position(ship[0]);
            holdPos[0] = new Vector3d(pos.x + (site.getX() + 13.02 - minX), pos.y - 8, pos.z);
            Ships.teleport(ship[0], holdPos[0], holdQ[0]);
            hold[0] = true;
            s.monitor(() -> {
                if (!hold[0]) return;
                try { Ships.teleport(ship[0], holdPos[0], holdQ[0]); Ships.zeroVelocity(ship[0]); } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        s.settle(name + ": settle (the ship becomes 'stopped')", 40);
    }

    private static void addCase(Script s, Rig rig, BlockPos site, double yaw, boolean byCommand) {
        MinecraftServer server = rig.server;
        String name = "boarding yaw " + (int) yaw + (byCommand ? " (command)" : " (code)");
        Object[] ship = new Object[1];
        Map<Long, Vec3> plotOf = new HashMap<>();
        boolean[] hold = new boolean[1];
        Vector3d[] holdPos = new Vector3d[1];
        Quaterniond[] holdQ = new Quaterniond[1];
        Track[] track = new Track[1];
        Entity[] seat = new Entity[1];
        float[] health = new float[1];
        BlockPos ground = site.offset(-4, 0, 0);
        addDeck(s, rig, site, yaw, name, ship, plotOf, hold, holdPos, holdQ);

        // --- to the seat
        s.until(name + ": walk to the seat and sit", () -> {
            rig.citizen.setPos(Rig.standing(ground));
            rig.citizen.getNavigation().stop();
            Vec3 seatPlot = plotOf.get(key(16, 0));
            Vec3 seatWorld = world(ship[0], seatPlot);
            if (byCommand) {
                String answer = command(server, "airline board " + rig.citizen.getUUID() + " " + BlockPos.containing(seatWorld).getX() + " " + BlockPos.containing(seatWorld).getY() + " " + BlockPos.containing(seatWorld).getZ());
                s.note("OBS " + name + ": the command answered: " + answer);
                s.check(Boarding.state(rig.citizen) == Boarding.State.WALKING, "the board command was accepted (" + answer + ")");
            } else {
                Boarding.Result r = Boarding.board(rig.citizen, Ships.id(ship[0]), BlockPos.containing(seatPlot));
                s.check(r == Boarding.Result.OK, "the board order was accepted (" + r + ")");
            }
            track[0] = new Track(rig.citizen);
        }, () -> {
            if (Boarding.state(rig.citizen) == Boarding.State.SEATED) return true;
            track[0].tick();
            return false;
        }, 500);
        s.step(name + ": seated", () -> {
            Vec3 sit = world(ship[0], plotOf.get(key(16, 0)).add(0, 0.5, 0));
            Vec3 at = rig.citizen.position();
            seat[0] = rig.citizen.getVehicle();
            health[0] = rig.citizen.getHealth();
            double flat = Math.hypot(at.x - sit.x, at.z - sit.z);
            boolean seated = seat[0] != null && seat[0].getClass().getSimpleName().equals("SittingEntity") && flat < 0.05;
            s.soft(name + " / sits on the seat (on MineColonies' seat entity, over the seat block)", seated,
                    String.format("vehicle=%s flatDistanceFromSeat=%.3f citizenY=%.3f seatTopY=%.3f", seat[0] == null ? "none" : seat[0].getClass().getSimpleName(), flat, at.y, sit.y));
            boolean walked = track[0].walked > 10 && track[0].maxJump < 1.0 && track[0].minY > site.getY() - 0.6 && track[0].alive;
            s.soft(name + " / got there by walking (no teleport, no fall)", walked, track[0].describe());
        });

        // --- carried while the ship moves
        double[] maxOff = new double[1], maxExtra = new double[1], shipMoved = new double[1], maxShipStep = new double[1];
        boolean[] stayed = {true};
        Vec3[] prevShip = new Vec3[1], prevMob = new Vec3[1], startShip = new Vec3[1];
        String[] refused = new String[1];
        int[] rideTicks = new int[1];
        s.step(name + ": let the ship go", () -> {
            hold[0] = false;
            startShip[0] = Ships.position(ship[0]);
            prevShip[0] = startShip[0];
            prevMob[0] = rig.citizen.position();
            Ships.impulse(ship[0], 6, 0, 0);
            s.monitor(() -> {
                if (hold[0]) return;
                try {
                    rideTicks[0]++;
                    Vec3 shipNow = Ships.position(ship[0]);
                    Vec3 mobNow = rig.citizen.position();
                    Vec3 sit = world(ship[0], plotOf.get(key(16, 0)).add(0, 0.5, 0));
                    maxOff[0] = Math.max(maxOff[0], Math.hypot(mobNow.x - sit.x, mobNow.z - sit.z));
                    double shipStep = shipNow.distanceTo(prevShip[0]);
                    maxShipStep[0] = Math.max(maxShipStep[0], shipStep);
                    maxExtra[0] = Math.max(maxExtra[0], Math.abs(mobNow.distanceTo(prevMob[0]) - shipStep));
                    stayed[0] &= rig.citizen.isAlive() && rig.citizen.getVehicle() == seat[0] && Boarding.state(rig.citizen) == Boarding.State.SEATED
                            && Colony.entity(rig.data) == rig.citizen;
                    if (rideTicks[0] == 15) refused[0] = String.valueOf(Boarding.leave(rig.citizen, ground));
                    if (rideTicks[0] == 30) Ships.impulse(ship[0], 60, 0, 0);     // then fast: about half a block per tick
                    prevShip[0] = shipNow;
                    prevMob[0] = mobNow;
                    shipMoved[0] = shipNow.distanceTo(startShip[0]);
                } catch (Exception e) { throw new AssertionError(e); }
            });
        });
        s.settle(name + ": the ship drifts", 60);
        s.step(name + ": ship stops", () -> Ships.zeroVelocity(ship[0]));
        s.settle(name + ": stopped away from the ground", 30);
        s.step(name + ": report the ride", () -> {
            s.soft(name + " / the ship really moved with the citizen on it", shipMoved[0] > 2.0, String.format("shipMoved=%.2f fastestStep=%.3f blocks/tick", shipMoved[0], maxShipStep[0]));
            s.soft(name + " / carried with the ship: stays on the seat, same citizen, never away from the seat, no jump of its own",
                    stayed[0] && maxOff[0] < 0.05 && maxExtra[0] < 0.05 && rig.citizen.getHealth() >= health[0],
                    String.format("stayedSeatedSameEntity=%s largestFlatDistanceFromSeat=%.4f largestOwnMovePerTick=%.4f health=%.1f (was %.1f) ticks=%d",
                            stayed[0], maxOff[0], maxExtra[0], rig.citizen.getHealth(), health[0], rideTicks[0]));
            s.soft(name + " / an order to leave is refused while the ship moves", "SHIP_MOVING".equals(refused[0]), "answer=" + refused[0]);
        });
        s.step(name + ": bring the ship back to the ground edge", () -> {
            Ships.teleport(ship[0], holdPos[0], holdQ[0]);
            Ships.zeroVelocity(ship[0]);
            hold[0] = true;
        });
        s.settle(name + ": settle at the ground edge", 40);
        s.step(name + ": still seated after the trip", () -> {
            Vec3 sit = world(ship[0], plotOf.get(key(16, 0)).add(0, 0.5, 0));
            Vec3 at = rig.citizen.position();
            s.soft(name + " / still on the same seat after the ship came back", rig.citizen.getVehicle() == seat[0] && Math.hypot(at.x - sit.x, at.z - sit.z) < 0.05,
                    String.format("flatDistanceFromSeat=%.4f state=%s", Math.hypot(at.x - sit.x, at.z - sit.z), Boarding.state(rig.citizen)));
        });

        // --- stand up and walk to the ground
        boolean[] stoodAtOnce = new boolean[1];
        s.until(name + ": stand up and walk to the ground", () -> {
            if (byCommand) {
                String answer = command(server, "airline leave " + rig.citizen.getUUID() + " " + ground.getX() + " " + ground.getY() + " " + ground.getZ());
                s.note("OBS " + name + ": the command answered: " + answer);
            } else {
                Boarding.Result r = Boarding.leave(rig.citizen, ground);
                s.check(r == Boarding.Result.OK, "the leave order was accepted (" + r + ")");
            }
            stoodAtOnce[0] = !rig.citizen.isPassenger() && Boarding.state(rig.citizen) == Boarding.State.LEAVING;
            track[0] = new Track(rig.citizen);
        }, () -> {
            if (Boarding.state(rig.citizen) == Boarding.State.NONE) return true;
            track[0].tick();
            return false;
        }, 500);
        s.step(name + ": on the ground", () -> {
            Vec3 at = rig.citizen.position();
            double dist = Math.hypot(at.x - (ground.getX() + 0.5), at.z - (ground.getZ() + 0.5));
            s.soft(name + " / stands up when told to leave", stoodAtOnce[0], "notRidingAndLeaving=" + stoodAtOnce[0]);
            s.soft(name + " / walks off the ship to the ground (no teleport, no fall)",
                    dist < 2.0 && track[0].walked > 10 && track[0].maxJump < 1.0 && track[0].minY > site.getY() - 0.6 && track[0].alive && !rig.citizen.isPassenger(),
                    String.format("distanceToGroundSpot=%.2f %s", dist, track[0].describe()));
        });
        s.step(name + ": remove", () -> { s.clearMonitors(); rig.removeShipSafely(ship[0], site.offset(-8, 0, 0)); });
    }

    /** The ship is deleted under a seated citizen: the citizen must end up alive on solid ground, and nothing may throw. */
    private static void addVanishingShip(Script s, Rig rig, BlockPos site) {
        String name = "boarding, ship vanishes";
        Object[] ship = new Object[1];
        Map<Long, Vec3> plotOf = new HashMap<>();
        boolean[] hold = new boolean[1];
        Vector3d[] holdPos = new Vector3d[1];
        Quaterniond[] holdQ = new Quaterniond[1];
        BlockPos ground = site.offset(-4, 0, 0);
        addDeck(s, rig, site, 0, name, ship, plotOf, hold, holdPos, holdQ);
        s.until(name + ": walk to the seat and sit", () -> {
            rig.citizen.setPos(Rig.standing(ground));
            rig.citizen.getNavigation().stop();
            Boarding.Result r = Boarding.board(rig.citizen, Ships.id(ship[0]), BlockPos.containing(plotOf.get(key(16, 0))));
            s.check(r == Boarding.Result.OK, "the board order was accepted (" + r + ")");
        }, () -> Boarding.state(rig.citizen) == Boarding.State.SEATED, 500);
        s.settle(name + ": sit a moment", 10);
        s.step(name + ": delete the ship", () -> {
            s.clearMonitors();
            try { Ships.remove(rig.level, ship[0]); } catch (Throwable t) { throw new AssertionError(t); }
        });
        s.settle(name + ": afterwards", 40);
        s.step(name + ": where is the citizen", () -> {
            Vec3 at = rig.citizen.position();
            BlockPos below = BlockPos.containing(at.x, at.y - 0.5, at.z);
            boolean onGround = !rig.level.getBlockState(below).isAir() && Math.abs(at.y - site.getY()) < 0.6;
            s.soft(name + " / the citizen is alive, standing on solid ground, no longer boarding",
                    rig.citizen.isAlive() && Colony.entity(rig.data) == rig.citizen && !rig.citizen.isPassenger() && onGround && Boarding.state(rig.citizen) == Boarding.State.NONE,
                    String.format("alive=%s passenger=%s position=(%.2f,%.2f,%.2f) blockBelow=%s state=%s cameFrom=(%.1f,%.1f,%.1f)",
                            rig.citizen.isAlive(), rig.citizen.isPassenger(), at.x, at.y, at.z, rig.level.getBlockState(below).getBlock(), Boarding.state(rig.citizen),
                            ground.getX() + 0.5, (double) ground.getY(), ground.getZ() + 0.5));
            s.soft(name + " / nothing threw", Boarding.failures() == 0, "failures=" + Boarding.failures());
        });
        s.step(name + ": clean up", () -> { try { Ships.removeNear(rig.level, Vec3.atCenterOf(site), 80); } catch (Throwable t) { throw new AssertionError(t); } });
    }
}
