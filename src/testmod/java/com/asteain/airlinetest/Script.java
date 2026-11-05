package com.asteain.airlinetest;

import com.mojang.logging.LogUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Tick-driven scripted steps (same shape as Nevi's EngineRegression). */
final class Script {
    interface Action { void run() throws Exception; }
    interface Cond { boolean ok() throws Exception; }

    private static final class Step {
        final String name; final Action action; final Cond until; final int limit;
        boolean started; int age; long since;
        Step(String name, Action action, Cond until, int limit) { this.name = name; this.action = action; this.until = until; this.limit = limit; }
    }

    static final int WATCHDOG_TICKS = Integer.getInteger("airlinetest.watchdogTicks", 6000);

    /** -Psprint=on: the server ticks as fast as it can. What the server does beside its ticks (loading land, MineColonies'
     * path finding) then takes more ticks than at the normal 20 a second, so under sprint a wait gives up only when its ticks are up and
     * as much real time has gone by as those ticks take at the normal speed. Fixed waits (settle) still count ticks only.
     * The reopen scenes, which stop the server, run at the normal speed all the same: stopped about a second after the world opened,
     * with land still loading beside the ticks, the server sprinted into its shutdown and never got through it (reopenc1, twice). */
    static final boolean SPRINT = "on".equals(System.getProperty("airlinetest.sprint", "off")) && !System.getProperty("airlinetest.scene", "").startsWith("reopen");

    /** Whether as much real time has gone by since {@code since} (System.nanoTime) as {@code ticks} take at 20 a second; always true without sprint. */
    static boolean pastInRealTime(long since, int ticks) {
        return !SPRINT || System.nanoTime() - since >= ticks * 50_000_000L;
    }

    private static String seconds(long since) {
        return String.format(" (%.1f s)", (System.nanoTime() - since) / 1e9);
    }

    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private final List<Runnable> monitors = new ArrayList<>();
    private final ArrayDeque<Runnable> cleanups = new ArrayDeque<>();
    final List<String> notes = new ArrayList<>();
    final List<String> failures = new ArrayList<>();
    int softPass;
    String label = "";
    int checks;
    private int ticks;
    private long started;
    String current = "start";

    void step(String name, Action action) { steps.add(new Step(name, action, null, 0)); }
    void settle(String name, int ticks) { steps.add(new Step(name, () -> {}, null, ticks)); }
    void until(String name, Action action, Cond cond, int maxTicks) { steps.add(new Step(name, action, cond, maxTicks)); }
    /** Waits for cond up to maxTicks; on time-out runs onTimeout and goes on (no failure). */
    void attempt(String name, Action action, Cond cond, int maxTicks, Action onTimeout) {
        int[] age = new int[1];
        long[] since = new long[1];
        steps.add(new Step(name, action, () -> {
            if (cond.ok()) return true;
            if (age[0] == 0) since[0] = System.nanoTime();
            if (++age[0] >= maxTicks && pastInRealTime(since[0], maxTicks)) { onTimeout.run(); return true; }
            return false;
        }, maxTicks + 5));
    }
    void monitor(Runnable r) { monitors.add(r); }
    void clearMonitors() { monitors.clear(); }
    void onFinish(Runnable r) { cleanups.push(r); }

    void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(current + ": " + message);
        checks++;
        LogUtils.getLogger().info("[AIRTEST] PASS {}", message);
    }

    /** Observation that never fails the run. */
    void note(String line) {
        notes.add(line);
        LogUtils.getLogger().info("[AIRTEST] {}", line);
    }

    /** A non-fatal expectation: the suite keeps running and the run fails at the end if any soft check failed. */
    void soft(String name, boolean ok, String observation) {
        if (ok) { softPass++; checks++; note("OK   " + name + " | " + observation); }
        else { failures.add(name); note("MISS " + name + " | " + observation); }
    }

    /** An expectation that something does NOT work (an unused ship): passing here means the expected failure is still true. */
    void softNot(String name, boolean worked, String observation) {
        if (!worked) { softPass++; checks++; note("OK   (expected not to work) " + name + " | " + observation); }
        else { failures.add(name + " (XPASS)"); note("MISS (XPASS) " + name + " | " + observation); }
    }

    /** An expected failure: observed failure is recorded as XFAIL; an unexpected success is an XPASS failure. */
    void expectFail(String name, boolean passed, String observation) {
        if (passed) throw new AssertionError("XPASS " + name + ": expected failure did not happen | " + observation);
        note("XFAIL " + name + " | " + observation);
    }

    /** @return the final result text when finished, else null. */
    String tick() {
        try {
            if (ticks == 0) started = System.nanoTime();
            if (++ticks > WATCHDOG_TICKS && pastInRealTime(started, WATCHDOG_TICKS))
                throw new AssertionError("watchdog: no result after " + ticks + " ticks" + seconds(started) + "; stuck in step '" + current + "'");
            for (Runnable m : new ArrayList<>(monitors)) m.run();
            while (!steps.isEmpty()) {
                Step s = steps.peek();
                current = s.name;
                if (!s.started) { s.started = true; s.since = System.nanoTime(); s.action.run(); }
                if (s.until == null) {
                    if (s.age >= s.limit) { steps.poll(); continue; }
                    s.age++;
                    return null;
                }
                if (s.until.ok()) { steps.poll(); continue; }
                if (++s.age > s.limit && pastInRealTime(s.since, s.limit))
                    throw new AssertionError("timed out after " + s.age + " ticks" + seconds(s.since) + " waiting in step '" + s.name + "'");
                return null;
            }
            current = "finish";
            if (!failures.isEmpty()) return finish("FAIL " + failures.size() + " expectation(s) not met (" + softPass + " met): " + String.join("; ", failures), null);
            return finish("PASS " + checks + " checks " + label, null);
        } catch (Throwable failure) {
            return finish("FAIL " + failure, failure);
        }
    }

    private String finish(String text, Throwable failure) {
        String result = text;
        while (!cleanups.isEmpty()) {
            try { cleanups.pop().run(); } catch (Throwable f) {
                LogUtils.getLogger().error("[AIRTEST] cleanup failed", f);
                if (result.startsWith("PASS ")) result = "FAIL cleanup: " + f;
            }
        }
        if (failure != null) LogUtils.getLogger().error("[AIRTEST] FAILED in step '{}'", current, failure);
        if (!notes.isEmpty()) result = result + "\n" + String.join("\n", notes);
        return result;
    }
}
