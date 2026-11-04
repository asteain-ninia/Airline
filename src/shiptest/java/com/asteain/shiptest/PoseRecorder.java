package com.asteain.shiptest;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/** Per-tick sampler of a ship: grid fractions of block centres, yaw/tilt, sway, tick-to-tick deltas, and the "stopped" window test. */
final class PoseRecorder {
    static final double STOP_POS = 0.01, STOP_DEG = 0.1;
    static final int STOP_TICKS = 20;

    final Object sub;
    final List<Vec3> plotPoints;
    int ticks;
    final double[] maxFrac = new double[3];
    double maxSway, maxYawDev, maxTilt, maxDeltaPos, maxDeltaDeg, maxLin, maxAng;
    int stoppedFirstTick = -1;   // first tick at which the last 20 ticks stayed within 0.01 block and 0.1 deg of the window start
    int stoppedWindows;          // number of ticks at which that window test passed
    boolean lastStoppedFlag;     // ServerSubLevel.getLastNetworkedStopped()
    private Vec3[] first;
    private Vec3 prevCenter;
    private Quaterniond prevQ;
    private final List<Vec3> centerHist = new ArrayList<>();
    private final List<Quaterniond> quatHist = new ArrayList<>();

    PoseRecorder(Object sub, List<Vec3> plotPoints) { this.sub = sub; this.plotPoints = plotPoints; }

    static double frac(double v) { return v - (Math.floor(v) + 0.5); }

    static double yawDev(Quaterniond q) {
        Vector3d v = q.transform(new Vector3d(1, 0, 0));
        double ang = Math.toDegrees(Math.atan2(-v.z, v.x));
        return ang - 90.0 * Math.round(ang / 90.0);
    }

    static double tilt(Quaterniond q) {
        Vector3d v = q.transform(new Vector3d(0, 1, 0));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, v.y))));
    }

    static double angleBetween(Quaterniond a, Quaterniond b) {
        double dot = Math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w);
        return Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot)));
    }

    void sample() throws Exception {
        if (first == null) first = new Vec3[plotPoints.size()];
        Vec3 center = Ships.position(sub);
        Quaterniond q = Ships.orientation(sub);
        for (int i = 0; i < plotPoints.size(); i++) {
            Vec3 w = Ships.toWorld(sub, plotPoints.get(i));
            double[] f = {frac(w.x), frac(w.y), frac(w.z)};
            for (int a = 0; a < 3; a++) maxFrac[a] = Math.max(maxFrac[a], Math.abs(f[a]));
            if (first[i] == null) first[i] = w;
            maxSway = Math.max(maxSway, w.distanceTo(first[i]));
        }
        maxYawDev = Math.max(maxYawDev, Math.abs(yawDev(q)));
        maxTilt = Math.max(maxTilt, tilt(q));
        maxLin = Math.max(maxLin, Ships.velocity(sub).length());
        maxAng = Math.max(maxAng, Ships.angularVelocity(sub).length());
        if (prevCenter != null) {
            maxDeltaPos = Math.max(maxDeltaPos, center.distanceTo(prevCenter));
            maxDeltaDeg = Math.max(maxDeltaDeg, angleBetween(q, prevQ));
        }
        prevCenter = center;
        prevQ = new Quaterniond(q);
        centerHist.add(center);
        quatHist.add(new Quaterniond(q));
        int n = centerHist.size();
        if (n >= STOP_TICKS) {
            Vec3 c0 = centerHist.get(n - STOP_TICKS);
            Quaterniond q0 = quatHist.get(n - STOP_TICKS);
            boolean ok = true;
            for (int i = n - STOP_TICKS; i < n && ok; i++)
                ok = centerHist.get(i).distanceTo(c0) <= STOP_POS && angleBetween(quatHist.get(i), q0) <= STOP_DEG;
            if (ok) { stoppedWindows++; if (stoppedFirstTick < 0) stoppedFirstTick = ticks; }
        }
        try {
            lastStoppedFlag = (boolean) Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel").getMethod("getLastNetworkedStopped").invoke(sub);
        } catch (ReflectiveOperationException e) { lastStoppedFlag = false; }
        ticks++;
    }

    String report() throws Exception {
        Quaterniond q = Ships.orientation(sub);
        Vec3 p = Ships.position(sub);
        return String.format("samples=%d finalPos=(%.4f,%.4f,%.4f) finalYawDevFrom90=%.4f finalTilt=%.4f | block-centre grid offset max|dx|=%.5f max|dy|=%.5f max|dz|=%.5f "
                        + "| window: yawDev<=%.4f deg tilt<=%.4f deg sway<=%.5f blocks, per-tick delta pos<=%.5f blocks ang<=%.5f deg, speed lin<=%.5f ang<=%.5f "
                        + "| stopped test (20 ticks within %.2f block and %.1f deg): first passed at sample %d, passed at %d of %d samples, final window passes=%s, Sable getLastNetworkedStopped=%s",
                ticks, p.x, p.y, p.z, yawDev(q), tilt(q), maxFrac[0], maxFrac[1], maxFrac[2], maxYawDev, maxTilt, maxSway, maxDeltaPos, maxDeltaDeg, maxLin, maxAng,
                STOP_POS, STOP_DEG, stoppedFirstTick, stoppedWindows, ticks, finalWindowOk(), lastStoppedFlag);
    }

    boolean finalWindowOk() {
        int n = centerHist.size();
        if (n < STOP_TICKS) return false;
        Vec3 c0 = centerHist.get(n - STOP_TICKS);
        Quaterniond q0 = quatHist.get(n - STOP_TICKS);
        for (int i = n - STOP_TICKS; i < n; i++)
            if (centerHist.get(i).distanceTo(c0) > STOP_POS || angleBetween(quatHist.get(i), q0) > STOP_DEG) return false;
        return true;
    }
}
