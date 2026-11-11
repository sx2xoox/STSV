package dev.stsv.economy.road;

import org.bukkit.Material;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 국도: which block goes where, one cross-section of the road at a time (README "국도"). Pure: it works on a {@link Site}
 * ({@link RoadModule} gives it the world, RoadPreviewTest a grid of its own), so the road can be built and looked at
 * without a server.
 *
 * <p>A cross-section is every column whose nearest point of the road is this one (so on diagonals and round bends they
 * neither leave gaps nor build over each other), out to 5.5 either side of the middle: the 5-wide surface with a dashed
 * centre line, a kerb each side, then the shoulders. What it is there depends on the point's {@link RoadProfile.Part}:
 * <ul>
 *   <li>ROAD: the surface at the road's level, cut 5 above it, filled below (stone, then cobblestone); the shoulders cut
 *       down and filled up to the road (grass on dirt); trees cleared.</li>
 *   <li>BRIDGE: the deck (stone bricks at the edges), a railing each side, a pier down to the ground every 10.</li>
 *   <li>TUNNEL: the surface, 4 of air, a stone brick ceiling (a light in it every 8) and walls; at each end a portal (the
 *       walls out to the shoulders and a block higher, a lintel over the opening).</li>
 * </ul>
 * Street lights every {@code lightEvery} (sides taking turns); direction signs ({@link RoadSign}: the site puts the model and
 * its words) a little way from the start, every {@code markerEvery} and just before the gate, all where the road is plain
 * road ({@link #signs}). A column of a player's land is left alone; a surface column there stops the road (nothing of that
 * cross-section is built).
 */
public final class RoadSection {

    /** The blocks the road is built in. */
    public interface Site {
        /** False below the world's floor or above its roof. */
        boolean inside(int y);

        boolean air(int x, int y, int z);

        boolean liquid(int x, int y, int z);

        /** Trees and the like: logs, leaves, vines, mushroom blocks, snow. */
        boolean natural(int x, int y, int z);

        /** What the road may fill: air, liquid, plants, anything not solid, trees. False outside the world. */
        boolean fillable(int x, int y, int z);

        /** Puts a block (nothing outside the world, nothing when it is that already). */
        void set(int x, int y, int z, Material m);

        boolean playerLand(int x, int z);

        /**
         * A direction sign ({@link RoadSign}): the point between its posts (x, z; y the road's surface), its face towards
         * yaw (Minecraft: 0 south, 90 west), the dock, the km left to it, the route's number.
         */
        void roadSign(double x, double y, double z, float yaw, String dock, double km, int route, RoadSign.Kind kind);
    }

    /** The blocks and spacings of roads.* in the config. */
    public record Style(Material surface, Material line, Material kerb, int lightEvery, int markerEvery) {}

    /** Half the width it touches: the surface ±2.5, the kerbs to ±3.5, the shoulders to ±5.5. */
    public static final int HALF = 5;

    private final Site site;
    private final Style style;
    private final List<double[]> points;
    private final int[] levels;
    private final RoadProfile.Part[] parts;
    private final String dock;
    private final int route;
    private final Map<Integer, RoadSign.Kind> signs;

    public RoadSection(Site site, Style style, List<double[]> points, int[] levels, RoadProfile.Part[] parts, String dock, int route) {
        this.site = site;
        this.style = style;
        this.points = points;
        this.levels = levels;
        this.parts = parts;
        this.dock = dock;
        this.route = route;
        this.signs = signs(parts, style.markerEvery());
    }

    /** The start sign this far along (blocks), the arrival sign this far before the end; no km sign closer than KM_GAP to either. */
    static final int SIGN_START = 24, SIGN_END = 20, KM_GAP = 150;

    /**
     * Where the signs go: point -> kind. Each on plain road (3 points of it either way), moved on (or back, at the end) to
     * the next such point when it would fall on a bridge or in a tunnel. A road shorter than the gaps has none.
     */
    static Map<Integer, RoadSign.Kind> signs(RoadProfile.Part[] parts, int every) {
        Map<Integer, RoadSign.Kind> out = new TreeMap<>();
        int n = parts.length;
        if (n < SIGN_START + SIGN_END + KM_GAP) return out;
        int start = plain(parts, SIGN_START, 1, 60), end = plain(parts, n - 1 - SIGN_END, -1, 60);
        if (start >= 0) out.put(start, RoadSign.Kind.START);
        for (int k = every; k < n; k += every) {
            int i = plain(parts, k, 1, 60);
            if (i < 0 || n - 1 - i < KM_GAP || (start >= 0 && i - start < KM_GAP)) continue;
            out.put(i, RoadSign.Kind.KM);
        }
        if (end >= 0 && (start < 0 || end - start >= KM_GAP)) out.put(end, RoadSign.Kind.ARRIVAL);
        return out;
    }

    /** The first point from `from` (stepping `dir`, at most `look`) with plain road 3 points either way, or -1. */
    private static int plain(RoadProfile.Part[] parts, int from, int dir, int look) {
        for (int k = 0; k < look; k++) {
            int i = from + k * dir;
            if (i - 3 < 0 || i + 3 >= parts.length) return -1;
            boolean ok = true;
            for (int j = i - 3; j <= i + 3 && ok; j++) ok = parts[j] == RoadProfile.Part.ROAD;
            if (ok) return i;
        }
        return -1;
    }

    /** The signs of this road (point -> kind). */
    public Map<Integer, RoadSign.Kind> signs() {
        return signs;
    }

    /**
     * Builds the cross-section at point i. Null: built; else {x, z} of the surface column that is a player's land (then
     * nothing of it is built and the road stops there).
     */
    public int[] build(int i) {
        double[] p = points.get(i);
        double[] a = points.get(Math.max(0, i - 2)), b = points.get(Math.min(points.size() - 1, i + 2));
        double hx = b[0] - a[0], hz = b[1] - a[1], len = Math.max(1e-6, Math.hypot(hx, hz));
        hx /= len;
        hz /= len;
        double rx = -hz, rz = hx;   // the right hand
        int level = levels[i];
        RoadProfile.Part part = parts[i];
        List<Col> cols = new ArrayList<>();
        int px = (int) Math.floor(p[0]), pz = (int) Math.floor(p[1]);
        for (int bx = px - HALF - 1; bx <= px + HALF + 1; bx++) {
            for (int bz = pz - HALF - 1; bz <= pz + HALF + 1; bz++) {
                double cx = bx + 0.5 - p[0], cz = bz + 0.5 - p[1];
                double lat = cx * rx + cz * rz, along = cx * hx + cz * hz;
                if (Math.abs(lat) > HALF + 0.5 || !owns(i, bx + 0.5, bz + 0.5)) continue;
                if ((i == 0 && along < -0.5) || (i == points.size() - 1 && along > 0.5)) continue;   // not past the ends
                double ao = Math.abs(lat);
                if (site.playerLand(bx, bz)) {
                    if (ao <= 3.5) return new int[]{bx, bz};
                    continue;   // a shoulder in someone's land: left alone
                }
                cols.add(new Col(bx, bz, lat));
            }
        }
        boolean dash = (i / 4) % 2 == 0;
        // a tunnel's ends: a portal (a stone brick face round the opening)
        boolean portal = part == RoadProfile.Part.TUNNEL && (i == 0 || i == points.size() - 1 || parts[i - 1] != part || parts[i + 1] != part);
        for (Col c : cols) {
            double ao = Math.abs(c.lat());
            Material top = ao <= 0.5 && dash ? style.line() : ao <= 2.5 ? style.surface() : style.kerb();
            switch (part) {
                case ROAD -> road(c.x(), c.z(), level, ao, top);
                case BRIDGE -> bridge(c.x(), c.z(), level, ao, top, i % 10 == 0);
                case TUNNEL -> tunnel(c.x(), c.z(), level, ao, top, i % 8 == 0, portal);
            }
        }
        if (i % style.lightEvery() == 0 && part != RoadProfile.Part.TUNNEL && i > 0 && i < points.size() - 1) {
            int side = (i / style.lightEvery()) % 2 == 0 && !signs.containsKey(i) ? 1 : -1;   // never in a sign's posts
            // on the bridge on its railing, else on the shoulder just past the kerb: never in the lanes
            Col c = nearest(cols, side * (part == RoadProfile.Part.BRIDGE ? 3.0 : 4.0), part == RoadProfile.Part.BRIDGE ? 2.5 : 3.5);
            if (c != null) lamp(c.x(), c.z(), level);
        }
        RoadSign.Kind sign = signs.get(i);
        if (sign != null) sign(p, rx, rz, level, RoadSign.yaw(-hx, -hz), i, sign);
        return null;
    }

    /** A column of a cross-section and how far right of the middle its centre is (left: below 0). */
    private record Col(int x, int z, double lat) {}

    /** The column whose lat is nearest `want` (on its side, further out than `beyond`), or null. */
    private static Col nearest(List<Col> cols, double want, double beyond) {
        Col best = null;
        for (Col c : cols) {
            if (Math.signum(c.lat()) != Math.signum(want) || Math.abs(c.lat()) <= beyond) continue;
            if (best == null || Math.abs(c.lat() - want) < Math.abs(best.lat() - want)) best = c;
        }
        return best;
    }

    /**
     * Whether point i is the nearest point of the road to (x, z) (a tie: the earlier point). Every column near the road
     * belongs to exactly one cross-section, so they neither leave gaps (on a diagonal, round a bend) nor build over each
     * other (a light put by one is never cut away by the next).
     */
    private boolean owns(int i, double x, double z) {
        double[] p = points.get(i);
        double d = (x - p[0]) * (x - p[0]) + (z - p[1]) * (z - p[1]);
        for (int j = Math.max(0, i - NEIGHBOURS); j <= Math.min(points.size() - 1, i + NEIGHBOURS); j++) {
            if (j == i) continue;
            double[] q = points.get(j);
            double e = (x - q[0]) * (x - q[0]) + (z - q[1]) * (z - q[1]);
            if (e < d - 1e-9 || (e < d + 1e-9 && j < i)) return false;
        }
        return true;
    }

    /** How many points each way a column's nearest point is looked for (enough for the width round a sharp bend). */
    private static final int NEIGHBOURS = 12;

    /** Trees and the like over a column, up to 30 above the road. */
    private void clearTrees(int x, int z, int from) {
        for (int y = from; y < from + 30 && site.inside(y); y++) if (site.natural(x, y, z)) site.set(x, y, z, Material.AIR);
    }

    /**
     * Cut over the road: air `height` blocks over its level, then on up as long as there is ground there, so no bank is left
     * hanging over the road (at most 30 over it).
     */
    private void cut(int x, int z, int level, int height) {
        for (int y = level + 1; y <= level + height; y++) site.set(x, y, z, Material.AIR);
        for (int y = level + height + 1; y <= level + 30 && site.inside(y) && !site.air(x, y, z); y++) site.set(x, y, z, Material.AIR);
    }

    private void road(int x, int z, int level, double ao, Material top) {
        if (ao <= 3.5) {
            site.set(x, level, z, top);
            cut(x, z, level, 5);
            for (int k = 1; k <= 8; k++) {                                                 // fill
                if (!site.fillable(x, level - k, z)) break;
                site.set(x, level - k, z, k == 1 ? Material.STONE : Material.COBBLESTONE);
            }
            clearTrees(x, z, level + 6);
            return;
        }
        // the shoulders: cut the bank down to the road, fill a dip to it (grass on top), clear trees
        cut(x, z, level, 4);
        if (site.fillable(x, level, z) && !site.liquid(x, level, z)) {
            site.set(x, level, z, Material.GRASS_BLOCK);
            for (int k = 1; k <= 8; k++) {   // down to the ground (as deep as the road itself is filled): never left hanging
                if (!site.fillable(x, level - k, z) || site.liquid(x, level - k, z)) break;
                site.set(x, level - k, z, Material.DIRT);
            }
        }
        clearTrees(x, z, level + 1);
    }

    private void bridge(int x, int z, int level, double ao, Material top, boolean pillar) {
        if (ao > 3.5) return;
        site.set(x, level, z, ao > 2.5 ? Material.STONE_BRICKS : top);
        for (int y = level + 1; y <= level + 4; y++) site.set(x, y, z, Material.AIR);
        if (ao > 2.5) site.set(x, level + 1, z, Material.STONE_BRICK_WALL);   // the railing
        if (pillar && ao <= 2.5) {
            for (int k = 1; k <= 64; k++) {
                if (!site.fillable(x, level - k, z)) break;
                site.set(x, level - k, z, Material.STONE_BRICKS);
            }
        }
        clearTrees(x, z, level + 5);
    }

    private void tunnel(int x, int z, int level, double ao, Material top, boolean light, boolean portal) {
        if (ao <= 3.5) {
            site.set(x, level, z, top);
            for (int y = level + 1; y <= level + 4; y++) site.set(x, y, z, Material.AIR);
            site.set(x, level + 5, z, light && ao <= 0.5 ? Material.SEA_LANTERN : Material.STONE_BRICKS);
            if (portal) site.set(x, level + 6, z, Material.STONE_BRICKS);   // the portal's lintel
        } else if (ao <= 4.5 || portal) {
            for (int y = level; y <= level + (portal ? 6 : 5); y++) site.set(x, y, z, Material.STONE_BRICKS);   // walls (the portal's: wider, higher)
            if (portal) clearTrees(x, z, level + 7);
        }
    }

    private void lamp(int x, int z, int level) {
        if (site.playerLand(x, z)) return;
        for (int y = level + 1; y <= level + 3; y++) site.set(x, y, z, Material.STONE_BRICK_WALL);
        site.set(x, level + 4, z, Material.LANTERN);
        if (site.fillable(x, level, z)) site.set(x, level, z, Material.STONE_BRICKS);
    }

    /**
     * A direction sign on the right at point i, facing the traffic coming from the start: its posts on the kerb and the
     * shoulder (a stone brick footing where the shoulder is not solid), none where a post would stand in a player's land.
     */
    private void sign(double[] p, double rx, double rz, int level, float yaw, int i, RoadSign.Kind kind) {
        int[][] posts = new int[2][];
        for (int k = 0; k < 2; k++) {
            double o = RoadSign.LATERAL + (k == 0 ? -RoadSign.POSTS : RoadSign.POSTS);
            posts[k] = new int[]{(int) Math.floor(p[0] + rx * o), (int) Math.floor(p[1] + rz * o)};
            if (site.playerLand(posts[k][0], posts[k][1])) return;
        }
        for (int[] c : posts) if (site.fillable(c[0], level, c[1]) && !site.liquid(c[0], level, c[1])) site.set(c[0], level, c[1], Material.STONE_BRICKS);
        site.roadSign(p[0] + rx * RoadSign.LATERAL, level + 1, p[1] + rz * RoadSign.LATERAL, yaw, dock, (points.size() - 1 - i) / 1000.0, route, kind);
    }
}
