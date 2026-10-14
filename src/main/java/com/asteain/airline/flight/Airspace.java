package com.asteain.airline.flight;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Traffic in the air (proposal of the assistant; the user's ruling: no "one aircraft per line", keep them apart by
 * their size, and do not let a dense network jam).
 *
 * <p>Every flight has a plan: where the aircraft means to be at which time, from the pad it leaves to the pad it lands
 * on. An aircraft on the ground only leaves when its plan keeps a distance from every plan that is already under way,
 * with a margin of time that grows the further ahead the plans look. So aircraft that would meet wait on the ground,
 * where waiting costs nothing and cannot lock up; several may use the same line at once as long as they stay apart,
 * and two that want the same line in opposite directions simply go one after the other.</p>
 *
 * <p>A plan is kept up to date while its aircraft flies (at every point it passes, and when it is held up). Plans that
 * come to clash after both aircraft left are not settled here: the aircraft still look ahead for other ships and hold.
 * Plans are not saved: after a restart an aircraft in the air makes its plan again when it takes up its flight.</p>
 */
public final class Airspace {
    /** Two aircraft keep at least this far apart, seen from above (two half diagonals of the one design there is, and room). */
    static final double APART_FLAT = 16.0;
    /** ... unless one is this much higher than the other (two half heights and room). */
    static final double APART_HEIGHT = 11.0;
    /** How wrong a plan's times may be: this many ticks at once, and this share of the time it looks ahead. */
    static final int SLACK_TICKS = 300;
    static final double SLACK_SHARE = 0.15;
    private static final int STEP = 20;

    /** Where an aircraft means to be: a path of places with the game time for each. Before the first and after the last time it stands still. */
    public record Plan(UUID ship, String dimension, long made, List<Vec3> places, long[] times) {
        public long start() { return times[0]; }
        public long end() { return times[times.length - 1]; }

        public Vec3 at(long time) {
            if (time <= times[0]) return places.get(0);
            for (int i = 1; i < times.length; i++) {
                if (time > times[i]) continue;
                final long span = times[i] - times[i - 1];
                final double part = span <= 0 ? 1.0 : (double) (time - times[i - 1]) / span;
                final Vec3 a = places.get(i - 1), b = places.get(i);
                return new Vec3(a.x + (b.x - a.x) * part, a.y + (b.y - a.y) * part, a.z + (b.z - a.z) * part);
            }
            return places.get(places.size() - 1);
        }
    }

    /** Builds plans leg by leg. */
    public static final class Builder {
        private final List<Vec3> places = new ArrayList<>();
        private final List<Long> times = new ArrayList<>();

        public Builder(Vec3 start, long time) {
            places.add(start);
            times.add(time);
        }

        /** On to a place, arriving this many ticks after the place before. */
        public Builder then(Vec3 place, long ticks) {
            places.add(place);
            times.add(times.get(times.size() - 1) + Math.max(1, ticks));
            return this;
        }

        /** On to a place at a speed (blocks per second, flat distance). */
        public Builder fly(Vec3 place, double speed) {
            final Vec3 from = places.get(places.size() - 1);
            return then(place, Math.round(Math.hypot(place.x - from.x, place.z - from.z) / Math.max(0.1, speed) * 20.0));
        }

        public Plan build(UUID ship, String dimension, long now) {
            final long[] at = new long[times.size()];
            for (int i = 0; i < at.length; i++) at[i] = times.get(i);
            return new Plan(ship, dimension, now, List.copyOf(places), at);
        }
    }

    private static final Map<MinecraftServer, Airspace> ALL = new WeakHashMap<>();
    private final Map<UUID, Plan> plans = new HashMap<>();

    public static synchronized Airspace get(MinecraftServer server) {
        return ALL.computeIfAbsent(server, s -> new Airspace());
    }

    public void put(Plan plan) {
        plans.put(plan.ship(), plan);
    }

    public void remove(UUID ship) {
        plans.remove(ship);
    }

    @Nullable
    public Plan plan(UUID ship) {
        return plans.get(ship);
    }

    public int size() {
        return plans.size();
    }

    /** The aircraft whose plan this one would come too near, or null when the way is free. Plans that are over long ago are dropped. */
    @Nullable
    public UUID clash(Plan plan, long now) {
        plans.values().removeIf(other -> other.end() + 12000 < now);
        for (final Plan other : plans.values()) {
            if (other.ship().equals(plan.ship()) || !other.dimension().equals(plan.dimension())) continue;
            if (near(plan, other)) return other.ship();
        }
        return null;
    }

    /** Whether two plans bring their aircraft too near each other, allowing for times that are off. */
    static boolean near(Plan a, Plan b) {
        final long made = Math.max(a.made(), b.made());
        final long from = Math.max(a.start(), b.start() - SLACK_TICKS), to = Math.min(a.end(), b.end() + slack(b.end() - made));
        for (long time = from; time <= to; time += STEP) {
            final Vec3 here = a.at(time);
            final long slack = slack(time - made);
            // The other aircraft may be anywhere it plans to be within the slack around this time.
            for (long other = time - slack; other <= time + slack; other += Math.max(STEP, slack / 4)) {
                if (other > b.end() && time > b.end()) break;      // it has landed and its plan is about to go
                final Vec3 there = b.at(other);
                if (Math.abs(here.y - there.y) < APART_HEIGHT && Math.hypot(here.x - there.x, here.z - there.z) < APART_FLAT) return true;
            }
        }
        return false;
    }

    private static long slack(long ahead) {
        return SLACK_TICKS + Math.round(Math.max(0, ahead) * SLACK_SHARE);
    }
}
