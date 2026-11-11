package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 국도 routing: a coarse A* over chunk-sized cells of a surveyed corridor (heightmaps), then the path pulled straight where
 * the straight line costs no more (README "국도"). Pure (unit-tested).
 *
 * <p>A step's cost is its length (1 across, √2 diagonally) plus: a little for every block of height it changes and much
 * more for what goes past the road's grade (1 in 3: the cut, fill or tunnel it will take), the cell's own roughness, and
 * for a cell of water a crossing price plus its depth (bridges are long and dear). A cell of a player's land is never
 * entered, nor cut at a corner (a diagonal step needs both cells beside it free): the road goes round claims. A step
 * along an existing road (both cells on one: {@link Field#road}) costs a fifth, one beside it more: a new road follows the
 * network from the origin and branches off it ({@link RoadNetwork#branch}) instead of running alongside.
 */
public final class RoadPlanner {

    private RoadPlanner() {}

    /** The surveyed ground, by cell (16 x 16 blocks: a chunk). */
    public interface Field {
        /** Surveyed (inside the corridor)? Cells outside are never entered. */
        boolean known(int cx, int cz);

        /** Ground height (blocks) in its middle. */
        double height(int cx, int cz);

        /** Water depth over the ground there (0 = dry land). */
        double water(int cx, int cz);

        /** How uneven it is inside (highest - lowest of its samples). */
        double rough(int cx, int cz);

        /** A player's land: never entered. */
        boolean blocked(int cx, int cz);

        /** An existing road runs through it (another dock's 국도: {@link RoadNetwork}): following it costs little. */
        default boolean road(int cx, int cz) {
            return false;
        }

        /** Next to such a cell but not on it: running alongside an existing road costs more (merge onto it instead). */
        default boolean nearRoad(int cx, int cz) {
            return false;
        }
    }

    /** How much each thing costs per cell step (see the class doc). */
    public record Weights(double climb, double steep, double rough, double water, double depth, double reuse, double beside) {
        public static final Weights DEFAULT = new Weights(0.02, 0.6, 0.03, 6, 0.4, 0.2, 0.6);

        public Weights(double climb, double steep, double rough, double water, double depth) {
            this(climb, steep, rough, water, depth, 1, 0);
        }
    }

    /** The road's grade: one block up or down in this many along. */
    public static final double GRADE_RUN = 3;

    static double stepCost(Field f, int ax, int az, int bx, int bz, Weights w) {
        double len = ax != bx && az != bz ? Math.sqrt(2) : 1;
        double dh = Math.abs(f.height(bx, bz) - f.height(ax, az));
        double allowed = len * 16 / GRADE_RUN;
        double c = len;
        c += w.climb() * dh;
        c += w.steep() * Math.max(0, dh - allowed);
        c += w.rough() * f.rough(bx, bz) * len;
        double water = f.water(bx, bz);
        if (water > 0) c += len * (w.water() + w.depth() * water);
        // along an existing road: it is there already (nothing is built: the new road branches off it later)
        if (f.road(ax, az) && f.road(bx, bz)) return c * w.reuse();
        if (f.nearRoad(bx, bz) && !f.road(bx, bz)) c += w.beside() * len;
        return c;
    }

    private static boolean open(Field f, int x, int z) {
        return f.known(x, z) && !f.blocked(x, z);
    }

    private static boolean canStep(Field f, int ax, int az, int bx, int bz) {
        if (!open(f, bx, bz)) return false;
        if (ax != bx && az != bz) return open(f, ax, bz) && open(f, bx, az);   // no corner cutting past a claim
        return true;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * The cheapest way from (sx, sz) to (ex, ez) over known, unblocked cells: the cells in order, both ends included; empty
     * when there is none (or more than maxNodes cells would be looked at).
     */
    public static List<int[]> path(Field f, int sx, int sz, int ex, int ez, Weights w, int maxNodes) {
        if (!open(f, sx, sz) || !open(f, ex, ez)) return List.of();
        record Node(int x, int z, double g, double f) {}
        PriorityQueue<Node> queue = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        queue.add(new Node(sx, sz, 0, Math.hypot(ex - sx, ez - sz)));
        best.put(key(sx, sz), 0.0);
        int seen = 0;
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n.g() > best.getOrDefault(key(n.x(), n.z()), Double.POSITIVE_INFINITY) + 1e-9) continue;
            if (n.x() == ex && n.z() == ez) {
                List<int[]> out = new ArrayList<>();
                long k = key(ex, ez);
                out.add(new int[]{ex, ez});
                while (from.containsKey(k)) {
                    k = from.get(k);
                    out.add(new int[]{(int) (k >> 32), (int) k});
                }
                Collections.reverse(out);
                return out;
            }
            if (++seen > maxNodes) return List.of();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = n.x() + dx, nz = n.z() + dz;
                    if (!canStep(f, n.x(), n.z(), nx, nz)) continue;
                    double g = n.g() + stepCost(f, n.x(), n.z(), nx, nz, w);
                    long k = key(nx, nz);
                    if (g + 1e-9 < best.getOrDefault(k, Double.POSITIVE_INFINITY)) {
                        best.put(k, g);
                        from.put(k, key(n.x(), n.z()));
                        queue.add(new Node(nx, nz, g, g + Math.hypot(ex - nx, ez - nz)));
                    }
                }
            }
        }
        return List.of();
    }

    /** What a path of cells costs, step by step. */
    public static double cost(Field f, List<int[]> cells, Weights w) {
        double c = 0;
        for (int i = 1; i < cells.size(); i++) c += stepCost(f, cells.get(i - 1)[0], cells.get(i - 1)[1], cells.get(i)[0], cells.get(i)[1], w);
        return c;
    }

    /**
     * The cells a straight line from cell a to cell b crosses (both ends included), in order, touching each other by a
     * side (a corner crossing takes both cells beside it): every cell the road's width may touch.
     */
    public static List<int[]> line(int ax, int az, int bx, int bz) {
        List<int[]> out = new ArrayList<>();
        int x = ax, z = az;
        int dx = Math.abs(bx - ax), dz = Math.abs(bz - az), sx = Integer.signum(bx - ax), sz = Integer.signum(bz - az);
        out.add(new int[]{x, z});
        int nx = 0, nz = 0;
        while (nx < dx || nz < dz) {
            // compare (0.5 + nx) / dx with (0.5 + nz) / dz
            long l = (1 + 2L * nx) * dz, r = (1 + 2L * nz) * dx;
            if (dz == 0 || (dx != 0 && l < r)) {
                x += sx;
                nx++;
            } else if (dx == 0 || l > r) {
                z += sz;
                nz++;
            } else {   // exactly through a corner: both cells beside it, then across
                out.add(new int[]{x + sx, z});
                out.add(new int[]{x, z + sz});
                x += sx;
                z += sz;
                nx++;
                nz++;
            }
            out.add(new int[]{x, z});
        }
        return out;
    }

    /**
     * Pulls a path straight: from each kept cell, the farthest later cell that a straight line reaches over open cells for no
     * more than the path between them costs (x 1.02). The kept cells (corners of the road), ends included.
     */
    public static List<int[]> smooth(Field f, List<int[]> cells, Weights w) {
        if (cells.size() <= 2) return List.copyOf(cells);
        List<int[]> out = new ArrayList<>();
        int i = 0;
        out.add(cells.getFirst());
        while (i < cells.size() - 1) {
            int best = i + 1;
            for (int j = Math.min(cells.size() - 1, i + LOOK_AHEAD); j > i + 1; j--) {
                List<int[]> straight = line(cells.get(i)[0], cells.get(i)[1], cells.get(j)[0], cells.get(j)[1]);
                boolean clear = true;
                for (int[] c : straight) {
                    if (!open(f, c[0], c[1])) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) continue;
                double along = cost(f, cells.subList(i, j + 1), w);
                if (cost(f, straight, w) <= along * 1.02 + 1e-9) {
                    best = j;
                    break;
                }
            }
            out.add(cells.get(best));
            i = best;
        }
        return out;
    }

    /** How far ahead (cells) smoothing looks for a straight line: about a kilometre. */
    static final int LOOK_AHEAD = 64;

    /** Points every `step` blocks along a polyline of block points (x, z), its corners kept, both ends included. */
    public static List<double[]> densify(List<double[]> corners, double step) {
        List<double[]> out = new ArrayList<>();
        if (corners.isEmpty()) return out;
        out.add(corners.getFirst().clone());
        for (int i = 1; i < corners.size(); i++) {
            double[] a = corners.get(i - 1), b = corners.get(i);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int n = Math.max(1, (int) Math.ceil(len / step - 1e-9));
            for (int k = 1; k <= n; k++) out.add(new double[]{a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n});
        }
        return out;
    }
}
