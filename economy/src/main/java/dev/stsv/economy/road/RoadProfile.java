package dev.stsv.economy.road;

/**
 * 국도: how high the road runs along its points (one a block) and what each stretch is (README "국도"). Pure (unit-tested).
 *
 * <ol>
 *   <li>The target is the ground (over water: one above the surface, a bridge deck), smoothed over a few blocks so single
 *       bumps do not move the road.</li>
 *   <li>{@link #fit}: the profile nearest the target that changes at most 1 in 3 per block (gentle grades: cut where the
 *       ground is higher, fill where it is lower, a bridge or a tunnel where that would be deep), the least work over the
 *       whole road.</li>
 *   <li>{@link #levels}: rounded to blocks. A grade of 1 in 3 rounds to a one-block step at most every three blocks.</li>
 *   <li>{@link #parts}: a bridge where it crosses water or runs more than {@code bridgeGap} over the ground (a ravine), a
 *       tunnel where the ground stands {@code tunnelCover} or more over it (a steep hill), else road on cut and fill. Short
 *       bridges and tunnels (shorter than minRun) become road (filled, or cut open); water stays a bridge.</li>
 * </ol>
 */
public final class RoadProfile {

    public enum Part { ROAD, BRIDGE, TUNNEL }

    /** One block up or down in this many along. */
    public static final double MAX_GRADE = 1 / RoadPlanner.GRADE_RUN;

    private RoadProfile() {}

    /** A moving average over 2 * half + 1 points (the ends use what they have). */
    public static double[] smooth(double[] target, int half) {
        double[] out = new double[target.length];
        for (int i = 0; i < target.length; i++) {
            double s = 0;
            int n = 0;
            for (int k = Math.max(0, i - half); k <= Math.min(target.length - 1, i + half); k++) {
                s += target[k];
                n++;
            }
            out[i] = s / n;
        }
        return out;
    }

    /** The target clamped to change at most maxStep a point: forward, then backward (which makes it hold everywhere). */
    public static double[] grade(double[] target, double maxStep) {
        int n = target.length;
        double[] p = target.clone();
        for (int i = 1; i < n; i++) p[i] = Math.clamp(p[i], p[i - 1] - maxStep, p[i - 1] + maxStep);
        for (int i = n - 2; i >= 0; i--) p[i] = Math.clamp(p[i], p[i + 1] - maxStep, p[i + 1] + maxStep);
        return p;
    }

    /**
     * Whole blocks (the road's surface block y), with a block of hysteresis: the level moves only once the profile is a
     * whole block away from it, so a profile hovering round a half never flickers up and down, and with the grade (1 in 3)
     * two steps are always at least three blocks apart. The level stays within a block of the profile.
     */
    public static int[] levels(double[] p) {
        int[] out = new int[p.length];
        if (p.length == 0) return out;
        out[0] = (int) Math.floor(p[0] + 0.5);
        for (int i = 1; i < p.length; i++) {
            int l = out[i - 1];
            if (p[i] >= l + 1 - 1e-9) l++;
            else if (p[i] <= l - 1 + 1e-9) l--;
            out[i] = l;
        }
        return out;
    }

    /**
     * The road's surface y at each point: ground[i] is the ground's top block y, water[i] whether the point is over water
     * (then surface[i] is the water's top block y). The ends stay at their ground (start and the dock's gate).
     */
    public static int[] plan(int[] ground, boolean[] water, int[] surface) {
        int n = ground.length;
        double[] target = new double[n];
        for (int i = 0; i < n; i++) target[i] = water[i] ? surface[i] + 2 : ground[i];
        double[] t = smooth(target, 4);
        if (n > 0) {
            t[0] = target[0];
            t[n - 1] = target[n - 1];
        }
        return levels(fit(t, water, MAX_GRADE));
    }

    /** What being h above the target costs a point: fill (per block), then bridge; cut, then tunnel (cheaper a block). */
    static double cost(double h) {
        if (h >= 0) return h <= 3 ? h : 3 + (h - 3) * 0.3;
        double d = -h;
        return Math.min(d, 5);
    }

    /**
     * The profile nearest the target that never changes more than maxStep a point: the least cut, fill, bridge and tunnel
     * ({@link #cost}) over the whole road, found by trying every height (in steps of maxStep) at every point. Never under
     * the target over water (the deck); the ends held at their targets. Unlike {@link #grade} it looks both ways: over a
     * hill it rises and falls symmetrically (or tunnels level) instead of climbing as long as the ground does and coming
     * down on a long bridge after it.
     */
    public static double[] fit(double[] t, boolean[] water, double maxStep) {
        int n = t.length;
        if (n == 0) return new double[0];
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : t) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double base = Math.floor(min) - 1;
        int m = (int) Math.ceil((Math.ceil(max) + 1 - base) / maxStep) + 1;
        double[] cost = new double[m], next = new double[m];
        byte[][] from = new byte[n][m];
        final double END = 1000, MOVE = 0.01, INF = 1e18;
        for (int k = 0; k < m; k++) cost[k] = END * Math.abs(base + k * maxStep - t[0]);
        for (int i = 1; i < n; i++) {
            for (int k = 0; k < m; k++) {
                double best = cost[k];
                byte d = 0;
                if (k > 0 && cost[k - 1] + MOVE < best) {
                    best = cost[k - 1] + MOVE;
                    d = -1;
                }
                if (k < m - 1 && cost[k + 1] + MOVE < best) {
                    best = cost[k + 1] + MOVE;
                    d = 1;
                }
                double h = base + k * maxStep - t[i];
                double c = water[i] && h < -1e-9 ? INF : cost(h);
                if (i == n - 1) c += END * Math.abs(h);
                next[k] = best + c;
                from[i][k] = d;
            }
            double[] s = cost;
            cost = next;
            next = s;
        }
        int k = 0;
        for (int j = 1; j < m; j++) if (cost[j] < cost[k]) k = j;
        double[] out = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            out[i] = base + k * maxStep;
            if (i > 0) k += from[i][k];
        }
        return out;
    }

    /** What each point is (see the class doc). */
    public static Part[] parts(int[] road, int[] ground, boolean[] water, int bridgeGap, int tunnelCover, int minRun) {
        int n = road.length;
        Part[] out = new Part[n];
        for (int i = 0; i < n; i++) {
            if (water[i] || ground[i] < road[i] - bridgeGap) out[i] = Part.BRIDGE;
            else if (ground[i] >= road[i] + tunnelCover) out[i] = Part.TUNNEL;
            else out[i] = Part.ROAD;
        }
        // short bridges (over dry ground) and short tunnels are not worth it: fill, or cut open
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && out[j + 1] == out[i]) j++;
            if (out[i] != Part.ROAD && j - i + 1 < minRun) {
                boolean wet = false;
                for (int k = i; k <= j; k++) wet |= water[k];
                if (!wet) for (int k = i; k <= j; k++) out[k] = Part.ROAD;
            }
            i = j + 1;
        }
        return out;
    }

    /** The largest change between neighbours (for tests and the report). */
    public static double steepest(double[] p) {
        double m = 0;
        for (int i = 1; i < p.length; i++) m = Math.max(m, Math.abs(p[i] - p[i - 1]));
        return m;
    }

    /** The fewest points between two one-block steps of rounded levels (Integer.MAX_VALUE: none or one step). */
    public static int closestSteps(int[] levels) {
        int last = -1, best = Integer.MAX_VALUE;
        for (int i = 1; i < levels.length; i++) {
            if (levels[i] == levels[i - 1]) continue;
            if (last >= 0) best = Math.min(best, i - last);
            last = i;
        }
        return best;
    }
}
