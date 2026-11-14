package dev.stsv.economy.road;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The 국도 already built in a world (README "국도", 도로망): every column they cover and how high their surface is there, so
 * a new road can use them instead of overwriting them. Pure (unit-tested): {@link RoadModule} builds it from the saved
 * roads ({@link RoadJob#halves}, or for a road saved before that, its full-block levels worked out again).
 *
 * <ul>
 *   <li><b>Reuse</b>: the chunk cells they run through ({@link #cell}) cost little to the planner ({@link RoadPlanner}),
 *       cells beside them more; a route that starts on the network and follows it is cut at the cell where it leaves,
 *       and the new road starts there, on the old road's centre line ({@link #branch}): a trunk and branches from the
 *       origin, never two roads side by side.</li>
 *   <li><b>Crossings</b> ({@link #crossings}): where a new road's middle runs over an old road's lanes. {@link RoadProfile}
 *       meets it level (a junction) when it can within the grade, else goes over it ({@link #CLEARANCE}).</li>
 *   <li><b>Protection</b> ({@link #at}): {@link RoadSection} never builds into an old road's lanes and kerbs (nor cuts or
 *       fills them), never puts a light or sign there, and at a junction only paves the old kerbs and shoulders it
 *       crosses, level with them.</li>
 * </ul>
 */
public final class RoadNetwork {

    /** A built road: its dock, its points (x, z, one a block) and its surface at each, in half blocks. */
    public record Road(String dock, List<double[]> points, int[] halves) {}

    /**
     * An old road at a column: which road and point it belongs to, the surface there (half blocks), how far from the
     * road's middle the column is (blocks).
     */
    public record Spot(String dock, int road, int point, int half, double lat) {
        /** Its lanes or kerbs (never built over). */
        public boolean paved() {
            return lat <= 3.5;
        }

        /** Its lanes. */
        public boolean lane() {
            return lat <= 2.5;
        }

        /** The block its surface is in. */
        public int y() {
            return RoadSection.surfaceY(half);
        }
    }

    /** Where a new road starts on the network: the point on the old road, its dock, and how far that road's gate is (blocks). */
    public record Branch(double x, double z, String dock, double left, int cell) {}

    /** How far (points) a branch keeps from the old road's street lights. */
    public static final int CLEAR_OF_LIGHTS = 8;

    /**
     * How far over an old road's highest surface near the crossing ({@link #headroom}) a new road's deck goes (blocks): its
     * lights (6 high on the flat) stay under it, and 6 clear under the deck is more than a vehicle needs
     * ({@link RoadClearance#HEADROOM}).
     */
    public static final int CLEARANCE = 7;

    private final List<Road> roads;
    private final Map<Long, Spot> columns = new HashMap<>();
    private final Set<Long> cells = new HashSet<>();

    private RoadNetwork(List<Road> roads) {
        this.roads = roads;
    }

    public static final RoadNetwork NONE = new RoadNetwork(List.of());

    static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public static RoadNetwork of(List<Road> roads) {
        RoadNetwork n = new RoadNetwork(List.copyOf(roads));
        for (int r = 0; r < roads.size(); r++) {
            Road road = roads.get(r);
            List<double[]> pts = road.points();
            for (int i = 0; i < pts.size() && i < road.halves().length; i++) {
                double[] p = pts.get(i), a = pts.get(Math.max(0, i - 2)), b = pts.get(Math.min(pts.size() - 1, i + 2));
                double hx = b[0] - a[0], hz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(hx, hz));
                hx /= len;
                hz /= len;
                n.cells.add(key((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[1]) >> 4));
                int px = (int) Math.floor(p[0]), pz = (int) Math.floor(p[1]);
                for (int x = px - 7; x <= px + 7; x++) {
                    for (int z = pz - 7; z <= pz + 7; z++) {
                        double cx = x + 0.5 - p[0], cz = z + 0.5 - p[1];
                        double along = cx * hx + cz * hz, lat = Math.abs(-cx * hz + cz * hx);
                        if (Math.abs(along) > 0.75 || lat > 5.5) continue;
                        long k = key(x, z);
                        Spot s = n.columns.get(k);
                        if (s == null || lat < s.lat()) n.columns.put(k, new Spot(road.dock(), r, i, road.halves()[i], lat));
                    }
                }
            }
        }
        return n;
    }

    public boolean isEmpty() {
        return roads.isEmpty();
    }

    /** The old road at a column (its nearest), or null. */
    public Spot at(int x, int z) {
        return columns.get(key(x, z));
    }

    /** Whether an old road runs through this chunk cell. */
    public boolean cell(int cx, int cz) {
        return cells.contains(key(cx, cz));
    }

    /** Whether a cell next to this one (not this one) has an old road. */
    public boolean near(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if ((dx != 0 || dz != 0) && cell(cx + dx, cz + dz)) return true;
        return false;
    }

    /**
     * Where a planned route (chunk cells from the start) leaves the network: when it starts on old roads and follows them
     * for a step or more, the old road's point in the last such cell nearest to the next cell's middle (or the end).
     * Null: it does not start along an old road.
     */
    public Branch branch(List<int[]> route, double endX, double endZ) {
        return branch(route, endX, endZ, 40);
    }

    /**
     * {@link #branch(List, double, double)}, never within {@link #CLEAR_OF_LIGHTS} points of the old road's street lights
     * (every lightEvery points): a light is never left standing in the new road.
     */
    public Branch branch(List<int[]> route, double endX, double endZ, int lightEvery) {
        if (route.size() < 2 || !cell(route.get(0)[0], route.get(0)[1]) || !cell(route.get(1)[0], route.get(1)[1])) return null;
        int k = 1;
        while (k + 1 < route.size() && cell(route.get(k + 1)[0], route.get(k + 1)[1])) k++;
        double tx = k + 1 < route.size() ? route.get(k + 1)[0] * 16 + 8.5 : endX, tz = k + 1 < route.size() ? route.get(k + 1)[1] * 16 + 8.5 : endZ;
        int cx = route.get(k)[0], cz = route.get(k)[1];
        Branch best = null;
        double bestD = Double.MAX_VALUE;
        for (Road r : roads) {
            List<double[]> pts = r.points();
            for (int i = 0; i < pts.size(); i++) {
                double[] p = pts.get(i);
                if (((int) Math.floor(p[0]) >> 4) != cx || ((int) Math.floor(p[1]) >> 4) != cz) continue;
                int m = Math.floorMod(i, lightEvery);
                if (Math.min(m, lightEvery - m) < CLEAR_OF_LIGHTS) continue;
                double d = Math.hypot(p[0] - tx, p[1] - tz);
                if (d < bestD) {
                    bestD = d;
                    best = new Branch(p[0], p[1], r.dock(), pts.size() - 1 - i, k);
                }
            }
        }
        return best;
    }

    /**
     * For each point of a new road: the old road's surface (half blocks) where its middle runs over that road's lanes or
     * kerbs, else {@link Integer#MIN_VALUE}.
     */
    public int[] crossings(List<double[]> points) {
        int[] out = new int[points.size()];
        for (int i = 0; i < points.size(); i++) {
            Spot s = at((int) Math.floor(points.get(i)[0]), (int) Math.floor(points.get(i)[1]));
            out[i] = s != null && s.paved() ? s.half() : Integer.MIN_VALUE;
        }
        return out;
    }

    /** How far (points along the old road) a deck over it looks for its highest surface: a vehicle's length and the deck's width. */
    public static final int HEADROOM_REACH = RoadClearance.REACH + RoadSection.HALF;

    /**
     * For each point of a new road: the highest surface (half blocks) of the old road it runs over within
     * {@link #HEADROOM_REACH} of where it crosses (a deck over it clears that: a long vehicle coming down its grade under
     * the deck stands on its high end), else {@link Integer#MIN_VALUE}.
     */
    public int[] headroom(List<double[]> points) {
        int[] out = new int[points.size()];
        for (int i = 0; i < points.size(); i++) {
            Spot s = at((int) Math.floor(points.get(i)[0]), (int) Math.floor(points.get(i)[1]));
            if (s == null || !s.paved()) {
                out[i] = Integer.MIN_VALUE;
                continue;
            }
            int[] h = roads.get(s.road()).halves();
            int m = s.half();
            for (int k = Math.max(0, s.point() - HEADROOM_REACH); k <= Math.min(h.length - 1, s.point() + HEADROOM_REACH); k++) m = Math.max(m, h[k]);
            out[i] = m;
        }
        return out;
    }

    /** The roads, for the planner's cells and the like. */
    public List<Road> roads() {
        return roads;
    }

    /** How many block columns the roads cover (their lanes, kerbs and shoulders). */
    public int columnCount() {
        return columns.size();
    }

    /** The chunks the roads' columns are in, each once, as {@link RoadJob#key} keys: what a sweep along every road visits. */
    public Set<Long> chunkKeys() {
        Set<Long> out = new java.util.LinkedHashSet<>();
        for (long k : columns.keySet()) out.add(RoadJob.key((int) (k >> 32) >> 4, (int) k >> 4));
        return out;
    }

    /** All the cells, for tests. */
    List<Long> cellKeys() {
        return new ArrayList<>(cells);
    }
}
