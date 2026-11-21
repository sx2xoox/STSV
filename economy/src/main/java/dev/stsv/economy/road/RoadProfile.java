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
 *   <li>{@link #levels}: rounded to blocks. A grade of 1 in 3 rounds to a one-block step at most every three blocks.
 *       {@link #halves}: rounded to half blocks instead (what is built: {@link #planHalves}), so the road climbs half a
 *       block (a slab) at a time, never two half steps on neighbouring points: still 1 in 3 at most, and a vehicle glides
 *       up and down it.</li>
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

    /**
     * A grade in half blocks (README "국도", 성격): never more than {@code halves} half steps in {@code window} points, nor
     * (for every shorter stretch j) more than its share rounded up, and never more than one between neighbours. Normal
     * (1 in 3): 2 in 3. Easy (1 in 5): 2 in 5 (half steps at least 2 apart). Hard (1 in 2.5): 4 in 5.
     */
    public record Grade(int window, int halves) {
        public static final Grade NORMAL = new Grade(3, 2);

        /** One block in `run` points, in half steps: the smallest window that holds whole half steps. */
        public static Grade of(double run) {
            for (int k = 2; k <= 8; k += 2) {
                double w = k * run / 2;
                if (Math.abs(w - Math.rint(w)) < 1e-6) return new Grade((int) Math.rint(w), k);
            }
            return new Grade((int) Math.round(run), 2);
        }

        /** Blocks a point at most (what {@link #fit} steps by). */
        public double step() {
            return halves / (2.0 * window);
        }

        /** Half steps at most over j points (1 .. window). */
        int limit(int j) {
            return Math.max(1, (int) Math.ceil(halves * (double) j / window - 1e-9));
        }

        /** Whether the stretch of j points is a rule of its own (tighter than its neighbours' rule chained): j = 1 always. */
        boolean binding(int j) {
            return j == 1 || limit(j) < j;
        }
    }

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
     * Half blocks (h: a full surface block at y = h / 2 when h is even, a bottom slab at y = (h + 1) / 2 on a full block
     * when odd; the surface's top at h / 2 + 1), with the same hysteresis as {@link #levels} by half a block, and
     * never more than a block (two half steps) in three points: the grade of 1 in 3.
     */
    public static int[] halves(double[] p) {
        return halves(p, Grade.NORMAL);
    }

    /** {@link #halves(double[])} at a grade of its own. */
    public static int[] halves(double[] p, Grade g) {
        int[] out = new int[p.length];
        if (p.length == 0) return out;
        out[0] = (int) Math.floor(p[0] * 2 + 0.5);
        for (int i = 1; i < p.length; i++) {
            int l = out[i - 1];
            double q = p[i] * 2;
            if (q >= l + 1 - 1e-9) l++;
            else if (q <= l - 1 + 1e-9) l--;
            // never more than a block (two half steps) in three points: the grade holds whatever the rounding did
            for (int j = 2; j <= g.window(); j++) if (g.binding(j) && i >= j && Math.abs(l - out[i - j]) > g.limit(j)) l = out[i - 1];
            out[i] = l;
        }
        return out;
    }

    /** The full block under a half-block level (the slab's support, or the surface block itself). */
    public static int block(int half) {
        return Math.floorDiv(half, 2);
    }

    /** The full blocks under half-block levels ({@link #parts} and the like work on these). */
    public static int[] blocks(int[] halves) {
        int[] out = new int[halves.length];
        for (int i = 0; i < halves.length; i++) out[i] = block(halves[i]);
        return out;
    }

    /** The road's surface at each point in half blocks ({@link #halves}); the ends at their ground. */
    public static int[] planHalves(int[] ground, boolean[] water, int[] surface) {
        return halves(fit(target(ground, water, surface, FOLLOW), water, MAX_GRADE));
    }

    /** How many points either way the ground is smoothed over before the road is fitted to it (normal). */
    public static final int FOLLOW = 4;

    private static double[] target(int[] ground, boolean[] water, int[] surface, int follow) {
        int n = ground.length;
        double[] target = new double[n];
        for (int i = 0; i < n; i++) target[i] = water[i] ? surface[i] + 2 : ground[i];
        double[] t = smooth(target, follow);
        if (n > 0) {
            t[0] = target[0];
            t[n - 1] = target[n - 1];
        }
        return t;
    }

    /**
     * The road's surface y at each point: ground[i] is the ground's top block y, water[i] whether the point is over water
     * (then surface[i] is the water's top block y). The ends stay at their ground (start and the dock's gate).
     */
    public static int[] plan(int[] ground, boolean[] water, int[] surface) {
        return levels(fit(target(ground, water, surface, FOLLOW), water, MAX_GRADE));
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
        return fit(t, water, maxStep, null, null);
    }

    /** {@link #fit}, never under lo[i] nor over hi[i] at point i (null: no bound; NaN: none there). */
    public static double[] fit(double[] t, boolean[] water, double maxStep, double[] lo, double[] hi) {
        int n = t.length;
        if (n == 0) return new double[0];
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            min = Math.min(min, t[i]);
            max = Math.max(max, t[i]);
            if (lo != null && !Double.isNaN(lo[i])) max = Math.max(max, lo[i]);
            if (hi != null && !Double.isNaN(hi[i])) min = Math.min(min, hi[i]);
        }
        double base = Math.floor(min) - 1;
        int m = (int) Math.ceil((Math.ceil(max) + 1 - base) / maxStep) + 1;
        double[] cost = new double[m], next = new double[m];
        byte[][] from = new byte[n][m];
        final double END = 1000, MOVE = 0.01, INF = 1e18;
        for (int k = 0; k < m; k++) cost[k] = END * Math.abs(base + k * maxStep - t[0]) + (outside(base + k * maxStep, lo, hi, 0) ? INF : 0);
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
                double c = (water[i] && h < -1e-9) || outside(base + k * maxStep, lo, hi, i) ? INF : cost(h);
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

    private static boolean outside(double v, double[] lo, double[] hi, int i) {
        return (lo != null && !Double.isNaN(lo[i]) && v < lo[i] - 1e-9) || (hi != null && !Double.isNaN(hi[i]) && v > hi[i] + 1e-9);
    }

    /**
     * A road among others ({@link RoadNetwork}): its surface in half blocks, and which points go over an old road.
     * cross[i]: the old road's surface (half blocks) where this road's middle runs over its lanes, else MIN_VALUE.
     */
    public record Plan(int[] halves, boolean[] over) {}

    /** How far (blocks) from an old road's level the free profile may be at a crossing and still meet it level (a junction). */
    public static final double MEET = 2.0;
    /** Points either side of a crossing that go over with it (the new road's own width over the old one's). */
    public static final int OVER_REACH = 7;

    /**
     * The profile as {@link #planHalves}, meeting each old road it crosses: level with it where the free profile is
     * within {@link #MEET} of it there (a junction: never cut into or filled over), else at least
     * {@link RoadNetwork#CLEARANCE} over its surface across it and {@link #OVER_REACH} points either side (an overpass).
     */
    public static Plan planNetwork(int[] ground, boolean[] water, int[] surface, int[] cross) {
        return planNetwork(ground, water, surface, cross, null);
    }

    /**
     * {@link #planNetwork(int[], boolean[], int[], int[])}, an overpass's deck over the highest surface of the old road
     * near the crossing (high[i]: {@link RoadNetwork#headroom}; null: only where it crosses).
     */
    public static Plan planNetwork(int[] ground, boolean[] water, int[] surface, int[] cross, int[] high) {
        return planNetwork(ground, water, surface, cross, high, Grade.NORMAL, FOLLOW);
    }

    /** {@link #planNetwork(int[], boolean[], int[], int[], int[])} at a grade of its own, the ground smoothed over `follow` points. */
    public static Plan planNetwork(int[] ground, boolean[] water, int[] surface, int[] cross, int[] high, Grade g, int follow) {
        int n = ground.length;
        double[] t = target(ground, water, surface, follow);
        double[] free = fit(t, water, g.step());
        double[] lo = new double[n], hi = new double[n];
        java.util.Arrays.fill(lo, Double.NaN);
        java.util.Arrays.fill(hi, Double.NaN);
        boolean[] over = new boolean[n], level = new boolean[n];
        int i = 0;
        while (i < n) {
            if (cross[i] == Integer.MIN_VALUE) {
                i++;
                continue;
            }
            int j = i;
            double far = 0;
            int top = cross[i];
            while (j + 1 < n && cross[j + 1] != Integer.MIN_VALUE) j++;
            for (int k = i; k <= j; k++) {
                far = Math.max(far, Math.abs(free[k] - cross[k] / 2.0));
                top = Math.max(top, high != null && high[k] != Integer.MIN_VALUE ? Math.max(cross[k], high[k]) : cross[k]);
            }
            if (far <= MEET || i == 0) {   // level (the start of a branch is always on its trunk)
                for (int k = i; k <= j; k++) {
                    lo[k] = cross[k] / 2.0 - 0.34;
                    hi[k] = cross[k] / 2.0 + 0.34;
                    level[k] = true;
                }
            } else {
                double deck = RoadSection.surfaceY(top) + RoadNetwork.CLEARANCE;
                for (int k = Math.max(1, i - OVER_REACH); k <= Math.min(n - 2, j + OVER_REACH); k++) {
                    lo[k] = Double.isNaN(lo[k]) ? deck : Math.max(lo[k], deck);
                    hi[k] = Double.NaN;
                    over[k] = true;
                }
            }
            i = j + 1;
        }
        int[] h = halves(fit(t, water, g.step(), lo, hi), g);
        boolean[] fixed = new boolean[n];
        for (int k = 0; k < n; k++) {
            if (level[k] && !over[k]) h[k] = cross[k];
            if (over[k]) h[k] = Math.max(h[k], (int) Math.ceil(lo[k] * 2));
            fixed[k] = level[k] || over[k];
        }
        relax(h, fixed, g);
        return new Plan(h, over);
    }

    /** A corner turning this many degrees or more is built level ({@link #levelBends}). */
    public static final double LEVEL_FROM = 30;
    /** Points either way of a corner held level at most. */
    public static final int LEVEL_MAX = 12;
    /** Half blocks either way of a corner's level a road not at the normal grade also tries to hold it at. */
    static final int LEVEL_SHIFT = 8;

    /**
     * Sharp corners made level (README "국도", 굽은 길): the road turns at a corner point, so the columns on a corner's inside
     * belong to points on both legs (each column takes its nearest point's height); on a grade those are far apart along
     * the road and the inside of the corner was a crease of full-block steps (1.5 high round a right angle at 1 in 3: a bus
     * cutting the corner hit it as a wall). Within as many points of a corner as its inside reaches (the shoulder's width
     * × tan(half the turn), plus one) the road is held at the corner's level, the grade pulled in round it ({@link #relax});
     * a level crossing or an overpass near it stays as it is (then that corner is not levelled). Changes h in place.
     */
    public static void levelBends(java.util.List<double[]> points, int[] h, boolean[] fixed) {
        levelBends(points, h, fixed, Grade.NORMAL);
    }

    /** {@link #levelBends(java.util.List, int[], boolean[])} at a grade of its own. */
    public static void levelBends(java.util.List<double[]> points, int[] h, boolean[] fixed, Grade g) {
        int n = h.length;
        boolean[] fix = fixed.clone();
        if (n > 0) {
            fix[0] = true;
            fix[n - 1] = true;
        }
        for (int v = 1; v + 1 < n && v + 1 < points.size(); v++) {
            double[] a = points.get(v - 1), p = points.get(v), b = points.get(v + 1);
            double ax = p[0] - a[0], az = p[1] - a[1], bx = b[0] - p[0], bz = b[1] - p[1];
            double turn = Math.toDegrees(Math.atan2(Math.abs(ax * bz - az * bx), ax * bx + az * bz));
            if (turn < LEVEL_FROM) continue;
            int want = Math.min(LEVEL_MAX, (int) Math.ceil((RoadSection.HALF + 0.5) * Math.tan(Math.toRadians(turn / 2))) + 1);
            // as wide as the grade lets it (on a long climb at 1 in 3 there is no room to hold it level: then less, or none)
            // (a road of another grade also tries levels a little off the corner's: on a steep climb between two bends
            // close together the corner's own level may be out of reach of the one levelled before it)
            int tries = g.equals(Grade.NORMAL) ? 0 : LEVEL_SHIFT;
            levelled:
            for (int w = want; w >= 2; w--) {
                int from = Math.max(1, v - w), to = Math.min(n - 2, v + w);
                boolean free = true;
                for (int k = from; k <= to && free; k++) free = !fix[k];
                if (!free) continue;
                for (int d = 0; d <= 2 * tries; d++) {
                    int level = h[v] + (d % 2 == 1 ? -(d + 1) / 2 : d / 2);
                    int[] t = h.clone();
                    boolean[] f = fix.clone();
                    for (int k = from; k <= to; k++) {
                        t[k] = level;
                        f[k] = true;
                    }
                    relax(t, f, g);
                    if (!graded(t, g)) continue;
                    System.arraycopy(t, 0, h, 0, n);
                    System.arraycopy(f, 0, fix, 0, n);
                    break levelled;
                }
            }
        }
    }

    /** Whether half levels keep the grade: never more than half a block between neighbours, nor a block in three points. */
    static boolean graded(int[] h) {
        return graded(h, Grade.NORMAL);
    }

    /** Whether half levels keep a grade ({@link Grade}). */
    static boolean graded(int[] h, Grade g) {
        for (int k = 1; k < h.length; k++)
            for (int j = 1; j <= g.window() && j <= k; j++) if (g.binding(j) && Math.abs(h[k] - h[k - j]) > g.limit(j)) return false;
        return true;
    }

    /**
     * Half levels pulled into the grade round the fixed ones (a junction's, an overpass's): never more than half a block
     * between neighbours, nor a block in three points; the fixed stay as they are.
     */
    static void relax(int[] h, boolean[] fixed) {
        relax(h, fixed, Grade.NORMAL);
    }

    /** {@link #relax(int[], boolean[])} to a grade of its own. */
    static void relax(int[] h, boolean[] fixed, Grade g) {
        int n = h.length;
        for (int round = 0; round < 8; round++) {
            boolean changed = false;
            for (int k = 1; k < n; k++) changed |= clamp(h, fixed, k, -1, g);
            for (int k = n - 2; k >= 0; k--) changed |= clamp(h, fixed, k, 1, g);
            if (!changed) return;
        }
    }

    private static boolean clamp(int[] h, boolean[] fixed, int k, int dir, Grade g) {
        if (fixed[k]) return false;
        int v = h[k];
        for (int j = 1; j <= g.window(); j++) {
            int o = k + dir * j;
            if (!g.binding(j) || o < 0 || o >= h.length) continue;
            v = Math.clamp(v, h[o] - g.limit(j), h[o] + g.limit(j));
        }
        if (v == h[k]) return false;
        h[k] = v;
        return true;
    }

    /** What each point is, for a road in half blocks ({@link #halves}): the block its surface is in counts. */
    public static Part[] partsHalves(int[] halves, int[] ground, boolean[] water, int bridgeGap, int tunnelCover, int minRun) {
        int[] top = new int[halves.length];
        for (int i = 0; i < halves.length; i++) top[i] = Math.floorDiv(halves[i] + 1, 2);
        return parts(top, ground, water, bridgeGap, tunnelCover, minRun);
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
