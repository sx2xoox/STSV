package dev.stsv.economy.secret.garage;

import java.util.List;

/**
 * The road as the 폐차고 sees it (pure, unit-tested): a 국도's points (x, z, one a block) and what is worked out from them —
 * the sharpest bend, a spot beside the road, how far along the road somebody is, and how fast the dead racer drives.
 * <p>
 * Sides are counted to the right of the way the road runs: +1 is the right-hand roadside, -1 the left.
 */
final class Roadway {

    private Roadway() {}

    /** A bend: the point it is sharpest at, how far the road turns over the window (degrees), and its outer side (+1 right, -1 left). */
    record Bend(int index, double degrees, int outside) {}

    /** Which road, and its bend. */
    record Pick(int road, Bend bend) {}

    /**
     * How far the road turns at point i, looking {@code half} points back and {@code half} ahead (degrees; + to the right,
     * - to the left; 0 too near an end).
     */
    static double turn(List<double[]> pts, int i, int half) {
        if (half < 1 || i - half < 0 || i + half >= pts.size()) return 0;
        double[] a = pts.get(i - half), b = pts.get(i), c = pts.get(i + half);
        double ax = b[0] - a[0], az = b[1] - a[1], bx = c[0] - b[0], bz = c[1] - b[1];
        double cross = ax * bz - az * bx, dot = ax * bx + az * bz;
        if (Math.abs(cross) < 1e-9 && Math.abs(dot) < 1e-9) return 0;
        // +z is south and +x east: a turn from south to west (to the right hand) has a positive cross
        return Math.toDegrees(Math.atan2(cross, dot));
    }

    /**
     * The sharpest heading change over a window of this many points, among the points from..to (inclusive); null when
     * the road is too short for a window there. Of equal bends the first.
     */
    static Bend sharpest(List<double[]> pts, int window, int from, int to) {
        int half = Math.max(1, window / 2);
        int a = Math.max(from, half), b = Math.min(to, pts.size() - 1 - half);
        Bend best = null;
        for (int i = a; i <= b; i++) {
            double t = turn(pts, i, half);
            if (best == null || Math.abs(t) > best.degrees() + 1e-9) best = new Bend(i, Math.abs(t), t > 0 ? -1 : 1);
        }
        return best;
    }

    /**
     * The road and bend the story happened on: the sharpest bend of a hard road; without a hard road long enough, of any
     * road. A bend lies at least {@code lead} points after the road's start and {@code tail} before its end. Null: no road.
     */
    static Pick pick(List<List<double[]>> roads, List<Boolean> hard, int window, int lead, int tail) {
        Pick best = null, bestHard = null;
        for (int r = 0; r < roads.size(); r++) {
            List<double[]> pts = roads.get(r);
            Bend b = sharpest(pts, window, lead, pts.size() - 1 - tail);
            if (b == null) continue;
            if (best == null || b.degrees() > best.bend().degrees() + 1e-9) best = new Pick(r, b);
            if (hard.get(r) && (bestHard == null || b.degrees() > bestHard.bend().degrees() + 1e-9)) bestHard = new Pick(r, b);
        }
        return bestHard != null ? bestHard : best;
    }

    /** The road's middle at s points along it (between two points: on the line between them). */
    static double[] at(List<double[]> pts, double s) {
        int n = pts.size();
        if (s <= 0) return new double[]{pts.getFirst()[0], pts.getFirst()[1]};
        if (s >= n - 1) return new double[]{pts.getLast()[0], pts.getLast()[1]};
        int i = (int) Math.floor(s);
        double f = s - i;
        double[] a = pts.get(i), b = pts.get(i + 1);
        return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
    }

    /** Which way the road runs at s (a unit x, z), looking {@code reach} points each way (smooth over the road's small steps). */
    static double[] tangent(List<double[]> pts, double s, int reach) {
        int n = pts.size(), i = (int) Math.round(Math.clamp(s, 0, n - 1));
        for (int r = Math.max(1, reach), tries = 0; tries < 12; r *= 2, tries++) {
            double[] a = pts.get(Math.max(0, i - r)), b = pts.get(Math.min(n - 1, i + r));
            double dx = b[0] - a[0], dz = b[1] - a[1], len = Math.hypot(dx, dz);
            if (len > 1e-6) return new double[]{dx / len, dz / len};
        }
        return new double[]{0, 1};
    }

    /** A spot {@code lateral} blocks to the right of the road's middle at s (negative: to the left). */
    static double[] beside(List<double[]> pts, double s, double lateral, int reach) {
        double[] p = at(pts, s), t = tangent(pts, s, reach);
        return new double[]{p[0] - t[1] * lateral, p[1] + t[0] * lateral};
    }

    /** The yaw (Minecraft's: 0 south, 90 west) of someone going this way. */
    static float yaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * How far along the road (points, with a fraction) the spot (x, z) is: the nearest place on the road's line between
     * the points from..to. A window keeps a road that doubles back from being mistaken for itself.
     */
    static double project(List<double[]> pts, double x, double z, int from, int to) {
        int a = Math.max(0, from), b = Math.min(pts.size() - 1, to);
        if (b <= a) return Math.clamp(a, 0, pts.size() - 1);
        double best = Double.MAX_VALUE, at = a;
        for (int i = a; i < b; i++) {
            double[] p = pts.get(i), q = pts.get(i + 1);
            double dx = q[0] - p[0], dz = q[1] - p[1], len2 = dx * dx + dz * dz;
            double t = len2 < 1e-12 ? 0 : Math.clamp(((x - p[0]) * dx + (z - p[1]) * dz) / len2, 0, 1);
            double ex = p[0] + dx * t - x, ez = p[1] + dz * t - z, d = ex * ex + ez * ez;
            if (d < best) {
                best = d;
                at = i + t;
            }
        }
        return at;
    }

    /** How far to the right of the road's middle at s the spot (x, z) is (negative: left). */
    static double lateral(List<double[]> pts, double s, double x, double z, int reach) {
        double[] p = at(pts, s), t = tangent(pts, s, reach);
        return (x - p[0]) * -t[1] + (z - p[1]) * t[0];
    }

    /**
     * How far out the near face of the block column (bx, bz) is from the spot (px, pz), measured along the unit direction
     * (nx, nz): the distance to the column's corner nearest to the spot (a rail's block seen from the road's middle).
     */
    static double nearFace(double px, double pz, double nx, double nz, int bx, int bz) {
        return (bx + 0.5 - px) * nx + (bz + 0.5 - pz) * nz - 0.5 * (Math.abs(nx) + Math.abs(nz));
    }

    /** The top of the road's surface at s (blocks): a half level h is h / 2 + 1 (RoadSection#top), between points on the line. */
    static double height(int[] halves, double s) {
        if (halves.length == 0) return 0;
        if (s <= 0) return halves[0] / 2.0 + 1;
        if (s >= halves.length - 1) return halves[halves.length - 1] / 2.0 + 1;
        int i = (int) Math.floor(s);
        double f = s - i;
        return (halves[i] + (halves[i + 1] - halves[i]) * f) / 2.0 + 1;
    }

    /** The block the road's surface is in at point i (RoadSection#surfaceY). */
    static int surfaceY(int[] halves, int i) {
        return Math.floorDiv(halves[Math.clamp(i, 0, halves.length - 1)] + 1, 2);
    }

    // ------------------------------------------------------------------------------------------
    // his pace
    // ------------------------------------------------------------------------------------------

    /** The speed he holds for the road ahead (blocks/s): {@code straight} on a straight, down to {@code bend} where it turns {@code sharp} degrees or more. */
    static double cruise(double turnDegrees, double straight, double bend, double sharp) {
        double k = sharp <= 0 ? 0 : Math.clamp(Math.abs(turnDegrees) / sharp, 0, 1);
        return straight + (bend - straight) * k;
    }

    /**
     * His speed with the player {@code gap} blocks ahead of him along the road (negative: behind). He eases off for
     * someone who lags and picks up a little for someone ahead, and never goes slower than {@code min}: he does not stop.
     */
    static double adapt(double cruise, double gap, double min) {
        double k = 1;
        if (gap < -4) k = Math.max(0.55, 1 + (gap + 4) / 36);
        else if (gap > 4) k = Math.min(1.2, 1 + (gap - 4) / 50);
        return Math.max(min, cruise * k);
    }

    /** A speed going towards its target over dt seconds, gaining at most accel and losing at most brake (blocks/s²). */
    static double step(double speed, double target, double accel, double brake, double dt) {
        if (target > speed) return Math.min(target, speed + accel * dt);
        return Math.max(target, speed - brake * dt);
    }

    /** Where the player is to him: beside him, drifting off (not near), or out (too far ahead or behind). */
    enum Place { BESIDE, FAR, OUT }

    /**
     * @param gap  the player's place along the road minus his (blocks; + ahead)
     * @param dist the straight distance between the two cars
     */
    static Place place(double gap, double dist, double near, double ahead, double behind) {
        if (gap > ahead || gap < -behind || dist > Math.max(ahead, behind) + 8) return Place.OUT;
        return dist > near ? Place.FAR : Place.BESIDE;
    }
}
