package dev.stsv.economy.bus;

import dev.stsv.economy.road.RoadNetwork;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The 국도 of one world as a graph for the 마을버스 (README "마을버스"): every road point a node, the points of a road joined in
 * order, a branch's first point joined to the old road it leaves, level crossings joined. A stop hangs on its nearest road
 * point; the distance between two stops is the shortest way along the roads (what a fare is counted by), and a stop with no
 * way to another is "not connected". Also where stops go by themselves ({@link #autoSpots}). Pure (unit-tested).
 */
public final class BusRoutes {

    /** How far a stop may be from a road point and still hang on it (blocks). */
    public static final double ATTACH = 20;
    /** A branch's first point joins a point of another road this close (blocks). */
    static final double JOIN = 6;

    private final List<RoadNetwork.Road> roads;
    /** the first node of each road */
    private final int[] base;
    private final double[] xs, zs;
    private final int[] half;
    /** which road a node is on */
    private final int[] roadOf;
    /** extra links (branches, crossings): node -> nodes */
    private final Map<Integer, List<Integer>> links = new HashMap<>();
    /** nodes where roads meet (no stop near them) */
    private final List<Integer> junctions = new ArrayList<>();

    public BusRoutes(List<RoadNetwork.Road> roads) {
        this.roads = List.copyOf(roads);
        base = new int[roads.size() + 1];
        for (int r = 0; r < roads.size(); r++) base[r + 1] = base[r] + roads.get(r).points().size();
        int n = base[roads.size()];
        xs = new double[n];
        zs = new double[n];
        half = new int[n];
        roadOf = new int[n];
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int r = 0; r < roads.size(); r++) {
            RoadNetwork.Road road = roads.get(r);
            for (int i = 0; i < road.points().size(); i++) {
                int k = base[r] + i;
                xs[k] = road.points().get(i)[0];
                zs[k] = road.points().get(i)[1];
                half[k] = i < road.halves().length ? road.halves()[i] : (road.halves().length > 0 ? road.halves()[road.halves().length - 1] : 0);
                roadOf[k] = r;
                grid.computeIfAbsent(cell(xs[k], zs[k]), c -> new ArrayList<>()).add(k);
            }
        }
        for (int r = 0; r < roads.size(); r++) {
            int size = roads.get(r).points().size();
            if (size == 0) continue;
            // a branch: its first point on another road (and a road whose end runs into another, the same)
            for (int k : new int[]{base[r], base[r] + size - 1}) {
                int near = nearest(grid, xs[k], zs[k], JOIN, r, Integer.MIN_VALUE);
                if (near >= 0) join(k, near);
            }
            // level crossings: the middle of another road within a block and a half, its surface within a block
            for (int i = 0; i < size; i += 2) {
                int k = base[r] + i;
                int near = nearest(grid, xs[k], zs[k], 1.5, r, half[k]);
                if (near >= 0 && roadOf[near] > r) join(k, near);
            }
        }
    }

    private void join(int a, int b) {
        links.computeIfAbsent(a, x -> new ArrayList<>()).add(b);
        links.computeIfAbsent(b, x -> new ArrayList<>()).add(a);
        junctions.add(a);
        junctions.add(b);
    }

    private static long cell(double x, double z) {
        return ((long) Math.floorDiv((int) Math.floor(x), 8) << 32) ^ (Math.floorDiv((int) Math.floor(z), 8) & 0xffffffffL);
    }

    /** The nearest node within reach not on road `except` (-1: any), at a surface within 2 halves of `level` (MIN: any level). */
    private int nearest(Map<Long, List<Integer>> grid, double x, double z, double reach, int except, int level) {
        int best = -1;
        double bd = reach * reach;
        int cx = Math.floorDiv((int) Math.floor(x), 8), cz = Math.floorDiv((int) Math.floor(z), 8);
        int span = (int) Math.ceil(reach / 8) + 1;
        for (int gx = cx - span; gx <= cx + span; gx++) {
            for (int gz = cz - span; gz <= cz + span; gz++) {
                List<Integer> ks = grid.get(((long) gx << 32) ^ (gz & 0xffffffffL));
                if (ks == null) continue;
                for (int k : ks) {
                    if (roadOf[k] == except || (level != Integer.MIN_VALUE && Math.abs(half[k] - level) > 2)) continue;
                    double d = (xs[k] - x) * (xs[k] - x) + (zs[k] - z) * (zs[k] - z);
                    if (d <= bd) {
                        bd = d;
                        best = k;
                    }
                }
            }
        }
        return best;
    }

    public int nodes() {
        return xs.length;
    }

    /** The road point a stop at (x, z) hangs on, or -1 (no road within {@link #ATTACH}). */
    public int attach(double x, double z) {
        int best = -1;
        double bd = ATTACH * ATTACH;
        for (int k = 0; k < xs.length; k++) {
            double d = (xs[k] - x) * (xs[k] - x) + (zs[k] - z) * (zs[k] - z);
            if (d < bd) {
                bd = d;
                best = k;
            }
        }
        return best;
    }

    /** The shortest way (blocks) from node `from` to each node; +inf where there is none. */
    public double[] distances(int from) {
        double[] d = new double[xs.length];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        if (from < 0 || from >= xs.length) return d;
        d[from] = 0;
        PriorityQueue<double[]> q = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        q.add(new double[]{0, from});
        while (!q.isEmpty()) {
            double[] top = q.poll();
            int k = (int) top[1];
            if (top[0] > d[k]) continue;
            int r = roadOf[k];
            if (k > base[r]) relax(d, q, k, k - 1);
            if (k + 1 < base[r + 1]) relax(d, q, k, k + 1);
            List<Integer> l = links.get(k);
            if (l != null) for (int o : l) relax(d, q, k, o);
        }
        return d;
    }

    /**
     * The shortest ways from one node (버스 내비게이션): every node's road distance and the node before it on its shortest way,
     * so the way to any node can be read back ({@link Tree#path}). The same graph and weights as {@link #distances}.
     */
    public record Tree(int from, double[] dist, int[] prev) {
        /** The nodes from {@code from} to {@code to} in order (empty: no way). */
        public int[] path(int to) {
            if (to < 0 || to >= dist.length || !Double.isFinite(dist[to])) return new int[0];
            int n = 1;
            for (int k = to; k != from && prev[k] >= 0; k = prev[k]) n++;
            int[] out = new int[n];
            int i = n - 1;
            for (int k = to; i >= 0; k = prev[k]) {
                out[i--] = k;
                if (k == from || prev[k] < 0) break;
            }
            return out;
        }
    }

    /** The shortest ways from node `from` to every node ({@link Tree}). */
    public Tree tree(int from) {
        double[] d = new double[xs.length];
        int[] prev = new int[xs.length];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        if (from < 0 || from >= xs.length) return new Tree(from, d, prev);
        d[from] = 0;
        PriorityQueue<double[]> q = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        q.add(new double[]{0, from});
        while (!q.isEmpty()) {
            double[] top = q.poll();
            int k = (int) top[1];
            if (top[0] > d[k]) continue;
            int r = roadOf[k];
            if (k > base[r]) step(d, prev, q, k, k - 1);
            if (k + 1 < base[r + 1]) step(d, prev, q, k, k + 1);
            List<Integer> l = links.get(k);
            if (l != null) for (int o : l) step(d, prev, q, k, o);
        }
        return new Tree(from, d, prev);
    }

    private void step(double[] d, int[] prev, PriorityQueue<double[]> q, int a, int b) {
        double w = d[a] + Math.hypot(xs[a] - xs[b], zs[a] - zs[b]);
        if (w < d[b]) {
            d[b] = w;
            prev[b] = a;
            q.add(new double[]{w, b});
        }
    }

    /** A node's road surface (half blocks). */
    public int half(int node) {
        return half[node];
    }

    private void relax(double[] d, PriorityQueue<double[]> q, int a, int b) {
        double w = d[a] + Math.hypot(xs[a] - xs[b], zs[a] - zs[b]);
        if (w < d[b]) {
            d[b] = w;
            q.add(new double[]{w, b});
        }
    }

    /**
     * The road distance between every two of these stops (by their nodes from {@link #attach}; -1 = no road): [i][j] in
     * blocks, +inf where there is no way.
     */
    public double[][] matrix(int[] stopNodes) {
        int n = stopNodes.length;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            if (stopNodes[i] < 0) {
                Arrays.fill(m[i], Double.POSITIVE_INFINITY);
                m[i][i] = 0;
                continue;
            }
            double[] d = distances(stopNodes[i]);
            for (int j = 0; j < n; j++) m[i][j] = stopNodes[j] < 0 ? (i == j ? 0 : Double.POSITIVE_INFINITY) : d[stopNodes[j]];
        }
        return m;
    }

    /** A node's place: x, z, its road's surface (half blocks), and which way the road runs there (unit x, z, terminal to dock). */
    public double[] place(int node) {
        int r = roadOf[node];
        // the way over 8 points either side: a stop's bay is set straight along the road, not along a pixel's step
        int a = Math.max(base[r], node - 8), b = Math.min(base[r + 1] - 1, node + 8);
        double hx = xs[b] - xs[a], hz = zs[b] - zs[a], len = Math.max(1e-6, Math.hypot(hx, hz));
        return new double[]{xs[node], zs[node], half[node], hx / len, hz / len};
    }

    /** The dock of the road a node is on. */
    public String dock(int node) {
        return roads.get(roadOf[node]).dock();
    }

    /** The node this many points further along the same road, or -1 (off its ends). */
    public int shift(int node, int points) {
        int r = roadOf[node], k = node + points;
        return k < base[r] || k >= base[r + 1] ? -1 : k;
    }

    /** The node of a road's point. */
    public int node(int road, int point) {
        return base[road] + point;
    }

    // ------------------------------------------------------------------------------------------
    // 노선: one a road, from the terminal (through the roads it branches off) to its dock
    // ------------------------------------------------------------------------------------------

    /** A line's way: its road's dock and the nodes from the terminal to the dock, in order. */
    public record Line(int road, String dock, int[] path) {}

    /**
     * Every road's line: the way from where the network starts to the road's end (its dock). A branch's way is its parent
     * road's from the start up to where the branch leaves it, then the branch.
     */
    public List<Line> lines() {
        List<Line> out = new ArrayList<>();
        for (int r = 0; r < roads.size(); r++) {
            if (base[r + 1] == base[r]) continue;
            List<int[]> segs = new ArrayList<>();   // road, from, to (inclusive)
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            int road = r, to = base[r + 1] - 1;
            while (road >= 0 && seen.add(road)) {
                segs.addFirst(new int[]{road, base[road], to});
                int parent = -1, at = -1;
                List<Integer> l = links.get(base[road]);
                if (l != null) for (int q : l) {
                    int pr = roadOf[q];
                    if (pr != road && q != base[pr] && !seen.contains(pr)) {   // joins another road along it (not at both starts)
                        parent = pr;
                        at = q;
                        break;
                    }
                }
                road = parent;
                to = at;
            }
            int n = 0;
            for (int[] g : segs) n += g[2] - g[1] + 1;
            int[] path = new int[n];
            int i = 0;
            for (int[] g : segs) for (int k = g[1]; k <= g[2]; k++) path[i++] = k;
            out.add(new Line(r, roads.get(r).dock(), path));
        }
        return out;
    }

    /** Where a node is: x, z. */
    public double[] xz(int node) {
        return new double[]{xs[node], zs[node]};
    }

    /** Whether roads meet at this node (a branch leaves, a crossing). */
    public boolean junction(int node) {
        return links.containsKey(node);
    }

    // ------------------------------------------------------------------------------------------
    // Where stops go by themselves
    // ------------------------------------------------------------------------------------------

    public enum Kind {START, MIDDLE, END}

    /** A place for a stop: its node, which road and dock, what kind of place. */
    public record Spot(int node, int road, String dock, Kind kind) {}

    /**
     * Where stops go along the roads (README "마을버스", 자동 정류장): after a road's start (a road that leaves a terminal, not a
     * branch), {@code endMin..endMax} points before its end (the dock's gate), and every {@code every} points between; never
     * within {@code junctionClear} points of a junction, nor within {@code apart} blocks of another stop (`taken`: x, z of the
     * stops there are) or of a spot already chosen, and only where the road is level along the bay (`bay` points). A spot
     * that breaks a rule moves along the road (up to 100 points either way) before it is given up.
     */
    public List<Spot> autoSpots(BusSpec.Auto a, int bay, List<double[]> taken) {
        List<Spot> out = new ArrayList<>();
        List<double[]> used = new ArrayList<>(taken);
        for (int r = 0; r < roads.size(); r++) {
            int size = roads.get(r).points().size();
            if (size < a.startSkip() + a.endMax() + bay) continue;
            boolean branch = links.containsKey(base[r]);
            List<int[]> wants = new ArrayList<>();   // point, kind
            if (!branch) wants.add(new int[]{a.startSkip() + bay / 2, Kind.START.ordinal()});
            int end = size - 1 - (a.endMin() + a.endMax()) / 2;
            int from = branch ? a.junctionClear() : a.startSkip() + bay / 2;
            for (int p = from + a.every(); p < end - a.every() / 2; p += a.every()) wants.add(new int[]{p, Kind.MIDDLE.ordinal()});
            wants.add(new int[]{end, Kind.END.ordinal()});
            for (int[] w : wants) {
                int at = settle(r, w[0], w[1] == Kind.END.ordinal() ? Math.max(0, size - 1 - a.endMax()) : 0,
                        w[1] == Kind.END.ordinal() ? size - 1 - a.endMin() : size - 1, a, bay, used);
                if (at < 0) continue;
                int node = base[r] + at;
                out.add(new Spot(node, r, roads.get(r).dock(), Kind.values()[w[1]]));
                used.add(new double[]{xs[node], zs[node]});
            }
        }
        return out;
    }

    private int settle(int r, int want, int lo, int hi, BusSpec.Auto a, int bay, List<double[]> used) {
        for (int step = 0; step <= 100; step += 5) {
            for (int sign : new int[]{1, -1}) {
                int p = want + sign * step;
                if (p < Math.max(lo, bay / 2) || p > Math.min(hi, roads.get(r).points().size() - 1 - bay / 2)) continue;
                if (fits(r, p, a, bay, used)) return p;
                if (step == 0) break;
            }
        }
        return -1;
    }

    private boolean fits(int r, int p, BusSpec.Auto a, int bay, List<double[]> used) {
        int k = base[r] + p;
        for (int j : junctions) {
            if (roadOf[j] == r && Math.abs(j - k) < a.junctionClear()) return false;
            if (roadOf[j] != r && Math.hypot(xs[j] - xs[k], zs[j] - zs[k]) < a.junctionClear()) return false;
        }
        for (double[] u : used) if (Math.hypot(u[0] - xs[k], u[1] - zs[k]) < a.apart()) return false;
        int lo = half[k], hi = half[k];
        for (int i = k - bay / 2; i <= k + bay / 2; i++) {
            lo = Math.min(lo, half[i]);
            hi = Math.max(hi, half[i]);
        }
        if (hi - lo > 2) return false;   // level along the bay (a block at most)
        return straight(k, bay / 2 + 4) < STRAIGHT;   // and straight: a bay on a bend leaves the road
    }

    /** How far the road turns across a bay (degrees). */
    static final double STRAIGHT = 12;

    /** The angle (degrees) between the road's way over the `reach` points before node k and over those after it. */
    double straight(int k, int reach) {
        int r = roadOf[k];
        int a = Math.max(base[r], k - reach), b = Math.min(base[r + 1] - 1, k + reach);
        double ax = xs[k] - xs[a], az = zs[k] - zs[a], bx = xs[b] - xs[k], bz = zs[b] - zs[k];
        double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
        if (la < 1e-6 || lb < 1e-6) return 0;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ax * bx + az * bz) / (la * lb)))));
    }
}
